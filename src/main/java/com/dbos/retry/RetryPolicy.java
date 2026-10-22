package com.dbos.retry;

import java.time.Duration;

/*
 * Decides what happens after a run fails.
 *
 * attemptNumber is 1-based and is the attempt that just failed: 1 is the
 * original run, 2 is the first retry, and so on. Passing a value below 1 is
 * a bug and is rejected.
 */
public interface RetryPolicy {

    // true if another attempt is allowed after the given attempt failed
    boolean canRetry(int attemptNumber);

    // how long to wait before the next attempt, after the given attempt failed
    Duration getRetryDelay(int attemptNumber);
}