-- Store POI images (from Tripadvisor and other sources) as binary blobs.
-- Kept in a dedicated table (not on poi) so a POI can hold many images and the
-- main poi table stays lean. poi_id is denormalized here (not a hard FK) so we
-- can also store images keyed by (osm_id, osm_type) for sources that upsert
-- rows before the image is known; the FK to poi keeps referential integrity
-- for rows that already exist.
CREATE TABLE IF NOT EXISTS poi_image (
    id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    poi_id        UUID        REFERENCES poi(id) ON DELETE CASCADE,
    osm_id        BIGINT      NOT NULL,
    osm_type      CHAR(1)     NOT NULL,
    source        VARCHAR(50) NOT NULL DEFAULT 'tripadvisor',
    source_url    TEXT        NOT NULL,
    mime_type     VARCHAR(100),
    width         INTEGER,
    height        INTEGER,
    image_data    BYTEA       NOT NULL,
    file_size     INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS poi_image_source_url_idx
    ON poi_image (osm_id, osm_type, source, source_url);

CREATE INDEX IF NOT EXISTS poi_image_poi_id_idx ON poi_image (poi_id);
CREATE INDEX IF NOT EXISTS poi_image_osm_idx    ON poi_image (osm_id, osm_type);
