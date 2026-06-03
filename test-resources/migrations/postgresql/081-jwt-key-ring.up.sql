-- JWT key ring for key rotation (Phase C, Step 1)
CREATE TABLE IF NOT EXISTS jwt_keys (
    kid        TEXT PRIMARY KEY,
    secret     TEXT NOT NULL,
    active     INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_jwt_keys_active ON jwt_keys(active);
