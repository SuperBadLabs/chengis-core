-- Runtime-toggleable feature flags with org-level overrides and percentage rollouts.
CREATE TABLE IF NOT EXISTS feature_flags (
    id                 TEXT PRIMARY KEY,
    flag_name          TEXT NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    org_id_override    TEXT,
    percentage_rollout INTEGER NOT NULL DEFAULT 100 CHECK (percentage_rollout BETWEEN 0 AND 100),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS idx_feature_flags_name_org
    ON feature_flags(flag_name, COALESCE(org_id_override, ''));
--;;
CREATE INDEX IF NOT EXISTS idx_feature_flags_org ON feature_flags(org_id_override);
