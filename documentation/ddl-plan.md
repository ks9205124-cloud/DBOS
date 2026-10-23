# DDL Plan — DBOS (Data Track)

A step-by-step plan for writing the Oracle schema scripts. The target is the 10-table design in `database-design.md`, cross-checked against the ER diagram. The point of writing it by hand is to learn it, so this plan gives the order, the rules and the checks, not the finished SQL.

**Deliverables**
- Re-runnable scripts that create the schema, indexes and seed data
- A drop/reset script
- A negative-test script proving the constraints reject bad data
- A short note for the engine track on column names, types and decisions

---

## Step 0: Lock the decisions first (about 1 hour, together with the engine track)

Changing these after the scripts exist is painful, so settle them before writing any SQL.

| Decision | Recommendation |
|---|---|
| Name of the job table | `JOB_DEFINITIONS` (matches the ER diagram) |
| Key naming | Every primary key is `id`. Every foreign key is `<entity>_id`, so `retry_policy_id`, not `policy_id` |
| Job name uniqueness | `JOB_DEFINITIONS.name` is UNIQUE. The in-memory store rejects duplicate names, and Oracle must behave the same |
| Active flag | `is_active` on `JOB_DEFINITIONS`, `NUMBER(1)`, default 1 |
| Boolean type | `NUMBER(1)` with a CHECK of 0 or 1. Oracle XE 21c has no boolean type |
| Timestamp type | Pick one and use it everywhere: `TIMESTAMP` holding UTC, or `TIMESTAMP WITH TIME ZONE`. The engine track's `OracleJobStore` conversion from Java `Instant` depends on this, so tell them |
| "No retry" policy | A seeded `RETRY_POLICIES` row with `max_retries = 0`, so the foreign key is never NULL |
| Logs when an execution is deleted | Decide between ON DELETE CASCADE and no cascade. Cascade conflicts with "history is never erased" if the archive procedure deletes old executions |
| `type_code` on `JOB_TYPES` | Keep it (UNIQUE) or drop it. The ER diagram currently omits it |

---

## Step 1: Set up the environment (1-2 hours)

1. Install Oracle XE and SQL Developer.
2. Check the version with `SELECT banner FROM v$version;`. Identity columns need 12c or later, which any current XE satisfies.
3. Connect to the pluggable database (service `XEPDB1` on XE), not the root container.
4. Create a dedicated user for the project, such as `dbos`, instead of working as `SYSTEM`. Grant it the right to connect and to create tables, views, procedures and triggers, plus space to store data.
5. Keep credentials out of git. The `.gitignore` already excludes common credential file names, but check that anything you create is covered.

---

## Step 2: Lay out the scripts

Put everything in an `sql/` folder, numbered so the order is obvious:

| File | Purpose |
|---|---|
| `01_tables.sql` | All tables, keys and constraints |
| `02_indexes.sql` | Indexes, kept separate so you can capture a query plan before and after |
| `03_seed.sql` | Demo roles, users, types, triggers, policies and jobs |
| `99_drop_all.sql` | Removes everything in the right order |
| `tests/negative_inserts.sql` | Inserts that must fail |

Every script must be safe to run twice. Run them in SQL Developer as a script (F5), not as a single statement, or only the first statement executes.

---

## Step 3: Create the tables, in dependency order

A table with a foreign key can only be created after the table it points at, so the order is:

1. `ROLES`
2. `USERS`
3. `JOB_TYPES`
4. `TRIGGERS`
5. `RETRY_POLICIES`
6. `JOB_DEFINITIONS`
7. `JOB_DEPENDENCIES`
8. `WORKERS`
9. `JOB_EXECUTIONS`
10. `EXECUTION_LOGS`

Do them one or two at a time and check each in SQL Developer before moving on. Use identity columns for primary keys, and give every constraint an explicit name, for example `pk_users`, `fk_users_role`, `ck_exec_status`. A named constraint makes an error message readable. Without names, Oracle generates ones like `SYS_C0012345`.

### Constraint checklist

