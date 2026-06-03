-- Tenant-bound webhook registrations (Phase A.5)
-- Each org can register unique webhook endpoints with per-org secrets.
-- The endpoint_slug creates a unique URL path: /api/webhook/:slug

CREATE TABLE IF NOT EXISTS webhook_registrations (
    id            TEXT PRIMARY KEY,
    org_id        TEXT NOT NULL,
    provider      TEXT NOT NULL,
    secret        TEXT NOT NULL,
    endpoint_slug TEXT NOT NULL UNIQUE,
    description   TEXT,
    enabled       INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_reg_org ON webhook_registrations(org_id);
CREATE INDEX IF NOT EXISTS idx_webhook_reg_slug ON webhook_registrations(endpoint_slug);
