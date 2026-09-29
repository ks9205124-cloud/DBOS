# DBOS — Database-Backed Job Orchestrator
### Combined OOP (CSC2104) + DBMS Lab (CSC2131) Mini Project Spec

**Team:** 2 members · **Timeline:** 4 weeks · **Stack:** Java 21, Oracle DB, plain Java (no Spring)

---

## 1. What we're building

A job scheduling/orchestration engine, similar in spirit to Quartz or Spring's `@Scheduled`, but built from scratch with:
- A pluggable storage layer (in-memory for demoing pure OOP, Oracle for the DBMS half)
- Dependency-aware job execution, retries with backoff, and role-based access
- Full Oracle backend: schema, PL/SQL procedures/functions/cursors/triggers, views, privileges

One codebase, two gradable halves — see the seam in Section 3.

---

## 2. Package structure & OOP concepts

```
com.dbos.core        → Job, JobDefinition, JobStatus (enum w/ transition rules), exceptions
com.dbos.trigger      → Trigger (interface), FixedRateTrigger, OneTimeTrigger, CronTrigger
com.dbos.retry        → RetryPolicy (interface), ExponentialBackoffPolicy, FixedDelayPolicy
com.dbos.store        → JobStore (interface), InMemoryJobStore, OracleJobStore
com.dbos.engine        → SchedulerEngine (singleton), WorkerPool (virtual threads)
com.dbos.annotation    → @Scheduled, TaskScanner (reflection)
com.dbos.security     → Role, Permission, AccessControlException
com.dbos.cli           → Console menu (thin — calls the engine only)
```

| Concept | Where |
|---|---|
| Interfaces + polymorphism | `Trigger`, `RetryPolicy`, `JobStore` |
| Builder pattern | `JobDefinition.builder()...build()` |
| Singleton | `SchedulerEngine.getInstance()` |
| Strategy pattern | swappable trigger/retry behavior |
| Reflection + custom annotations | `@Scheduled`, `TaskScanner` |
| Static nested class | `JobDefinition.Builder` |
| Enum with behavior | `JobStatus.canTransitionTo(...)` |
| Custom exceptions | `JobExecutionException`, `AccessControlException`, `ConcurrentClaimException` |
| Concurrency | Virtual thread `WorkerPool` |

**The one rule that keeps this sane:** the engine (`core`, `trigger`, `retry`, `engine`) must never import anything Oracle-specific. It only talks to `JobStore`, an interface. This is what lets the app run and demo with zero DB setup.

---

## 3. The seam: who owns what

- **Person A — Engine track:** `core`, `trigger`, `retry`, `engine`, `annotation`, `cli`, `InMemoryJobStore`
- **Person B — Data track:** Oracle schema, PL/SQL, `OracleJobStore`, `security`, the DBMS report

**Contract to agree on Day 1 and never break:** the exact method signatures of the `JobStore` interface (e.g. `save`, `claimNext`, `updateStatus`, `findById`). Once that's fixed, both people can work independently for the rest of the month.

---

## 4. Oracle schema

- `USERS` (id, username, password_hash, role_id)
- `ROLES` (id, name) → OPERATOR, VIEWER, ADMIN
- `JOB_TYPES` (id, class_name, description)
- `JOB_DEFINITIONS` (id, name, job_type_id, trigger_type, trigger_config, max_retries, backoff_seconds, status, created_by → USERS, next_run_time)
- `JOB_DEPENDENCIES` (job_id, depends_on_job_id) — drop this table first if time runs short
- `JOB_EXECUTIONS` (id, job_id, worker_id, started_at, ended_at, status, attempt_number)
- `EXECUTION_LOGS` (id, execution_id, log_level, message, logged_at)
- `WORKERS` (id, hostname, last_heartbeat)

Deliverables: ER diagram (ERDplus/Dia), normalization writeup to 3NF, full DDL with FK/CHECK constraints.

---

## 5. PL/SQL objects (CSC2131.4 / CSC2131.5)

