-- Application users (email+password signup and/or Google OAuth).
CREATE TABLE IF NOT EXISTS app_user
(
    id                            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email                         VARCHAR(320) NOT NULL UNIQUE,
    password_hash                 VARCHAR(100),
    full_name                     VARCHAR(200),
    email_verified                BOOLEAN      NOT NULL DEFAULT FALSE,
    auth_provider                 VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',
    google_sub                    VARCHAR(100),
    verification_token            VARCHAR(100),
    verification_token_expires_at TIMESTAMPTZ,
    last_login_at                 TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT app_user_email_lower CHECK (email = LOWER(email)),
    CONSTRAINT app_user_auth_provider_check CHECK (auth_provider IN ('LOCAL', 'GOOGLE')),
    CONSTRAINT app_user_google_sub_unique UNIQUE (google_sub)
);

-- One-time OAuth state tokens so the Google redirect can be bound to this
-- login attempt (prevents login CSRF).
CREATE TABLE IF NOT EXISTS oauth_state
(
    state      VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS app_user_verification_token_idx ON app_user (verification_token);
CREATE INDEX IF NOT EXISTS oauth_state_created_at_idx ON oauth_state (created_at);