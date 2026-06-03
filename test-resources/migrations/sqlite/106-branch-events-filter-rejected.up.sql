-- Extend `branch_events.event_type` vocabulary to include 'filter_rejected'
-- (CHG-FEAT-003 PR5 — trigger-time glob enforcement).
--
-- Why: when an operator narrows the multibranch `:include` (or expands
-- `:exclude`) between discovery and trigger time, an already-discovered
-- branch can fail the live filter. PR5 adds a per-trigger gate that
-- rejects such builds; this rejection MUST land in the branch-lifecycle
-- audit log so operators can answer "why didn't this branch build?".
-- The existing CHECK constraint (migration 102) only permits the four
-- reconcile-time transitions; rejection is a fifth, semantically
-- distinct event.
--
-- SQLite cannot ALTER a CHECK constraint in place, so we follow the
-- documented table-rebuild dance: create a new table with the widened
-- constraint, copy rows over, drop the old, rename. The id column is
-- INTEGER PRIMARY KEY AUTOINCREMENT — we preserve the rowid via the
-- explicit id select so existing primary-key references remain valid.
-- The created_at default is preserved verbatim so freshly-inserted rows
-- continue to share the lex-sortable UTC + ms text shape with 098-101.
CREATE TABLE branch_events_new (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id      TEXT NOT NULL,
  branch_name TEXT NOT NULL,
  event_type  TEXT NOT NULL,
  from_sha    TEXT,
  to_sha      TEXT,
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
  CONSTRAINT branch_events_event_type_chk
    CHECK (event_type IN ('created', 'updated', 'archived', 'resurrected', 'filter-rejected'))
);

--;;

INSERT INTO branch_events_new (id, job_id, branch_name, event_type, from_sha, to_sha, created_at)
SELECT id, job_id, branch_name, event_type, from_sha, to_sha, created_at
FROM branch_events;

--;;

DROP TABLE branch_events;

--;;

ALTER TABLE branch_events_new RENAME TO branch_events;

--;;

-- Rebuild the indexes that lived on the original table.
CREATE INDEX IF NOT EXISTS idx_branch_events_job_branch_created
  ON branch_events(job_id, branch_name, created_at DESC);

--;;

CREATE INDEX IF NOT EXISTS idx_branch_events_created_at
  ON branch_events(created_at DESC);
