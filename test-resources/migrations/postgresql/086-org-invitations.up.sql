CREATE TABLE IF NOT EXISTS org_invitations (
    id          TEXT PRIMARY KEY,
    org_id      TEXT NOT NULL,
    email       TEXT NOT NULL,
    token       TEXT NOT NULL UNIQUE,
    role        TEXT NOT NULL DEFAULT 'developer',
    invited_by  TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    accepted_at TEXT,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_org_invitations_org ON org_invitations(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_org_invitations_token ON org_invitations(token);
--;;
CREATE INDEX IF NOT EXISTS idx_org_invitations_email ON org_invitations(email);
--;;
CREATE INDEX IF NOT EXISTS idx_org_invitations_expires ON org_invitations(expires_at);
