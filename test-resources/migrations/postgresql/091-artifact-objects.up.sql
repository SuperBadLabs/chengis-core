-- Content-addressable artifact object store for S3-compatible backends.
-- Tracks each unique artifact by SHA-256 hash, its S3 storage key,
-- per-org ownership, compressed size, and a reference count for deduplication.

CREATE TABLE IF NOT EXISTS artifact_objects (
  id                     TEXT         NOT NULL PRIMARY KEY,
  sha256_hash            TEXT         NOT NULL,
  storage_key            TEXT         NOT NULL,
  org_id                 TEXT,
  content_type           TEXT         NOT NULL DEFAULT 'application/octet-stream',
  original_size_bytes    BIGINT       NOT NULL,
  compressed_size_bytes  BIGINT       NOT NULL,
  ref_count              INTEGER      NOT NULL DEFAULT 1,
  created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT artifact_objects_sha256_unique UNIQUE (sha256_hash)
);
--;;
CREATE INDEX IF NOT EXISTS idx_artifact_objects_sha256
  ON artifact_objects(sha256_hash);
--;;
CREATE INDEX IF NOT EXISTS idx_artifact_objects_org
  ON artifact_objects(org_id);
--;;

CREATE TABLE IF NOT EXISTS org_artifact_storage_config (
  org_id                      TEXT    NOT NULL PRIMARY KEY,
  max_artifact_storage_bytes  BIGINT  NOT NULL DEFAULT 10737418240
);
