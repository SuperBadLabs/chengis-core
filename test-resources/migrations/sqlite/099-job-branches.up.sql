-- Multibranch pipelines (CHG-FEAT-003 PR1): per-job catalog of branches
-- discovered from the SCM. A row exists for every branch the discovery layer
-- has seen at least once; `archived_at IS NULL` means the branch is still
-- present on the remote, so it can be a candidate for per-branch build
-- triggering (wiring lands in PR2+ — this migration only establishes the
-- contract).
--
-- The UNIQUE(job_id, branch_name) constraint is what `upsert-branch!` keys
-- on: SCM discovery is idempotent — re-discovering a branch updates
-- `head_sha` + `last_seen_at` instead of inserting a duplicate row. We keep
-- archived rows (instead of deleting) so the branch-lifecycle event log in
-- PR4 can audit "branch X was deleted on date Y" and so per-branch build
-- numbering in PR3 can continue to monotonically grow even after a transient
-- branch deletion + re-creation.
--
-- Timestamp storage matches migration 098 (UTC + millisecond text format) so
-- ORDER BY {discovered,last_seen,archived}_at is lex-equivalent to
-- chronological even when multiple discovery runs land in the same second.
-- See coverage_results.up.sql for the full rationale.
CREATE TABLE IF NOT EXISTS job_branches (
  id             TEXT PRIMARY KEY,
  job_id         TEXT NOT NULL,
  branch_name    TEXT NOT NULL,
  head_sha       TEXT,                  -- NULL when the SCM list didn't include SHAs
  discovered_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  last_seen_at   TEXT,                  -- updated on every successful re-discovery
  archived_at    TEXT,                  -- non-NULL means branch was removed from SCM
  UNIQUE(job_id, branch_name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_job_branches_job_id
  ON job_branches(job_id);

--;;

-- Composite index for `list-active-branches`: SELECT ... WHERE job_id = ?
-- AND archived_at IS NULL — the planner can use this index for both the
-- equality and the IS NULL predicate.
CREATE INDEX IF NOT EXISTS idx_job_branches_job_archived
  ON job_branches(job_id, archived_at);
