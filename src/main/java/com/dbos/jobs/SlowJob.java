package com.dbos.jobs;

import com.dbos.core.Job;
import com.dbos.core.JobExecutionException;

// a job that takes 3 seconds, so several of them running together show the worker threads working at once
public class SlowJob implements Job {

    @Override
    public void execute() throws JobExecutionException {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobExecutionException("interrupted", e);
        }
    }
}
