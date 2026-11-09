package com.vgu.client.model.storage;

/** Wrong password, corrupt file, or I/O failure while using the keystore. */
public class KeyStoreException extends RuntimeException {
    public KeyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
