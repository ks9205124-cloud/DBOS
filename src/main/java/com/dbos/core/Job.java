package com.dbos.core;

import com.dbos.store.JobExecutionException;

public interface Job {
    void execute() throws JobExecutionException;
}
