CREATE TABLE IF NOT EXISTS org_retention_policies (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL UNIQUE,
    builds_days     INTEGER,
    build_logs_days INTEGER,
    audit_days      INTEGER,
    webhook_days    INTEGER,
    analytics_days  INTEGER,
    cost_days       INTEGER,
    test_days       INTEGER,
    trace_days      INTEGER,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_org_retention_org ON org_retention_policies(org_id);
