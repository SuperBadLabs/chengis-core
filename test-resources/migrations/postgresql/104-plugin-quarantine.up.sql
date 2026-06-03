-- Plugin quarantine: hard-block flag + reason (M2b governance enforcement).
-- A quarantined plugin is refused at load regardless of allowlist/trust, with
-- an auditable reason (e.g. "vulnerable", "stale", "deprecated").
ALTER TABLE plugin_policies ADD COLUMN quarantined INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE plugin_policies ADD COLUMN quarantine_reason TEXT;
