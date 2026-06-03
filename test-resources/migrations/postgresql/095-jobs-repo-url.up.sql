-- Denormalize the pipeline source URL onto the jobs row so webhook lookup
-- can hit an index instead of full-table-scanning and EDN-deserializing
-- every job. See sqlite/095 for full rationale.
ALTER TABLE jobs ADD COLUMN repo_url TEXT;

CREATE INDEX IF NOT EXISTS idx_jobs_org_repo_url ON jobs(org_id, repo_url);
CREATE INDEX IF NOT EXISTS idx_jobs_repo_url     ON jobs(repo_url);
