DROP INDEX IF EXISTS idx_api_tokens_fast_hash;
-- SQLite doesn't support DROP COLUMN before 3.35.0; leave column in place.
