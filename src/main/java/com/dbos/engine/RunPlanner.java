package com.dbos.engine;

import com.dbos.core.JobExecution;
import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.Trigger;

import java.time.Instant;
import java.util.Optional;

/*
 * The scheduling rules of the engine, as pure functions of their inputs.
 *
 * 1. The first run is the trigger's answer for "now".
 * 2. The next run counts from the finished run's SCHEDULED time, not from when
 *    it ended, so a fixed-rate job does not drift.
 * 3. A time that is already in the past is moved up to "now". Without this, a
 *    long outage or a slow run would be followed by a burst of back-to-back
 *    catch-up runs.
 * 4. A trigger that does not move strictly forward has finished. This is how a
 *    one-time job ends: it always answers with the same moment.
 * 5. A retry is scheduled at "now" plus the policy's delay, and only while the
 *    policy allows another attempt.
 *
 * Examples (fixed rate of 10s):
 *   registered at 12:00:00                       -> first run 12:00:10
 *   run scheduled 12:00:10, finished 12:00:12    -> next run 12:00:20
 *   run scheduled 12:00:10, finished 12:00:35    -> next run 12:00:35 (12:00:20 is past)
 *
 * One-time job firing at 12:00:05:
 *   registered at 12:00:00                       -> first run 12:00:05
 *   that run finishes                            -> no next run (12:00:05 is not later)
 *
 * After a chain of retries ends, the next run counts from the last attempt's
 * scheduled time, because the original schedule is not stored.
 */
public final class RunPlanner {

    // when the first run of a newly registered job should happen
    public Instant firstRun(Trigger trigger, Instant now) {
        require(trigger, "trigger");
        require(now, "now");
        return notBefore(timeFrom(trigger, now), now);
    }

    // when the run after a finished one should happen; empty if the schedule has ended
    public Optional<Instant> nextRun(Trigger trigger, JobExecution finished, Instant now) {
        require(trigger, "trigger");
        require(finished, "finished run");
        require(now, "now");

        Instant lastScheduled = finished.getScheduledFor();
        Instant next = timeFrom(trigger, lastScheduled);
        if (!next.isAfter(lastScheduled)) {
            return Optional.empty();
        }
        return Optional.of(notBefore(next, now));
    }

    // when to retry after the given attempt failed; empty if no retry is allowed
    public Optional<Instant> retryTime(RetryPolicy policy, int failedAttempt, Instant now) {
        require(policy, "retry policy");
        require(now, "now");

        if (!policy.canRetry(failedAttempt)) {
            return Optional.empty();
        }
        return Optional.of(now.plus(policy.getRetryDelay(failedAttempt)));
    }

    private Instant timeFrom(Trigger trigger, Instant after) {
        Instant next = trigger.nextFireTime(after);
        if (next == null) {
            throw new IllegalStateException("The trigger returned no fire time");
        }
        return next;
    }

    // a time in the past becomes "now"
    private Instant notBefore(Instant time, Instant now) {
        return time.isBefore(now) ? now : time;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}