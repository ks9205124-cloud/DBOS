package com.dbos.engine;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.store.JobStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/*
 * The scheduler. It talks only to the JobStore interface, so the same engine
 * runs on the in-memory store and on Oracle.
 *
 *   register(job)  saves the job and schedules its first run
 *   cancel(id)     stops the job; its pending runs are cancelled
 *   runNext(w)     claims one due run for worker w and processes it
 *   tick()         processes every run that is due right now, one after another
 *
 * The scheduling rules live in RunPlanner and the handling of a single run in
 * ExecutionProcessor. The engine never prints; it reports through the
 * ExecutionListener. All time comes from the injected Clock.
 *
 * tick() runs on the calling thread. The worker pool added later calls
 * runNext(workerId) from several threads, which is safe because the store's
 * claim is atomic.
 */
public class SchedulerEngine {

    private static final long TICK_WORKER_ID = 1;

    private final JobStore store;
    private final Clock clock;
    private final ExecutionListener listener;
    private final RunPlanner planner = new RunPlanner();
    private final ExecutionProcessor processor;

    public SchedulerEngine(JobStore store, Clock clock, ExecutionListener listener) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.store = store;
        this.clock = clock;
        this.listener = (listener == null) ? ExecutionListener.NOOP : listener;
        this.processor = new ExecutionProcessor(store, planner, clock, this.listener);
    }

    // saves the job and schedules its first run; returns the saved job with its real id
    public JobDefinition register(JobDefinition job) throws DuplicateJobException {
        if (job == null) {
            throw new IllegalArgumentException("job is required");
        }
        JobDefinition saved = store.addJob(job);
        fire(() -> listener.onJobRegistered(saved));

        Instant firstRun = planner.firstRun(saved.getTrigger(), clock.instant());
        JobExecution run = store.createExecution(saved.getId(), 1, firstRun);
        fire(() -> listener.onRunScheduled(saved, run));
        return saved;
    }

    public void cancel(long jobId) {
        store.cancelJob(jobId);
    }

    // claims and processes one due run; false if nothing was due
    public boolean runNext(long workerId) {
        Optional<JobExecution> claimed = store.claimNextExecution(workerId, clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        processor.process(claimed.get());
        return true;
    }

    // processes everything that is due right now; returns how many runs were processed
    public int tick() {
        int processed = 0;
        while (runNext(TICK_WORKER_ID)) {
            processed++;
        }
        return processed;
    }

    // read-only views for the console menu and the GUI
    public List<JobDefinition> listJobs() {
        return store.getAllJobs();
    }

    public List<JobExecution> listExecutions(long jobId) {
        return store.listExecutions(jobId);
    }

    private static void fire(Runnable event) {
        try {
            event.run();
        } catch (RuntimeException ignored) {
            // a faulty listener must not break the engine
        }
    }
}