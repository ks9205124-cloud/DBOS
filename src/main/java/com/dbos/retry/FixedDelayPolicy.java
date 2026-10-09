package com.dbos.retry;

import java.time.Duration;

// fxn -> can we retry + wait for fixed time to retry
/*
    e.g. max retry = 3, dealy = 2
    assuming first retry at 0
    attemptNumber: 0, 1, 2
    we get the following delays
    2, 2, 2
*/
public class FixedDelayPolicy implements RetryPolicy{

    private final int maxRetries;
    private final Duration delay;

    public FixedDelayPolicy(int maxRetries, Duration delay) {
        this.maxRetries = maxRetries;
        this.delay = delay;
    }

    @Override
    public boolean canRetry(int attemptNumber) {
        return attemptNumber < maxRetries;
    }

    @Override
    public Duration getRetryDelay(int attemptNumber) {
        return delay;
    }
}
