-- See sqlite/099-job-branches.up.sql for full rationale.
-- Postgres mirror: same schema, same indexes. Timestamp columns are TEXT
-- (mirroring SQLite, which has no TIMESTAMPTZ) with the UTC + millisecond
-- text default from migration 098 so lexical comparisons are
-- chronologically equivalent across both DB backends.
CREATE TABLE IF NOT EXISTS job_branches (
  id             TEXT PRIMARY KEY,
  job_id         TEXT NOT NULL,
  branch_name    TEXT NOT NULL,
  head_sha       TEXT,
  discovered_at  TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS'),
  last_seen_at   TEXT,
  archived_at    TEXT,
  UNIQUE(job_id, branch_name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_job_branches_job_id
  ON job_branches(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_job_branches_job_archived
  ON job_branches(job_id, archived_at);
