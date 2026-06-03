-- See sqlite/096 for rationale.
CREATE TABLE IF NOT EXISTS system_metadata (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
