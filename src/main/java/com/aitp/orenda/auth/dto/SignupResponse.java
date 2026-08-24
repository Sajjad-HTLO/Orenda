package com.aitp.orenda.auth.dto;

public record SignupResponse(
        UserResponse user,
        boolean emailSent,
        String message
) {
    public static SignupResponse of(UserResponse user, boolean emailSent) {
        return new SignupResponse(user, emailSent,
                "Account created. A verification email has been sent to your inbox.");
    }
}