-- Runtime-toggleable feature flags with org-level overrides and percentage rollouts.
CREATE TABLE IF NOT EXISTS feature_flags (
    id                 TEXT PRIMARY KEY,
    flag_name          TEXT NOT NULL,
    enabled            INTEGER NOT NULL DEFAULT 0,
    org_id_override    TEXT,                         -- NULL means global default
    percentage_rollout INTEGER NOT NULL DEFAULT 100, -- 0-100
    created_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
-- Only one row per (flag_name, org_id_override) pair.
-- Uses '' for global scope so the UNIQUE index works with nullable org_id.
CREATE UNIQUE INDEX IF NOT EXISTS idx_feature_flags_name_org
    ON feature_flags(flag_name, COALESCE(org_id_override, ''));
--;;
CREATE INDEX IF NOT EXISTS idx_feature_flags_org ON feature_flags(org_id_override);
