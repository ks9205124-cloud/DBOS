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

public class InMemoryJobStore implements JobStore {

    private final Map<Integer, JobDefinition> jobs = new LinkedHashMap<>();
    private final Map<Integer, JobExecution> executions = new LinkedHashMap<>();
    // plain counters are safe: they are only used inside synchronized methods
    private int jobIdCounter = 0;
    private int executionIdCounter = 0;
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

        JobDefinition saved = job.withId(++jobIdCounter);
        jobs.put(saved.getId(), saved);
        return saved;
    }

    @Override
    public synchronized JobDefinition getJob(int id) {
        return jobs.get(id);   // null if there is no such job
    }

    @Override
    public synchronized List<JobDefinition> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public synchronized void cancelJob(int id) {
        JobDefinition job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Job does not exist: " + id);
        }

        jobs.put(id, job.withActive(false));

        Instant cancelTime = clock.instant();
        for (JobExecution execution : executions.values()) {
            if (execution.getJobId() == id && execution.getStatus() == JobStatus.PENDING) {
                execution.markCancelled(cancelTime);
            }
        }
    }


    @Override
    public synchronized JobExecution createExecution(int jobId, int attemptNumber, Instant scheduledFor) {
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
        JobExecution saved = draft.withId(++executionIdCounter);
        executions.put(saved.getId(), saved);
        return copyOf(saved);
    }

    @Override
    public synchronized JobExecution claimNextExecution(int workerId, Instant now) {
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
            return null;   // nothing is due
        }

        // changes the stored object, which never leaves this class; the caller gets a copy
        earliest.markRunning(workerId, now);
        return copyOf(earliest);
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
    public synchronized List<JobExecution> listExecutions(int jobId) {
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