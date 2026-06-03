DROP INDEX IF EXISTS idx_jobs_org_repo_url;
DROP INDEX IF EXISTS idx_jobs_repo_url;
ALTER TABLE jobs DROP COLUMN IF EXISTS repo_url;
