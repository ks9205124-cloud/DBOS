package com.dbos.trigger;

import java.time.Duration;
import java.time.Instant;

// fxn -> when should this next run? once, at one fixed moment
/*
    Built from an exact moment:
        new OneTimeTrigger(Instant.parse("2026-01-01T12:00:10Z"))
        fire time = 12:00:10, always

    Or from a delay, measured from the moment the trigger is created:
        created at 21:00:00 with a 10s delay  ->  fire time = 21:00:10

    nextFireTime ignores its argument and always returns that one fixed
    moment, so it does not repeat. The engine ends the schedule when the
    answer is not later than the run it just finished.
*/
public class OneTimeTrigger implements Trigger {

    private final Instant fireTime;

    // exact moment (also how a stored row is turned back into a trigger)
    public OneTimeTrigger(Instant fireTime) {
        if (fireTime == null) {
            throw new IllegalArgumentException("fireTime is required");
        }
        this.fireTime = fireTime;
    }

    // delay from now (reads the real clock, so tests should use the Instant constructor)
    public OneTimeTrigger(Duration delay) {
        this(Instant.now().plus(requireNotNegative(delay)));
    }

    @Override
    public Instant nextFireTime(Instant lastFireTime) {
        return fireTime;
    }

    // exposed so a store can save it (TRIGGERS.fire_at)
    public Instant getFireTime() {
        return fireTime;
    }

    private static Duration requireNotNegative(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be zero or positive");
        }
        return delay;
    }
}