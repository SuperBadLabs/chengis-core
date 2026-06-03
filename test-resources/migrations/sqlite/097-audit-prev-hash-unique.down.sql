DROP INDEX IF EXISTS idx_audit_prev_hash_unique;
--;;
-- Reverse the genesis sentinel back to NULL. Only the unique genesis row
-- carries this value (idx_audit_prev_hash_unique enforces it pre-drop),
-- so this UPDATE is a no-op for non-genesis rows.
UPDATE audit_logs SET prev_hash = NULL WHERE prev_hash = 'genesis';
