package com.aitp.orenda.auth.exception;

public class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException() {
        super("Verification link is invalid or has expired");
    }
}