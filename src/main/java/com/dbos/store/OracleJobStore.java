package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/*
 * The Oracle-backed JobStore (data track). Not implemented yet: every method
 * fails loudly instead of returning a fake empty answer, so nobody mistakes
 * this stub for a working store.
 */
public class OracleJobStore implements JobStore {

    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("OracleJobStore is not implemented yet");
    }

    @Override
    public JobDefinition addJob(JobDefinition job) throws DuplicateJobException {
        throw notImplemented();
    }

    @Override
    public Optional<JobDefinition> getJob(long id) {
        throw notImplemented();
    }

    @Override
    public List<JobDefinition> getAllJobs() {
        throw notImplemented();
    }

    @Override
    public void cancelJob(long id) {
        throw notImplemented();
    }

    @Override
    public JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor) {
        throw notImplemented();
    }

    @Override
    public Optional<JobExecution> claimNextExecution(long workerId, Instant now) {
        throw notImplemented();
    }

    @Override
    public void updateExecution(JobExecution execution) {
        throw notImplemented();
    }

    @Override
    public List<JobExecution> listExecutions(long jobId) {
        throw notImplemented();
    }
}
