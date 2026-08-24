package com.aitp.orenda.auth.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String email) {
        super("No account found for email '" + email + "'");
    }
}