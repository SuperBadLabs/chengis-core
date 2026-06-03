-- Content-addressable artifact object store for S3-compatible backends.
-- Tracks each unique artifact by SHA-256 hash, its S3 storage key,
-- per-org ownership, compressed size, and a reference count for deduplication.

CREATE TABLE IF NOT EXISTS artifact_objects (
  id                     TEXT    PRIMARY KEY,
  sha256_hash            TEXT    NOT NULL UNIQUE,
  storage_key            TEXT    NOT NULL,
  org_id                 TEXT,
  content_type           TEXT    NOT NULL DEFAULT 'application/octet-stream',
  original_size_bytes    INTEGER NOT NULL,
  compressed_size_bytes  INTEGER NOT NULL,
  ref_count              INTEGER NOT NULL DEFAULT 1,
  created_at             TEXT    DEFAULT (datetime('now'))
);
--;;
CREATE INDEX IF NOT EXISTS idx_artifact_objects_sha256
  ON artifact_objects(sha256_hash);
--;;
CREATE INDEX IF NOT EXISTS idx_artifact_objects_org
  ON artifact_objects(org_id);
--;;

-- Per-org storage usage is derived from artifact_objects but we also add a
-- max_artifact_storage_bytes quota column to the org_quotas table if it
-- does not already exist.  The column is ignored if org_quotas does not
-- exist in this deployment (e.g. fresh install without orgs).
-- We use a conditional approach compatible with SQLite's limited ALTER TABLE.
-- The INSERT OR IGNORE below is a no-op if org_quotas does not exist.
CREATE TABLE IF NOT EXISTS org_artifact_storage_config (
  org_id                      TEXT PRIMARY KEY,
  max_artifact_storage_bytes  INTEGER NOT NULL DEFAULT 10737418240
);
