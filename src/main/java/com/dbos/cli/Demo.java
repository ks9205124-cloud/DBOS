package com.dbos.cli;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobExecutionException;
import com.dbos.engine.ExecutionListener;
import com.dbos.engine.SchedulerEngine;
import com.dbos.engine.WorkerPool;
import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.store.InMemoryJobStore;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.OneTimeTrigger;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Runs the whole system for 15 seconds on the in-memory store, with a pool of
 * four workers, and prints everything that happens. Expect:
 *   - "cleanup" every 2s
 *   - "flaky-ingest" failing twice and then succeeding after 1s and 2s backoffs
 *   - "one-off-report" running once
 *   - "slow-1", "slow-2", "slow-3" all starting at the same moment on three
 *     different workers and finishing 3s later, which shows real concurrency;
 *     the fourth worker stays free, so "cleanup" keeps its 2s rhythm meanwhile
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.systemUTC();
        ConsoleListener listener = new ConsoleListener(clock);
        SchedulerEngine engine = new SchedulerEngine(new InMemoryJobStore(clock), clock, listener);

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

        for (int i = 1; i <= 3; i++) {
            engine.register(JobDefinition.builder("slow-" + i)
                    .job(Demo::threeSecondJob)
                    .trigger(new OneTimeTrigger(Duration.ofSeconds(5)))
                    .build());
        }

        WorkerPool pool = new WorkerPool(engine, 4, Duration.ofMillis(100), listener, null);
        pool.start();
        Thread.sleep(15_000);
        pool.stop();
        System.out.println("--- demo finished ---");
    }

    private static void threeSecondJob() throws JobExecutionException {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExecutionException("interrupted", e);
        }
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
            print("started   '" + job.getName() + "' attempt " + run.getAttemptNumber()
                    + " on worker " + run.getWorkerId().map(String::valueOf).orElse("?"));
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

        @Override
        public void onWorkerError(long workerId, Throwable problem) {
            print("worker " + workerId + " error: " + problem);
        }
    }
}
