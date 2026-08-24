package com.aitp.orenda.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256-signing-123456";

    private final JwtService jwtService = new JwtService(SECRET, 60_000);

    @Test
    void generateAndValidate_roundTrips() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "user@example.com");

        assertThat(token).isNotBlank();
        assertThat(jwtService.validateToken(token)).isEqualTo(userId.toString());
    }

    @Test
    void validateToken_returnsNull_forGarbage() {
        assertThat(jwtService.validateToken("not-a-jwt")).isNull();
    }

    @Test
    void validateToken_returnsNull_forNullAndBlank() {
        assertThat(jwtService.validateToken(null)).isNull();
        assertThat(jwtService.validateToken("  ")).isNull();
    }

    @Test
    void validateToken_rejectsTokenSignedWithDifferentSecret() {
        JwtService other = new JwtService(
                "another-secret-that-is-long-enough-for-hs256-signing-abcdef", 60_000);
        String token = other.generateToken(UUID.randomUUID(), "user@example.com");

        assertThat(jwtService.validateToken(token)).isNull();
    }
}