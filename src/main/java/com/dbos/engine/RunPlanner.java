package com.dbos.engine;

import com.dbos.core.JobExecution;
import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.Trigger;

import java.time.Instant;

/*
    map triger to engine
*/
public final class RunPlanner {

    // when the first run of a newly registered job should happen
    public Instant firstRun(Trigger trigger, Instant now) {
        require(trigger, "trigger");
        require(now, "now");
        // get next fire time of trigger if already passed do it now else wait
        return notBefore(timeFrom(trigger, now), now);
    }

    // when the run after a finished one should happen; null if the schedule has ended
    public Instant nextRun(Trigger trigger, JobExecution finished, Instant now) {
        require(trigger, "trigger");
        require(finished, "finished run");
        require(now, "now");


        Instant lastScheduled = finished.getScheduledFor();
        Instant next = timeFrom(trigger, lastScheduled);
        if (!next.isAfter(lastScheduled)) {
            return null;
        }
        return notBefore(next, now);
    }

    // when to retry after the given attempt failed; null if no retry is allowed
    public Instant retryTime(RetryPolicy policy, int failedAttempt, Instant now) {
        require(policy, "retry policy");
        require(now, "now");

        if (!policy.canRetry(failedAttempt)) {
            return null;
        }
        return now.plus(policy.getRetryDelay(failedAttempt));
    }

    // return next fore time of trigger if null then error
    private Instant timeFrom(Trigger trigger, Instant after) {
        Instant next = trigger.nextFireTime(after);
        if (next == null) {
            throw new IllegalStateException("The trigger returned no fire time");
        }
        return next;
    }

    // if next fire time of trigger already passed then fire it now, or else wait
    private Instant notBefore(Instant time, Instant now) {
        return time.isBefore(now) ? now : time;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}