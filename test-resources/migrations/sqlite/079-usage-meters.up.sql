-- Usage meters for billing (Phase B.3)
CREATE TABLE IF NOT EXISTS usage_meters (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    meter_type      TEXT NOT NULL,
    period          TEXT NOT NULL,
    quantity        REAL NOT NULL DEFAULT 0,
    last_updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, meter_type, period)
);

CREATE INDEX IF NOT EXISTS idx_usage_meters_org ON usage_meters(org_id);
CREATE INDEX IF NOT EXISTS idx_usage_meters_period ON usage_meters(period);
