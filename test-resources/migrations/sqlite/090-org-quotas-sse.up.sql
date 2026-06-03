-- Add SSE connection and webhook delivery limits to per-org quotas.
ALTER TABLE org_quotas ADD COLUMN max_sse_connections       INTEGER NOT NULL DEFAULT 50;
--;;
ALTER TABLE org_quotas ADD COLUMN max_webhook_deliveries_rpm INTEGER NOT NULL DEFAULT 120;
