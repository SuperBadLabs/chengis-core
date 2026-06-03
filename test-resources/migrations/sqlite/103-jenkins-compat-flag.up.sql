-- Seed the global default row for the `jenkins-compat` feature flag.
-- The Jenkins-compatibility layer (src/chengis/compat/jenkins/) reads this
-- flag at every gate point; disabled by default so existing orgs see no
-- behavior change until they opt in via /admin/feature-flags.
--
-- Per-org overrides are stored as additional rows with org_id_override set;
-- see src/chengis/db/feature_flag_store.clj.
INSERT OR IGNORE INTO feature_flags (id, flag_name, enabled, org_id_override, percentage_rollout)
VALUES ('flag-jenkins-compat-global', 'jenkins-compat', 0, NULL, 100);
