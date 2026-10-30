package com.dbos.engine;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.store.JobStore;

import java.time.Clock;
import java.time.Instant;

/*
    Handles one claimed run from start to finish: runs the job, records whether
    it worked, tells the listener, and plans what comes next.
    Anything the job throws (even an Error) is an ordinary failed run, so one
    bad job never stops the caller.
*/
final class ExecutionProcessor {

    private final JobStore store;
    private final RunPlanner planner;
    private final Clock clock;
    private final ExecutionListener listener;

    ExecutionProcessor(JobStore store, RunPlanner planner, Clock clock, ExecutionListener listener) {
        this.store = store;
        this.planner = planner;
        this.clock = clock;
        this.listener = listener;
    }

    // the run must already be claimed (RUNNING)
    void process(JobExecution run) {
        JobDefinition job = store.getJob(run.getJobId());
        if (job == null) {
            // a run without a job rule cannot be executed
            run.markFailed(clock.instant());
            store.updateExecution(run);
            return;
        }

        listener.onRunStarted(job, copy(run));

        Throwable failure = execute(job);
        Instant ended = clock.instant();

        if (failure == null) {
            run.markDone(ended);
            store.updateExecution(run);
            listener.onRunSucceeded(job, copy(run));
            scheduleNext(job, run, ended);
        } else {
            run.markFailed(ended);
            store.updateExecution(run);
            listener.onRunFailed(job, copy(run), failure);
            scheduleRetryOrNext(job, run, ended);
        }
    }

    private Throwable execute(JobDefinition job) {
        try {
            job.getJob().execute();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void scheduleRetryOrNext(JobDefinition job, JobExecution failed, Instant now) {
        Instant retryAt = planner.retryTime(job.getRetryPolicy(), failed.getAttemptNumber(), now);
        if (retryAt != null) {
            schedule(job, failed.getAttemptNumber() + 1, retryAt);
        } else {
            scheduleNext(job, failed, now);
        }
    }

    // the regular next run always starts again at attempt 1
    private void scheduleNext(JobDefinition job, JobExecution finished, Instant now) {
        Instant next = planner.nextRun(job.getTrigger(), finished, now);
        if (next == null) {
            listener.onScheduleEnded(job);
            return;
        }
        schedule(job, 1, next);
    }

    private void schedule(JobDefinition job, int attemptNumber, Instant at) {
        try {
            JobExecution created = store.createExecution(job.getId(), attemptNumber, at);
            listener.onRunScheduled(job, copy(created));
        } catch (IllegalStateException cancelled) {
            // the store refuses runs for a cancelled job: a normal way for a schedule to end
            listener.onScheduleEnded(job);
        }
    }

    // the listener gets a copy, so it cannot change the engine's own object
    private static JobExecution copy(JobExecution run) {
        return run.withId(run.getId());
    }
}
