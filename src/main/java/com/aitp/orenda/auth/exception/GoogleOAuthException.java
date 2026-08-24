package com.aitp.orenda.auth.exception;

public class GoogleOAuthException extends RuntimeException {
    public GoogleOAuthException(String message) {
        super(message);
    }

    public GoogleOAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}