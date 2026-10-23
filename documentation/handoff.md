# DBOS Handoff: paste this into the new chat

## 1. The project

DBOS is a job scheduler (Quartz or Spring `@Scheduled` style) built in plain Java 21 with Maven. It is a college mini project (Manipal University Jaipur): OOP course CSC2104 now, possibly also the RDBMS Lab CSC2131 later. Team of two. This chat's track is the **OOP / engine side**. The teammate is doing the Oracle side (ER diagram, DDL, `OracleJobStore`).

Environment: Windows, IntelliJ IDEA, JDK 25 installed with the project targeting 21, GitHub repo `ks9205124-cloud/DBOS`, working branch `engine`. The IntelliJ green run arrows for tests don't show up, so run tests through the Maven panel (Lifecycle, test).

## 2. What is done

| Area | State |
|---|---|
| Interfaces and enum | `Job`, `Trigger`, `RetryPolicy`, `JobStore`, `JobStatus` |
| Triggers | `FixedRateTrigger`, `OneTimeTrigger` (has an `Instant` constructor too) |
| Retry policies | `ExponentialBackoffPolicy`, `FixedDelayPolicy`, both 1-based, method `getRetryDelay` |
| Model | `JobDefinition` (immutable, builder that validates name, job, trigger), `JobExecution` (mutable, guarded transitions) |
| Store | `InMemoryJobStore`: synchronized, copies executions, injectable `Clock` |
| Engine | `RunPlanner`, `ExecutionProcessor`, `SchedulerEngine` (synchronous `tick()`), `ExecutionListener` |
| Demo | `com.dbos.cli.Demo` ran correctly on the real clock |
| GUI | `com.dbos.gui.SchedulerApp` (Swing) written, **not yet confirmed run by the user** |
| Tests | Expected 70 passing: store contract (26), retry (8), `JobDefinition` (8), `RunPlanner` (13), engine (15). **Confirm with `mvn test`** |

## 3. Package layout

```
src/main/java/com/dbos/
  core/     Job, JobStatus, JobDefinition, JobExecution, JobExecutionException, DuplicateJobException
  trigger/  Trigger, FixedRateTrigger, OneTimeTrigger
  retry/    RetryPolicy, ExponentialBackoffPolicy, FixedDelayPolicy
  store/    JobStore, InMemoryJobStore, OracleJobStore (teammate's stub)
  engine/   RunPlanner, ExecutionProcessor (package-private), SchedulerEngine, ExecutionListener
  cli/      Demo
  gui/      SchedulerApp
src/test/java/com/dbos/  store/ (contract + in-memory), retry/, core/, engine/ (incl. TestClock)
documentation/           markdown docs (several are stale, see section 7)
```

## 4. Key APIs (current)

```java
// Job
void execute() throws JobExecutionException;
// Trigger
Instant nextFireTime(Instant lastFireTime);
// RetryPolicy: attemptNumber is 1-based and is the attempt that just failed
boolean canRetry(int attemptNumber);
Duration getRetryDelay(int attemptNumber);

// JobStore
JobDefinition addJob(JobDefinition job) throws DuplicateJobException;   // unique by name
Optional<JobDefinition> getJob(long id);
List<JobDefinition> getAllJobs();
void cancelJob(long id);
JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor);
Optional<JobExecution> claimNextExecution(long workerId, Instant now);   // atomic, earliest due PENDING, lower id wins ties
void updateExecution(JobExecution execution);
List<JobExecution> listExecutions(long jobId);

// SchedulerEngine (plain class, not a singleton)
SchedulerEngine(JobStore store, Clock clock, ExecutionListener listener)
JobDefinition register(JobDefinition job) throws DuplicateJobException;
void cancel(long jobId);
boolean runNext(long workerId);     // claim and process one due run; false if none
int tick();                         // runNext(1) in a loop until nothing is due
List<JobDefinition> listJobs();
List<JobExecution> listExecutions(long jobId);

// ExecutionListener (all default no-ops, NOOP constant)
onJobRegistered, onRunScheduled, onRunStarted, onRunSucceeded, onRunFailed(job, run, cause), onScheduleEnded
```

## 5. Design decisions already made (do not reopen)

