-- Extended user personal profile: avatar, home city, dietary restrictions.
ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS home_city  VARCHAR(200),
    ADD COLUMN IF NOT EXISTS dietary_restrictions TEXT[];