-- Per-org quotas for rate limiting and concurrency control (Phase B)
CREATE TABLE IF NOT EXISTS org_quotas (
    id                      TEXT PRIMARY KEY,
    org_id                  TEXT NOT NULL UNIQUE,
    api_rpm                 INTEGER NOT NULL DEFAULT 300,
    webhook_rpm             INTEGER NOT NULL DEFAULT 600,
    max_concurrent_builds   INTEGER NOT NULL DEFAULT 5,
    max_builds_per_hour     INTEGER NOT NULL DEFAULT 50,
    max_artifact_storage_mb INTEGER NOT NULL DEFAULT 5120,
    enabled                 INTEGER NOT NULL DEFAULT 1,
    created_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_org_quotas_org ON org_quotas(org_id);
