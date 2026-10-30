package com.dbos.core;

import com.dbos.retry.FixedDelayPolicy;
import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.Trigger;

import java.time.Duration;

/*
    all the properties of a job encapsulated under a class

    we make use of builder pattern here
*/

public final class JobDefinition {

    // used when a job is built without a retry policy: zero retries, so the first failure is final
    private static final RetryPolicy NO_RETRY = new FixedDelayPolicy(0, Duration.ZERO);

    private final int id;
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
    private JobDefinition(JobDefinition other, int id, boolean active) {
        this.id = id;
        this.name = other.name;
        this.job = other.job;
        this.retryPolicy = other.retryPolicy;
        this.trigger = other.trigger;
        this.active = active;
    }

    public int getId() {
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
    public JobDefinition withId(int newId) {
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
        private int id;                 // stays 0 until a store assigns a real one
        private Job job;
        private RetryPolicy retryPolicy;
        private Trigger trigger;
        private boolean active = true;   // a new job rule starts active

        // the name is trimmed, so " report" and "report" cannot become two different jobs
        public JobDefinitionBuilder(String name) {
            this.name = (name == null) ? null : name.trim();
        }

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
            if (name == null || name.isEmpty()) {
                throw new IllegalStateException("Job definition name is required");
            }
            if (job == null) {
                throw new IllegalStateException("Job '" + name + "' needs a Job to run");
            }
            if (trigger == null) {
                throw new IllegalStateException("Job '" + name + "' needs a Trigger");
            }
            if (retryPolicy == null) {
                retryPolicy = NO_RETRY;
            }
            return new JobDefinition(this);
        }
    }
}
