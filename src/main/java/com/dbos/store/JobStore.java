package com.dbos.store;

import com.dbos.core.DuplicateJobException;
import com.dbos.core.JobDefinition;
import com.dbos.core.JobExecution;

import java.time.Instant;
import java.util.List;

public interface JobStore {

    // JobDefinition
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    // null if there is no job with this id
    JobDefinition getJob(int id);
    List<JobDefinition> getAllJobs();
    void cancelJob(int id);

    // JobExecution
    JobExecution createExecution(int jobId, int attemptNumber, Instant scheduledFor);
    // null if no run is due
    JobExecution claimNextExecution(int workerId, Instant now);
    void updateExecution(JobExecution execution);
    List<JobExecution> listExecutions(int jobId);

}