-- Allow Tripadvisor-sourced hotel POIs (osm_type = 'T') alongside existing sources.
ALTER TABLE poi DROP CONSTRAINT IF EXISTS poi_osm_type_check;
ALTER TABLE poi ADD CONSTRAINT poi_osm_type_check CHECK (osm_type IN ('N', 'W', 'R', 'Q', 'G', 'P', 'T'));

CREATE TABLE IF NOT EXISTS tripadvisor_crawled_pages (
    id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    page_offset   INTEGER     NOT NULL UNIQUE,
    url           TEXT        NOT NULL,
    status        VARCHAR(30) NOT NULL,
    hotel_count   INTEGER     NOT NULL DEFAULT 0,
    attempt_count INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT,
    crawled_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS tripadvisor_crawled_pages_status_idx ON tripadvisor_crawled_pages(status);
