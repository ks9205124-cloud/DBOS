# Core Interfaces — DBOS

This document explains the foundational types that everything else in DBOS is built on top of. Almost all other code (the engine, both `JobStore` implementations, the GUI) depends on these contracts staying stable, so they should not change without both team members reviewing the change together.

---

## What is a `Job`?

A `Job` is anything that knows how to run itself: a cleanup task, a database archiver, a report generator. The system does not need to know *what* a job does internally, only that it *can* execute.

```java
public interface Job {
    void execute() throws JobExecutionException;
}
```

- **No parameters.** A `Job` object already carries everything it needs internally (for example a database archiver holds its own connection, set up when the object was created). This keeps the interface generic: the engine can call `execute()` on any job without knowing its internals.
- **`void` return.** Success is simply "did not throw".
- **`throws JobExecutionException`.** A failure is an exceptional outcome, so it is communicated by an exception rather than a return value. The engine goes further and treats *anything* a job throws, even a runtime exception or an `Error`, as an ordinary failed run.
- **A lambda is enough.** Because `Job` has exactly one method, any job can be written as a lambda, which is how the demo and the tests create jobs.

---

## `Trigger`: "when should this run next?"

A `Trigger` answers a single timing question, independent of what the job does or whether it previously succeeded.

```java
public interface Trigger {
    Instant nextFireTime(Instant lastFireTime);
}
```

- **Input:** an `Instant`, because "when next" cannot be computed without "when last". If the last dose of a medicine was at 8:00 and it repeats every 4 hours, the next is 12:00.
- **Output:** also an `Instant`, the next point in time the job should fire.
- Implementations (`FixedRateTrigger`, `OneTimeTrigger`) compute this differently, but the engine only ever talks to the interface.

---

## `RetryPolicy`: "should I retry, and how long should I wait?"

Two questions about handling a failure, bundled into one interface.

```java
public interface RetryPolicy {
    boolean canRetry(int attemptNumber);
    Duration getRetryDelay(int attemptNumber);
}
```

- **`attemptNumber` is 1-based and is the attempt that just failed.** 1 is the original run, 2 is the first retry. A value below 1 is a bug and is rejected.
- **`canRetry`** returns a `boolean`. With a maximum of 3 retries, a retry is allowed while the failed attempt number is at most 3, so a job can run up to 4 times in total.
- **`getRetryDelay`** returns a `Duration` (a length of time, such as "wait 8 seconds"), not an `Instant`. The policy computes how long to wait; the engine adds that to the current time.
- Implementations (`ExponentialBackoffPolicy`, `FixedDelayPolicy`) hold the actual arithmetic. A job with no policy gets a `FixedDelayPolicy` with zero retries, which means "never retry".

---

## `JobStatus`: the lifecycle of a single execution

An enum with behavior: it lists the possible states of one `JobExecution` and also knows which moves between them are legal, so no code elsewhere can create an illegal transition.

| State | Meaning | Can move to |
|---|---|---|
| `PENDING` | Waiting to run (the starting state) | `RUNNING`, `CANCELLED` |
| `RUNNING` | Claimed by a worker and executing | `DONE`, `FAILED` |
| `DONE` | Finished successfully | nothing (final) |
| `FAILED` | Finished unsuccessfully | nothing (final) |
| `CANCELLED` | Stopped before it started running | nothing (final) |

**Status belongs to an execution, not to a job.** A `JobDefinition` is the recurring rule ("clean up every 10 seconds"); a `JobExecution` is one run of it. When a recurring job fires again, the engine creates a **new** execution instead of reopening an old one, which is why `DONE`, `FAILED` and `CANCELLED` are all final.

**A retry is a new execution, not a status moving backwards.** When a run fails and the retry policy allows another attempt, the engine creates a new execution with the attempt number increased by one. The failed run stays `FAILED` forever as part of the history. `CANCELLED` is likewise a state, not a deletion, so there is no delete operation anywhere in the system.

---

## `JobStore`: remembers everything

The storage seam: everything the engine needs from persistence, without caring whether the implementation is an in-memory structure or Oracle. Two implementations honor the same contract: `InMemoryJobStore` (no database, used for development and for demonstrating the object-oriented design) and `OracleJobStore` (the DBMS-backed version, in progress on the data track).

```java
public interface JobStore {
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    Optional<JobDefinition> getJob(long id);
    List<JobDefinition> getAllJobs();
    void cancelJob(long id);

    JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor);
    Optional<JobExecution> claimNextExecution(long workerId, Instant now);
    void updateExecution(JobExecution execution);
    List<JobExecution> listExecutions(long jobId);
}
```

- **`addJob`** returns the saved job (the store assigns the real id) and throws `DuplicateJobException` if a job with the same name already exists.
- **`getJob`** returns an `Optional`, because "not found" is a routine outcome, not a broken state.
- **`getAllJobs`** returns a possibly empty list; zero jobs is a normal state.
- **`cancelJob`** marks the job inactive and cancels its pending runs. A run that is already running finishes, and no follow-up run is created. Nothing is deleted.
- **`createExecution`** creates a new `PENDING` run. It refuses (with an `IllegalStateException`) if the job has been cancelled, which is how a schedule ends cleanly.
- **`claimNextExecution`** is the concurrency primitive: it atomically finds the earliest due `PENDING` run (the lower id wins a tie), marks it `RUNNING` for the given worker and returns it. No two workers can ever receive the same run.
- **`updateExecution`** saves a run's new state. It rejects stale copies that would move a run backwards and any change to the fields fixed at creation.
- **`listExecutions`** returns a job's full run history.
- **The store hands out copies of executions.** A caller's changes reach the store only through `updateExecution`, which is also how a database behaves. `JobDefinition` is immutable and is shared as is.

---

## Why this matters

Every one of these types is an abstraction the rest of the system uses without knowing the details underneath:
- The engine calls `job.execute()` without knowing what kind of job it is.
- The engine calls `trigger.nextFireTime(...)` without knowing whether it is fixed-rate or one-time.
- The engine calls `retryPolicy.canRetry(...)` without knowing whether backoff is exponential or fixed.
- The engine calls `store.claimNextExecution(...)` without knowing whether it is a synchronized method on a map or an Oracle query with row locking.
- Every status change goes through `JobStatus.canTransitionTo(...)`, so illegal transitions are impossible by construction.

This is what lets two people build the engine and the Oracle backend independently and have them come together correctly at the end.
