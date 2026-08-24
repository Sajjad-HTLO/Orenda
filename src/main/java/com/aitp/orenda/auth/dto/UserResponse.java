package com.aitp.orenda.auth.dto;

import com.aitp.orenda.auth.AuthProvider;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        boolean emailVerified,
        AuthProvider authProvider,
        Instant createdAt
) {
}