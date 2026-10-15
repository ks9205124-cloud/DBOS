
import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobStatus;
import com.dbos.store.InMemoryJobStore;
import com.dbos.store.JobStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class JobStoreTestRunner {

    public static void main(String[] args) {
        System.out.println("=== STARTING JOB STORE CORE TESTS ===");

        try {
            testBasicJobsAndDuplicates();
            testExecutionCreationAndValidation();
            testClaimingLogicAndTieBreaking();
            testUpdateExecutionAndStaleGuards();
            testJobCancellationCascade();
            testConcurrencyStressTest();

            System.out.println("\n=== ALL CORE TESTS PASSED SUCCESSFULLY! ===");
        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED WITH EXCEPTION:");
            e.printStackTrace();
        }
    }

    private static void testBasicJobsAndDuplicates() throws Exception {
        System.out.println("\n[Test 1] Testing Job Addition, Lookups, and Duplicate Names...");
        JobStore store = new InMemoryJobStore();

        JobDefinition j1 = JobDefinition.builder("report-job").build();
        JobDefinition saved1 = store.addJob(j1);

        // Check ID assignment and immutability preservation
        assert saved1.getId() == 1 : "Expected job ID to be 1";
        assert j1.getId() == 0 : "Original job object should remain unchanged (ID 0)";

        store.addJob(JobDefinition.builder("email-job").build());

        // Check getJob and getAllJobs
        assert store.getJob(1).isPresent() : "Job 1 should exist";
        assert store.getAllJobs().size() == 2 : "Expected 2 jobs in store";

        // Test duplicate name exception
        boolean threwDuplicate = false;
        try {
            store.addJob(JobDefinition.builder("report-job").build());
        } catch (DuplicateJobException e) {
            threwDuplicate = true;
        }
        assert threwDuplicate : "Adding a duplicate job name must throw DuplicateJobException";

        System.out.println("-> Test 1 Passed.");
    }

    private static void testExecutionCreationAndValidation() throws Exception {
        System.out.println("\n[Test 2] Testing Execution Creation and Inactive Job Rules...");
        JobStore store = new InMemoryJobStore();
        Instant now = Instant.now();

        try {
            // Should fail because job doesn't exist yet
            store.createExecution(999, 1, now);
            assert false : "Expected exception for non-existent job";
        } catch (IllegalArgumentException e) {
            // Expected
        }

        JobDefinition job = store.addJob(JobDefinition.builder("task-job").build());
        JobExecution exec = store.createExecution(job.getId(), 1, now);

        assert exec.getId() == 1 : "Execution ID should be 1";
        assert exec.getStatus() == JobStatus.PENDING : "New execution status must be PENDING";

        System.out.println("-> Test 2 Passed.");
    }

    private static void testClaimingLogicAndTieBreaking() throws Exception {
        System.out.println("\n[Test 3] Testing Claiming Logic and Strict Tie-Breaking...");
        JobStore store = new InMemoryJobStore();
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");

        JobDefinition job = store.addJob(JobDefinition.builder("claim-job").build());
        long jobId = job.getId();

        // Create runs at t+10, t+5, and t+10 (tie)
        store.createExecution(jobId, 1, baseTime.plusSeconds(10)); // ID 1
        store.createExecution(jobId, 1, baseTime.plusSeconds(5));  // ID 2 (Earliest time wins)
        store.createExecution(jobId, 1, baseTime.plusSeconds(10)); // ID 3 (Ties with ID 1, lower ID wins)

        Instant claimNow = baseTime.plusSeconds(15);

        // First claim should get ID 2 (t+5)
        Optional<JobExecution> claimed1 = store.claimNextExecution(101, claimNow);
        assert claimed1.isPresent() && claimed1.get().getId() == 2 : "Expected execution 2 to be claimed first";

        // Second claim should get ID 1 (t+10, lower ID than 3)
        Optional<JobExecution> claimed2 = store.claimNextExecution(101, claimNow);
        assert claimed2.isPresent() && claimed2.get().getId() == 1 : "Expected execution 1 to be claimed second due to tie-breaker";

        // Third claim should get ID 3 (t+10)
        Optional<JobExecution> claimed3 = store.claimNextExecution(101, claimNow);
        assert claimed3.isPresent() && claimed3.get().getId() == 3 : "Expected execution 3 to be claimed third";

        // Nothing more due
        Optional<JobExecution> claimed4 = store.claimNextExecution(101, claimNow);
        assert claimed4.isEmpty() : "No more executions should be due";

        System.out.println("-> Test 3 Passed.");
    }

    private static void testUpdateExecutionAndStaleGuards() throws Exception {
        System.out.println("\n[Test 4] Testing Update Execution and Stale-Write Guards...");
        JobStore store = new InMemoryJobStore();
        Instant now = Instant.now();

        JobDefinition job = store.addJob(JobDefinition.builder("update-job").build());
        JobExecution exec = store.createExecution(job.getId(), 1, now);

        // Claim it
        JobExecution claimed = store.claimNextExecution(500, now).get();

        // Modify local copy to DONE and save
        claimed.markDone(now.plusSeconds(5));
        store.updateExecution(claimed);

        // Verify store reflects DONE
        List<JobExecution> executions = store.listExecutions(job.getId());
        assert executions.get(0).getStatus() == JobStatus.DONE : "Store execution status should be DONE";

        // Test Stale-Write Guard: try to apply a stale FAILED copy over a DONE execution
        boolean threwIllegalState = false;
        try {
            JobExecution staleCopy = claimed.withId(claimed.getId());
            staleCopy.markFailed(now.plusSeconds(10));
            store.updateExecution(staleCopy);
        } catch (IllegalStateException e) {
            threwIllegalState = true;
        }
        assert threwIllegalState : "Stale/Illegal status transition must throw IllegalStateException";

        System.out.println("-> Test 4 Passed.");
    }

    private static void testJobCancellationCascade() throws Exception {
        System.out.println("\n[Test 5] Testing Job Cancellation and Pending Run Cascade...");
        JobStore store = new InMemoryJobStore();
        Instant now = Instant.now();

        JobDefinition job = store.addJob(JobDefinition.builder("cancel-job").build());
        long jobId = job.getId();

        // Create two PENDING runs and one RUNNING run
        JobExecution e1 = store.createExecution(jobId, 1, now);
        JobExecution e2 = store.createExecution(jobId, 1, now);
        JobExecution e3 = store.createExecution(jobId, 1, now);

        // Claim e3 so it becomes RUNNING
        store.claimNextExecution(200, now);

        // Cancel the job
        store.cancelJob(jobId);

        // Verify job is inactive
        assert !store.getJob(jobId).get().isActive() : "Job should be marked inactive";

        // Verify e1 and e2 are CANCELLED, but e3 remains RUNNING
        List<JobExecution> executions = store.listExecutions(jobId);
        assert executions.get(0).getStatus() == JobStatus.CANCELLED : "Pending run e1 should be CANCELLED";
        assert executions.get(1).getStatus() == JobStatus.CANCELLED : "Pending run e2 should be CANCELLED";
        assert executions.get(2).getStatus() == JobStatus.RUNNING : "Running run e3 should remain untouched";

        // Verify new executions cannot be created for cancelled jobs
        boolean threwException = false;
        try {
            store.createExecution(jobId, 2, now);
        } catch (IllegalArgumentException e) {
            threwException = true;
        }
        assert threwException : "Creating execution for a cancelled job must throw IllegalArgumentException";

        System.out.println("-> Test 5 Passed.");
    }

    private static void testConcurrencyStressTest() throws Exception {
        System.out.println("\n[Test 6] Running Concurrency Stress Tests (Step 7)...");
        JobStore store = new InMemoryJobStore();
        Instant now = Instant.now();

        JobDefinition job = store.addJob(JobDefinition.builder("stress-job").build());
        long jobId = job.getId();

        // Create 50 pending runs
        for (int i = 0; i < 50; i++) {
            store.createExecution(jobId, 1, now.minusSeconds(10));
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        Set<Long> claimedExecutionIds = ConcurrentHashMap.newKeySet();

        // Start 10 threads looping on claimNextExecution
        for (int t = 0; t < threadCount; t++) {
            final long workerId = t;
            executor.submit(() -> {
                try {
                    while (true) {
                        Optional<JobExecution> claimed = store.claimNextExecution(workerId, now);
                        if (claimed.isEmpty()) {
                            break;
                        }
                        boolean added = claimedExecutionIds.add(claimed.get().getId());
                        assert added : "Duplicate claim detected for execution ID: " + claimed.get().getId();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assert claimedExecutionIds.size() == 50 : "Expected exactly 50 unique executions claimed, got: " + claimedExecutionIds.size();
        System.out.println("-> Concurrency Claim Test Passed: All 50 runs claimed with zero duplicates!");

        // Test concurrent duplicate job name creation race
        ExecutorService nameExecutor = Executors.newFixedThreadPool(10);
        CountDownLatch nameLatch = new CountDownLatch(10);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            nameExecutor.submit(() -> {
                try {
                    store.addJob(JobDefinition.builder("unique-race-job").build());
                    successCount.incrementAndGet();
                } catch (DuplicateJobException e) {
                    duplicateCount.incrementAndGet();
                } finally {
                    nameLatch.countDown();
                }
            });
        }

        nameLatch.await();
        nameExecutor.shutdown();

        assert successCount.get() == 1 : "Exactly 1 thread should successfully add the job name, got: " + successCount.get();
        assert duplicateCount.get() == 9 : "Exactly 9 threads should throw DuplicateJobException, got: " + duplicateCount.get();
        System.out.println("-> Concurrency Duplicate Job Name Test Passed!");
    }
}