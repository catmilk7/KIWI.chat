package com.vgu.client.models.storage;

/** Unchecked wrapper for any failure of the local chat database. */
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
