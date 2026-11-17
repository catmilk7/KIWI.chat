package com.vgu.client.storage;

/** Unchecked wrapper for any failure of the local chat database. */
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
