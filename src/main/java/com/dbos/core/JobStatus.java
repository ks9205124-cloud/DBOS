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
        return switch (this) {
            case PENDING -> newStatus == CANCELLED || newStatus == RUNNING;
            case RUNNING -> newStatus == DONE || newStatus == FAILED;
            case DONE, FAILED, CANCELLED -> false;
        };
    }
}