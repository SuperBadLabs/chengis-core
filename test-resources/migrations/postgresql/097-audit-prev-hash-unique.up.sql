-- See sqlite/097-audit-prev-hash-unique.up.sql for full rationale, including
-- the legacy-row safety filter (entry_hash IS NOT NULL) that excludes pre-
-- migration-030 audit rows from the genesis-sentinel rewrite.
--
-- Postgres also treats NULL as distinct in UNIQUE by default, so legacy
-- rows with NULL prev_hash coexist freely alongside the unique enforcement
-- on the "genesis" sentinel and real entry_hash chain-link values.
--
-- Note on locking: this uses plain CREATE UNIQUE INDEX (not CONCURRENTLY)
-- because the migration runner wraps each --;;-separated statement in an
-- implicit transaction, and CONCURRENTLY cannot run inside a transaction
-- block. For chengis's audit_logs sizes (≤ low millions), the ACCESS
-- EXCLUSIVE lock held during the build is acceptable. If/when the table
-- grows beyond that scale, factor the index build into a separate
-- maintenance step that runs CONCURRENTLY outside the migration runner.
UPDATE audit_logs
   SET prev_hash = 'genesis'
 WHERE prev_hash IS NULL
   AND entry_hash IS NOT NULL;
--;;
CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_prev_hash_unique
  ON audit_logs(prev_hash);
