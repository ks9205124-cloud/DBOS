package com.dbos.engine;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.Job;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobExecutionException;
import com.dbos.core.JobStatus;
import com.dbos.store.InMemoryJobStore;
import com.dbos.store.JobStore;
import com.dbos.trigger.OneTimeTrigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * These tests use real threads, so they wait for results instead of sleeping a
 * fixed time. The clock is still a TestClock: every job is a one-time job that
 * is already due when the pool starts, so the clock never has to move again.
 * Because the clock is frozen, started/ended times in the store are all equal;
 * overlap between runs is therefore measured inside the jobs themselves.
 */
class WorkerPoolTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration FAST_POLL = Duration.ofMillis(20);
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private TestClock clock;
    private InMemoryJobStore store;
    private SchedulerEngine engine;
    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        store = new InMemoryJobStore(clock);
        engine = new SchedulerEngine(store, clock, new ExecutionListener(false));
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.stop(PATIENCE);   // never leave worker threads behind
        }
    }

    // registers a one-time job that fires one second from now, on the given engine
    private JobDefinition register(SchedulerEngine target, String name, Job work) throws DuplicateJobException {
        return target.register(JobDefinition.builder(name)
                .job(work)
                .trigger(new OneTimeTrigger(T0.plusSeconds(1)))
                .build());
    }

    private JobDefinition register(String name, Job work) throws DuplicateJobException {
        return register(engine, name, work);
    }

    private void makeEverythingDue() {
        clock.advance(Duration.ofSeconds(1));
    }

    private static boolean waitUntil(BooleanSupplier condition, Duration limit) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static boolean allDone(JobStore source, List<JobDefinition> jobs) {
        for (JobDefinition job : jobs) {
            List<JobExecution> runs = source.listExecutions(job.getId());
            if (runs.size() != 1 || runs.get(0).getStatus() != JobStatus.DONE) {
                return false;
            }
        }
        return true;
    }

    private JobStatus onlyStatusOf(JobDefinition job) {
        List<JobExecution> runs = store.listExecutions(job.getId());
        assertEquals(1, runs.size());
        return runs.get(0).getStatus();
    }

    // ---------- the showpiece: many slow jobs, several workers ----------

    @Test
    void twentyJobsOnFourWorkersEachRunExactlyOnceAndOverlap() throws Exception {
        int jobCount = 20;
        // the first two jobs wait for each other: they can only finish if two workers run side by side
        CountDownLatch rendezvous = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();

        List<AtomicInteger> callCounts = new ArrayList<>();
        List<JobDefinition> jobs = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            AtomicInteger calls = new AtomicInteger();
            callCounts.add(calls);
            jobs.add(register("slow-" + i, () -> {
                calls.incrementAndGet();
                int now = running.incrementAndGet();
                mostAtOnce.accumulateAndGet(now, Math::max);
                try {
                    rendezvous.countDown();
                    if (!rendezvous.await(5, TimeUnit.SECONDS)) {
                        throw new JobExecutionException("two jobs never ran at the same time");
                    }
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobExecutionException("interrupted", e);
                } finally {
                    running.decrementAndGet();
                }
            }));
        }
        makeEverythingDue();

        pool = new WorkerPool(engine, 4, FAST_POLL);
        pool.start();

        assertTrue(waitUntil(() -> allDone(store, jobs), PATIENCE), "not every run finished in time");
        assertTrue(pool.stop(PATIENCE), "stop() should return cleanly");

        // exactly once
        for (AtomicInteger calls : callCounts) {
            assertEquals(1, calls.get());
        }
        // several workers took part, all with ids from this pool
        Set<Integer> workerIds = new TreeSet<>();
        for (JobDefinition job : jobs) {
            workerIds.add(store.listExecutions(job.getId()).get(0).getWorkerId());
        }
        assertTrue(workerIds.size() >= 2, "expected at least two workers, saw " + workerIds);
        for (int id : workerIds) {
            assertTrue(id >= 1 && id <= 4, "unexpected worker id " + id);
        }
        // runs overlapped in time, but never more than the pool size
        assertTrue(mostAtOnce.get() >= 2);
        assertTrue(mostAtOnce.get() <= 4);
    }

    // ---------- stop() ----------

    @Test
    void stopLetsARunningJobFinishWithoutInterruptingIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        JobDefinition job = register("long-ish", () -> {
            started.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw new JobExecutionException("interrupted", e);
            }
            finished.set(true);
        });
        makeEverythingDue();

        pool = new WorkerPool(engine, 2, FAST_POLL);
        pool.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertTrue(pool.stop(PATIENCE));
        assertTrue(finished.get());
        assertFalse(interrupted.get());
        assertEquals(JobStatus.DONE, onlyStatusOf(job));
    }

    @Test
    void stopReturnsFalseWhenTheTimeoutPassesAndCanBeRepeated() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobDefinition job = register("blocked", () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobExecutionException("interrupted", e);
            }
        });
        makeEverythingDue();

        pool = new WorkerPool(engine, 1, FAST_POLL);
        pool.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertFalse(pool.stop(Duration.ofMillis(100)), "the job is still running");
        release.countDown();
        assertTrue(pool.stop(PATIENCE), "a second stop() should wait for the finish");
        assertEquals(JobStatus.DONE, onlyStatusOf(job));
    }

    @Test
    void idleWorkersWakeUpAtOnceWhenStopped() throws Exception {
        pool = new WorkerPool(engine, 3, Duration.ofSeconds(30));
        pool.start();
        Thread.sleep(100);   // let the workers settle into their long wait

        long begin = System.nanoTime();
        assertTrue(pool.stop(Duration.ofSeconds(5)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertTrue(elapsedMillis < 2000, "stop() took " + elapsedMillis + " ms; it should not wait out the poll interval");
    }

    // ---------- a worker never dies ----------

    @Test
    void aWorkerSurvivesStoreFailures() throws Exception {
        FlakyStore flaky = new FlakyStore(new InMemoryJobStore(clock), 3);
        SchedulerEngine flakyEngine = new SchedulerEngine(flaky, clock, new ExecutionListener(false));
        JobDefinition job = register(flakyEngine, "after-outage", () -> { });
        makeEverythingDue();

        ExecutionListener errorListener = new ExecutionListener(false);
        pool = new WorkerPool(flakyEngine, 1, FAST_POLL, errorListener);
        pool.start();

        assertTrue(waitUntil(() -> allDone(flaky, List.of(job)), PATIENCE), "the worker did not recover");
        assertTrue(pool.stop(PATIENCE));
        List<String> errors = errorListener.getLines();
        assertEquals(3, errors.size());
        assertTrue(errors.get(0).contains("worker 1 error"));
        assertTrue(errors.get(0).contains("db down"));
    }

    @Test
    void aFailingJobDoesNotStopTheWorkerFromTakingTheNextOne() throws Exception {
        JobDefinition bad = register("bad", () -> {
            throw new IllegalStateException("boom");
        });
        JobDefinition good = register("good", () -> { });
        makeEverythingDue();

        pool = new WorkerPool(engine, 1, FAST_POLL);
        pool.start();

        assertTrue(waitUntil(() -> allFinished(List.of(bad, good)), PATIENCE));
        assertTrue(pool.stop(PATIENCE));
        assertEquals(JobStatus.FAILED, onlyStatusOf(bad));
        assertEquals(JobStatus.DONE, onlyStatusOf(good));
    }

    @Test
    void anInterruptFlagLeftByAJobDoesNotLeakIntoTheNextRun() throws Exception {
        AtomicBoolean nextJobSawInterrupt = new AtomicBoolean();
        JobDefinition leaky = register("leaky", () -> Thread.currentThread().interrupt());
        JobDefinition next = register("next", () -> nextJobSawInterrupt.set(Thread.currentThread().isInterrupted()));
        makeEverythingDue();

        pool = new WorkerPool(engine, 1, FAST_POLL);
        pool.start();

        assertTrue(waitUntil(() -> allDone(store, List.of(leaky, next)), PATIENCE));
        assertTrue(pool.stop(PATIENCE));
        assertFalse(nextJobSawInterrupt.get());
    }

    private boolean allFinished(List<JobDefinition> jobs) {
        for (JobDefinition job : jobs) {
            List<JobExecution> runs = store.listExecutions(job.getId());
            if (runs.size() != 1 || !runs.get(0).getStatus().equals(JobStatus.DONE)
                    && !runs.get(0).getStatus().equals(JobStatus.FAILED)) {
                return false;
            }
        }
        return true;
    }

    // ---------- construction and lifecycle ----------

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(null, 2, FAST_POLL));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(engine, 0, FAST_POLL));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(engine, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(engine, 2, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new WorkerPool(engine, 2, Duration.ofMillis(-1)));
    }

    @Test
    void cannotBeStartedTwiceOrRestartedAfterStop() {
        pool = new WorkerPool(engine, 1, FAST_POLL);
        pool.start();
        assertThrows(IllegalStateException.class, pool::start);

        assertTrue(pool.stop(PATIENCE));
        assertThrows(IllegalStateException.class, pool::start);
    }

    @Test
    void stoppingAPoolThatNeverStartedIsHarmless() {
        pool = new WorkerPool(engine, 2, FAST_POLL);
        assertTrue(pool.stop(PATIENCE));
        assertThrows(IllegalStateException.class, pool::start);
    }

    // a store that fails its first few claims, then behaves like the real one
    private static final class FlakyStore implements JobStore {
        private final JobStore real;
        private final AtomicInteger failuresLeft;

        FlakyStore(JobStore real, int failures) {
            this.real = real;
            this.failuresLeft = new AtomicInteger(failures);
        }

        @Override
        public JobExecution claimNextExecution(int workerId, Instant now) {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("db down");
            }
            return real.claimNextExecution(workerId, now);
        }

        @Override
        public JobDefinition addJob(JobDefinition job) throws DuplicateJobException {
            return real.addJob(job);
        }

        @Override
        public JobDefinition getJob(int id) {
            return real.getJob(id);
        }

        @Override
        public List<JobDefinition> getAllJobs() {
            return real.getAllJobs();
        }

        @Override
        public void cancelJob(int id) {
            real.cancelJob(id);
        }

        @Override
        public JobExecution createExecution(int jobId, int attemptNumber, Instant scheduledFor) {
            return real.createExecution(jobId, attemptNumber, scheduledFor);
        }

        @Override
        public void updateExecution(JobExecution execution) {
            real.updateExecution(execution);
        }

        @Override
        public List<JobExecution> listExecutions(int jobId) {
            return real.listExecutions(jobId);
        }
    }
}
