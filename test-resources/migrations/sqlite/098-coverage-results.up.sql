-- Coverage results: per-build code-coverage reports parsed from Cobertura /
-- JaCoCo / LCOV outputs uploaded by build steps. Two tables so the per-file
-- breakdown (needed for "where did coverage regress" views) doesn't bloat the
-- summary table that backs the trend chart.
--
-- One coverage_results row per (build_id, format) — a build that uploads both
-- a Cobertura report and an LCOV report gets two rows so neither format is
-- privileged. The UNIQUE(build_id, format) constraint prevents accidental
-- double-upload from inflating the trend.
--
-- coverage_files rows are 1:N from coverage_results via coverage_id (TEXT
-- FK, no FK constraint declared because the existing tables in this schema
-- already use that convention — see test_results / flaky_tests).
CREATE TABLE IF NOT EXISTS coverage_results (
  id               TEXT PRIMARY KEY,
  build_id         TEXT NOT NULL,
  job_id           TEXT,
  org_id           TEXT DEFAULT 'default-org',
  format           TEXT NOT NULL,        -- 'cobertura' | 'jacoco' | 'lcov'
  lines_total      INTEGER NOT NULL,
  lines_covered    INTEGER NOT NULL,
  branches_total   INTEGER,              -- NULL when format doesn't report branches
  branches_covered INTEGER,
  coverage_pct     REAL,                 -- cached: lines_covered/lines_total*100
  -- Millisecond-resolution timestamp (Codex PR #60 r5 P1). Bare
  -- CURRENT_TIMESTAMP is second-only in SQLite, so rapid uploads from one
  -- job within the same second would tie on ORDER BY created_at and any
  -- LIMIT-N window could exclude newer rows. strftime('%f', 'now') returns
  -- fractional seconds; the resulting "YYYY-MM-DD HH:MM:SS.fff" string
  -- sorts both lexically and chronologically (matches Postgres's TEXT
  -- coercion of CURRENT_TIMESTAMP).
  created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
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
  coverage_id      TEXT NOT NULL,         -- joins to coverage_results.id
  file_path        TEXT NOT NULL,
  lines_total      INTEGER NOT NULL,
  lines_covered    INTEGER NOT NULL,
  branches_total   INTEGER,
  branches_covered INTEGER,
  -- Same millisecond default as coverage_results; not currently used for
  -- ordering but kept consistent so future per-file trend views don't hit
  -- the same second-resolution tie hazard.
  created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);

--;;

CREATE INDEX IF NOT EXISTS idx_coverage_files_coverage_id
  ON coverage_files(coverage_id);
