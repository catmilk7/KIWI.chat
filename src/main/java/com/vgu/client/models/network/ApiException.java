package com.vgu.client.models.network;

/**
 * A failed call to the KIWI server. {@code status} is the HTTP status, or 0 when
 * the server could not be reached at all (see {@link #isNetworkError()}).
 * The message is the server's own reason for HTTP errors.
 */
public class ApiException extends Exception {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }

    public boolean isNetworkError() {
        return status == 0;
    }
}
