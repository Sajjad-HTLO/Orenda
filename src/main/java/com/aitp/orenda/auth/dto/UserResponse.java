package com.aitp.orenda.auth.dto;

import com.aitp.orenda.auth.AuthProvider;
import com.aitp.orenda.auth.UserEntity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        boolean emailVerified,
        AuthProvider authProvider,
        Instant createdAt,
        String avatarUrl,
        String homeCity,
        List<String> dietaryRestrictions
) {

    public static UserResponse of(UserEntity user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.isEmailVerified(),
                user.getAuthProvider(),
                user.getCreatedAt(),
                user.getAvatarUrl(),
                user.getHomeCity(),
                user.getDietaryRestrictions() == null ? null : Arrays.asList(user.getDietaryRestrictions()));
    }
}