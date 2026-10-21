package com.dbos.core;

/*
 * Thrown by a Job when a run fails. The second constructor lets a job wrap
 * the underlying error (a SQL error, an IO error) so the reason is not lost.
 */
public class JobExecutionException extends Exception {

    public JobExecutionException(String message) {
        super(message);
    }

    public JobExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}