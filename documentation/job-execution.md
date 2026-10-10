# JobExecution — DBOS

`JobExecution` lives in `com.dbos.core`. It represents **one single run** of a job. This document explains what it holds, how a run changes over its life, and why it is shaped the way it is.

---

## 1. What it is

A `JobDefinition` is the permanent rule, such as "clean temp files every 10 seconds". It exists once and never carries a status.

A `JobExecution` is one run of that rule. Every time the rule fires, a new execution is created. Think of a recurring standup meeting: the recurring event is the definition, and Monday's standup is one execution. Monday's standup, once over, stays over. Tuesday's is a new one.

This is why status lives here and not on the job. A recurring job runs many times, and each run needs its own status, timing and outcome. A finished run is never reopened; the next run is a new object.

In the database it corresponds to one row of `JOB_EXECUTIONS`.

---

## 2. Fields

| Field | Type | Fixed once created? | Meaning |
|---|---|---|---|
| `id` | `long` | yes | Identifies this run. The store assigns the real value when the run is saved |
| `jobId` | `long` | yes | Which job this is a run of |
| `attemptNumber` | `int` | yes | 1 for the original run, 2 for the first retry, and so on |
| `scheduledFor` | `Instant` | yes | When the run is supposed to start |
| `status` | `JobStatus` | no | Where the run is in its life. Starts as `PENDING` |
| `workerId` | `Long` | no | Which worker claimed the run. Empty until claimed |
| `startedAt` | `Instant` | no | When it actually began. Empty until it starts |
| `endedAt` | `Instant` | no | When it finished. Empty until it ends |

**Why `jobId` and not the whole `JobDefinition`.** The database row stores a number that points at the job. Holding only the number keeps the object light and avoids loading a whole chain of objects just to look at one run.

**Why some fields are fixed and some are not.** The first four describe what the run *is* and never change. The last four describe how the run is *going*, and they fill in as it progresses.

---

## 3. Empty values and `Optional`

A run that has not started has no start time, no end time and no worker. Those fields are `null` internally, which honestly means "this has not happened yet". Two details follow:

- **`workerId` is a `Long`, not a `long`.** A primitive `long` always holds a number and cannot be empty. Using `0` to mean "nobody" would be a hack, and it could collide with a real id.
- **The getters for these three fields return `Optional`.** Handing out a raw `null` invites a `NullPointerException` the moment someone calls a method on it. With `Optional`, the caller has to deal with the empty case at the line where they use the value.

For a run that is still `PENDING`, `getStartedAt()` returns an empty `Optional`. For a finished run it holds the real time. A dashboard can show "still running" for a missing end time by mapping the value to text and falling back when it is empty.

The always-present fields (`id`, `jobId`, `attemptNumber`, `scheduledFor`, `status`) have ordinary getters.

---

## 4. The lifecycle

A run's status can only move along the legal paths defined by the `JobStatus` enum:

| From | Can move to |
|---|---|
| `PENDING` | `RUNNING`, `CANCELLED` |
| `RUNNING` | `DONE`, `FAILED` |
| `DONE` | nothing |
| `FAILED` | nothing for an execution (see the retry note below) |
| `CANCELLED` | nothing |

Each real event has its own method:

| Method | Event | Move | What it records |
|---|---|---|---|
| `markRunning(workerId, startedAt)` | a worker claims and starts the run | `PENDING` → `RUNNING` | the worker and the start time |
| `markDone(endedAt)` | the job finished successfully | `RUNNING` → `DONE` | the end time |
| `markFailed(endedAt)` | the job threw an error | `RUNNING` → `FAILED` | the end time |
| `cancel(endedAt)` | an operator stopped a run that had not started | `PENDING` → `CANCELLED` | the end time |

**Every method checks the move before changing anything.** It asks the enum whether the current status can go to the new one. If not, it throws an `IllegalStateException` and the run is left untouched. For example, calling `markDone` on a run that is still `PENDING` fails, because a run cannot finish without having started.

**There is no `setStatus`.** A plain setter would let any code put a run into any status, bypassing the rules. With one method per event, the only way to change status is through a legal move, and the status and its related times change together so they cannot disagree.

**Times are passed in, not read from the clock inside the method.** The engine decides what "now" is, and tests can use fixed times to get repeatable results.

**The error messages name both statuses.** For example, "cannot move from PENDING to DONE". A message that names the wrong status points a debugging session in the wrong direction.

### Retry note

A retry is not a status moving backwards. When a run fails and the retry policy allows another attempt, the engine creates a **new** `JobExecution` with the attempt number increased by one. The failed run stays `FAILED` permanently as part of the job's history. This is why `FAILED` has no outgoing moves for an execution, even though the enum still allows `FAILED` to `PENDING`.

---

## 5. Example: one run from start to finish

The job "clean temp files, every 10 seconds" is due at 12:00:10.

1. The engine creates a `JobExecution` for it: attempt 1, scheduled for 12:00:10. Status is `PENDING`. No worker, no start, no end.
2. A worker claims it. `markRunning(worker 2, 12:00:10)` makes it `RUNNING` with a worker and a start time.
3. The job runs and succeeds. `markDone(12:00:11)` makes it `DONE` with an end time.
4. The run is now permanent history. The engine asks the job's trigger for the next fire time and creates a new execution for 12:00:20.

If step 3 fails instead, `markFailed(12:00:11)` marks the run `FAILED`. The engine asks the retry policy about attempt 1. If a retry is allowed, a new execution is created with attempt 2, scheduled after the backoff delay.

---

## 6. How it relates to the rest of the system

- **`JobDefinition`** holds the rule. Many executions point at one definition through `jobId`.
- **`JobStatus`** supplies the legal moves. `JobExecution` is the only place that applies them to a run.
- **`JobStore`** will save executions, find them, and claim the next due one. Its contract is being redesigned around executions.
- **`JOB_EXECUTIONS` table** mirrors these fields. `EXECUTION_LOGS` records each status change separately, written by a database trigger, so a run does not need to remember its own history.

---

## 7. Not done yet

- **The constructor does not validate its inputs.** It currently accepts any values, including an attempt number below 1 or a missing scheduled time. These should be rejected at creation, since bad data caught there is easier to trace than a failure later in the engine.
- **The four lifecycle methods repeat the same check.** It can be moved into one private helper so the rule is written once.
- **Thread safety.** A run is mutable, so once several workers share runs, status changes will need the same protection as the store.
