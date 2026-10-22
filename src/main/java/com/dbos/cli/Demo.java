package com.dbos.cli;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobExecutionException;
import com.dbos.engine.ExecutionListener;
import com.dbos.engine.SchedulerEngine;
import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.store.InMemoryJobStore;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.OneTimeTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Runs the whole system for 15 seconds on the in-memory store and prints
 * everything that happens. Expect: "cleanup" every 2s, "flaky-ingest" failing
 * twice and then succeeding after 1s and 2s backoffs, and "one-off-report"
 * running once.
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.systemUTC();
        SchedulerEngine engine = new SchedulerEngine(new InMemoryJobStore(clock), clock, new ConsoleListener(clock));

        engine.register(JobDefinition.builder("cleanup")
                .job(() -> { })
                .trigger(new FixedRateTrigger(Duration.ofSeconds(2)))
                .build());

        AtomicInteger calls = new AtomicInteger();
        engine.register(JobDefinition.builder("flaky-ingest")
                .job(() -> {
                    if (calls.incrementAndGet() <= 2) {
                        throw new JobExecutionException("source unavailable (call " + calls.get() + ")");
                    }
                })
                .trigger(new OneTimeTrigger(Duration.ofSeconds(1)))
                .retryPolicy(new ExponentialBackoffPolicy(3, Duration.ofSeconds(1)))
                .build());

        engine.register(JobDefinition.builder("one-off-report")
                .job(() -> { })
                .trigger(new OneTimeTrigger(Duration.ofSeconds(3)))
                .build());

        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            engine.tick();
            Thread.sleep(200);
        }
        System.out.println("--- demo finished ---");
    }

    private static class ConsoleListener implements ExecutionListener {
        private final Clock clock;

        ConsoleListener(Clock clock) {
            this.clock = clock;
        }

        private void print(String message) {
            System.out.println(clock.instant().truncatedTo(ChronoUnit.MILLIS) + "  " + message);
        }

        @Override
        public void onJobRegistered(JobDefinition job) {
            print("registered '" + job.getName() + "'");
        }

        @Override
        public void onRunScheduled(JobDefinition job, JobExecution run) {
            print("scheduled '" + job.getName() + "' attempt " + run.getAttemptNumber()
                    + " for " + run.getScheduledFor());
        }

        @Override
        public void onRunStarted(JobDefinition job, JobExecution run) {
            print("started   '" + job.getName() + "' attempt " + run.getAttemptNumber());
        }

        @Override
        public void onRunSucceeded(JobDefinition job, JobExecution run) {
            print("DONE      '" + job.getName() + "' attempt " + run.getAttemptNumber());
        }

        @Override
        public void onRunFailed(JobDefinition job, JobExecution run, Throwable cause) {
            print("FAILED    '" + job.getName() + "' attempt " + run.getAttemptNumber() + ": " + cause.getMessage());
        }

        @Override
        public void onScheduleEnded(JobDefinition job) {
            print("schedule ended for '" + job.getName() + "'");
        }
    }
}