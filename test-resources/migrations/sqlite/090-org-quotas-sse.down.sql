-- SQLite does not support DROP COLUMN before 3.35; recreate to roll back.
CREATE TABLE org_quotas_backup AS SELECT
    id, org_id, api_rpm, webhook_rpm, max_concurrent_builds,
    max_builds_per_hour, max_artifact_storage_mb, enabled,
    created_at, updated_at
FROM org_quotas;
--;;
DROP TABLE org_quotas;
--;;
ALTER TABLE org_quotas_backup RENAME TO org_quotas;
--;;
CREATE INDEX IF NOT EXISTS idx_org_quotas_org ON org_quotas(org_id);
