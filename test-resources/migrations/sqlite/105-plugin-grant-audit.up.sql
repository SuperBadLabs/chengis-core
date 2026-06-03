-- Plugin grant audit trail (M3c): append-only history of every external-plugin
-- load and the capability grant it received — trust context, capabilities, and
-- signing provenance (which key signed, if any).
--
-- Why a table when the registry already tracks grants? chengis.plugin.registry
-- holds only the CURRENT grant per plugin, in memory — it is destroyed on
-- restart and overwritten on every reload. Governance needs the stream: "who
-- loaded what, when, with which trust/capabilities", surviving restarts and
-- ordered for an admin feed. This mirrors the append-only branch_events pattern
-- (migration 102): one row per load event, never updated in place.
--
-- Schema notes:
--   * id INTEGER PRIMARY KEY AUTOINCREMENT — append-only, monotonic, matches
--     the BIGSERIAL on Postgres; lets the UI sort (granted_at, id) stably even
--     when two loads land in the same millisecond.
--   * capabilities is an EDN vector string (e.g. "[:http :log]") — dialect-
--     agnostic and read back with clojure.edn in the store.
--   * signed is INTEGER 0/1 in BOTH dialects (matching the plugin_policies
--     allowed/quarantined convention from migrations 034/104), avoiding the
--     bool-coercion mismatch between SQLite and Postgres.
--   * granted_at uses the UTC + millisecond text default shared by migrations
--     098-102, so lex order == chronological order across backends.
CREATE TABLE IF NOT EXISTS plugin_grant_audit (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  org_id       TEXT,
  plugin_name  TEXT NOT NULL,
  trust_level  TEXT NOT NULL,
  capabilities TEXT NOT NULL DEFAULT '[]',
  signed       INTEGER NOT NULL DEFAULT 0,
  signed_by    TEXT,
  granted_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);

--;;

-- Dominant query: "grant history for this plugin (in this org), newest first".
CREATE INDEX IF NOT EXISTS idx_plugin_grant_audit_plugin
  ON plugin_grant_audit(org_id, plugin_name, granted_at DESC);

--;;

-- Cross-plugin recent-activity feed for the admin page.
CREATE INDEX IF NOT EXISTS idx_plugin_grant_audit_granted_at
  ON plugin_grant_audit(granted_at DESC);
