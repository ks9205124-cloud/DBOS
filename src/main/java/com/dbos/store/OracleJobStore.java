package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.util.List;

public class OracleJobStore implements JobStore {

    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("OracleJobStore is not implemented yet");
    }

    @Override
    public JobDefinition addJob(JobDefinition job) throws DuplicateJobException {
        throw notImplemented();
    }

    @Override
    public JobDefinition getJob(int id) {
        throw notImplemented();
    }

    @Override
    public List<JobDefinition> getAllJobs() {
        throw notImplemented();
    }

    @Override
    public void cancelJob(int id) {
        throw notImplemented();
    }

    @Override
    public JobExecution createExecution(int jobId, int attemptNumber, Instant scheduledFor) {
        throw notImplemented();
    }

    @Override
    public JobExecution claimNextExecution(int workerId, Instant now) {
        throw notImplemented();
    }

    @Override
    public void updateExecution(JobExecution execution) {
        throw notImplemented();
    }

    @Override
    public List<JobExecution> listExecutions(int jobId) {
        throw notImplemented();
    }
}
