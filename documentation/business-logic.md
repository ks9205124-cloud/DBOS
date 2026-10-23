# Business Logic — DBOS

This document explains how DBOS actually works at runtime: what a job does, who decides what, how work is picked up concurrently, how results and retries are recorded, and what happens when something crashes. It complements [`database-design.md`](./database-design.md), which describes the tables, and [`core-interfaces.md`](./core-interfaces.md), which describes the Java contracts.

---

## 1. The model in one paragraph

Java is the **brain and the muscle**: it decides when things run, starts them, runs many at once, and decides whether to retry. Oracle is the **memory and the referee**: it stores every job and every run, makes sure two workers never take the same run, records every status change, and answers questions about history. Neither side is complete alone, and the split below is what the project is designed around.

---

## 2. Who owns what

| Concern | Owner | Mechanism |
|---|---|---|
| What a job does | Java | the `Job` interface, which may call PL/SQL |
| When a job runs next | Java | the `Trigger` interface |
| Whether and how long to retry | Java | the `RetryPolicy` interface |
| Recording a retry atomically | Oracle | PL/SQL procedure with a savepoint |
| Running many jobs at once | Java | a fixed pool of worker threads (`WorkerPool`) |
| Stopping two workers taking the same run | Oracle | row locking with `SKIP LOCKED` |
| Storing jobs, runs and results | Oracle | the 10 tables |
| Audit trail of status changes | Oracle | database trigger |
| Success rates, health, analytics | Oracle | functions, views and queries |
| Archiving old history | Oracle | cursor-based PL/SQL procedure |
| Access control | Both | database privileges plus Java role checks |

The rule of thumb: **decisions that need objects and strategy live in Java; decisions that need consistency, history and locking live in Oracle.**

---

## 3. What "performing a job" means

Java always starts a job, because every job is a Java object with an `execute()` method. What happens inside depends on the kind of job:

- **Database jobs** (log archiver, metrics exporter): `execute()` opens a connection and calls a PL/SQL procedure or runs SQL. Oracle does the data work. These are the jobs that showcase the DBMS side.
- **Java jobs** (temp-file cleanup, the deliberately flaky ingestion job used to demo retries): pure Java, no SQL involved.

So the accurate statement is *Java launches every job; Oracle does the data work for database-type jobs.* The main demo should lean on database jobs, since that is where PL/SQL business logic is visible.

A job's type is recorded in `JOB_TYPES`, which maps a short code to the Java class that implements it. The scheduler never knows what a job does; it only loads the class and calls `execute()`.

---

## 4. A job's life, step by step

### 4.1 Registering a job
An operator (through the CLI, the GUI, or the API) supplies a name, a job type, a trigger and a retry policy. The store saves the trigger and retry policy rows (or reuses existing shared ones), then saves the job. Registering a job does not run it, and creates no status; it only defines the rule.

### 4.2 Scheduling a run
For every active job, the engine makes sure there is a pending run waiting. A run is an execution row with status `PENDING`, attempt 1, and a `scheduled_for` time taken from the job's trigger. Because the trigger is a Java object, **Java creates the execution rows**; Oracle only stores them.

### 4.3 Claiming a run (this is where concurrency happens)
Workers repeatedly look for the next due run. A run is due when it is `PENDING` and its `scheduled_for` time has passed.

Claiming is a single short transaction in PL/SQL: select the earliest due pending run with row locking that skips rows other workers have locked, set it to `RUNNING`, record the worker and start time, and commit. Then the worker runs the job.

Two things make this correct:
- **Skipping locked rows** means two workers that look at the same moment get different runs, never the same one.
- **Committing before the job runs** means the lock is held for milliseconds, not for the whole duration of a long job.

### 4.4 Executing
The worker calls `execute()` on the job. Success means it returns normally. Failure means it throws a `JobExecutionException`.

### 4.5 Recording the result
- **Success:** the run is marked `DONE` with its end time. The engine then asks the job's trigger for the next fire time and creates a new `PENDING` run for it. The finished run stays `DONE` forever as history.
- **Failure:** the run is marked `FAILED` with its end time, and Java inserts an `ERROR` row in `EXECUTION_LOGS` containing the exception message.

That last part matters. The audit trigger in Oracle can see that a status went from `RUNNING` to `FAILED`, but it cannot see *why*, because the exception lives in Java. So the audit trigger records **what changed**, and Java records **why**.

### 4.6 Retrying
Java decides; Oracle records.

1. Java reads the failed run's attempt number.
2. Java asks the job's retry policy whether another attempt is allowed, and how long to wait.
3. If yes, Java calls a PL/SQL retry procedure, passing the failed run and the delay. The procedure creates the new run (attempt number plus one, scheduled that far in the future) inside one transaction with a savepoint, so a half-finished retry rolls back cleanly.
4. If no, the run simply stays `FAILED`.

The backoff arithmetic exists once, in Java. The retry procedure exists to make the *recording* atomic, and it takes a delay rather than an absolute time so that Oracle can add it to its own clock.

