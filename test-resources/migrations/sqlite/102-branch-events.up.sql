-- Branch-lifecycle event log (CHG-FEAT-003 PR6): append-only audit trail of
-- every transition `job_branches` rows undergo. Migration 099 stores the
-- CURRENT state (one row per (job_id, branch_name) with mutating
-- head_sha / last_seen_at / archived_at columns) — that schema by design
-- destroys history every time discovery runs. This table preserves it.
--
-- Why a separate table instead of an `updated_at` trigger / journal column?
--   * Append-only beats trigger-driven row-history for two reasons: (1)
--     SQLite and Postgres triggers diverge enough in syntax that the
--     equivalence cost is high, and (2) the consumers (admin audit UI in
--     PR8, future webhook fan-out) want a stream they can ORDER BY +
--     paginate, not a flat per-branch history column.
--   * Keeps `job_branches` schema unchanged — PR1's hot read path
--     (list-active-branches → scheduler loop) doesn't pay any cost for
--     the new audit data.
--
-- Event-type vocabulary (locked, but extensible):
--   * 'created'     — first time this (job_id, branch_name) is upserted.
--   * 'updated'     — re-discovered with a different head_sha (from_sha
--                     and to_sha both populated).
--   * 'archived'    — branch disappeared from `ls-remote`; archive-branch!
--                     called (from_sha populated, to_sha NULL).
--   * 'resurrected' — branch was previously archived and reappears in a
--                     discovery result; upsert clears archived_at.
-- Future PRs may extend (e.g. PR7 'protection-changed' for branch-
-- protection deltas); the CHECK constraint is named so it's straightforward
-- to drop and recreate with a wider set.
--
-- Schema decisions:
--   * `id` INTEGER PRIMARY KEY AUTOINCREMENT — append-only, no business
--     key. SQLite's AUTOINCREMENT guarantees the rowid only ever grows
--     (matches the BIGSERIAL semantics on the Postgres side) which keeps
--     the (id ASC) sort order monotonic with insertion order — critical
--     for the "give me events newer than rowid N" cursor the UI feed will
--     use in PR8.
--   * `branch_name` is the canonical name straight from `ls-remote` (not
--     sanitised). Sanitisation happens at workspace-path generation time
--     (PR4); audit needs the truth, not the slug.
--   * `from_sha` / `to_sha` are NULLable to encode the asymmetric
--     lifecycle transitions: created has only `to_sha`, archived has only
--     `from_sha`, updated has both, resurrected uses the same shape as
--     updated (prior archived row's head_sha → new head_sha from
--     re-discovery).
--   * `created_at` matches migration 098/099/100/101's UTC + millisecond
--     text default. ORDER BY created_at then id is lex-equivalent to
--     chronological even when multiple events for the same branch land
--     in the same millisecond (id breaks the tie monotonically).
CREATE TABLE IF NOT EXISTS branch_events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id      TEXT NOT NULL,
  branch_name TEXT NOT NULL,
  event_type  TEXT NOT NULL,
  from_sha    TEXT,
  to_sha      TEXT,
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  CONSTRAINT branch_events_event_type_chk
    CHECK (event_type IN ('created', 'updated', 'archived', 'resurrected'))
);

--;;

-- Composite index for the dominant query shape:
--   "give me the event history for (job_id, branch_name), newest first"
-- The DESC on created_at matches how the PR8 UI feed will scroll. SQLite
-- 3.8+ honours the index order on a leading-equality + range scan, so the
-- planner avoids a separate sort step for the per-branch history page.
CREATE INDEX IF NOT EXISTS idx_branch_events_job_branch_created
  ON branch_events(job_id, branch_name, created_at DESC);

--;;

-- Cross-branch recent-events scan (PR8 "recent activity across all
-- branches"). Plain index on created_at handles ORDER BY created_at DESC
-- LIMIT N without falling back to a table scan when the table grows past
-- a few thousand rows.
CREATE INDEX IF NOT EXISTS idx_branch_events_created_at
  ON branch_events(created_at DESC);
