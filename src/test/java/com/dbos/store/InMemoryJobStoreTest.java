package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;
import com.dbos.core.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// runs every contract test against the in-memory store, plus behavior specific to it
class InMemoryJobStoreTest extends JobStoreContractTest {

    @Override
    protected JobStore createStore() {
        return new InMemoryJobStore();
    }

    // a database identity column may skip numbers after a failure; this store promises it won't
    @Test
    void aFailedAddJobDoesNotUseUpAnId() throws Exception {
        JobDefinition a = store.addJob(newJob("a"));
        assertThrows(DuplicateJobException.class, () -> store.addJob(newJob("a")));

        JobDefinition b = store.addJob(newJob("b"));

        assertEquals(a.getId() + 1, b.getId());
    }

    @Test
    void aFailedCreateExecutionDoesNotUseUpAnId() throws Exception {
        JobDefinition job = store.addJob(newJob("ids"));
        JobExecution first = store.createExecution(job.getId(), 1, T0);
        assertThrows(IllegalArgumentException.class, () -> store.createExecution(job.getId(), 0, T0));

        JobExecution second = store.createExecution(job.getId(), 1, T0);

        assertEquals(first.getId() + 1, second.getId());
    }

    @Test
    void cancelJobUsesTheInjectedClock() throws Exception {
        InMemoryJobStore fixedClockStore = new InMemoryJobStore(Clock.fixed(T0, ZoneOffset.UTC));
        JobDefinition job = fixedClockStore.addJob(newJob("clock"));
        fixedClockStore.createExecution(job.getId(), 1, T0.plusSeconds(60));

        fixedClockStore.cancelJob(job.getId());

        JobExecution run = fixedClockStore.listExecutions(job.getId()).get(0);
        assertEquals(JobStatus.CANCELLED, run.getStatus());
        assertEquals(Optional.of(T0), run.getEndedAt());
    }

    @Test
    void aNullClockIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryJobStore(null));
    }
}