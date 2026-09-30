package com.dbos.store;

import com.dbos.core.JobDefinition;
import com.dbos.core.JobStatus;

import java.util.List;
import java.util.Optional;

public interface JobStore {
    JobDefinition addJob(JobDefinition job) throws DuplicateJobException;
    Optional<JobDefinition> getJob(long id);
    List<JobDefinition> getAllJobs();
    void updateStatus(long id, JobStatus newStatus);
}