1. **A job is a rule; an execution is one run.** Status lives on executions. DONE, FAILED and CANCELLED are final; a retry is a new execution with `attemptNumber + 1`.
2. **One live run per job.** The next run is created only after the current chain ends.
3. **The next run counts from the previous run's scheduled time,** not its end time (no drift).
4. **Missed runs are not replayed.** A next time in the past is clamped to now.
5. **A one-time job ends** when the trigger's next time is not after the run's scheduled time.
6. **Any `Exception` from a job is an ordinary failure.** Listener exceptions are swallowed.
7. **The engine never prints or logs;** it reports through `ExecutionListener`.
8. **After retries run out, the regular schedule continues** with attempt 1.
9. **Cancelling** marks the job inactive and cancels its PENDING runs; a RUNNING run finishes, and no follow-up is created.
10. **The store hands out copies of executions;** `JobDefinition` is immutable and shared.
11. **A missing retry policy becomes "no retries"** (`FixedDelayPolicy(0, ZERO)`).
12. **Swing, not JavaFX,** for the front end.
13. **Platform threads, not virtual threads,** for the workers (a fixed pool). The correctness comes from the store's atomic claim, not from the thread type. Create threads through a `ThreadFactory` so virtual threads stay a one-line swap later.

## 6. What is left, in order

1. **Worker threads** (the OOP concurrency showpiece). Plan:
   - A fixed pool of N platform threads (for example `Executors.newFixedThreadPool(4)`), created through a `ThreadFactory`, each a worker with id N calling `engine.runNext(workerId)`. Virtual threads are skipped on purpose.
   - `start(int workerCount, Duration pollInterval)` and `stop()` on the engine, or a small `WorkerPool` class.
   - Each worker loops: `runNext`; if nothing was due, sleep the poll interval; exit when a running flag is false.
   - A worker must never die: catch every exception inside the loop.
   - `stop()` clears the flag and waits with a timeout; it does not interrupt a job mid-run.
   - Tests: 4 workers and 20 slow jobs, each run executed exactly once, at least two worker ids appear, runs overlap in time, `stop()` returns cleanly.
2. **Switch the Swing GUI** from its single ticker thread to the worker pool (the Worker column then shows concurrency, and a slow job no longer blocks the others).
3. **`@Scheduled` annotation plus a reflection scanner** (optional; first thing to cut if time is short).
4. **OOP report, demo script, refreshed docs.**
5. Make the `OracleJobStore` stub methods throw `UnsupportedOperationException` instead of returning `null`.

## 7. Known loose ends

- Stale docs in `documentation/`: `core-interfaces.md` (old `JobStore`, `FAILED` to `PENDING`, `backoffFor`), `implementations.md` (`JobDefinition` still described with status, 0-based retries), `job-execution.md` (method called `cancel`; it is `markCancelled`), `database-design.md` (calls the job table `JOBS`; the ER diagram says `JOB_DEFINITIONS`).
- Docs that say "virtual-thread worker pool" (`business-logic.md`, `database-design.md`, `dbos-project-spec.md`) should say "worker thread pool". The report can mention virtual threads as a possible extension.
- `pom.xml`: replace the `maven.compiler.source` and `target` lines with `<maven.compiler.release>21</maven.compiler.release>`.
- **Unanswered:** does the OOP instructor accept a custom project (the handout's closest item is #21, Priority-Based Task Scheduling), and can the same project count for both courses? Also the OOP deadline.


## 9. How to work with me

- Casual and direct. Blunt technical honesty is welcome.
- **Ask to see my current files before changing them.** Don't rewrite code from memory. I can paste files or attach a zip; GitHub links can't be fetched.
- Give **complete files, no empty method bodies,** a few at a time, with the full path of each.
- Keep documentation prose-based, without code dumps.
- Tell me what to run (`mvn test`) and what to expect; I paste back failures.
- Trace examples by hand before writing comments or tests.
- Commit messages are welcome after each step.

## 10. First message for the new chat

> DBOS OOP project. The handoff is attached. The engine core and a Swing GUI are done. Next: a fixed pool of worker threads (platform threads, no virtual threads) calling `engine.runNext(workerId)`. Here is `SchedulerEngine.java` and `SchedulerApp.java`: (paste both)
