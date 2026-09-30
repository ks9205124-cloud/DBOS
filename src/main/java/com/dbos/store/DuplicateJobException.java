package com.dbos.store;

public class DuplicateJobException extends Exception{
    public DuplicateJobException(String message) {
        super(message);
    }
}
