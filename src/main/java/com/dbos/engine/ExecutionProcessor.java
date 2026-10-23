package com.dbos.engine;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.store.JobStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/*
 * Handles one claimed run from start to finish:
 *   1. run the job
 *   2. record the outcome (DONE or FAILED) in the store
 *   3. schedule what comes next: a retry after a failure, otherwise the
 *      job's next regular run (or nothing, if the schedule has ended)
 *
 * Anything the job throws, including an Error such as StackOverflowError, is
 * an ordinary failure, so one bad job never stops the caller or leaves its run
 * stuck in RUNNING. Listener errors are swallowed for the same reason.
 * Store errors are not swallowed; they propagate to the caller.
 *
 * If the job was cancelled while the run was in progress, the run still
 * finishes normally, but the store refuses to create a follow-up run and the
 * schedule simply ends.
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
        Optional<JobDefinition> found = store.getJob(run.getJobId());
        if (found.isEmpty()) {
            // a run without a job rule cannot be executed
            run.markFailed(clock.instant());
            store.updateExecution(run);
            return;
        }
        JobDefinition job = found.get();

        fire(() -> listener.onRunStarted(job, copy(run)));
        Throwable failure = execute(job);
        Instant ended = clock.instant();

        if (failure == null) {
            run.markDone(ended);
            store.updateExecution(run);
            fire(() -> listener.onRunSucceeded(job, copy(run)));
            scheduleNext(job, run, ended);
        } else {
            run.markFailed(ended);
            store.updateExecution(run);
            fire(() -> listener.onRunFailed(job, copy(run), failure));
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
        Optional<Instant> retryAt = planner.retryTime(job.getRetryPolicy(), failed.getAttemptNumber(), now);
        if (retryAt.isPresent()) {
            schedule(job, failed.getAttemptNumber() + 1, retryAt.get());
        } else {
            scheduleNext(job, failed, now);
        }
    }

    // the regular next run always starts again at attempt 1
    private void scheduleNext(JobDefinition job, JobExecution finished, Instant now) {
        Optional<Instant> next = planner.nextRun(job.getTrigger(), finished, now);
        if (next.isEmpty()) {
            fire(() -> listener.onScheduleEnded(job));
            return;
        }
        schedule(job, 1, next.get());
    }

    private void schedule(JobDefinition job, int attemptNumber, Instant at) {
        try {
            JobExecution created = store.createExecution(job.getId(), attemptNumber, at);
            fire(() -> listener.onRunScheduled(job, copy(created)));
        } catch (IllegalStateException cancelled) {
            // the store refuses runs for a cancelled job: a normal way for a schedule to end
            fire(() -> listener.onScheduleEnded(job));
        }
    }

    // listeners get copies, so they cannot change the engine's own objects
    private static JobExecution copy(JobExecution run) {
        return run.withId(run.getId());
    }

    private static void fire(Runnable event) {
        try {
            event.run();
        } catch (RuntimeException ignored) {
            // a faulty listener must not break job processing
        }
    }
}