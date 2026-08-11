CREATE TABLE poi_feedback
(
    id            UUID PRIMARY KEY     DEFAULT uuid_generate_v4(),
    poi_id        UUID        NOT NULL REFERENCES poi (id) ON DELETE CASCADE,
    feedback_type VARCHAR(20) NOT NULL
        CHECK (feedback_type IN ('CLOSED', 'INACCURATE', 'MOVED', 'DUPLICATE', 'OTHER')),
    details       TEXT,
    session_id    VARCHAR(100),
    reported_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX poi_feedback_poi_id_idx ON poi_feedback (poi_id);
CREATE INDEX poi_feedback_type_idx ON poi_feedback (feedback_type);
