package com.aitp.orenda.auth.dto;

public record VerifyEmailResponse(
        boolean verified,
        UserResponse user
) {
}