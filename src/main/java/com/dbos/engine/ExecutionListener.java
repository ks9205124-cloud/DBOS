package com.dbos.engine;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/*
    Takes care of printing. The engine tells it each time something happens
    (a job saved, a run started, a run failed ...) and it writes one line for it.
    Every line is printed on the console and also kept in a list, so the GUI can
    show the same lines in its Events pane.

    The class is final, so nobody can extend it or override its methods.
*/
public final class ExecutionListener {

    private final boolean printToConsole;
    private final List<String> lines = new ArrayList<>();

    // prints every event on the console and keeps it in the list
    public ExecutionListener() {
        this(true);
    }

    // false = only keep the lines (used by tests, which do not want console noise)
    public ExecutionListener(boolean printToConsole) {
        this.printToConsole = printToConsole;
    }

    // a job rule was saved
    public void onJobRegistered(JobDefinition job) {
        log("registered '" + job.getName() + "'");
    }

    // a new PENDING run was created (the first run, the next recurrence, or a retry)
    public void onRunScheduled(JobDefinition job, JobExecution run) {
        log("scheduled '" + job.getName() + "' attempt " + run.getAttemptNumber()
                + " for " + timeOf(run.getScheduledFor()));
    }

    // a worker claimed the run and is about to execute the job
    public void onRunStarted(JobDefinition job, JobExecution run) {
        log("started   '" + job.getName() + "' attempt " + run.getAttemptNumber()
                + " on worker " + run.getWorkerId());
    }

    // the job returned normally and the run is DONE
    public void onRunSucceeded(JobDefinition job, JobExecution run) {
        log("DONE      '" + job.getName() + "' attempt " + run.getAttemptNumber());
    }

    // the job threw and the run is FAILED; cause holds the reason
    public void onRunFailed(JobDefinition job, JobExecution run, Throwable cause) {
        log("FAILED    '" + job.getName() + "' attempt " + run.getAttemptNumber()
                + ": " + cause.getMessage());
    }

    // no more runs will be scheduled for this job (for example, a finished one-time job)
    public void onScheduleEnded(JobDefinition job) {
        log("schedule ended for '" + job.getName() + "'");
    }

    // a worker hit an unexpected error (for example, the store failed); the worker keeps running
    public void onWorkerError(int workerId, Throwable problem) {
        log("worker " + workerId + " error: " + problem);
    }

    // a copy of every line so far, oldest first
    public synchronized List<String> getLines() {
        return new ArrayList<>(lines);
    }

    // synchronized: several worker threads call this at the same time
    private synchronized void log(String message) {
        String line = timeOf(Instant.now()) + "  " + message;
        lines.add(line);
        if (printToConsole) {
            System.out.println(line);
        }
    }

    // hh:mm:ss in the local time zone
    private static String timeOf(Instant instant) {
        return String.format("%tT", LocalTime.ofInstant(instant, ZoneId.systemDefault()));
    }
}
