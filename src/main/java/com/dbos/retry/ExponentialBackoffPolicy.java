package com.dbos.retry;

import java.time.Duration;

/*
    attempt 3 failed -> retry after 8s  (2s * 2^n-1)
 */
public class ExponentialBackoffPolicy implements RetryPolicy {

    private static final int MAX_EXPONENT = 30;

    private final int maxRetries;
    private final Duration baseDelay;

    public ExponentialBackoffPolicy(int maxRetries, Duration baseDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be zero or positive");
        }
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
    }

    @Override
    public boolean canRetry(int attemptNumber) {
        requireValidAttempt(attemptNumber);
        return attemptNumber <= maxRetries;
    }

    @Override
    public Duration getRetryDelay(int attemptNumber) {
        requireValidAttempt(attemptNumber);
        int exponent = Math.min(attemptNumber - 1, MAX_EXPONENT);
        return baseDelay.multipliedBy(1L << exponent);
    }

    private static void requireValidAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be at least 1");
        }
    }
}