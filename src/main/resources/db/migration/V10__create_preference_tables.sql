-- Long-term traveler profile (set during short onboarding; trip requests can override it).
CREATE TABLE IF NOT EXISTS traveler_profile
(
    session_id
    VARCHAR
(
    100
) PRIMARY KEY,
    interests TEXT[],
    pace VARCHAR
(
    20
),
    budget VARCHAR
(
    20
),
    walking VARCHAR
(
    20
),
    food VARCHAR
(
    20
),
    group_type VARCHAR
(
    20
),
    age_range VARCHAR
(
    20
),
    mobility VARCHAR
(
    20
),
    traveler_count INTEGER,
    children_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW
(
),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW
(
)
    );

-- Learned per-category preference weights (0..1), updated by feedback over time.
CREATE TABLE IF NOT EXISTS user_preference
(
    session_id
    VARCHAR
(
    100
) NOT NULL,
    category VARCHAR
(
    50
) NOT NULL,
    weight DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    feedback_count INT NOT NULL DEFAULT 0,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW
(
),
    PRIMARY KEY
(
    session_id,
    category
)
    );

-- Raw preference-feedback events (reactions + optional reasons).
CREATE TABLE IF NOT EXISTS preference_feedback
(
    id
    UUID
    PRIMARY
    KEY
    DEFAULT
    uuid_generate_v4
(
),
    session_id VARCHAR
(
    100
) NOT NULL,
    poi_id UUID NOT NULL REFERENCES poi
(
    id
) ON DELETE CASCADE,
    reaction VARCHAR
(
    20
) NOT NULL
    CHECK
(
    reaction
    IN
(
    'LIKE',
    'DISLIKE',
    'LOVE',
    'NOT_INTERESTED',
    'RATED'
)),
    rating SMALLINT
    CHECK
(
    rating
    BETWEEN
    1
    AND
    5
),
    reason VARCHAR
(
    40
),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW
(
)
    );

CREATE INDEX IF NOT EXISTS preference_feedback_session_idx ON preference_feedback (session_id);
CREATE INDEX IF NOT EXISTS user_preference_session_idx ON user_preference (session_id);