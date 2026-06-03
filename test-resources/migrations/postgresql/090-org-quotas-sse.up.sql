ALTER TABLE org_quotas ADD COLUMN IF NOT EXISTS max_sse_connections        INTEGER NOT NULL DEFAULT 50;
--;;
ALTER TABLE org_quotas ADD COLUMN IF NOT EXISTS max_webhook_deliveries_rpm  INTEGER NOT NULL DEFAULT 120;
