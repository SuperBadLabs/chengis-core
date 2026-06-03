CREATE TABLE IF NOT EXISTS backup_catalog (
    id          TEXT PRIMARY KEY,
    filename    TEXT NOT NULL,
    path        TEXT NOT NULL,
    size_bytes  INTEGER NOT NULL,
    db_type     TEXT NOT NULL DEFAULT 'sqlite',
    status      TEXT NOT NULL DEFAULT 'completed',
    verified_at TEXT,
    error       TEXT,
    trigger     TEXT NOT NULL DEFAULT 'manual',
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_backup_catalog_created ON backup_catalog(created_at);
--;;
CREATE INDEX IF NOT EXISTS idx_backup_catalog_status ON backup_catalog(status);
