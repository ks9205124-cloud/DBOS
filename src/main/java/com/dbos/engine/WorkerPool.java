package com.dbos.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/*
    A fixed number of worker threads that keep the engine busy.
    Every worker has an id (1..N) and repeats:
        1. engine.runNext(workerId)   claim one due run and process it
        2. if a run was processed, go straight back to 1 (there may be more waiting)
        3. if nothing was due, wait one poll interval and go back to 1

    Why this is safe: the workers share nothing except the store, and the store's
    claim is synchronized, so two workers can never get the same run.

    A worker never dies: anything unexpected is reported to the listener and the
    worker carries on after waiting one poll interval.

    stop() does not interrupt anything. It tells the workers to finish what they
    are doing and exit, wakes the ones that are waiting, and then waits (up to a
    timeout) for every thread to end.
*/
public final class WorkerPool {

    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(10);

    private final SchedulerEngine engine;
    private final int workerCount;
    private final Duration pollInterval;   // wait between checks, so an idle worker does not spin
    private final ExecutionListener listener;

    // lock guards started and stopRequested; waiting workers sleep on it so stop() can wake them
    private final Object lock = new Object();
    private boolean started = false;
    private boolean stopRequested = false;
    private final List<Thread> threads = new ArrayList<>();

    public WorkerPool(SchedulerEngine engine, int workerCount, Duration pollInterval) {
        this(engine, workerCount, pollInterval, null);
    }

    // a null listener means nobody is told about worker errors
    public WorkerPool(SchedulerEngine engine, int workerCount, Duration pollInterval, ExecutionListener listener) {
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
        this.listener = (listener == null) ? new ExecutionListener(false) : listener;
    }

    public int getWorkerCount() {
        return workerCount;
    }

    // starts all workers; a pool can be started only once
    public void start() {
        synchronized (lock) {
            if (stopRequested) {
                throw new IllegalStateException("This pool has been stopped; create a new one");
            }
            if (started) {
                throw new IllegalStateException("This pool is already started");
            }
            started = true;
            for (int id = 1; id <= workerCount; id++) {
                Thread thread = new Thread(new Worker(id), "dbos-worker-" + id);
                thread.setDaemon(true);   // a forgotten stop() cannot keep the program alive
                threads.add(thread);
                thread.start();
            }
        }
    }

    // stop() with a default timeout of 10 seconds
    public boolean stop() {
        return stop(DEFAULT_STOP_TIMEOUT);
    }

    // asks the workers to finish their current run and exit, then waits up to the timeout;
    // returns true if every worker has ended, false if a long job was still running
    public boolean stop(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be zero or positive");
        }
        List<Thread> toWaitFor;
        synchronized (lock) {
            stopRequested = true;
            lock.notifyAll();   // wake the workers that are waiting
            toWaitFor = new ArrayList<>(threads);
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : toWaitFor) {
            long millisLeft = (deadline - System.nanoTime()) / 1_000_000;
            try {
                thread.join(Math.max(1, millisLeft));   // join(0) would wait forever, so at least 1
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (thread.isAlive()) {
                return false;
            }
        }
        return true;
    }

    private boolean isStopRequested() {
        synchronized (lock) {
            return stopRequested;
        }
    }

    // the code each worker thread runs
    private class Worker implements Runnable {

        private final int workerId;

        Worker(int workerId) {
            this.workerId = workerId;
        }

        @Override
        public void run() {
            while (!isStopRequested()) {
                boolean processed = false;
                try {
                    processed = engine.runNext(workerId);
                } catch (Throwable problem) {
                    // the worker must survive anything; a failed round counts as "nothing processed"
                    report(problem);
                }
                // a job may have left the interrupt flag set; it must not leak into the next run
                Thread.interrupted();

                if (!processed && !waitOnePollInterval()) {
                    return;
                }
            }
        }

        // waits one poll interval; false if stop() was called in the meantime
        private boolean waitOnePollInterval() {
            synchronized (lock) {
                if (stopRequested) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, pollInterval.toMillis()));   // wait(0) would wait forever
                } catch (InterruptedException e) {
                    // nobody interrupts a worker on purpose, so this is a stray flag; carry on
                }
                return !stopRequested;
            }
        }

        private void report(Throwable problem) {
            try {
                listener.onWorkerError(workerId, problem);
            } catch (RuntimeException ignored) {
                // a faulty listener must not kill the worker
            }
        }
    }
}
