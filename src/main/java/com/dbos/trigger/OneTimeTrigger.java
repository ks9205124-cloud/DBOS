package com.dbos.trigger;

import java.time.Instant;
import java.time.Duration;

// fxn -> when should this next run? at duration d from now
/*
    e.g. intervalDuration = 10 seconds
    if lastFireTime = 12:00:00 no use
    if currInstant = 21:00:00
    nextFireTime = 21:00:00 + 10s = 21:00:10

    this does not repeat
*/
public class OneTimeTrigger implements Trigger {

    private final Instant fireTime;

    public OneTimeTrigger(Duration delay) {
        this.fireTime = Instant.now().plus(delay);
    }
    @Override
    public Instant nextFireTime(Instant lastFireTime) {
        return fireTime;
    }
}