**Attempt numbering (applied in the code).** `attempt_number` is 1-based: 1 is the original run, 2 is the first retry. The retry policy receives the number of the attempt that just failed. With a maximum of 3 retries, a retry is allowed while that number is at most 3, so a job can run up to 4 times in total. For exponential backoff the wait is the base delay times 2 raised to (attempt number minus 1): 2s, 4s, 8s for a 2-second base. Both retry policies and their tests use this convention.

The next scheduled run of a recurring job starts again at attempt 1; the attempt counter only counts consecutive tries of the same run.

### 4.7 Crash recovery
If the application is killed while jobs are running, those runs stay `RUNNING` because nothing is left alive to finish them. On the next start, a reconciler:

1. finds workers whose last heartbeat is older than a threshold and marks them `DEAD`,
2. marks the `RUNNING` runs those workers owned as `FAILED`,
3. lets the retry policy decide whether each one is re-queued,
4. starts new workers.

Oracle has already rolled back any uncommitted work from the dead sessions, so the data is consistent when the reconciler runs. For the demo, the process must be killed hard (end the process from the IDE or with the OS tool). A normal Ctrl+C runs shutdown hooks and does not simulate a crash.

---

## 5. Delivery guarantee: at-least-once

The design guarantees that **no two workers run the same execution at the same time**, and that **a run interrupted by a crash will be retried**. It does *not* guarantee a job runs exactly once: a job that finished its work but crashed before its status was committed will run again. This is called at-least-once execution, and it has a practical consequence: **jobs should be safe to run twice.** State this plainly in the report; it is the kind of limit an examiner is likely to ask about, and knowing it is a strength.

---

## 6. Operating rules

- **Time source.** "Is this run due?" is decided with the database clock, so there is one authority. Scheduled times are computed in Java. The assumption is that the app and database share a machine or synchronized clocks, which is true for the demo.
- **Polling.** Oracle does not push work to the app. Workers poll for due runs roughly once a second. The GUI's live dashboard polls the same way, so "live" means a short refresh interval, not a real-time stream.
- **Connections.** Each worker needs its own database connection while it works, so the connection pool must be at least as large as the worker count.
- **Short transactions.** Claiming, status changes and retry recording each commit quickly. No transaction is held open while a job runs.

---

## 7. Two storage modes, one contract

The engine talks only to the `JobStore` interface. Behind it are two implementations:

- **In-memory store:** no database, used for development and for demonstrating the object-oriented design. Claiming a due run here is done in Java with proper locking.
- **Oracle store:** the real thing, where claiming is the PL/SQL procedure with row locking.

Because "claim the next due run" is implemented differently in each, it belongs on the `JobStore` interface itself (as a claim-next-due operation), with one implementation per store. That is also why the in-memory store is thread-safe: its claim is a synchronized method, and the worker pool relies on that atomicity for correctness.

---

## 8. Where the rules are enforced

The same rule is protected at several levels, so bypassing one layer does not break the system:

| Rule | Java | Oracle |
|---|---|---|
| Valid status values | `JobStatus` enum | `CHECK` constraint |
| Legal status transitions | `canTransitionTo` | `BEFORE UPDATE` trigger (planned) |
| Who may do what | role checks in the engine | database privileges per role |
| Required data on a job | `build()` validation | `NOT NULL` and foreign keys |

The transition rule exists in two places, which is deliberate defense in depth but means the two must be kept in sync. The viewer role is a good example: the GUI hides the register and cancel buttons, and the database also refuses the writes, so calling the data layer directly still fails.

---

## 9. Open decisions

1. **A generic procedure-calling job.** One `Job` implementation could take a PL/SQL procedure name and call it, so adding a new database job means adding a `JOB_TYPES` row and no new Java. That needs a column to hold the procedure name. Worth deciding before the DDL is final.
2. ~~Apply the attempt-numbering convention~~ Done: the retry policies, tests and `core-interfaces.md` all use 1-based attempts.
3. **Retry procedure signature.** The proposal is that it takes the failed execution and a delay in seconds. Agree on this before the PL/SQL is written.
4. **Whether to add the transition-enforcing trigger.** It strengthens the "bypass the app" answer but duplicates the rule.
5. **Idempotency.** Decide which demo jobs are safe to run twice, so the at-least-once explanation has a concrete example.

---

## 10. Where each course sees this

| Topic | OOP report | DBMS report |
|---|---|---|
| `Job`, `Trigger`, `RetryPolicy` as swappable strategies | yes | |
| Builder, enum with behavior, listener (observer) | yes | |
| Worker thread pool (virtual threads as a possible extension) | yes | |
| Tables, keys, constraints, normalization | | yes |
| Claim procedure with row locking | | yes |
| Retry procedure with savepoint | | yes |
| Audit trigger, views, functions, archive cursor | | yes |
| Privileges and roles | partly | yes |
| Crash recovery and at-least-once | yes | yes |
