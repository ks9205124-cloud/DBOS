# Demo Script — DBOS (OOP side)

A run order for showing the project to an examiner: about 8 minutes, with the time split between the console demo, the GUI and the tests. Everything runs on the in-memory store, so no database is needed.

---

## Before you start

- Open the project in IntelliJ with the JDK set to 21 or newer.
- Run `mvn test` once (Maven panel, Lifecycle, test) and confirm 81 passing tests. Have that result visible.
- Have two things ready to run: `com.dbos.cli.Demo` and `com.dbos.gui.SchedulerApp`.

---

## Part 1: the architecture in one minute

Say: *DBOS is a job scheduler like Quartz, built from scratch in plain Java. A job is a rule; each run of it is an execution, and status belongs to the execution. The engine only talks to interfaces, so the same engine runs on an in-memory store or on Oracle.*

Show the package layout, then point at the four interfaces and what each one is a strategy for: `Job` (what to run), `Trigger` (when), `RetryPolicy` (what to do after a failure), `JobStore` (where state lives).

---

## Part 2: console demo (2 minutes)

Run `Demo`. It runs for 15 seconds with four workers. Point out each of these as it appears in the output:

1. **Recurrence:** `cleanup` runs every 2 seconds on whichever worker is free.
2. **Retry with backoff:** `flaky-ingest` fails on attempt 1, is retried one second later and fails again, is retried two seconds after that and succeeds on attempt 3. The gaps double: that is exponential backoff.
3. **One-time job:** `one-off-report` runs once and then prints `schedule ended`.
4. **Real concurrency:** `slow-1`, `slow-2` and `slow-3` all start in the same instant on three different workers, and all finish three seconds later. Meanwhile `cleanup` keeps its two-second rhythm on the fourth worker. Run sequentially this would take nine seconds.

Say: *The engine never prints. It reports events through a listener, and the demo's listener is what prints these lines.*

---

## Part 3: GUI (2 minutes)

Run `SchedulerApp`.

1. Register a job named `report` with job type **Slow**, trigger **One-time in 2 seconds**, no retry. Repeat for `report-2` and `report-3`.
2. Select each job and show the **Worker** column: three runs, three different workers, overlapping times. The window stays responsive the whole time.
3. Register `flaky` with job type **Flaky**, fixed rate every 10 seconds, retry **Exponential backoff**, max 3, delay 2. Select it and show its runs: two `FAILED` attempts, then `DONE`, and the Events pane telling the story.
4. Select `flaky` and click **Cancel selected job**. Show that its pending run becomes `CANCELLED` and nothing new appears.

---

## Part 4: the tests (1 minute)

Show the Maven result (81 passing). Open `WorkerPoolTest` and describe the main test: 20 slow jobs on 4 workers, each run executes exactly once, several workers take part, and two jobs rendezvous with each other, which can only happen if two run at the same time. Mention that tests use a fake clock, so the 15-second scenarios in the engine tests run in milliseconds.

---

## Part 5: the OOP concepts to name (1 minute)

| Concept | Where to point |
|---|---|
| Interfaces and polymorphism | `Trigger`, `RetryPolicy`, `JobStore`, `Job` |
| Strategy pattern | swapping `FixedRateTrigger` for `OneTimeTrigger`, or `FixedDelayPolicy` for `ExponentialBackoffPolicy` |
| Builder pattern, static nested class | `JobDefinition.builder(...)` with validation in `build()` |
| Immutability | `JobDefinition` changes only by copying (`withId`, `withActive`) |
| Enum with behavior | `JobStatus.canTransitionTo` |
| Encapsulation and guarded state | `JobExecution` has no `setStatus`; each event has its own method that checks the move |
| Observer pattern | `ExecutionListener` |
| Dependency injection | engine takes a store, a clock and a listener |
| Custom exceptions, `Optional` | `JobExecutionException`, `DuplicateJobException`; `getJob` and the optional times |
| Concurrency | `WorkerPool`, atomic `claimNextExecution`, copies across the store boundary |

---

## Questions an examiner may ask

- **How do you stop two workers running the same job?** The store's claim is one atomic step: find the earliest due run and mark it running. In memory that is a synchronized method; in Oracle it would be `SELECT ... FOR UPDATE SKIP LOCKED`. The tests hammer it from many threads.
- **Why platform threads and not virtual threads?** Correctness comes from the atomic claim, not from the thread type. Threads come from a `ThreadFactory`, so virtual threads are a one-line change and a sensible extension.
- **What if a job crashes or throws an `Error`?** Anything a job throws is an ordinary failed run; the worker survives and the retry policy decides what happens next.
- **What if the engine is stopped while a job runs?** `stop()` lets running jobs finish and reports whether they did within the timeout. Nothing is interrupted.
- **What does not exist yet?** The Oracle store is the other half of the project and is built separately, behind the same interface. `@Scheduled` annotations and cron triggers were planned as extras and were not built; the engine is designed so they would plug in without changes.
- **Does a missed run catch up?** One catch-up run happens, not a burst. That was a deliberate rule.
