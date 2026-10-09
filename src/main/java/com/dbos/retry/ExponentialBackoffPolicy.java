package com.dbos.retry;

import java.time.Duration;

// fxn -> can we retry + wait for exponential time to retry
/*
    e.g. max retry = 3, dealy = 2
    formula for exponential dealy :- dealy * 2^(attempt number) achieved via left shift
    attemptNumber: 0, 1, 2
    we get the following delays
    2, 4, 8
*/
public class ExponentialBackoffPolicy implements RetryPolicy{

    private final int maxRetries;
    private final Duration baseDelay;
    public ExponentialBackoffPolicy(int maxRetries, Duration baseDelay) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
    }

    @Override
    public boolean canRetry(int attemptNumber) {
        return attemptNumber < maxRetries;
    }

    @Override
    public Duration getRetryDelay(int attemptNumber) {
        return baseDelay.multipliedBy(1L << attemptNumber);
    }
}
