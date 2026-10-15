package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Thread-safe, in-memory JobStore.
 *
 * - Every public method is synchronized, so each check-then-act step
 *   (duplicate name, claim, cancel) is atomic.
 * - JobDefinition is immutable, so it is shared as is.
 * - JobExecution is mutable, so it is copied every time it crosses the
 *   store's boundary. A caller's changes only reach the store through
 *   updateExecution, which is how the Oracle store behaves too.
 * - The store assigns every id itself, whatever id an incoming object carries.
 */
public class InMemoryJobStore implements JobStore {

    private final Map<Long, JobDefinition> jobs = new LinkedHashMap<>();
    private final Map<Long, JobExecution> executions = new LinkedHashMap<>();
    private final AtomicLong jobIdCounter = new AtomicLong(0);
    private final AtomicLong executionIdCounter = new AtomicLong(0);
    private final Clock clock;

    public InMemoryJobStore() {
        this(Clock.systemUTC());
    }

    public InMemoryJobStore(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Clock cannot be null");
        }
        this.clock = clock;
    }


    @Override
    public synchronized JobDefinition addJob(JobDefinition job) throws DuplicateJobException {
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }

        for (JobDefinition existing : jobs.values()) {
            if (existing.getName().equals(job.getName())) {
                throw new DuplicateJobException("Job '" + job.getName() + "' already exists");
            }
        }

        JobDefinition saved = job.withId(jobIdCounter.incrementAndGet());
        jobs.put(saved.getId(), saved);
        return saved;
    }

    @Override
    public synchronized Optional<JobDefinition> getJob(long id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public synchronized List<JobDefinition> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public synchronized void cancelJob(long id) {
        JobDefinition job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Job does not exist: " + id);
        }

        jobs.put(id, job.withActive(false));

        // pending runs are cancelled; running and finished ones are left alone
        Instant cancelTime = clock.instant();
        for (JobExecution execution : executions.values()) {
            if (execution.getJobId() == id && execution.getStatus() == JobStatus.PENDING) {
                execution.markCancelled(cancelTime);
            }
        }
    }

    // ---------- executions ----------

    @Override
    public synchronized JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor) {
        JobDefinition job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Job does not exist: " + jobId);
        }
        if (!job.isActive()) {
            // a normal outcome for the engine (the job was cancelled), not a bug
            throw new IllegalStateException("Job " + jobId + " has been cancelled");
        }

        // building the draft first validates the input before an id is used up
        JobExecution draft = new JobExecution(0, jobId, attemptNumber, scheduledFor);
        JobExecution saved = draft.withId(executionIdCounter.incrementAndGet());
        executions.put(saved.getId(), saved);
        return copyOf(saved);
    }

    @Override
    public synchronized Optional<JobExecution> claimNextExecution(long workerId, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now cannot be null");
        }

        // earliest due PENDING run; iteration is in id order and the comparison is
        // strict, so on a tie the lower id wins
        JobExecution earliest = null;
        for (JobExecution candidate : executions.values()) {
            if (candidate.getStatus() != JobStatus.PENDING || candidate.getScheduledFor().isAfter(now)) {
                continue;
            }
            if (earliest == null || candidate.getScheduledFor().isBefore(earliest.getScheduledFor())) {
                earliest = candidate;
            }
        }

        if (earliest == null) {
            return Optional.empty();
        }

        // changes the stored object, which never leaves this class; the caller gets a copy
        earliest.markRunning(workerId, now);
        return Optional.of(copyOf(earliest));
    }

    @Override
    public synchronized void updateExecution(JobExecution execution) {
        if (execution == null) {
            throw new IllegalArgumentException("Execution cannot be null");
        }

        JobExecution stored = executions.get(execution.getId());
        if (stored == null) {
            throw new IllegalArgumentException("Execution does not exist: " + execution.getId());
        }
        if (stored.getJobId() != execution.getJobId()) {
            throw new IllegalArgumentException("Job id mismatch for execution " + execution.getId());
        }

        // the fields fixed at creation must not be rewritten
        if (stored.getAttemptNumber() != execution.getAttemptNumber()
                || !stored.getScheduledFor().equals(execution.getScheduledFor())) {
            throw new IllegalArgumentException(
                    "Execution " + execution.getId() + " has a different attempt number or scheduled time");
        }

        // stops a stale copy from overwriting a newer state
        JobStatus from = stored.getStatus();
        JobStatus to = execution.getStatus();
        if (from != to && !from.canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Execution " + execution.getId() + " cannot move from " + from + " to " + to);
        }

        executions.put(execution.getId(), copyOf(execution));
    }

    @Override
    public synchronized List<JobExecution> listExecutions(long jobId) {
        List<JobExecution> result = new ArrayList<>();
        for (JobExecution execution : executions.values()) {
            if (execution.getJobId() == jobId) {
                result.add(copyOf(execution));
            }
        }
        return result;
    }

    // withId with the same id is a copy that carries over status, worker and times
    private JobExecution copyOf(JobExecution execution) {
        return execution.withId(execution.getId());
    }
}