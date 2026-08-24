package com.aitp.orenda.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email
) {
}