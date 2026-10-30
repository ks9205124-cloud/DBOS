package com.dbos.engine;

import com.dbos.core.Job;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobExecutionException;
import com.dbos.core.JobStatus;
import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.retry.FixedDelayPolicy;
import com.dbos.retry.RetryPolicy;
import com.dbos.store.InMemoryJobStore;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.OneTimeTrigger;
import com.dbos.trigger.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* Every test runs on a clock that only moves when the test advances it, so nothing sleeps. */
class SchedulerEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final RetryPolicy NO_RETRY = new FixedDelayPolicy(0, Duration.ZERO);

    private TestClock clock;
    private InMemoryJobStore store;
    private ExecutionListener listener;
    private SchedulerEngine engine;

    @BeforeEach
    void setUp() {
        clock = new TestClock(T0);
        store = new InMemoryJobStore(clock);
        listener = new ExecutionListener(false);   // keeps the lines, prints nothing
        engine = new SchedulerEngine(store, clock, listener);
    }

    private JobDefinition job(String name, Job work, Trigger trigger, RetryPolicy policy) {
        return JobDefinition.builder(name).job(work).trigger(trigger).retryPolicy(policy).build();
    }

    private Trigger every(int seconds) {
        return new FixedRateTrigger(Duration.ofSeconds(seconds));
    }

    private void advance(int seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    private List<JobExecution> runs(JobDefinition job) {
        return store.listExecutions(job.getId());
    }

    private List<JobStatus> statuses(JobDefinition job) {
        List<JobStatus> result = new ArrayList<>();
        for (JobExecution run : runs(job)) {
            result.add(run.getStatus());
        }
        return result;
    }

    // ---------- registering ----------

    @Test
    void registerSchedulesTheFirstRun() throws Exception {
        JobDefinition saved = engine.register(job("first", () -> { }, every(10), NO_RETRY));

        List<JobExecution> runs = runs(saved);
        assertEquals(1, runs.size());
        assertEquals(T0.plusSeconds(10), runs.get(0).getScheduledFor());
        assertEquals(JobStatus.PENDING, runs.get(0).getStatus());
        assertEquals(1, runs.get(0).getAttemptNumber());
    }

    @Test
    void nothingRunsBeforeTheDueTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        engine.register(job("early", calls::incrementAndGet, every(10), NO_RETRY));

        assertEquals(0, engine.tick());
        advance(9);
        assertEquals(0, engine.tick());
        assertEquals(0, calls.get());
    }

    // ---------- success and recurrence ----------

    @Test
    void successRunsTheJobAndSchedulesTheNextRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JobDefinition saved = engine.register(job("ok", calls::incrementAndGet, every(10), NO_RETRY));

        advance(10);
        assertEquals(1, engine.tick());

        assertEquals(1, calls.get());
        List<JobExecution> runs = runs(saved);
        assertEquals(2, runs.size());
        assertEquals(JobStatus.DONE, runs.get(0).getStatus());
        assertEquals(T0.plusSeconds(10), runs.get(0).getStartedAt());
        assertEquals(T0.plusSeconds(10), runs.get(0).getEndedAt());
        assertEquals(JobStatus.PENDING, runs.get(1).getStatus());
        assertEquals(T0.plusSeconds(20), runs.get(1).getScheduledFor());
        assertEquals(1, runs.get(1).getAttemptNumber());
    }

    @Test
    void aRecurringJobKeepsRunning() throws Exception {
        JobDefinition saved = engine.register(job("loop", () -> { }, every(10), NO_RETRY));

        for (int i = 0; i < 3; i++) {
            advance(10);
            assertEquals(1, engine.tick());
        }

        assertEquals(List.of(JobStatus.DONE, JobStatus.DONE, JobStatus.DONE, JobStatus.PENDING), statuses(saved));
    }

    @Test
    void aOneTimeJobRunsOnceAndTheScheduleEnds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JobDefinition saved = engine.register(
                job("once", calls::incrementAndGet, new OneTimeTrigger(T0.plusSeconds(5)), NO_RETRY));

        advance(5);
        assertEquals(1, engine.tick());
        advance(100);
        assertEquals(0, engine.tick());

        assertEquals(1, calls.get());
        assertEquals(List.of(JobStatus.DONE), statuses(saved));
        assertTrue(logged("schedule ended"));
    }

    // ---------- failure and retry ----------

    @Test
    void aFlakyJobIsRetriedUntilItSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Job flaky = () -> {
            if (calls.incrementAndGet() <= 2) {
                throw new JobExecutionException("fail " + calls.get());
            }
        };
        JobDefinition saved = engine.register(
                job("flaky", flaky, new OneTimeTrigger(T0.plusSeconds(5)), new FixedDelayPolicy(3, Duration.ofSeconds(2))));

        advance(5);
        assertEquals(1, engine.tick());   // attempt 1 fails, retry at +2s
        assertEquals(0, engine.tick());
        advance(2);
        assertEquals(1, engine.tick());   // attempt 2 fails, retry at +2s
        advance(2);
        assertEquals(1, engine.tick());   // attempt 3 succeeds

        assertEquals(3, calls.get());
        List<JobExecution> runs = runs(saved);
        assertEquals(List.of(JobStatus.FAILED, JobStatus.FAILED, JobStatus.DONE), statuses(saved));
        assertEquals(List.of(1, 2, 3),
                List.of(runs.get(0).getAttemptNumber(), runs.get(1).getAttemptNumber(), runs.get(2).getAttemptNumber()));
        assertEquals(T0.plusSeconds(7), runs.get(1).getScheduledFor());
        assertEquals(T0.plusSeconds(9), runs.get(2).getScheduledFor());
    }

    @Test
    void exponentialBackoffSpacesRetriesFurtherApart() throws Exception {
        Job alwaysFails = () -> { throw new JobExecutionException("no"); };
        JobDefinition saved = engine.register(
                job("backoff", alwaysFails, new OneTimeTrigger(T0.plusSeconds(1)),
                        new ExponentialBackoffPolicy(2, Duration.ofSeconds(2))));

        advance(1);
        engine.tick();   // attempt 1 fails at +1s, retry in 2s
        advance(2);
        engine.tick();   // attempt 2 fails at +3s, retry in 4s

        List<JobExecution> runs = runs(saved);
        assertEquals(3, runs.size());
        assertEquals(T0.plusSeconds(3), runs.get(1).getScheduledFor());
        assertEquals(T0.plusSeconds(7), runs.get(2).getScheduledFor());
    }

    @Test
    void whenRetriesRunOutTheRegularScheduleContinues() throws Exception {
        Job alwaysFails = () -> { throw new JobExecutionException("no"); };
        JobDefinition saved = engine.register(
                job("exhausted", alwaysFails, every(10), new FixedDelayPolicy(1, Duration.ofSeconds(2))));

        advance(10);
        engine.tick();   // attempt 1 fails, retry at +12s
        advance(2);
        engine.tick();   // attempt 2 fails, no retries left, next regular run at +22s

        List<JobExecution> runs = runs(saved);
        assertEquals(List.of(JobStatus.FAILED, JobStatus.FAILED, JobStatus.PENDING), statuses(saved));
        assertEquals(2, runs.get(1).getAttemptNumber());
        assertEquals(1, runs.get(2).getAttemptNumber());   // a fresh schedule starts at attempt 1
        assertEquals(T0.plusSeconds(22), runs.get(2).getScheduledFor());
    }

    @Test
    void aRuntimeExceptionIsAnOrdinaryFailure() throws Exception {
        Job buggy = () -> { throw new IllegalStateException("bug"); };
        JobDefinition saved = engine.register(job("buggy", buggy, every(10), NO_RETRY));

        advance(10);
        assertEquals(1, engine.tick());   // the engine survives

        assertEquals(List.of(JobStatus.FAILED, JobStatus.PENDING), statuses(saved));
        assertTrue(logged("FAILED", "bug"));
    }

    @Test
    void anErrorFromAJobIsAlsoAnOrdinaryFailure() throws Exception {
        Job broken = () -> { throw new StackOverflowError("deep"); };
        JobDefinition saved = engine.register(job("broken", broken, every(10), NO_RETRY));

        advance(10);
        assertEquals(1, engine.tick());   // the run does not stay stuck in RUNNING

        assertEquals(List.of(JobStatus.FAILED, JobStatus.PENDING), statuses(saved));
        assertTrue(logged("FAILED", "deep"));
    }

    // ---------- cancelling ----------

    @Test
    void cancelStopsFutureRuns() throws Exception {
        JobDefinition saved = engine.register(job("stopped", () -> { }, every(10), NO_RETRY));

        engine.cancel(saved.getId());
        advance(10);

        assertEquals(0, engine.tick());
        assertEquals(List.of(JobStatus.CANCELLED), statuses(saved));
        assertFalse(store.getJob(saved.getId()).isActive());
    }

    @Test
    void aJobCancelledWhileRunningFinishesAndIsNotRescheduled() throws Exception {
        AtomicInteger id = new AtomicInteger();
        JobDefinition saved = engine.register(
                job("self-cancel", () -> engine.cancel(id.get()), every(10), NO_RETRY));
        id.set(saved.getId());

        advance(10);
        assertEquals(1, engine.tick());

        assertEquals(List.of(JobStatus.DONE), statuses(saved));
        assertFalse(store.getJob(saved.getId()).isActive());
    }

    // ---------- timing rules ----------

    @Test
    void aLongRunIsFollowedByOneCatchUpRunNotABurst() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Job slowOnce = () -> {
            if (calls.getAndIncrement() == 0) {
                clock.advance(Duration.ofSeconds(25));   // the first run takes 25 seconds
            }
        };
        JobDefinition saved = engine.register(job("slow", slowOnce, every(10), NO_RETRY));

        advance(10);
        assertEquals(2, engine.tick());

        // the 12:00:20 and 12:00:30 slots were missed and are not replayed
        List<JobExecution> runs = runs(saved);
        assertEquals(3, runs.size());
        assertEquals(T0.plusSeconds(35), runs.get(1).getScheduledFor());
        assertEquals(T0.plusSeconds(45), runs.get(2).getScheduledFor());
    }

    // ---------- listener and construction ----------

    @Test
    void listenerEventsComeInOrder() throws Exception {
        engine.register(job("events", () -> { }, every(10), NO_RETRY));
        advance(10);
        engine.tick();

        List<String> lines = listener.getLines();
        assertEquals(5, lines.size());
        assertTrue(lines.get(0).contains("registered"));
        assertTrue(lines.get(1).contains("scheduled"));
        assertTrue(lines.get(2).contains("started"));
        assertTrue(lines.get(3).contains("DONE"));
        assertTrue(lines.get(4).contains("scheduled"));
    }

    @Test
    void constructionAndRegistrationRejectMissingArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SchedulerEngine(null, clock, listener));
        assertThrows(IllegalArgumentException.class, () -> new SchedulerEngine(store, null, listener));
        assertThrows(IllegalArgumentException.class, () -> engine.register(null));
    }

    // true if one logged line contains all the given pieces of text
    private boolean logged(String... parts) {
        for (String line : listener.getLines()) {
            boolean all = true;
            for (String part : parts) {
                if (!line.contains(part)) {
                    all = false;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
