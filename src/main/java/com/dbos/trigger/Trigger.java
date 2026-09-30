package com.dbos.trigger;

import java.time.Instant;

public interface Trigger {
    Instant nextFireTime(Instant lastFireTime);
}
