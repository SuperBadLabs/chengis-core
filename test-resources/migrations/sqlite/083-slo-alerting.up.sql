CREATE TABLE IF NOT EXISTS slos (
    id          TEXT PRIMARY KEY,
    org_id      TEXT NOT NULL,
    name        TEXT NOT NULL,
    metric      TEXT NOT NULL,
    threshold   REAL NOT NULL,
    comparator  TEXT NOT NULL DEFAULT 'gte',
    window_hours INTEGER NOT NULL DEFAULT 24,
    enabled     INTEGER NOT NULL DEFAULT 1,
    notify_config TEXT,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_slos_org ON slos(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_slos_enabled ON slos(enabled);
--;;
CREATE TABLE IF NOT EXISTS slo_violations (
    id           TEXT PRIMARY KEY,
    slo_id       TEXT NOT NULL REFERENCES slos(id),
    org_id       TEXT NOT NULL,
    metric_value REAL NOT NULL,
    threshold    REAL NOT NULL,
    message      TEXT,
    notified     INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_slo_violations_slo ON slo_violations(slo_id);
--;;
CREATE INDEX IF NOT EXISTS idx_slo_violations_org ON slo_violations(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_slo_violations_created ON slo_violations(created_at);
