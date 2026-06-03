DROP INDEX IF EXISTS idx_builds_job_branch_for_numbering;

--;;

DROP INDEX IF EXISTS idx_builds_job_branch_number;

--;;

ALTER TABLE builds DROP COLUMN branch_number;
