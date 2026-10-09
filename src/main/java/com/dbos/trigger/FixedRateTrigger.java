package com.dbos.trigger;

import java.time.Instant;
import java.time.Duration;

// fxn -> when should this next run? at lastFireTime + intervalDuration
/*
    e.g. intervalDuration = 10 seconds
    if lastFireTime = 12:00:00
    nextFireTime = 12:00:00 + 10s = 12:00:10

    this repeats every time it's called
*/
public class FixedRateTrigger implements Trigger{

    private final Duration triggerIntervalDuration;

    public FixedRateTrigger(Duration triggerIntervalDuration) {
        this.triggerIntervalDuration = triggerIntervalDuration;
    }

    @Override
    public Instant nextFireTime(Instant lastFireTime) {
        return lastFireTime.plus(triggerIntervalDuration);
    }
}
