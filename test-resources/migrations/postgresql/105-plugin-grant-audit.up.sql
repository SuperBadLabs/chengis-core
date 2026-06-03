-- See sqlite/105-plugin-grant-audit.up.sql for full rationale.
-- Postgres mirror: same table, same indexes. id is BIGSERIAL so the monotonic
-- insertion-order property matches SQLite's INTEGER PRIMARY KEY AUTOINCREMENT.
-- signed is INTEGER 0/1 (NOT boolean) to match the plugin_policies convention
-- on this backend (migrations 034/104). granted_at uses the UTC + ms text
-- default so lex order == chronological order, consistent across backends.
CREATE TABLE IF NOT EXISTS plugin_grant_audit (
  id           BIGSERIAL PRIMARY KEY,
  org_id       TEXT,
  plugin_name  TEXT NOT NULL,
  trust_level  TEXT NOT NULL,
  capabilities TEXT NOT NULL DEFAULT '[]',
  signed       INTEGER NOT NULL DEFAULT 0,
  signed_by    TEXT,
  granted_at   TEXT NOT NULL
    DEFAULT to_char((CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
                    'YYYY-MM-DD HH24:MI:SS.MS')
);

--;;

CREATE INDEX IF NOT EXISTS idx_plugin_grant_audit_plugin
  ON plugin_grant_audit(org_id, plugin_name, granted_at DESC);

--;;

CREATE INDEX IF NOT EXISTS idx_plugin_grant_audit_granted_at
  ON plugin_grant_audit(granted_at DESC);
