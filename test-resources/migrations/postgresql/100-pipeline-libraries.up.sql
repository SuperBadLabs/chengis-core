-- See sqlite/100-pipeline-libraries.up.sql for full rationale.
-- Postgres mirror: same shape and indexes; created_at / published_at are
-- TEXT (mirroring SQLite, which lacks TIMESTAMPTZ) and forced to a UTC
-- wall-clock + millisecond default to compare lex-equivalent to the
-- SQLite strftime('%Y-%m-%d %H:%M:%f','now') format. Same hazard as
-- migration 098: a bare CURRENT_TIMESTAMP would render through the
-- session's DateStyle / TimeZone, producing strings that sort
-- inconsistently across connections.
CREATE TABLE IF NOT EXISTS pipeline_libraries (
  id              TEXT PRIMARY KEY,
  org_id          TEXT NOT NULL DEFAULT 'default-org',
  name            TEXT NOT NULL,
  description     TEXT,
  source_url      TEXT NOT NULL,
  default_version TEXT,
  created_at      TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS'),
  UNIQUE(org_id, name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_pipeline_libraries_org_name
  ON pipeline_libraries(org_id, name);

--;;

CREATE TABLE IF NOT EXISTS pipeline_library_versions (
  id           TEXT PRIMARY KEY,
  library_id   TEXT NOT NULL,
  version      TEXT NOT NULL,
  git_ref      TEXT NOT NULL,
  sha          TEXT NOT NULL,
  published_at TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS'),
  UNIQUE(library_id, version)
);

--;;

CREATE INDEX IF NOT EXISTS idx_pipeline_library_versions_library_id
  ON pipeline_library_versions(library_id);

--;;

-- Index for the @sha: resolution path (Codex PR #85 r4 P2). See
-- sqlite/100-pipeline-libraries.up.sql for rationale.
CREATE INDEX IF NOT EXISTS idx_pipeline_library_versions_library_sha
  ON pipeline_library_versions(library_id, sha);
