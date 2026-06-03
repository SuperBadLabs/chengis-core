CREATE TABLE IF NOT EXISTS nonce_cache (
    nonce    TEXT PRIMARY KEY,
    seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;
CREATE INDEX IF NOT EXISTS idx_nonce_cache_seen_at ON nonce_cache(seen_at);
