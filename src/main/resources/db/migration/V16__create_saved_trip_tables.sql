-- Saved trips: a persisted TripPlanResponse snapshot owned by a user.
CREATE TABLE IF NOT EXISTS saved_trip
(
    id                 UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id            UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name               VARCHAR(200),
    destination        VARCHAR(200),
    start_date         DATE         NOT NULL,
    end_date           DATE         NOT NULL,
    trip_days          INT          NOT NULL DEFAULT 1,
    summary            TEXT,
    weather_summary    TEXT,
    narrative          TEXT,
    preference_insight TEXT,
    notes              JSONB,
    archived           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS saved_trip_user_idx ON saved_trip (user_id, archived);
CREATE INDEX IF NOT EXISTS saved_trip_user_updated_idx ON saved_trip (user_id, updated_at DESC);

-- One row per itinerary day of a saved trip.
CREATE TABLE IF NOT EXISTS saved_trip_day
(
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trip_id   UUID NOT NULL REFERENCES saved_trip (id) ON DELETE CASCADE,
    day       INT  NOT NULL,
    date      DATE,
    weather   TEXT,
    narrative TEXT,
    notes     JSONB
);

CREATE INDEX IF NOT EXISTS saved_trip_day_trip_idx ON saved_trip_day (trip_id);

-- One row per scheduled stop. POI data is snapshotted so a deleted or later
-- changed POI does not break a saved itinerary.
CREATE TABLE IF NOT EXISTS saved_trip_stop
(
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    day_id                  UUID NOT NULL REFERENCES saved_trip_day (id) ON DELETE CASCADE,
    poi_id                  UUID REFERENCES poi (id) ON DELETE SET NULL,
    name_tr                 TEXT,
    name_en                 TEXT,
    category                TEXT,
    subcategory             TEXT,
    lat                     DOUBLE PRECISION,
    lon                     DOUBLE PRECISION,
    score                   DOUBLE PRECISION,
    travel_minutes          INT,
    visit_minutes           INT,
    start_time              VARCHAR(5),
    end_time                VARCHAR(5),
    open_at_scheduled_time  BOOLEAN,
    reasons                 JSONB,
    factors                 JSONB,
    sort_order              INT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS saved_trip_stop_day_idx ON saved_trip_stop (day_id);