-- See sqlite/102-branch-events.up.sql for full rationale.
-- Postgres mirror: same table, same indexes, same CHECK. id is BIGSERIAL
-- so the monotonic insertion-order property matches SQLite's
-- INTEGER PRIMARY KEY AUTOINCREMENT. Timestamp default uses the UTC + ms
-- text pattern from migration 098/099/100/101 so lex-comparisons of
-- created_at remain coherent across backends.
CREATE TABLE IF NOT EXISTS branch_events (
  id          BIGSERIAL PRIMARY KEY,
  job_id      TEXT NOT NULL,
  branch_name TEXT NOT NULL,
  event_type  TEXT NOT NULL,
  from_sha    TEXT,
  to_sha      TEXT,
  created_at  TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS'),
  CONSTRAINT branch_events_event_type_chk
    CHECK (event_type IN ('created', 'updated', 'archived', 'resurrected'))
);

--;;

CREATE INDEX IF NOT EXISTS idx_branch_events_job_branch_created
  ON branch_events(job_id, branch_name, created_at DESC);

--;;

CREATE INDEX IF NOT EXISTS idx_branch_events_created_at
  ON branch_events(created_at DESC);
