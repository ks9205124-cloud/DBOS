package com.dbos.trigger;

import java.time.Duration;
import java.time.Instant;

// fxn -> when should this next run? at lastFireTime + interval
/*
    e.g. interval = 10 seconds
    lastFireTime = 12:00:00  ->  nextFireTime = 12:00:10
    lastFireTime = 12:00:10  ->  nextFireTime = 12:00:20

    this repeats every time it's called.

    The interval must be positive. A zero or negative interval would never
    move forward in time, so it is rejected when the trigger is created.
*/
public class FixedRateTrigger implements Trigger {

    private final Duration triggerIntervalDuration;

    public FixedRateTrigger(Duration triggerIntervalDuration) {
        if (triggerIntervalDuration == null
                || triggerIntervalDuration.isZero()
                || triggerIntervalDuration.isNegative()) {
            throw new IllegalArgumentException("Interval must be positive");
        }
        this.triggerIntervalDuration = triggerIntervalDuration;
    }

    @Override
    public Instant nextFireTime(Instant lastFireTime) {
        if (lastFireTime == null) {
            throw new IllegalArgumentException("lastFireTime is required");
        }
        return lastFireTime.plus(triggerIntervalDuration);
    }

    // exposed so a store can save the interval (TRIGGERS.interval_seconds)
    public Duration getInterval() {
        return triggerIntervalDuration;
    }
}