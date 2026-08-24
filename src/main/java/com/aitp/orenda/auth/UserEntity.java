package com.aitp.orenda.auth;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class UserEntity {

    private UUID id;
    private String email;
    private String passwordHash;
    private String fullName;
    private boolean emailVerified;
    private AuthProvider authProvider;
    private String googleSub;
    private String verificationToken;
    private Instant verificationTokenExpiresAt;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
}