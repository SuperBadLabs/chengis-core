-- Prevent hash-chain bifurcation: only one child per parent in audit_logs,
-- AND only one genesis row.
--
-- Before: insert-audit! reads the current chain tip then INSERTs a row
-- pointing at it. Two concurrent writers could SELECT the same tip and
-- both INSERT children pointing at the same prev_hash, OR (on an empty
-- table) both INSERT genesis rows with prev_hash = NULL — turning the
-- chain into a tree from row 1.
--
-- Fix: a UNIQUE constraint on prev_hash. The chain genesis row stores
-- the literal sentinel string "genesis" (matching the sentinel that
-- compute-entry-hash already uses when prev_hash is nil — see
-- audit_store.clj — so chain verification stays consistent for both
-- historical and future rows).
--
-- Legacy-row safety (Codex P1): the UPDATE below filters to rows that
-- are PART OF the hash chain (entry_hash IS NOT NULL). Pre-migration-030
-- audit rows pre-date the hash chain entirely — they have both
-- entry_hash AND prev_hash NULL. Those rows are NOT in any chain and
-- must NOT be rewritten to the "genesis" sentinel; doing so would
-- collapse N legacy rows into N duplicate "genesis" values and make
-- the UNIQUE INDEX fail on healthy upgrades from older deployments.
-- They keep prev_hash = NULL post-migration. The full UNIQUE index
-- below allows multiple NULLs (SQLite and Postgres both treat NULL as
-- distinct in UNIQUE by default), so legacy rows coexist freely.
-- Unique enforcement applies only to non-NULL prev_hash values:
-- "genesis" (one row) plus every real entry_hash chain-link value.
UPDATE audit_logs
   SET prev_hash = 'genesis'
 WHERE prev_hash IS NULL
   AND entry_hash IS NOT NULL;
--;;
-- IF NOT EXISTS guards against partial-failure replay: if the index
-- already built on a prior attempt but a downstream step in the
-- migration runner failed, re-running won't error on a redundant CREATE.
CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_prev_hash_unique
  ON audit_logs(prev_hash);
