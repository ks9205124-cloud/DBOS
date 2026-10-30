package com.dbos.engine;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.store.JobStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;


/*
    this class requires 3 other classes jobStore, clock, ExecutionListener
    job store is basically a place to store jobs
    clock used to get the current time ---> used at creating a job and managing the execution
    Execution Listener takes care of printing
 */
public class SchedulerEngine {

    private static final int TICK_WORKER_ID = 1;

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
        this.listener = (listener == null) ? new ExecutionListener(false) : listener;
        this.processor = new ExecutionProcessor(store, planner, clock, this.listener);
    }

    // saves the job and schedules its first run; returns the saved job with its real id
    public JobDefinition register(JobDefinition jobDefinition) throws DuplicateJobException {
        // if job is null dont do anything
        if (jobDefinition == null) {
            throw new IllegalArgumentException("job is required");
        }
        // saved curr job and also store it in a var
        JobDefinition saved = store.addJob(jobDefinition);
        // log that the job has been saved
        try {
            listener.onJobRegistered(saved);
        }
        catch (RuntimeException e) {
            // ignore
        }

        // todo will cover after understanding what planner is
        Instant firstRun = planner.firstRun(saved.getTrigger(), clock.instant());

        JobExecution run = store.createExecution(saved.getId(), 1, firstRun);
        // log that the job has been saved
        try {
            listener.onRunScheduled(saved, run);
        }
        catch (RuntimeException e) {
            // ignore
        }
        return saved;
    }

    public void cancel(int jobId) {
        store.cancelJob(jobId);
    }

    // claims and processes one due run; false if nothing was due
    public boolean runNext(int workerId) {
        JobExecution claimed = store.claimNextExecution(workerId, clock.instant());
        if (claimed == null) {
            return false;
        }
        processor.process(claimed);
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

    public List<JobExecution> listExecutions(int jobId) {
        return store.listExecutions(jobId);
    }
}