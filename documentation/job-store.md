# JobStore — DBOS

`JobStore` lives in `com.dbos.store`. It defines the persistence contract for the entire system: everything the execution engine needs to read and write jobs and runs, without knowing whether the underlying storage is an in-memory structure or an Oracle database.

---

## 1. What it is and why it exists

The `JobStore` interface is the core architectural seam of DBOS. By separating the execution engine completely from data storage, it allows two parallel tracks of development:
- **The Engine Track:** Builds the scheduling loops, thread pools, and execution logic using the `JobStore` interface.
- **The Data Track:** Builds the database schema, DDL, PL/SQL procedures, and `OracleJobStore`.

Because the engine only talks to this interface, `InMemoryJobStore` and `OracleJobStore` are completely interchangeable without modifying engine code.

---

## 2. The Contract Methods

The interface is divided into two logical groups: job definition management and execution/concurrency management.

```java
public interface JobStore {
    // --- Job Definition Management ---
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    Optional<JobDefinition> getJob(long id);
    List<JobDefinition> getAllJobs();
    void cancelJob(long id);

    // --- Execution & Concurrency Methods ---
    JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor);
    Optional<JobExecution> claimNextExecution(long workerId, Instant now);
    void updateExecution(JobExecution execution);
    List<JobExecution> listExecutions(long jobId);
}
```

### Job Definition Management
- **`addJob(JobDefinition job)`** — Registers a new job template. Returns the saved job (with its generated primary key ID) and throws `DuplicateJobException` if a job with the same name already exists (a genuinely abnormal case).
- **`getJob(long id)`** — Returns an `Optional<JobDefinition>`. "Not found" is treated as a routine, expected outcome rather than an error condition, avoiding mandatory try/catch blocks.
- **`getAllJobs()`** — Returns a `List<JobDefinition>` of all registered job rules in the system.
- **`cancelJob(long id)`** — Soft-cancels a job rule by marking it inactive, preventing further execution generation while preserving historical records.

### Execution & Concurrency Management
- **`createExecution(long jobId, int attemptNumber, Instant scheduledFor)`** — Instantiates a new run ticket for a job, defaulting to `PENDING` status.
- **`claimNextExecution(long workerId, Instant now)`** — The core concurrency primitive. Atomically finds the earliest due `PENDING` execution, marks it as `RUNNING` for the given worker ID, and returns it. In memory, this uses synchronized locking; in Oracle, it maps to `SELECT ... FOR UPDATE SKIP LOCKED`.
- **`updateExecution(JobExecution execution)`** — Persists modified execution state back to the store after a worker changes its progress (e.g., marking it `DONE` or `FAILED`).
- **`listExecutions(long jobId)`** — Retrieves the complete audit history of all run attempts for a specific job.

---

## 3. Design Principles

- **Execution-Aware Seam:** Because a recurring job template is immutable and separate from its individual runs, state tracking and status updates are handled strictly on `JobExecution` objects rather than on job definitions.
- **At-Least-Once Support:** The atomic claim pattern guarantees that no two workers can claim the same execution simultaneously, preventing race conditions.
- **Copies Across the Boundary:** the store hands out copies of executions, and a caller's changes reach the store only through `updateExecution`. `JobDefinition` is immutable and shared as is. This keeps the in-memory store honest about how a database behaves.
- **No Deletes:** Deletion methods are intentionally absent. All cancellations and terminations are captured via status transitions to maintain an intact audit trail.