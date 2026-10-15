package com.dbos.core;

import java.time.Instant;
import java.util.Optional;

public class JobExecution {

    // initial fields
    private final long id;
    private final long jobId;
    private final int attemptNumber;
    private final Instant scheduledFor;
    // transition fields
    private JobStatus status = JobStatus.PENDING;
    private Long workerId;
    private Instant startedAt;
    private Instant endedAt;

    public JobExecution(long id, long jobId, int attemptNumber, Instant scheduledFor) {
        this.id = id;
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.scheduledFor = scheduledFor;

        // TODO generate error for attempt number less than 1
        if (attemptNumber < 1) {
            throw new IllegalArgumentException(
                    "Attempt number must be at least 1"
            );
        }
        if (scheduledFor == null) {
            throw new IllegalArgumentException(
                    "Scheduled time is required"
            );
        }
    }

    public long getId() {
        return id;
    }

    public long getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Optional<Long> getWorkerId() {
        return Optional.ofNullable(workerId);
    }

    public Optional<Instant> getStartedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> getEndedAt() {
        return Optional.ofNullable(endedAt);
    }

    // working -> convert state from pending to running
    /*
        when running startAt, workerId not initialized therefore initialize them,
        and change the state
    */
    public void markRunning(long workerId, Instant startedAt) {
        if (!status.canTransitionTo(JobStatus.RUNNING)) {
            throw new IllegalStateException(
                    "Execution " + id + " cannot move from " + status + " to RUNNING");
        }
        this.status = JobStatus.RUNNING;
        this.workerId = workerId;
        this.startedAt = startedAt;
    }

    /* working -> convert state from running to done

    when execution is completed, record the ending time
    and change the state to DONE
    */
    public void markDone(Instant endedAt) {
        if (!status.canTransitionTo(JobStatus.DONE)) {
            throw new IllegalStateException(
                    "Execution " + id + " cannot move from " + status + " to DONE"
            );
        }

        this.status = JobStatus.DONE;
        this.endedAt = endedAt;
    }

    /* working -> convert state from running to failed

    when execution fails, record the ending time
    and change the state to FAILED
    */
    public void markFailed(Instant endedAt) {
        if (!status.canTransitionTo(JobStatus.FAILED)) {
            throw new IllegalStateException(
                    "Execution " + id + " cannot move from " + status + " to FAILED"
            );
        }

        this.status = JobStatus.FAILED;
        this.endedAt = endedAt;
    }

    /* working -> convert state from pending to cancelled

    when a pending execution is cancelled, record the ending time
    and change the state to CANCELLED
    */
    public void markCancelled(Instant endedAt) {
        if (!status.canTransitionTo(JobStatus.CANCELLED)) {
            throw new IllegalStateException(
                    "Execution " + id + " cannot move from " + status + " to CANCELLED"
            );
        }

        this.status = JobStatus.CANCELLED;
        this.endedAt = endedAt;
    }

    public JobExecution withId(long newId) {
        JobExecution copy = new JobExecution(newId, jobId, attemptNumber, scheduledFor);
        copy.status = this.status;
        copy.workerId = this.workerId;
        copy.startedAt = this.startedAt;
        copy.endedAt = this.endedAt;
        return copy;
    }
}