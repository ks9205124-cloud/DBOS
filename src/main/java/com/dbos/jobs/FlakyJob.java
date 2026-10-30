package com.dbos.jobs;

import com.dbos.core.Job;
import com.dbos.core.JobExecutionException;

// a job that fails on its first two calls and succeeds after that, to show retries
public class FlakyJob implements Job {

    private int calls = 0;   // each FlakyJob object counts its own calls

    @Override
    public void execute() throws JobExecutionException {
        calls++;
        if (calls <= 2) {
            throw new JobExecutionException("source unavailable (call " + calls + ")");
        }
    }
}
