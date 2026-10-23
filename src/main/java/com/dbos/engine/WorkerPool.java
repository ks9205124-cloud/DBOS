package com.dbos.engine;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * A fixed pool of worker threads that keep the engine busy.
 *
 * Every worker has an id (1..N) and runs the same loop:
 *
 *   1. engine.runNext(workerId)   claim one due run and process it
 *   2. if a run was processed, go straight back to 1 (there may be a backlog)
 *   3. if nothing was due, wait one poll interval and go back to 1
 *
 * Why this is safe: the workers share nothing except the JobStore, and the
 * store's claimNextExecution is atomic, so two workers can never get the same
 * run. The threads are ordinary platform threads from a fixed pool. They are
 * created through a ThreadFactory, so switching to virtual threads later means
 * passing a different factory and nothing else.
 *
 * A worker never dies. Anything unexpected (for example the store throwing) is
 * reported through ExecutionListener.onWorkerError, and the worker carries on
 * after a poll interval, so a broken store cannot cause a hot loop. An
 * interrupt flag left behind by a job is cleared after each run; the pool is
 * stopped only through stop().
 *
 * stop() does not interrupt anything. It tells the workers to finish what they
 * are doing and exit, and it wakes the idle ones at once, so a running job is
 * never cut off halfway. It then waits up to a timeout for the workers to
 * finish and says whether they all did.
 *
 * A pool is used once: start() once, stop() once or more. To run again, create
 * a new pool. Do not mix a running pool with SchedulerEngine.tick(); tick()
 * reports itself as worker 1.
 */
public final class WorkerPool {

    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final SchedulerEngine engine;
    private final int workerCount;
    private final Duration pollInterval;
    private final ExecutionListener listener;
    private final ThreadFactory threadFactory;

    // released by stop(); the workers wait on it instead of sleeping, so stop() wakes them at once
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private ExecutorService executor;   // null until start(); guarded by "this"

    public WorkerPool(SchedulerEngine engine, int workerCount, Duration pollInterval) {
        this(engine, workerCount, pollInterval, ExecutionListener.NOOP, null);
    }

    // a null listener means nobody is told about worker errors; a null factory means the default threads
    public WorkerPool(SchedulerEngine engine, int workerCount, Duration pollInterval,
                      ExecutionListener listener, ThreadFactory threadFactory) {
        if (engine == null) {
            throw new IllegalArgumentException("engine is required");
        }
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least 1");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.engine = engine;
        this.workerCount = workerCount;
        this.pollInterval = pollInterval;
        this.listener = (listener == null) ? ExecutionListener.NOOP : listener;
        this.threadFactory = (threadFactory == null) ? new NamedDaemonThreads() : threadFactory;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    // starts all workers; a pool can be started only once
    public synchronized void start() {
        if (stopSignal.getCount() == 0) {
            throw new IllegalStateException("This pool has been stopped; create a new one");
        }
        if (executor != null) {
            throw new IllegalStateException("This pool is already started");
        }
        executor = Executors.newFixedThreadPool(workerCount, threadFactory);
        for (int i = 1; i <= workerCount; i++) {
            long workerId = i;
            executor.execute(() -> workLoop(workerId));
        }
    }

    // stop() with a default timeout of 10 seconds
    public boolean stop() {
        return stop(DEFAULT_STOP_TIMEOUT);
    }

    /*
     * Asks the workers to finish their current run and exit, then waits up to
     * the timeout. Returns true if every worker has exited. Returns false if
     * the timeout passed first (a long job is still running) or if the calling
     * thread was interrupted while waiting; the workers still exit by
     * themselves when their current run ends, and stop() can be called again
     * to wait some more.
     */
    public boolean stop(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be zero or positive");
        }
        ExecutorService running;
        synchronized (this) {
            stopSignal.countDown();
            running = executor;
        }
        if (running == null) {
            return true;   // never started
        }
        running.shutdown();
        try {
            return running.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void workLoop(long workerId) {
        while (stopSignal.getCount() > 0) {
            boolean processed = false;
            try {
                processed = engine.runNext(workerId);
            } catch (Throwable problem) {
                // the worker must survive anything; a failed round counts as "nothing processed"
                report(workerId, problem);
            }
            // a job may have left the interrupt flag set; it must not leak into the next run
            Thread.interrupted();

            if (!processed && !pause()) {
                return;
            }
        }
    }

    // waits one poll interval; false if stop() was called in the meantime
    private boolean pause() {
        try {
            return !stopSignal.await(pollInterval.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            // nobody interrupts a worker on purpose (stop() never does), so this is
            // a stray flag; the loop re-checks the stop signal and carries on
            return true;
        }
    }

    private void report(long workerId, Throwable problem) {
        try {
            listener.onWorkerError(workerId, problem);
        } catch (RuntimeException ignored) {
            // a faulty listener must not kill the worker
        }
    }

    // daemon threads named dbos-worker-1, dbos-worker-2, ...; a forgotten stop() cannot keep the JVM alive
    private static final class NamedDaemonThreads implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dbos-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
