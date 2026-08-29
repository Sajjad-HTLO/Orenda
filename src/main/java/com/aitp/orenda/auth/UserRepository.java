package com.aitp.orenda.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbc;

    private static final String COLUMNS = """
            id, email, password_hash, full_name, email_verified, auth_provider,
            google_sub, verification_token, verification_token_expires_at,
            last_login_at, created_at, updated_at, avatar_url, home_city,
            dietary_restrictions
            """;

    private static final RowMapper<UserEntity> ROW_MAPPER = (rs, rowNum) -> UserEntity.builder()
            .id(UUID.fromString(rs.getString("id")))
            .email(rs.getString("email"))
            .passwordHash(rs.getString("password_hash"))
            .fullName(rs.getString("full_name"))
            .emailVerified(rs.getBoolean("email_verified"))
            .authProvider(AuthProvider.valueOf(rs.getString("auth_provider")))
            .googleSub(rs.getString("google_sub"))
            .verificationToken(rs.getString("verification_token"))
            .verificationTokenExpiresAt(toInstant(rs.getObject("verification_token_expires_at", OffsetDateTime.class)))
            .lastLoginAt(toInstant(rs.getObject("last_login_at", OffsetDateTime.class)))
            .createdAt(toInstant(rs.getObject("created_at", OffsetDateTime.class)))
            .updatedAt(toInstant(rs.getObject("updated_at", OffsetDateTime.class)))
            .avatarUrl(rs.getString("avatar_url"))
            .homeCity(rs.getString("home_city"))
            .dietaryRestrictions(rs.getArray("dietary_restrictions") == null ? null
                    : (String[]) rs.getArray("dietary_restrictions").getArray())
            .build();

    @Transactional
    public UserEntity insert(UserEntity user) {
        Map<String, Object> row = jdbc.queryForMap("""
                        INSERT INTO app_user (email, password_hash, full_name, email_verified,
                                              auth_provider, google_sub, verification_token,
                                              verification_token_expires_at)
                        VALUES (LOWER(?), ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id, created_at, updated_at
                        """,
                user.getEmail(),
                user.getPasswordHash(),
                user.getFullName(),
                user.isEmailVerified(),
                user.getAuthProvider().name(),
                user.getGoogleSub(),
                user.getVerificationToken(),
                toDb(user.getVerificationTokenExpiresAt()));

        user.setId(UUID.fromString(row.get("id").toString()));
        user.setCreatedAt(toInstant(row.get("created_at")));
        user.setUpdatedAt(toInstant(row.get("updated_at")));
        return user;
    }

    public Optional<UserEntity> findById(UUID id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE id = ?",
                ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<UserEntity> findByEmail(String email) {
        if (email == null) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE email = LOWER(?)",
                ROW_MAPPER, email).stream().findFirst();
    }

    public Optional<UserEntity> findByGoogleSub(String googleSub) {
        if (googleSub == null) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE google_sub = ?",
                ROW_MAPPER, googleSub).stream().findFirst();
    }

    public Optional<UserEntity> findByVerificationToken(String token) {
        if (token == null) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM app_user WHERE verification_token = ?",
                ROW_MAPPER, token).stream().findFirst();
    }

    @Transactional
    public void markEmailVerified(UUID userId) {
        jdbc.update("""
                        UPDATE app_user
                        SET email_verified = TRUE,
                            verification_token = NULL,
                            verification_token_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                userId);
    }

    @Transactional
    public void updateVerificationToken(UUID userId, String token, Instant expiresAt) {
        jdbc.update("""
                        UPDATE app_user
                        SET verification_token = ?,
                            verification_token_expires_at = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                token, toDb(expiresAt), userId);
    }

    @Transactional
    public void touchLastLogin(UUID userId) {
        jdbc.update("""
                        UPDATE app_user
                        SET last_login_at = NOW(),
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                userId);
    }

    @Transactional
    public void linkGoogleSub(UUID userId, String googleSub) {
        jdbc.update("""
                        UPDATE app_user
                        SET google_sub = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                googleSub, userId);
    }

    @Transactional
    public void updateProfile(UUID userId, String fullName, String avatarUrl,
                              String homeCity, String[] dietaryRestrictions) {
        jdbc.execute("""
                        UPDATE app_user
                        SET full_name = ?,
                            avatar_url = ?,
                            home_city = ?,
                            dietary_restrictions = ?,
                            updated_at = NOW()
                        WHERE id = ?
                        """, (org.springframework.jdbc.core.PreparedStatementCallback<Integer>) ps -> {
            ps.setString(1, fullName);
            ps.setString(2, avatarUrl);
            ps.setString(3, homeCity);
            if (dietaryRestrictions == null) {
                ps.setNull(4, java.sql.Types.ARRAY);
            } else {
                ps.setArray(4, ps.getConnection().createArrayOf("text", dietaryRestrictions));
            }
            ps.setObject(5, userId);
            return ps.executeUpdate();
        });
    }

    // ── OAuth state (login CSRF protection) ─────────────────────────────────

    @Transactional
    public void saveOAuthState(String state) {
        jdbc.update("""
                        INSERT INTO oauth_state (state)
                        VALUES (?)
                        ON CONFLICT (state) DO NOTHING
                        """,
                state);
    }

    /**
     * Atomically consumes a state token: returns true only if it existed.
     */
    @Transactional
    public boolean consumeOAuthState(String state) {
        int deleted = jdbc.update("DELETE FROM oauth_state WHERE state = ?", state);
        return deleted > 0;
    }

    @Transactional
    public void purgeExpiredOAuthStates() {
        jdbc.update("DELETE FROM oauth_state WHERE created_at < NOW() - INTERVAL '10 minutes'");
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        return null;
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}