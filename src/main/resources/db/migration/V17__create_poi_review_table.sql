-- Traveler reviews/ratings for POIs. Reviews are tied to an authenticated
-- user when submitted; the reviewer's display name is snapshotted so the
-- review survives account deletion (user_id -> SET NULL).
CREATE TABLE IF NOT EXISTS poi_review
(
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poi_id        UUID NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    user_id       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    traveler_name VARCHAR(200),
    rating        SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title         VARCHAR(200),
    comment       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS poi_review_poi_idx ON poi_review (poi_id, created_at DESC);
CREATE INDEX IF NOT EXISTS poi_review_user_idx ON poi_review (user_id);