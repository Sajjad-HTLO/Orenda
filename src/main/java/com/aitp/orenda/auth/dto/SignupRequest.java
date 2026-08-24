package com.aitp.orenda.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 6, max = 72, message = "password must be between 6 and 72 characters")
        String password,

        @Size(max = 200, message = "fullName must be at most 200 characters")
        String fullName
) {
}