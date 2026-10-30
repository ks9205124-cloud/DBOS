package com.dbos.core;

/*
 * The lifecycle of one execution (one run of a job).
 *
 *   PENDING -> RUNNING or CANCELLED
 *   RUNNING -> DONE or FAILED
 *   DONE, FAILED, CANCELLED are final
 *
 * A failed run is never reopened. A retry is a new execution with a higher
 * attempt number, so the failed one stays FAILED as part of the history.
 */
public enum JobStatus {

    PENDING,
    CANCELLED,
    RUNNING,
    DONE,
    FAILED;

    public boolean canTransitionTo(JobStatus newStatus) {
        if (this == PENDING) {
            return newStatus == CANCELLED || newStatus == RUNNING;
        }
        if (this == RUNNING) {
            return newStatus == DONE || newStatus == FAILED;
        }
        return false;   // DONE, FAILED and CANCELLED are final
    }
}