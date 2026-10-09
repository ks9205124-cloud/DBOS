package com.dbos.retry;

import java.time.Duration;

public interface RetryPolicy {

    boolean canRetry(int attemptNumber);
    Duration getRetryDelay(int  attemptNumber);
}