| Object | Purpose |
|---|---|
| Procedure `claim_next_job(p_worker_id)` | `SELECT ... FOR UPDATE SKIP LOCKED`, checks `JOB_DEPENDENCIES`, returns a job |
| Procedure `retry_job(p_job_id)` | Reschedule logic wrapped in `SAVEPOINT` / `ROLLBACK TO` (TCL) |
| Function `success_rate(p_job_id) RETURN NUMBER` | Aggregate over `JOB_EXECUTIONS` |
| Procedure `archive_old_executions` | Cursor loop over old executions → archive table |
| Trigger `trg_log_execution_change` | AFTER UPDATE on `JOB_EXECUTIONS` → writes `EXECUTION_LOGS` |
| View `v_job_health` | Join across definitions + executions + success rate |
| View `v_active_operators` | Simple filtered view for the VIEWER role |
| Privileges | `GRANT SELECT ON v_job_health TO viewer_role;` — VIEWER cannot touch `JOB_DEFINITIONS` |
| Index | Composite on `(status, next_run_time)` — include an `EXPLAIN PLAN` before/after in the report |

**Report queries** (joins + subqueries + group by, for the DBMS write-up):
1. Top 5 jobs by failure rate — join + `GROUP BY` + `HAVING`
2. Jobs currently slower than their type's average duration — correlated subquery
3. Users who've never had a job fail — `NOT EXISTS` subquery

---

## 6. Grading map

| Requirement | Covered by |
|---|---|
| ER diagram + normalization | Section 4 |
| DDL + constraints | Section 4 |
| DML / joins / subqueries / group-by | Section 5, report queries |
| TCL + privileges | `retry_job` savepoints, ROLES/VIEWER grant |
| PL/SQL + cursors + triggers | Section 5 |
| OOP + advanced Java | Section 2 |
| Live demo (both courses) | CLI in Section 7 |

---

## 7. CLI (demo mode)

```
=== DBOS: DATABASE-BACKED JOB ORCHESTRATOR ===
1. Register New Job
2. List All Registered Jobs
3. View Live Execution History
4. Start / Pause Scheduler
5. Cancel / Unschedule Job
6. View Oracle DB Metrics Summary
0. Exit
```

Startup reconciler: on launch, mark orphaned `RUNNING` rows `FAILED`, re-enqueue retryable jobs, spin up the virtual thread pool.

**Crash-recovery demo — important fix:** don't use Ctrl+C, it triggers graceful JVM shutdown hooks and proves nothing. Use `taskkill /F` (Windows) or kill the process from IntelliJ's process list, then relaunch and show Oracle preserved state.

---

## 8. Timeline (4 weeks, 2 people)

| Week | Engine track (A) | Data track (B) |
|---|---|---|
| 1 | Core engine + `JobStore` interface + `InMemoryJobStore`, working CLI demo, no DB needed | Draft ER diagram, finalize DDL, agree on `JobStore` contract |
| 2 | Retry/backoff strategies, `WorkerPool` (virtual threads) | Oracle schema live, `OracleJobStore`, basic CRUD |
| 3 | Annotation scanner (`@Scheduled`, `TaskScanner`) | `claim_next_job` procedure + `SKIP LOCKED`, audit trigger, cursor procedure |
| 4 | RBAC wiring, polish, integration testing, crash-recovery demo | Views, privileges, analytics queries, DBMS report + ER diagram writeup |

**If behind schedule, cut in this order:** `JOB_DEPENDENCIES` table → metrics CLI screen → annotation scanner → everything else stays.

---

## 9. Definition of done

- [ ] App runs with zero DB setup (in-memory mode)
- [ ] App runs against Oracle, survives a hard kill, resumes cleanly
- [ ] All PL/SQL objects in Section 5 exist and are demonstrated live
- [ ] ER diagram + normalization writeup in the DBMS report
- [ ] Both reports (OOP + DBMS) reference the same GitHub repo
- [ ] Oracle Academy PL/SQL certification attempted (Case 2 marks)
