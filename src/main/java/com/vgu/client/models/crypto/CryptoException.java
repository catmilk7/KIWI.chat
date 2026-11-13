package com.vgu.client.models.crypto;

/** Unchecked wrapper for any cryptographic failure (bad key, bad tag, bad padding). */
public class CryptoException extends RuntimeException {
    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }

    public CryptoException(String message) {
        super(message);
    }
}
