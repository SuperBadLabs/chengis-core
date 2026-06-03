-- Pipeline shared libraries: registry + versioning (CHG-FEAT-005 PR1).
--
-- Two tables so a library's metadata (URL, description, default-version
-- pointer) stays small and indexable while the version history grows
-- monotonically in a separate table that's only joined when a pipeline
-- :import resolver actually fires.
--
-- pipeline_libraries:
--   One row per (org_id, name) pair — UNIQUE constraint enforces it.
--   `name` is the human-readable identifier used in Chengisfile :import
--   strings (e.g. "org/shared"), `source_url` is the git URL the resolver
--   clones from. `default_version` is an OPTIONAL pointer to a version
--   string in pipeline_library_versions; the resolver falls back to it
--   when the import omits an @version suffix.
--
-- pipeline_library_versions:
--   N rows per library, each pinning a `version` label (a tag like
--   "v1.2.0" or a free-form name) to a concrete `git_ref` + `sha`. The
--   `version` column is what callers ask for in :import strings; `sha`
--   is the immutable target the resolver will eventually check out.
--   Multiple version labels may point at the same sha (e.g. "v1" and
--   "v1.2.0" can both resolve to the same commit on a moving tag).
--
-- No FK constraint between the tables — matches the existing chengis
-- schema convention (see migration 098 / test_result_store).
--
-- created_at / published_at: millisecond-precision text default, same
-- shape as migration 098, so trend-style ORDER BY ... DESC stays stable
-- under tight insert loops.
CREATE TABLE IF NOT EXISTS pipeline_libraries (
  id              TEXT PRIMARY KEY,
  org_id          TEXT NOT NULL DEFAULT 'default-org',
  name            TEXT NOT NULL,
  description     TEXT,
  source_url      TEXT NOT NULL,
  default_version TEXT,
  created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
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
  published_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  UNIQUE(library_id, version)
);

--;;

CREATE INDEX IF NOT EXISTS idx_pipeline_library_versions_library_id
  ON pipeline_library_versions(library_id);

--;;

-- Index for the @sha: resolution path in
-- chengis.db.library-store/resolve-version (Codex PR #85 r4 P2). Without
-- it, every @sha lookup degrades to a full scan of all versions for the
-- library as histories grow; the composite (library_id, sha) lets the
-- planner hit both equality predicates in resolve-version's WHERE clause
-- directly.
CREATE INDEX IF NOT EXISTS idx_pipeline_library_versions_library_sha
  ON pipeline_library_versions(library_id, sha);
