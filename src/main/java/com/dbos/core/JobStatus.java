package com.dbos.core;

public enum JobStatus {

    PENDING,
    CANCELED,
    RUNNING,
    DONE,
    FAILED;

    boolean canTransitionTo(JobStatus newStatus) {
        return switch (this) {
            case PENDING -> newStatus == CANCELED || newStatus == RUNNING;
            case RUNNING -> newStatus == DONE || newStatus == FAILED;
            case FAILED -> newStatus == PENDING;
            case DONE,CANCELED -> false;
        };
    }
}
