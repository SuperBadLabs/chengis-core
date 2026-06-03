-- Denormalize the pipeline source URL onto the jobs row so webhook lookup
-- can hit an index instead of full-table-scanning and EDN-deserializing
-- every job to check :pipeline :source :url. Set lazily on insert/update
-- (see chengis.db.job-store/extract-repo-url); existing NULL rows are
-- backfilled by chengis.db.job-store/backfill-job-repo-urls! on startup.
ALTER TABLE jobs ADD COLUMN repo_url TEXT;

-- Partial-ish index — both columns nullable, NULL repo_url rows aren't
-- in the working set anyway. (org_id, repo_url) compound matches the
-- per-org webhook lookup pattern; (repo_url) alone supports the
-- cross-org find-matching-jobs path.
CREATE INDEX IF NOT EXISTS idx_jobs_org_repo_url ON jobs(org_id, repo_url);
CREATE INDEX IF NOT EXISTS idx_jobs_repo_url     ON jobs(repo_url);
