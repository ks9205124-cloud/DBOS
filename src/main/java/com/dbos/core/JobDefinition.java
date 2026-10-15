package com.dbos.core;

import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.Trigger;

/*
 * Why we use a Builder here:
 *
 * 1. Optional fields without constructor bloat: Only 'name' is strictly required
 *    by build() today. Callers set what's available instead of passing nulls.
 *    (TODO: build() should also require job and trigger.)
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
 *
 * A JobDefinition is fully immutable. The id and the active flag are the only
 * things that ever differ between saved versions of a job, so they change by
 * making a copy (withId, withActive), never by mutation.
 */

public final class JobDefinition {
    private final long id;
    private final String name;
    private final Job job;
    private final RetryPolicy retryPolicy;
    private final Trigger trigger;
    private final boolean active;

    private JobDefinition(JobDefinitionBuilder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.job = builder.job;
        this.retryPolicy = builder.retryPolicy;
        this.trigger = builder.trigger;
        this.active = builder.active;
    }

    // copy constructor: same job, with a different id and/or active flag
    private JobDefinition(JobDefinition other, long id, boolean active) {
        this.id = id;
        this.name = other.name;
        this.job = other.job;
        this.retryPolicy = other.retryPolicy;
        this.trigger = other.trigger;
        this.active = active;
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
    public Trigger getTrigger() {
        return trigger;
    }
    public boolean isActive() {
        return active;
    }

    // used by the store to assign the real id when a job is saved
    public JobDefinition withId(long newId) {
        return new JobDefinition(this, newId, this.active);
    }

    // used by the store to cancel (active = false) a job rule
    public JobDefinition withActive(boolean newActive) {
        return new JobDefinition(this, this.id, newActive);
    }

    @Override
    public String toString() {
        return "JobDefinition{id=" + id + ", name='" + name + "', active=" + active + "}";
    }

    public static JobDefinitionBuilder builder(String name) {
        return new JobDefinitionBuilder(name);
    }

    public static class JobDefinitionBuilder {
        private final String name;
        private long id;                 // stays 0 until a store assigns a real one
        private Job job;
        private RetryPolicy retryPolicy;
        private Trigger trigger;
        private boolean active = true;   // a new job rule starts active

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

        // TODO: validate job, trigger, retryPolicy
        public JobDefinition build() {
            if (this.name == null) {
                throw new IllegalStateException("Job definition name is required");
            }
            return new JobDefinition(this);
        }
    }
}