package com.dbos.retry;

import java.time.Duration;


public interface RetryPolicy {

    // true if another attempt is allowed after the given attempt failed
    boolean canRetry(int attemptNumber);

    // how long to wait before the next attempt, after the given attempt failed
    Duration getRetryDelay(int attemptNumber);
}