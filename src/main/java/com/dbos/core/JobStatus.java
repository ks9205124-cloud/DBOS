package com.dbos.core;

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
            case FAILED -> newStatus == PENDING;
            case DONE,CANCELLED -> false;
        };
    }
}
