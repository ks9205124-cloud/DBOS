package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobStatus;
import com.dbos.retry.FixedDelayPolicy;
import com.dbos.trigger.FixedRateTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Behavior every JobStore must have. Each implementation supplies a tiny
 * subclass with createStore(), so the in-memory and Oracle stores run the
 * exact same tests. Nothing here assumes ids start at 1 or have no gaps,
 * because a database identity column does not promise that.
 */
abstract class JobStoreContractTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    protected JobStore store;

    // must return a fresh, empty store every time
    protected abstract JobStore createStore();

    @BeforeEach
    void setUp() {
        store = createStore();
    }

    // a fully built job, so these tests keep working once build() validates job and trigger
    protected JobDefinition newJob(String name) {
        return JobDefinition.builder(name)
                .job(() -> { })
                .trigger(new FixedRateTrigger(Duration.ofSeconds(10)))
                .retryPolicy(new FixedDelayPolicy(0, Duration.ZERO))
                .build();
    }

    // ---------- job definitions ----------

    @Test
    void addJobAssignsARealIdAndLeavesTheOriginalUntouched() throws Exception {
        JobDefinition original = newJob("report");
        JobDefinition saved = store.addJob(original);

        assertTrue(saved.getId() > 0);
        assertEquals(0, original.getId());
        assertEquals("report", saved.getName());
        assertTrue(saved.isActive());
    }

    @Test
    void addJobGivesEachJobADifferentId() throws Exception {
        JobDefinition a = store.addJob(newJob("a"));
        JobDefinition b = store.addJob(newJob("b"));

        assertNotEquals(a.getId(), b.getId());
    }

    @Test
    void addJobRejectsADuplicateName() throws Exception {
        store.addJob(newJob("same"));

        assertThrows(DuplicateJobException.class, () -> store.addJob(newJob("same")));
        assertEquals(1, store.getAllJobs().size());
    }

    @Test
    void getJobFindsASavedJobAndReturnsEmptyForAMissingOne() throws Exception {
        JobDefinition saved = store.addJob(newJob("find-me"));

        assertEquals(saved.getId(), store.getJob(saved.getId()).orElseThrow().getId());
        assertTrue(store.getJob(saved.getId() + 1000).isEmpty());
    }

    @Test
    void getAllJobsReturnsEveryJobInInsertionOrder() throws Exception {
        store.addJob(newJob("a"));
        store.addJob(newJob("b"));
        store.addJob(newJob("c"));

        List<String> names = new ArrayList<>();
        for (JobDefinition job : store.getAllJobs()) {
            names.add(job.getName());
        }
        assertEquals(List.of("a", "b", "c"), names);
    }

    // ---------- creating executions ----------

    @Test
    void createExecutionStartsPendingWithTheGivenValues() throws Exception {
        JobDefinition job = store.addJob(newJob("task"));

        JobExecution execution = store.createExecution(job.getId(), 1, T0);

        assertTrue(execution.getId() > 0);
        assertEquals(job.getId(), execution.getJobId());
        assertEquals(1, execution.getAttemptNumber());
        assertEquals(T0, execution.getScheduledFor());
        assertEquals(JobStatus.PENDING, execution.getStatus());
        assertTrue(execution.getWorkerId().isEmpty());
        assertTrue(execution.getStartedAt().isEmpty());
        assertTrue(execution.getEndedAt().isEmpty());
    }

    @Test
    void createExecutionRejectsAnUnknownJob() {
        assertThrows(IllegalArgumentException.class, () -> store.createExecution(999_999, 1, T0));
    }

    @Test
    void createExecutionRejectsBadInput() throws Exception {
        JobDefinition job = store.addJob(newJob("bad-input"));

        assertThrows(IllegalArgumentException.class, () -> store.createExecution(job.getId(), 0, T0));
        assertThrows(IllegalArgumentException.class, () -> store.createExecution(job.getId(), 1, null));
        assertTrue(store.listExecutions(job.getId()).isEmpty());
    }

    @Test
    void theExecutionReturnedByCreateIsACopy() throws Exception {
        JobDefinition job = store.addJob(newJob("create-copy"));
        JobExecution created = store.createExecution(job.getId(), 1, T0);

        created.markRunning(1, T0);   // changes only the caller's copy

        assertEquals(JobStatus.PENDING, store.listExecutions(job.getId()).get(0).getStatus());
    }

    // ---------- claiming ----------

    @Test
    void claimTakesTheEarliestDueExecutionAndBreaksTiesByLowerId() throws Exception {
        JobDefinition job = store.addJob(newJob("claim"));
        JobExecution at10 = store.createExecution(job.getId(), 1, T0.plusSeconds(10));
        JobExecution at5 = store.createExecution(job.getId(), 1, T0.plusSeconds(5));
        JobExecution at10Again = store.createExecution(job.getId(), 1, T0.plusSeconds(10));

        Instant now = T0.plusSeconds(15);

        assertEquals(at5.getId(), store.claimNextExecution(101, now).orElseThrow().getId());
        assertEquals(at10.getId(), store.claimNextExecution(101, now).orElseThrow().getId());
        assertEquals(at10Again.getId(), store.claimNextExecution(101, now).orElseThrow().getId());
        assertTrue(store.claimNextExecution(101, now).isEmpty());
    }

    @Test
    void claimSkipsExecutionsScheduledInTheFuture() throws Exception {
        JobDefinition job = store.addJob(newJob("future"));
        store.createExecution(job.getId(), 1, T0.plusSeconds(60));

        assertTrue(store.claimNextExecution(1, T0).isEmpty());
    }

    @Test
    void claimMarksTheExecutionRunningForThatWorker() throws Exception {
        JobDefinition job = store.addJob(newJob("running"));
        store.createExecution(job.getId(), 1, T0);
        Instant now = T0.plusSeconds(1);

        JobExecution claimed = store.claimNextExecution(7, now).orElseThrow();

        assertEquals(JobStatus.RUNNING, claimed.getStatus());
        assertEquals(Optional.of(7L), claimed.getWorkerId());
        assertEquals(Optional.of(now), claimed.getStartedAt());
        // the claim itself is saved, with no updateExecution call
        assertEquals(JobStatus.RUNNING, store.listExecutions(job.getId()).get(0).getStatus());
    }

    // ---------- updating executions ----------

    @Test
    void changesToAClaimedExecutionAreNotVisibleUntilItIsUpdated() throws Exception {
        JobDefinition job = store.addJob(newJob("copy"));
        store.createExecution(job.getId(), 1, T0);
        JobExecution claimed = store.claimNextExecution(1, T0).orElseThrow();

        claimed.markDone(T0.plusSeconds(1));
        assertEquals(JobStatus.RUNNING, store.listExecutions(job.getId()).get(0).getStatus());

        store.updateExecution(claimed);
        JobExecution saved = store.listExecutions(job.getId()).get(0);
        assertEquals(JobStatus.DONE, saved.getStatus());
        assertEquals(Optional.of(T0.plusSeconds(1)), saved.getEndedAt());
    }

    @Test
    void updateRejectsAStaleCopyThatWouldOverwriteANewerState() throws Exception {
        JobDefinition job = store.addJob(newJob("stale"));
        store.createExecution(job.getId(), 1, T0);
        JobExecution claimed = store.claimNextExecution(1, T0).orElseThrow();
        JobExecution staleCopy = claimed.withId(claimed.getId());   // taken while still RUNNING

        claimed.markDone(T0.plusSeconds(1));
        store.updateExecution(claimed);

        staleCopy.markFailed(T0.plusSeconds(2));                    // legal on the copy itself
        assertThrows(IllegalStateException.class, () -> store.updateExecution(staleCopy));
        assertEquals(JobStatus.DONE, store.listExecutions(job.getId()).get(0).getStatus());
    }

    @Test
    void updateRejectsChangesToTheFieldsFixedAtCreation() throws Exception {
        JobDefinition job = store.addJob(newJob("fixed-fields"));
        JobExecution created = store.createExecution(job.getId(), 1, T0);

        JobExecution differentAttempt = new JobExecution(created.getId(), job.getId(), 2, T0);
        JobExecution differentTime = new JobExecution(created.getId(), job.getId(), 1, T0.plusSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> store.updateExecution(differentAttempt));
        assertThrows(IllegalArgumentException.class, () -> store.updateExecution(differentTime));
    }

    @Test
    void updateRejectsAnUnknownExecution() throws Exception {
        JobDefinition job = store.addJob(newJob("unknown-exec"));
        JobExecution ghost = new JobExecution(999_999, job.getId(), 1, T0);

        assertThrows(IllegalArgumentException.class, () -> store.updateExecution(ghost));
    }

    // ---------- listing ----------

    @Test
    void listExecutionsReturnsOnlyThatJobsRuns() throws Exception {
        JobDefinition first = store.addJob(newJob("first"));
        JobDefinition second = store.addJob(newJob("second"));
        store.createExecution(first.getId(), 1, T0);
        store.createExecution(first.getId(), 2, T0);
        store.createExecution(second.getId(), 1, T0);

        List<JobExecution> runs = store.listExecutions(first.getId());

        assertEquals(2, runs.size());
        for (JobExecution run : runs) {
            assertEquals(first.getId(), run.getJobId());
        }
        assertTrue(store.listExecutions(999_999).isEmpty());
    }

    // ---------- cancelling ----------

    @Test
    void cancelJobDeactivatesItAndCancelsOnlyPendingExecutions() throws Exception {
        JobDefinition job = store.addJob(newJob("cancel"));
        store.createExecution(job.getId(), 1, T0.plusSeconds(1));
        store.createExecution(job.getId(), 1, T0.plusSeconds(1));
        JobExecution earliest = store.createExecution(job.getId(), 1, T0);   // the one the claim will take

        JobExecution running = store.claimNextExecution(1, T0.plusSeconds(1)).orElseThrow();
        assertEquals(earliest.getId(), running.getId());

        store.cancelJob(job.getId());

        assertFalse(store.getJob(job.getId()).orElseThrow().isActive());
        for (JobExecution run : store.listExecutions(job.getId())) {
            if (run.getId() == earliest.getId()) {
                assertEquals(JobStatus.RUNNING, run.getStatus());
            } else {
                assertEquals(JobStatus.CANCELLED, run.getStatus());
            }
        }
        assertThrows(IllegalStateException.class, () -> store.createExecution(job.getId(), 2, T0));
    }

    @Test
    void cancelJobRejectsAnUnknownJob() {
        assertThrows(IllegalArgumentException.class, () -> store.cancelJob(999_999));
    }

    @Test
    void cancellingTheSameJobTwiceIsHarmless() throws Exception {
        JobDefinition job = store.addJob(newJob("twice"));
        store.createExecution(job.getId(), 1, T0);

        store.cancelJob(job.getId());
        store.cancelJob(job.getId());

        assertEquals(JobStatus.CANCELLED, store.listExecutions(job.getId()).get(0).getStatus());
    }

    // ---------- concurrency ----------

    @Test
    void concurrentClaimsNeverReturnTheSameExecution() throws Exception {
        JobDefinition job = store.addJob(newJob("claim-race"));
        int executionCount = 50;
        for (int i = 0; i < executionCount; i++) {
            store.createExecution(job.getId(), 1, T0);
        }

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 1; t <= threads; t++) {
            final long workerId = t;
            Callable<List<Long>> worker = () -> {
                List<Long> mine = new ArrayList<>();
                startGate.await();   // every thread starts together
                Optional<JobExecution> next;
                while ((next = store.claimNextExecution(workerId, T0)).isPresent()) {
                    mine.add(next.get().getId());
                }
                return mine;
            };
            futures.add(pool.submit(worker));
        }
        startGate.countDown();

        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> future : futures) {
            all.addAll(future.get(10, TimeUnit.SECONDS));   // a failure inside a thread fails the test
        }
        pool.shutdown();

        assertEquals(executionCount, all.size());                 // none missed
        assertEquals(executionCount, new HashSet<>(all).size());  // none claimed twice
        for (JobExecution run : store.listExecutions(job.getId())) {
            assertEquals(JobStatus.RUNNING, run.getStatus());
        }
    }

    @Test
    void onlyOneThreadCanAddAGivenName() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                startGate.await();
                try {
                    store.addJob(newJob("same-name"));
                    added.incrementAndGet();
                } catch (DuplicateJobException expected) {
                    rejected.incrementAndGet();
                }
                return null;
            };
            futures.add(pool.submit(task));
        }
        startGate.countDown();
        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, added.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, store.getAllJobs().size());
    }
}