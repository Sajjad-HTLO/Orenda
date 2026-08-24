package com.aitp.orenda.auth.exception;

public class OAuthNotConfiguredException extends RuntimeException {
    public OAuthNotConfiguredException() {
        super("Google sign-in is not configured on this server");
    }
}