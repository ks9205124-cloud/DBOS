package com.dbos.core;

import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.Trigger;

/*
 * Why we use a Builder here:
 *
 * 1. Optional fields without constructor bloat: Only 'name' is strictly required.
 *    Instead of forcing callers to pass nulls for missing fields like job,
 *    trigger, or retryPolicy, they only set what's actually available.
 *
 * 2. Self-documenting call sites:
 *    Compare:
 *        new JobDefinition("db-log-archive", archiverJob, fixedTrigger, backoffPolicy);
 *    With:
 *        JobDefinition.builder("db-log-archive")
 *            .job(archiverJob)
 *            .trigger(fixedTrigger)
 *            .retryPolicy(backoffPolicy)
 *            .build();
 *
 *    Explicit method names make it immediately clear what each argument does,
 *    and stop you from accidentally swapping parameters in the wrong order.
 */

public class JobDefinition {
    private final long id;
    private final String name;
    private final Job job;
    private final RetryPolicy retryPolicy;
    private final Trigger trigger;
    private JobStatus jobStatus;

    private JobDefinition(JobDefinitionBuilder jobDefinitionBuilder) {
        this.id = jobDefinitionBuilder.id;
        this.name = jobDefinitionBuilder.name;
        this.job = jobDefinitionBuilder.job;
        this.retryPolicy = jobDefinitionBuilder.retryPolicy;
        this.trigger = jobDefinitionBuilder.trigger;
        this.jobStatus = jobDefinitionBuilder.jobStatus;
    }
    public long getId() {
        return id;
    }
    public String getName() {
        return name;
    }
    public Job getJob() {
        return job;
    }
    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }
    public JobStatus getJobStatus() {
        return jobStatus;
    }
    public Trigger getTrigger() {
        return trigger;
    }
    public void setJobStatus(JobStatus newJobStatus) {
        if (!this.jobStatus.canTransitionTo(newJobStatus)) {
            throw new IllegalStateException("Job status can't transition to " + newJobStatus);
        }
        this.jobStatus = newJobStatus;
    }

    public static JobDefinitionBuilder builder(String name) {
        return new JobDefinitionBuilder(name);
    }

    public static class JobDefinitionBuilder {
        private final String name;
        private long id;
        private Job job;
        private RetryPolicy retryPolicy;
        private final JobStatus jobStatus = JobStatus.PENDING;
        private Trigger trigger;

        // required field during obj creation
        public JobDefinitionBuilder(String name) {
            this.name = name;
        }

        // optional field during object creation
        public JobDefinitionBuilder job(Job job) {
            this.job = job;
            return this;
        }
        public JobDefinitionBuilder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }
        public JobDefinitionBuilder trigger(Trigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public JobDefinition build() {
            if (this.name == null) {
                throw new IllegalStateException("Job definition name is required");
            }
            return new JobDefinition(this);
        }
    }

}
