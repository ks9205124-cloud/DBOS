package com.dbos.core;

import com.dbos.retry.ExponentialBackoffPolicy;
import com.dbos.retry.RetryPolicy;
import com.dbos.trigger.FixedRateTrigger;
import com.dbos.trigger.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDefinitionTest {

    private final Job job = () -> { };
    private final Trigger trigger = new FixedRateTrigger(Duration.ofSeconds(10));
    private final RetryPolicy retryPolicy = new ExponentialBackoffPolicy(3, Duration.ofSeconds(2));

    private JobDefinition.JobDefinitionBuilder complete(String name) {
        return JobDefinition.builder(name).job(job).trigger(trigger).retryPolicy(retryPolicy);
    }

    @Test
    void buildsWithAllFieldsAndSensibleDefaults() {
        JobDefinition definition = complete("report").build();

        assertEquals("report", definition.getName());
        assertEquals(0, definition.getId());           // placeholder until a store assigns one
        assertTrue(definition.isActive());             // a new rule starts active
        assertSame(job, definition.getJob());
        assertSame(trigger, definition.getTrigger());
        assertSame(retryPolicy, definition.getRetryPolicy());
    }

    @Test
    void nameIsRequired() {
        assertThrows(IllegalStateException.class,
                () -> JobDefinition.builder(null).job(job).trigger(trigger).build());
        assertThrows(IllegalStateException.class,
                () -> JobDefinition.builder("").job(job).trigger(trigger).build());
        assertThrows(IllegalStateException.class,
                () -> JobDefinition.builder("   ").job(job).trigger(trigger).build());
    }

    @Test
    void nameIsTrimmed() {
        assertEquals("report", complete("  report  ").build().getName());
    }

    @Test
    void jobIsRequired() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JobDefinition.builder("no-job").trigger(trigger).build());

        assertTrue(e.getMessage().contains("no-job"));
    }

    @Test
    void triggerIsRequired() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JobDefinition.builder("no-trigger").job(job).build());

        assertTrue(e.getMessage().contains("no-trigger"));
    }

    @Test
    void missingRetryPolicyMeansNeverRetry() {
        JobDefinition definition = JobDefinition.builder("no-retry").job(job).trigger(trigger).build();

        assertNotNull(definition.getRetryPolicy());
        assertFalse(definition.getRetryPolicy().canRetry(1));
    }

    @Test
    void withIdReturnsACopyAndLeavesTheOriginalUntouched() {
        JobDefinition original = complete("copy").build();

        JobDefinition copy = original.withId(7);

        assertEquals(7, copy.getId());
        assertEquals(0, original.getId());
        assertEquals("copy", copy.getName());
        assertTrue(copy.isActive());
        assertSame(original.getJob(), copy.getJob());
        assertSame(original.getTrigger(), copy.getTrigger());
        assertSame(original.getRetryPolicy(), copy.getRetryPolicy());
    }

    @Test
    void withActiveReturnsACopyAndKeepsTheId() {
        JobDefinition saved = complete("cancel").build().withId(5);

        JobDefinition cancelled = saved.withActive(false);

        assertFalse(cancelled.isActive());
        assertEquals(5, cancelled.getId());
        assertTrue(saved.isActive());                  // the original is not changed
    }
}