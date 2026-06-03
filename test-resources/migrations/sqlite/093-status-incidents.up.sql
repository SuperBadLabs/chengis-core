CREATE TABLE IF NOT EXISTS status_incidents (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    severity        TEXT NOT NULL DEFAULT 'minor',   -- minor, major, critical
    status          TEXT NOT NULL DEFAULT 'investigating', -- investigating, identified, monitoring, resolved
    started_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at     TEXT,
    created_by      TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_status_incidents_org ON status_incidents(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_status_incidents_started ON status_incidents(org_id, started_at DESC);
--;;
CREATE TABLE IF NOT EXISTS maintenance_windows (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    scheduled_start TEXT NOT NULL,
    scheduled_end   TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'scheduled', -- scheduled, in_progress, completed, cancelled
    created_by      TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_maintenance_windows_org ON maintenance_windows(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_maintenance_windows_start ON maintenance_windows(org_id, scheduled_start);
