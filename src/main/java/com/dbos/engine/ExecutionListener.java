package com.dbos.engine;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

/*
 * Receives a notification each time something happens in the engine, so the
 * engine itself never prints or logs. The console demo prints these events,
 * and a database-backed listener could write them to EXECUTION_LOGS later.
 *
 * Every method has an empty default, so an implementation overrides only the
 * events it cares about.
 */
public interface ExecutionListener {

    // used when nobody is listening
    ExecutionListener NOOP = new ExecutionListener() { };

    // a job rule was saved
    default void onJobRegistered(JobDefinition job) { }

    // a new PENDING run was created (the first run, the next recurrence, or a retry)
    default void onRunScheduled(JobDefinition job, JobExecution run) { }

    // a worker claimed the run and is about to execute the job
    default void onRunStarted(JobDefinition job, JobExecution run) { }

    // the job returned normally and the run is DONE
    default void onRunSucceeded(JobDefinition job, JobExecution run) { }

    // the job threw and the run is FAILED; cause holds the reason
    default void onRunFailed(JobDefinition job, JobExecution run, Throwable cause) { }

    // no more runs will be scheduled for this job (for example, a finished one-time job)
    default void onScheduleEnded(JobDefinition job) { }
}