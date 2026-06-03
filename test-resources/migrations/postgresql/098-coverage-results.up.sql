-- See sqlite/098-coverage-results.up.sql for full rationale.
-- Postgres mirror: same schema, same indexes; REAL is mapped to DOUBLE PRECISION
-- to match the rest of the postgresql schema's float-column convention.
--
-- created_at storage (Codex PR #60 r7 P1): the column is TEXT (to mirror
-- SQLite, which has no TIMESTAMPTZ) but the store layer compares it
-- LEXICALLY for trend ordering and retention cutoffs. A bare
-- CURRENT_TIMESTAMP default would render through the session's DateStyle /
-- TimeZone settings, so a non-UTC connection could produce a string that
-- sorts inconsistently with another connection (or with the Java-side
-- cutoff in cleanup-old-coverage!, which is always UTC). We force the
-- format here: UTC wall-clock + millisecond precision, "YYYY-MM-DD
-- HH24:MI:SS.MS", which compares lex-equivalent to the SQLite
-- strftime('%Y-%m-%d %H:%M:%f','now') default and to the Java
-- db-timestamp-formatter ("yyyy-MM-dd HH:mm:ss.SSS"). Width and field
-- order match across all three producers; differences in fractional
-- digits beyond ms (Postgres can't emit > ms via MS code) don't arise.
CREATE TABLE IF NOT EXISTS coverage_results (
  id               TEXT PRIMARY KEY,
  build_id         TEXT NOT NULL,
  job_id           TEXT,
  org_id           TEXT DEFAULT 'default-org',
  format           TEXT NOT NULL,
  lines_total      INTEGER NOT NULL,
  lines_covered    INTEGER NOT NULL,
  branches_total   INTEGER,
  branches_covered INTEGER,
  coverage_pct     DOUBLE PRECISION,
  created_at       TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS'),
  UNIQUE(build_id, format)
);

--;;

CREATE INDEX IF NOT EXISTS idx_coverage_results_build_id
  ON coverage_results(build_id);

--;;

CREATE INDEX IF NOT EXISTS idx_coverage_results_job_org
  ON coverage_results(job_id, org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_coverage_results_org_created
  ON coverage_results(org_id, created_at DESC);

--;;

CREATE TABLE IF NOT EXISTS coverage_files (
  id               TEXT PRIMARY KEY,
  coverage_id      TEXT NOT NULL,
  file_path        TEXT NOT NULL,
  lines_total      INTEGER NOT NULL,
  lines_covered    INTEGER NOT NULL,
  branches_total   INTEGER,
  branches_covered INTEGER,
  -- Same UTC + ms text default as coverage_results (see header comment).
  created_at       TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS')
);

--;;

CREATE INDEX IF NOT EXISTS idx_coverage_files_coverage_id
  ON coverage_files(coverage_id);
