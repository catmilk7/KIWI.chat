package com.vgu.client.controllers.auth;

/** A failed login or registration. The message is safe to show to the user. */
public class AuthException extends Exception {
    public AuthException(String message) {
        super(message);
    }
}
