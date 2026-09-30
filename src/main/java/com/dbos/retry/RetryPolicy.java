package com.dbos.retry;

import javax.xml.datatype.Duration;

public interface RetryPolicy {

    boolean canRetry(int attemptNumber);
    Duration getRetryDelay(int  attemptNumber);
}
