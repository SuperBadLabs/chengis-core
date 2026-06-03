-- GitHub App installations for OAuth-based integration (Phase B.4)
CREATE TABLE IF NOT EXISTS github_app_installations (
    id                   TEXT PRIMARY KEY,
    org_id               TEXT NOT NULL,
    installation_id      INTEGER NOT NULL UNIQUE,
    account_login        TEXT NOT NULL,
    account_type         TEXT NOT NULL DEFAULT 'Organization',
    permissions          TEXT,
    repository_selection TEXT DEFAULT 'all',
    suspended            INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_gh_app_inst_org ON github_app_installations(org_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_gh_app_inst_id ON github_app_installations(installation_id);
