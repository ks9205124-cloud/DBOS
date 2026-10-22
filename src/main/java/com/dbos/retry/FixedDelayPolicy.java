package com.dbos.retry;

import java.time.Duration;

/*
 * Every retry waits the same amount of time.
 *
 * Example: maxRetries = 3, delay = 2s
 *   attempt 1 failed -> retry after 2s
 *   attempt 2 failed -> retry after 2s
 *   attempt 3 failed -> retry after 2s
 *   attempt 4 failed -> no more retries
 *
 * With maxRetries = 0 this means "never retry", which is what a job with no
 * retry policy gets.
 */
public class FixedDelayPolicy implements RetryPolicy {

    private final int maxRetries;
    private final Duration delay;

    public FixedDelayPolicy(int maxRetries, Duration delay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be zero or positive");
        }
        this.maxRetries = maxRetries;
        this.delay = delay;
    }

    @Override
    public boolean canRetry(int attemptNumber) {
        requireValidAttempt(attemptNumber);
        return attemptNumber <= maxRetries;
    }

    @Override
    public Duration getRetryDelay(int attemptNumber) {
        requireValidAttempt(attemptNumber);
        return delay;
    }

    private static void requireValidAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be at least 1");
        }
    }
}