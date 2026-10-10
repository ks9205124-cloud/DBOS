package com.dbos.store;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobStore {

    // JobDefinition
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    Optional<JobDefinition> getJob(long id);
    List<JobDefinition> getAllJobs();
    void cancelJob(long id);

    // JobExecution
    JobExecution createExecution(long jobId, int attemptNumber, Instant scheduledFor);
    Optional<JobExecution> claimNextExecution(long workerId, Instant now);
    void updateExecution(JobExecution execution);
    List<JobExecution> listExecutions(long jobId);

}