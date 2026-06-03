-- See sqlite/101-builds-branch-number.up.sql for full rationale.
-- Postgres mirror: same column, same partial UNIQUE index, same secondary
-- index for the MAX(branch_number) scan path.
ALTER TABLE builds ADD COLUMN branch_number INTEGER;

--;;

CREATE UNIQUE INDEX IF NOT EXISTS idx_builds_job_branch_number
  ON builds(job_id, git_branch, branch_number)
  WHERE branch_number IS NOT NULL;

--;;

CREATE INDEX IF NOT EXISTS idx_builds_job_branch_for_numbering
  ON builds(job_id, git_branch);
