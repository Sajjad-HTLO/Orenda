package com.aitp.orenda.auth.exception;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email address has not been verified yet");
    }
}