package com.dbos.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Attempts are 1-based and mean the attempt that just failed.
 * With maxRetries = 3 a job runs once plus up to 3 retries, 4 runs in total.
 */
class RetryPolicyTest {

    @Test
    void exponentialAllowsRetriesUpToTheMaximum() {
        RetryPolicy policy = new ExponentialBackoffPolicy(3, Duration.ofSeconds(2));

        assertTrue(policy.canRetry(1));
        assertTrue(policy.canRetry(2));
        assertTrue(policy.canRetry(3));
        assertFalse(policy.canRetry(4));
    }

    @Test
    void exponentialDelayDoublesEachAttempt() {
        RetryPolicy policy = new ExponentialBackoffPolicy(3, Duration.ofSeconds(2));

        assertEquals(Duration.ofSeconds(2), policy.getRetryDelay(1));
        assertEquals(Duration.ofSeconds(4), policy.getRetryDelay(2));
        assertEquals(Duration.ofSeconds(8), policy.getRetryDelay(3));
    }

    @Test
    void exponentialDelayDoesNotOverflowForLargeAttempts() {
        RetryPolicy policy = new ExponentialBackoffPolicy(1000, Duration.ofSeconds(1));

        // the exponent is capped at 30, so the delay stops growing instead of overflowing
        assertEquals(Duration.ofSeconds(1L << 30), policy.getRetryDelay(100));
        assertFalse(policy.getRetryDelay(100).isNegative());
    }

    @Test
    void fixedAllowsRetriesUpToTheMaximum() {
        RetryPolicy policy = new FixedDelayPolicy(3, Duration.ofSeconds(2));

        assertTrue(policy.canRetry(1));
        assertTrue(policy.canRetry(2));
        assertTrue(policy.canRetry(3));
        assertFalse(policy.canRetry(4));
    }

    @Test
    void fixedDelayIsTheSameEveryAttempt() {
        RetryPolicy policy = new FixedDelayPolicy(3, Duration.ofSeconds(2));

        assertEquals(Duration.ofSeconds(2), policy.getRetryDelay(1));
        assertEquals(Duration.ofSeconds(2), policy.getRetryDelay(2));
        assertEquals(Duration.ofSeconds(2), policy.getRetryDelay(3));
    }

    @Test
    void zeroRetriesMeansNeverRetry() {
        assertFalse(new ExponentialBackoffPolicy(0, Duration.ofSeconds(2)).canRetry(1));
        assertFalse(new FixedDelayPolicy(0, Duration.ofSeconds(2)).canRetry(1));
    }

    @Test
    void attemptsBelowOneAreRejected() {
        RetryPolicy exponential = new ExponentialBackoffPolicy(3, Duration.ofSeconds(2));
        RetryPolicy fixed = new FixedDelayPolicy(3, Duration.ofSeconds(2));

        assertThrows(IllegalArgumentException.class, () -> exponential.canRetry(0));
        assertThrows(IllegalArgumentException.class, () -> exponential.getRetryDelay(0));
        assertThrows(IllegalArgumentException.class, () -> exponential.canRetry(-1));
        assertThrows(IllegalArgumentException.class, () -> fixed.canRetry(0));
        assertThrows(IllegalArgumentException.class, () -> fixed.getRetryDelay(0));
        assertThrows(IllegalArgumentException.class, () -> fixed.canRetry(-1));
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoffPolicy(-1, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoffPolicy(3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExponentialBackoffPolicy(3, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedDelayPolicy(-1, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedDelayPolicy(3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedDelayPolicy(3, Duration.ofSeconds(-1)));
    }
}