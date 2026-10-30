package com.dbos.jobs;

import com.dbos.core.Job;

// a job that does nothing and always succeeds (like a housekeeping task)
public class CleanupJob implements Job {

    @Override
    public void execute() {
    }
}
