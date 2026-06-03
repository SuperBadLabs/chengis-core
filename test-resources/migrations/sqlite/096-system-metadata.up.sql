-- Generic key/value table for one-time post-migration markers and other
-- runtime metadata that doesn't deserve its own bespoke table. First
-- consumer: jobs.repo_url backfill (PR #25), which needs to skip
-- re-scanning non-git rows (whose repo_url is correctly NULL forever)
-- on every subsequent boot.
CREATE TABLE IF NOT EXISTS system_metadata (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
