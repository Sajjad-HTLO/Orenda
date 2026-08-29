package com.aitp.orenda.auth.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Updates to the authenticated user's personal profile. All fields optional;
 * only non-null values are persisted (null = leave unchanged).
 */
public record UpdateProfileRequest(
        @Size(max = 200, message = "fullName must be at most 200 characters")
        String fullName,

        @Size(max = 500, message = "avatarUrl must be at most 500 characters")
        String avatarUrl,

        @Size(max = 200, message = "homeCity must be at most 200 characters")
        String homeCity,

        List<@Size(max = 50, message = "dietary restriction must be at most 50 characters") String> dietaryRestrictions
) {
}