# The Engine — DBOS

This document explains the scheduling engine in `com.dbos.engine`: which class does what, the rules it follows, and how several workers run jobs at the same time without clashing. It builds on [`core-interfaces.md`](./core-interfaces.md) and [`job-execution.md`](./job-execution.md).

---

## 1. The pieces

| Class | Job |
|---|---|
| `SchedulerEngine` | The front door. Registers jobs, cancels them, and runs due work: `runNext(workerId)` processes one due run, `tick()` processes everything due right now on the calling thread |
| `RunPlanner` | The scheduling rules, written as pure functions of their inputs: when is the first run, the next run, a retry |
| `ExecutionProcessor` | Handles one claimed run from start to finish: run the job, record the outcome, schedule what comes next. Package-private, used only by the engine |
| `ExecutionListener` | Receives a notification for every event, so the engine itself never prints or logs |
| `WorkerPool` | A fixed pool of threads that keep calling `runNext` |

The engine is a plain class, not a singleton. It is created with the three things it depends on: a `JobStore`, a `Clock` and an `ExecutionListener`. That makes it easy to test (a test clock and an in-memory store) and to reuse (the same engine runs on the in-memory store or on Oracle).

---

## 2. The rules

These were decided once and are covered by tests.

1. **A job is a rule; an execution is one run.** Status lives on executions. `DONE`, `FAILED` and `CANCELLED` are final, and a retry is a new execution with the attempt number increased by one.
2. **One live run per job.** The next run is created only after the current chain of attempts ends.
3. **No drift.** The next run counts from the previous run's *scheduled* time, not from when it finished. A 10-second job scheduled for 12:00:10 that finishes at 12:00:12 is followed by a run at 12:00:20, not 12:00:22.
4. **Missed runs are not replayed.** If the next time is already in the past (a long outage, or workers all busy), it is moved up to "now". The result is one catch-up run, not a burst of back-to-back runs.
5. **A one-time job ends.** If the trigger's next time is not later than the run that just finished, the schedule is over and the listener is told.
6. **Anything a job throws is an ordinary failure.** That includes runtime exceptions and even an `Error`, so one badly behaved job can never crash a worker or leave its run stuck as `RUNNING`. Listener exceptions are swallowed for the same reason.
7. **After the retries run out, the regular schedule continues** with attempt 1.
8. **Cancelling** marks the job inactive and cancels its pending runs. A run that is already running finishes, and no follow-up run is created.
9. **A missing retry policy means "no retries".**

Worked example, fixed rate of 10 seconds: registered at 12:00:00, the first run is 12:00:10. If it finishes at 12:00:12, the next is 12:00:20. If it instead finishes at 12:00:35, the next is 12:00:35, because 12:00:20 is already past.

---

## 3. What happens to one run

A worker calls `runNext(workerId)`:

1. The store atomically claims the earliest due `PENDING` run and marks it `RUNNING` for that worker. If nothing is due, `runNext` returns false.
2. The processor loads the job's rule and tells the listener the run started.
3. The job executes. Success or failure is decided by whether it returned or threw.
4. The run is marked `DONE` or `FAILED` and saved, and the listener is told.
5. On success the processor asks the trigger for the next run. On failure it asks the retry policy; if a retry is allowed a new execution is created after the delay, otherwise the regular schedule continues. A cancelled job simply ends here.

All time comes from the injected `Clock`, never from the system clock directly, so tests move time by hand and never sleep.

---

## 4. Running many jobs at once: `WorkerPool`

`WorkerPool` starts N worker threads from a fixed pool. Each worker has an id (1 to N) and loops: call `runNext`; if it processed a run, go straight back for more (there may be a backlog); if nothing was due, wait one poll interval and try again.

**Why it is correct.** The workers share nothing except the store, and the store's claim is atomic, so two workers can never receive the same run. The thread type is irrelevant to correctness. This is why the pool uses ordinary platform threads: a thread is just a place to run the loop, and the guarantee comes from the claim. Threads are created through a `ThreadFactory`, so moving to virtual threads later means passing a different factory and changing nothing else. That is a good extension to mention in the report.

**A worker never dies.** If anything unexpected happens (for example the store throws), the worker reports it through the listener's `onWorkerError` event, waits one poll interval so a broken store cannot cause a hot loop, and carries on. A stray interrupt flag left behind by a job is cleared after each run.

**Stopping is graceful.** `stop()` does not interrupt anything. It tells workers to finish what they are doing and exit, wakes idle workers immediately (they wait on a latch, not a sleep), and then waits up to a timeout. It returns true if every worker exited. If a long job outlasts the timeout it returns false, the workers still exit by themselves when their run ends, and `stop()` can be called again to keep waiting. A job is therefore never cut off halfway.

**A pool is single-use.** It is started once and stopped once or more; to run again, create a new pool. `tick()` and a running pool should not be mixed, because `tick()` reports itself as worker 1.

---

## 5. How it is tested

- **Rules (`RunPlannerTest`, 13 tests)** check each scheduling rule as a pure function.
- **Engine (`SchedulerEngineTest`, 16 tests)** runs whole scenarios on a test clock that only moves when the test says so: recurrence, retries with backoff, cancelling, catch-up behavior, listener order and failure handling.
- **Workers (`WorkerPoolTest`, 10 tests)** use real threads. The main test runs 20 slow jobs on 4 workers and checks that every run executed exactly once, at least two workers took part, and runs genuinely overlapped. Overlap is proven by two jobs that can only finish if they run at the same moment, so the test cannot pass by luck. Other tests cover stopping without interrupting a running job, the stop timeout, a worker surviving store failures and a failing job.
- **Store (`JobStoreContractTest`, `InMemoryJobStoreTest`)** define what any `JobStore` must do, including concurrent claims never returning the same run, so the Oracle store can be checked against the same contract.
