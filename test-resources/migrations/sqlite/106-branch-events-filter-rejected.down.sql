-- Revert the widened CHECK. Filter-rejected rows would violate the
-- narrower constraint so we delete them first — the down migration
-- is best-effort cleanup, not a guarantee.
DELETE FROM branch_events WHERE event_type = 'filter-rejected';

--;;

CREATE TABLE branch_events_old (
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

INSERT INTO branch_events_old (id, job_id, branch_name, event_type, from_sha, to_sha, created_at)
SELECT id, job_id, branch_name, event_type, from_sha, to_sha, created_at
FROM branch_events;

--;;

DROP TABLE branch_events;

--;;

ALTER TABLE branch_events_old RENAME TO branch_events;

--;;

CREATE INDEX IF NOT EXISTS idx_branch_events_job_branch_created
  ON branch_events(job_id, branch_name, created_at DESC);

--;;

CREATE INDEX IF NOT EXISTS idx_branch_events_created_at
  ON branch_events(created_at DESC);
