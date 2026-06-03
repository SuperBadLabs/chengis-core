-- Per-branch build numbering (CHG-FEAT-003 PR3): give each
-- (job_id, branch_name) pair its own monotonic counter so a feature branch
-- shows builds #1, #2, #3 independently of master's #1, #2, #3. This is
-- the multibranch UX users coming from Jenkins expect.
--
-- We add a nullable `branch_number` column on `builds` rather than a side
-- table because:
--   * The hot read path is "render this build" — embedding the number on
--     the row keeps that O(1).
--   * Legacy builds (created before this migration) leave it NULL; the
--     view layer falls back to the existing global `build_number`. No
--     backfill needed — branches are tracked from PR1 forward.
--   * Allocation uses an atomic `SELECT COALESCE(MAX(...), 0) + 1` in the
--     same transaction as the INSERT (see chengis.db.build-store), with
--     UNIQUE(job_id, branch_name, branch_number) enforcing the no-collision
--     contract even under concurrent allocations on the same (job, branch).
--
-- The partial UNIQUE index (WHERE branch_number IS NOT NULL) keeps the
-- contract pristine while letting legacy NULL rows coexist. SQLite has
-- supported partial indexes since 3.8 (2013), well below our floor.
ALTER TABLE builds ADD COLUMN branch_number INTEGER;

--;;

CREATE UNIQUE INDEX IF NOT EXISTS idx_builds_job_branch_number
  ON builds(job_id, git_branch, branch_number)
  WHERE branch_number IS NOT NULL;

--;;

-- Read-path support for `SELECT MAX(branch_number) WHERE job_id = ? AND
-- git_branch = ?`. The unique partial index above CAN serve this but only
-- as a covering scan; a plain composite index keeps the planner choice
-- obvious and matches how migration 099 paired UNIQUE with a query-shape
-- index. NULL-on-most-rows is fine — SQLite indexes NULL entries cheaply.
CREATE INDEX IF NOT EXISTS idx_builds_job_branch_for_numbering
  ON builds(job_id, git_branch);
