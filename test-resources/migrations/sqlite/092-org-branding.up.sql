CREATE TABLE IF NOT EXISTS org_branding (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL UNIQUE,
    logo_url        TEXT,
    primary_color   TEXT,
    secondary_color TEXT,
    product_name    TEXT,
    custom_domain   TEXT,
    email_sender    TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_org_branding_org ON org_branding(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_org_branding_domain ON org_branding(custom_domain);
