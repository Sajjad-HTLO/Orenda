-- Crawled Tripadvisor traveler reviews for POIs, separate from the
-- user-submitted reviews in poi_review. Each review is linked back to the POI
-- and stores the reviewer, overall rating, title, comment, date and source.
CREATE TABLE IF NOT EXISTS poi_review_crawled
(
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    poi_id        UUID NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    tripadvisor_id BIGINT,
    reviewer_name VARCHAR(200),
    rating        SMALLINT CHECK (rating BETWEEN 1 AND 5),
    title         VARCHAR(300),
    comment       TEXT,
    review_date   VARCHAR(50),
    source_url    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (poi_id, reviewer_name, review_date, comment)
);

CREATE INDEX IF NOT EXISTS poi_review_crawled_poi_idx ON poi_review_crawled (poi_id, created_at DESC);
CREATE INDEX IF NOT EXISTS poi_review_crawled_tripadvisor_idx ON poi_review_crawled (tripadvisor_id);

-- Per-aspect sub-ratings for a crawled review (e.g. Cleanliness, Service,
-- Value, Atmosphere). Attraction/tour products generally expose no aspects, so
-- this is mostly populated for hotel/restaurant-style POIs.
CREATE TABLE IF NOT EXISTS poi_review_aspect
(
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    review_id UUID NOT NULL REFERENCES poi_review_crawled (id) ON DELETE CASCADE,
    aspect    VARCHAR(100) NOT NULL,
    rating    SMALLINT CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX IF NOT EXISTS poi_review_aspect_review_idx ON poi_review_aspect (review_id);
