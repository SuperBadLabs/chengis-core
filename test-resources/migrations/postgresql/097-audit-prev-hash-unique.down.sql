DROP INDEX IF EXISTS idx_audit_prev_hash_unique;
--;;
UPDATE audit_logs SET prev_hash = NULL WHERE prev_hash = 'genesis';
