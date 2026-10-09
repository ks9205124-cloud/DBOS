# Core Interfaces — DBOS

This document explains the five foundational types that everything else in DBOS is built on top of. They were finalized on Day 1 and should not change without both team members reviewing the change together — almost all other code (the engine, both `JobStore` implementations, the GUI) depends on these contracts staying stable.

---

## What is a `Job`?

A `Job` is anything that knows how to run itself — a cleanup task, a database archiver, a report generator. The system doesn't need to know *what* a job does internally, only that it *can* execute.

```java
public interface Job {
    void execute() throws JobExecutionException;
}
```

- **No parameters** — a `Job` object already carries everything it needs internally (e.g. a `DatabaseArchiverJob` holds its own database connection, set up when the object was created, not passed in at execution time). This keeps the interface generic: the engine can call `.execute()` on any job without knowing its internals.
- **`void` return** — success is simply "didn't throw."
- **`throws JobExecutionException`** — a failure (locked file, unreachable DB, etc.) is treated as an exceptional, unexpected outcome, not a routine one, so it's communicated via an exception rather than a return value.

---

## `Trigger` — "when should this run next?"

A `Trigger` answers a single timing question, independent of what the job actually does or whether it previously succeeded or failed.

```java
public interface Trigger {
    Instant nextFireTime(Instant lastRun);
}
```

- **Input:** `lastRun`, an `Instant` (a specific point in time) — you can't compute "when next" without knowing "when last." Same logic as a medicine reminder: if the last dose was at 8:00 AM and it repeats every 4 hours, the next dose is 12:00 PM — the calculation needs the last timestamp.
- **Output:** also an `Instant` — the next point in time the job should fire.
- Implementations (`FixedRateTrigger`, `OneTimeTrigger`, later `CronTrigger`) each compute this differently, but the engine only ever talks to the interface.

---

## `RetryPolicy` — "should I retry, and how long should I wait?"

Two separate questions bundled into one interface, since they're both about handling a failure.

```java
public interface RetryPolicy {
    boolean canRetry(int attemptNumber);
    Duration backoffFor(int attemptNumber);
}
```

- **`canRetry(int attemptNumber)`** — takes the current attempt number (not a configured max — that's a setting the specific policy implementation already knows internally). Returns a `boolean` since it's a yes/no question.
- **`backoffFor(int attemptNumber)`** — returns a `Duration` (a *length* of time, like "wait 8 seconds"), not an `Instant` (a specific clock time). A backoff policy computes *how long to wait*, not *what time it currently is*.
- Implementations (`ExponentialBackoffPolicy`, `FixedDelayPolicy`) contain the actual math (e.g. `2^attemptNumber` seconds); the interface itself has none.

---

## `JobStatus` — the lifecycle of a single execution

An enum representing the fixed set of states one `JobExecution` can be in, plus the rules for which transitions between them are legal. This is an **enum with behavior** — the transition rules are baked into the type itself, so no code elsewhere in the system can set an illegal transition.

```java
public enum JobStatus {
    PENDING, RUNNING, DONE, FAILED, CANCELLED;

    boolean canTransitionTo(JobStatus next) {
        return switch (this) {
            case PENDING -> next == RUNNING || next == CANCELLED;
            case RUNNING -> next == DONE || next == FAILED;
            case FAILED  -> next == PENDING;
            case DONE, CANCELLED -> false;
        };
    }
}
```

| State | Meaning | Can transition to |
|---|---|---|
| `PENDING` | Waiting to run (default starting state) | `RUNNING`, `CANCELLED` |
| `RUNNING` | Currently executing | `DONE`, `FAILED` |
| `DONE` | Finished successfully | *(nothing — permanent)* |
| `FAILED` | Finished unsuccessfully | `PENDING` *(if the `RetryPolicy` allows another attempt)* |
| `CANCELLED` | Manually stopped before running | *(nothing — permanent)* |

**Important distinction:** `JobStatus` belongs to a single `JobExecution` (one specific run), not to the recurring `JobDefinition` (the rule, like "clean up every 10 seconds"). When a recurring job fires again, the engine creates a **new** `JobExecution` record rather than reopening an old `DONE` one — this is why `DONE` has no outgoing transitions. Recurrence is handled by creating new records, not reusing old ones. Likewise, `CANCELLED` is a state, not a deletion — the job still exists for audit history, so there is no separate "delete" operation anywhere in the system.

---

## `JobStore` — remembers everything

The storage seam: everything the engine needs from persistence, without caring whether the underlying implementation is an in-memory structure or Oracle. Two implementations will exist — `InMemoryJobStore` (no database required, used for early development and demoing pure OOP) and `OracleJobStore` (the real DBMS-backed version) — both honoring this exact same contract.

```java
public interface JobStore {
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    Optional<JobDefinition> getJob(long id);
    List<JobDefinition> getAllJobs();
    void updateStatus(long id, JobStatus newStatus);
}
```

- **`addJob`** — returns the saved `JobDefinition` (useful since a new ID may be generated on save) and throws `DuplicateJobException` if the job already exists — a genuinely abnormal case.
- **`getJob(long id)`** — returns `Optional<JobDefinition>` rather than throwing. "Not found" is a routine, expected outcome (e.g. a job could be cancelled by one operator the instant before another operator looks it up), not a broken state — `Optional` lets the caller check for absence without a mandatory try/catch.
- **`getAllJobs()`** — returns a `List<JobDefinition>`, possibly empty. No exception needed; zero jobs is a normal state, especially right after startup.
- **`updateStatus(long id, JobStatus newStatus)`** — returns `void`. The caller already supplies both the id and the new status themselves, so there's nothing new to hand back.
- **No delete method** — deliberately. Since cancellation is modeled as a `JobStatus` transition (`updateStatus(id, CANCELLED)`) rather than row deletion, a separate delete operation would work against the design and destroy audit history.

---

## Why this matters

Every one of these five types is an abstraction the rest of the system depends on without needing to know the details underneath:
- The engine calls `job.execute()` without knowing what kind of job it is.
- The engine calls `trigger.nextFireTime(...)` without knowing if it's a fixed-rate or one-time trigger.
- The engine calls `retryPolicy.canRetry(...)` without knowing if backoff is exponential or fixed.
- The engine calls `store.addJob(...)` without knowing if it's writing to a `HashMap` or an Oracle table.
- Every status change goes through `JobStatus.canTransitionTo(...)`, so illegal transitions are impossible by construction, not just checked ad hoc in scattered places.

This is what lets two people build the engine and the Oracle backend independently and have them come together correctly at the end.
