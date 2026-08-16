-- Constraints learned from feedback reasons ("too expensive", "too far",
-- "too crowded", "not suitable for kids", "prefer quieter"). These shape the
-- next plan, not just the preference weights.
CREATE TABLE IF NOT EXISTS preference_constraint
(
    session_id
    VARCHAR
(
    100
) NOT NULL,
    constraint_key VARCHAR
(
    40
) NOT NULL,
    value VARCHAR
(
    100
) NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW
(
),
    PRIMARY KEY
(
    session_id,
    constraint_key
)
    );

CREATE INDEX IF NOT EXISTS preference_constraint_session_idx ON preference_constraint (session_id);