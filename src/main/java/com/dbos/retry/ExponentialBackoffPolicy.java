package com.dbos.retry;

import java.time.Duration;

/*
 * Each retry waits twice as long as the one before.
 *
 * Example: maxRetries = 3, baseDelay = 2s
 *   attempt 1 failed -> retry after 2s  (2s * 2^0)
 *   attempt 2 failed -> retry after 4s  (2s * 2^1)
 *   attempt 3 failed -> retry after 8s  (2s * 2^2)
 *   attempt 4 failed -> no more retries
 *
 * The doubling is a left shift, which stays in whole-number math (no
 * floating-point rounding). The exponent is capped so a very large
 * attempt number cannot overflow.
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