| Table | Must have |
|---|---|
| `ROLES` | `name` NOT NULL and UNIQUE, CHECK in `ADMIN`, `OPERATOR`, `VIEWER` |
| `USERS` | `username` UNIQUE and NOT NULL, `password_hash` NOT NULL (a hash, never a plain password), FK to `ROLES`, `created_at` default current time |
| `JOB_TYPES` | `class_name` NOT NULL, `type_code` UNIQUE if kept |
| `TRIGGERS` | `trigger_type` CHECK in `FIXED_RATE`, `ONE_TIME`. A table-level CHECK tying the columns to the type: FIXED_RATE needs `interval_seconds` greater than 0 and no `fire_at`; ONE_TIME needs `fire_at` and no `interval_seconds` |
| `RETRY_POLICIES` | `retry_type` CHECK in `EXPONENTIAL`, `FIXED`. `max_retries` and `backoff_seconds` both at least 0 |
| `JOB_DEFINITIONS` | `name` NOT NULL and UNIQUE. FKs to `JOB_TYPES`, `TRIGGERS`, `RETRY_POLICIES` and `USERS`, all NOT NULL. `is_active` default 1, CHECK 0 or 1. `created_at` default current time |
| `JOB_DEPENDENCIES` | Composite primary key of the two job ids. Both FK to `JOB_DEFINITIONS`. CHECK that the two ids differ |
| `WORKERS` | `status` CHECK in `ACTIVE`, `DEAD`. `last_heartbeat` NOT NULL |
| `JOB_EXECUTIONS` | FK to `JOB_DEFINITIONS` NOT NULL. FK to `WORKERS` **nullable**. `status` CHECK in `PENDING`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`. `attempt_number` at least 1, default 1. `scheduled_for` NOT NULL. `started_at` and `ended_at` nullable. CHECK that `ended_at` is not before `started_at` |
| `EXECUTION_LOGS` | FK to `JOB_EXECUTIONS` NOT NULL. `log_level` CHECK in `INFO`, `WARN`, `ERROR`. `message` NOT NULL |

For each foreign key, choose its ON DELETE behavior on purpose. The default (no action) is the safe choice for almost all of them, since nothing in the design deletes rows.

---

## Step 4: Indexes (separate script)

- **`JOB_EXECUTIONS (status, scheduled_for)`** supports the claim query that finds the next due pending run.
- **Foreign key columns used in joins:** `job_id` and `worker_id` on `JOB_EXECUTIONS`, and `execution_id` on `EXECUTION_LOGS`. Oracle does **not** index foreign keys automatically. Primary keys and UNIQUE constraints are indexed automatically.

For the report, run `EXPLAIN PLAN` on the claim query with some seed data *before* creating the status index, save the output, then create the index and capture the plan again. That before-and-after is a strong DBMS-report item, and it's why this script is separate.

---

## Step 5: Seed data (`03_seed.sql`)

Insert in dependency order, and make it re-runnable.

- 3 roles and 3 users (admin, operator, viewer). Use placeholder hashes for now, not real passwords.
- 4 job types matching the demo jobs (temp-file cleanup, flaky ingestion, slow job, database archiver). **Agree the exact `class_name` values with the engine track,** since they must match real Java classes.
- A few triggers (a 5-second fixed rate, a 30-second fixed rate, a one-time).
- Retry policies, including the "no retry" row.
- Around 5 job definitions so the list isn't empty on startup.

Later, a PL/SQL loop can generate a few hundred fake past executions for the analytics queries.

---

## Step 6: Drop and reset (`99_drop_all.sql`)

Drop in the reverse of the creation order, using `DROP TABLE ... CASCADE CONSTRAINTS PURGE`. Also write a lighter reset that clears only the runtime tables (executions, logs, workers) and reloads the seed, so every demo rehearsal starts clean.

---

## Step 7: Verify

**Positive checks**
- Drop, create and seed run clean twice in a row.
- `SELECT table_name FROM user_tables;` shows exactly 10 tables.
- Compare the result against the ER diagram's relational conversion and `database-design.md`, column by column.

**Negative checks** (each of these must be *rejected*, and it's worth saving them as a script):
1. A second job definition with an existing name
2. An execution with status `PAUSED`
3. An execution with an `ended_at` before its `started_at`
4. A job that depends on itself
5. A FIXED_RATE trigger that has `fire_at`
6. A ONE_TIME trigger with no `fire_at`
7. An execution pointing at a job id that doesn't exist
8. A user with a role that doesn't exist
9. A retry policy with a negative `max_retries`
10. A second user with the same username

If any of these succeeds, a constraint is missing.

---

## Step 8: Hand off and commit

- Work on a **separate branch for the data track**, so the engine track's pull request stays readable.
- Commit the scripts in pieces: tables, then indexes, then seed, then tests.
- Send the engine track: the final column names and types, the timestamp decision, the `class_name` list, and the unique-name rule.

---

## Oracle gotchas to know up front

- **Empty string is NULL in Oracle.** A blank name violates NOT NULL.
- **DDL commits automatically,** and there is no rollback for a CREATE or DROP.
- **Avoid reserved words** as names. `LEVEL` is one, which is why the column is `log_level`.
- **A UNIQUE constraint allows multiple NULLs.** Make a column NOT NULL as well if it should always be set.
- **Unique names are case-sensitive.** `Cleanup` and `cleanup` are different values.

---

## After the DDL passes

The next layer is PL/SQL: the claim procedure with row locking, the audit trigger on `JOB_EXECUTIONS`, the retry procedure with a savepoint, the health view and success-rate function, the archive cursor procedure, and roles with privileges. These sit on top of a schema that already works.

**Rough effort:** decisions and setup about half a day, tables and constraints about half a day, indexes, seed and reset about half a day, verification a few hours.
