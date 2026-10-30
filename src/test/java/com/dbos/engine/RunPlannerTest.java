package com.dbos.engine;

import com.dbos.core.JobExecution;
import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.retry.FixedDelayPolicy;
import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.OneTimeTrigger;
import com.dbos.trigger.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunPlannerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    private final RunPlanner planner = new RunPlanner();
    private final Trigger every10Seconds = new FixedRateTrigger(Duration.ofSeconds(10));

    // a run that was scheduled at the given time (its status does not matter to the planner)
    private JobExecution runScheduledAt(Instant scheduledFor) {
        return new JobExecution(1, 1, 1, scheduledFor);
    }

    // ---------- first run ----------

    @Test
    void firstRunForAFixedRateJobIsOneIntervalFromNow() {
        assertEquals(T0.plusSeconds(10), planner.firstRun(every10Seconds, T0));
    }

    @Test
    void firstRunUsesTheFixedMomentOfAOneTimeJob() {
        Trigger oneTime = new OneTimeTrigger(T0.plusSeconds(5));

        assertEquals(T0.plusSeconds(5), planner.firstRun(oneTime, T0));
    }

    @Test
    void firstRunIsMovedUpToNowWhenTheTriggerTimeIsInThePast() {
        Trigger oneTime = new OneTimeTrigger(T0.minusSeconds(5));

        assertEquals(T0, planner.firstRun(oneTime, T0));
    }

    // ---------- next run ----------

    @Test
    void nextRunCountsFromTheScheduledTimeNotTheEndTime() {
        JobExecution finished = runScheduledAt(T0.plusSeconds(10));

        Instant next = planner.nextRun(every10Seconds, finished, T0.plusSeconds(12));

        assertEquals(T0.plusSeconds(20), next);
    }

    @Test
    void nextRunIsMovedUpToNowAfterALongRun() {
        JobExecution finished = runScheduledAt(T0.plusSeconds(10));

        // the run took 25 seconds, so the natural next time (12:00:20) has already passed
        Instant next = planner.nextRun(every10Seconds, finished, T0.plusSeconds(35));

        assertEquals(T0.plusSeconds(35), next);
    }

    @Test
    void aOneTimeJobHasNoNextRun() {
        Trigger oneTime = new OneTimeTrigger(T0.plusSeconds(5));
        JobExecution finished = runScheduledAt(T0.plusSeconds(5));

        assertNull(planner.nextRun(oneTime, finished, T0.plusSeconds(6)));
    }

    @Test
    void aOneTimeJobHasNoNextRunEvenWhenItsRunWasMovedUpToNow() {
        Trigger oneTime = new OneTimeTrigger(T0.plusSeconds(5));
        JobExecution finished = runScheduledAt(T0.plusSeconds(20));   // it actually ran late

        assertNull(planner.nextRun(oneTime, finished, T0.plusSeconds(21)));
    }

    @Test
    void aTriggerThatDoesNotMoveForwardEndsTheSchedule() {
        Trigger stuck = last -> last;
        JobExecution finished = runScheduledAt(T0);

        assertNull(planner.nextRun(stuck, finished, T0));
    }

    // ---------- retry ----------

    @Test
    void retryIsScheduledAfterTheBackoffDelay() {
        RetryPolicy policy = new ExponentialBackoffPolicy(3, Duration.ofSeconds(2));

        assertEquals(T0.plusSeconds(2), planner.retryTime(policy, 1, T0));
        assertEquals(T0.plusSeconds(4), planner.retryTime(policy, 2, T0));
        assertEquals(T0.plusSeconds(8), planner.retryTime(policy, 3, T0));
    }

    @Test
    void noRetryOnceTheLimitIsReached() {
        RetryPolicy policy = new FixedDelayPolicy(3, Duration.ofSeconds(2));

        assertNull(planner.retryTime(policy, 4, T0));
    }

    @Test
    void aPolicyWithZeroRetriesNeverRetries() {
        RetryPolicy policy = new FixedDelayPolicy(0, Duration.ZERO);

        assertNull(planner.retryTime(policy, 1, T0));
    }

    // ---------- bad input ----------

    @Test
    void aTriggerThatReturnsNothingIsRejected() {
        Trigger broken = last -> null;

        assertThrows(IllegalStateException.class, () -> planner.firstRun(broken, T0));
    }

    @Test
    void nullArgumentsAreRejected() {
        JobExecution run = runScheduledAt(T0);
        RetryPolicy policy = new FixedDelayPolicy(1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> planner.firstRun(null, T0));
        assertThrows(IllegalArgumentException.class, () -> planner.firstRun(every10Seconds, null));
        assertThrows(IllegalArgumentException.class, () -> planner.nextRun(null, run, T0));
        assertThrows(IllegalArgumentException.class, () -> planner.nextRun(every10Seconds, null, T0));
        assertThrows(IllegalArgumentException.class, () -> planner.retryTime(null, 1, T0));
        assertThrows(IllegalArgumentException.class, () -> planner.retryTime(policy, 1, null));
    }
}