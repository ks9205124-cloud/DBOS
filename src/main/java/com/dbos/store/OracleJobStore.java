package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class OracleJobStore implements JobStore {
    @Override
    public JobDefinition addJob(JobDefinition job) throws DuplicateJobException {
        return null;
    }

    @Override
    public Optional<JobDefinition> getJob(long id) {
        return Optional.empty();
    }

    @Override
    public List<JobDefinition> getAllJobs() {
        return List.of();
    }

    @Override
    public void cancelJob(long id) {

    }

    @Override
    public JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor) {
        return null;
    }

    @Override
    public Optional<JobExecution> claimNextExecution(long workerId, Instant now) {
        return Optional.empty();
    }

    @Override
    public void updateExecution(JobExecution execution) {

    }

    @Override
    public List<JobExecution> listExecutions(long jobId) {
        return List.of();
    }
}
