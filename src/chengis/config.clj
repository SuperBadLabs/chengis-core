(ns chengis.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [chengis.product :as product]
            [chengis.product.capability :as capability]))

(def default-config
  {:database {:type "sqlite"    ;; "sqlite" or "postgresql"
              :path "chengis.db"  ;; SQLite file path (used when type=sqlite)
              ;; PostgreSQL connection (used when type=postgresql)
              :host "localhost"
              :port 5432
              :dbname "chengis"
              :user "chengis"
              :password nil
              ;; HikariCP pool settings (PostgreSQL only)
              :pool {:minimum-idle 2
                     :maximum-pool-size 10}}
   :workspace {:root "workspaces"
               ;; CHG-FEAT-003 PR4 — per-(job, branch) workspace isolation
               ;; for multibranch jobs. `:per-branch` is the master switch
               ;; (defaults ON; flip to false to force every build onto
               ;; the legacy per-build layout — useful for very tight disk
               ;; budgets). `:branch-retention-days` caps how long a
               ;; per-branch workspace dir lives after the branch is
               ;; soft-archived in `job_branches`.
               :per-branch true
               :branch-retention-days 14}
   :scheduler {:enabled false}
   :server {:port 8080 :host "0.0.0.0"
            :worker-threads 8        ;; http-kit worker thread pool size (default 8)
            :queue-size 20480        ;; http-kit request queue capacity
            :max-body 8388608}       ;; max request body size (8MB)
   :secrets {:master-key nil
             :backend "local"      ;; "local" (AES-256-GCM in DB) or "vault" (HashiCorp Vault)
             :fallback-to-local false  ;; When true, Vault errors fall back to local store (NOT recommended for production)
             :vault {:url nil      ;; e.g., "http://127.0.0.1:8200" or VAULT_ADDR env
                     :token nil    ;; Vault token or VAULT_TOKEN env
                     :mount "secret"
                     :prefix "chengis/"}}
   :artifacts {:root "artifacts" :retention-builds 10
               :max-size-bytes (* 500 1024 1024)}  ;; 500MB per artifact
   :notifications {:slack {:default-webhook nil}
                   :email {:host nil
                           :port 587
                           :tls true
                           :username nil
                           :password nil
                           :from "chengis@localhost"
                           :default-recipients []}}
   :cleanup {:enabled false :interval-hours 24 :retention-builds 10}
   ;; Plugin-health auto-quarantine sweep (M3b). When :enabled, a periodic job
   ;; quarantines external plugins that are stale (the .clj file hasn't changed
   ;; in > :staleness-days) or match a known-vulnerable advisory, and lifts
   ;; auto-quarantines that no longer apply (manual quarantines are never
   ;; touched). :advisories is an inline vector of
   ;;   {:plugin <name> :id <ident> :reason <text>? :versions [<v> ...]?}
   ;; (omit :versions to match all versions); :advisories-path reads the same
   ;; shape from an EDN file. See chengis.engine.plugin-health.
   :plugin-health {:enabled false
                   :interval-hours 24
                   :staleness-days 90
                   :advisories []
                   :advisories-path nil}
   :plugins {:directory "plugins" :enabled []
             ;; Plugin provenance (M2a/M3a). :public-keys is a vector of trusted
             ;; Ed25519 signing keys; each entry is either a base64 X.509 SPKI
             ;; string (an always-active key) or a map
             ;;   {:key "<base64 SPKI>" :status :active|:revoked
             ;;    :label "..." :added "..." :revoked-at "..." :reason "..."}
             ;; Rotation = add a new key alongside the old; revocation = flip the
             ;; old key to {:status :revoked} (it stops verifying immediately but
             ;; stays for audit). Generate keys with `chengis sign-plugin keygen`.
             ;; When :require-signed is true, unsigned plugins are blocked entirely.
             :signing {:public-keys []
                       :require-signed false}}
   ;; Pipeline DSL (.clj) evaluation. :eval-mode :sandboxed (default) runs
   ;; pipeline-definition files in an SCI sandbox — only the DSL vocabulary +
   ;; safe clojure.core, no Java interop / eval / filesystem, with a wall-clock
   ;; timeout. :trusted falls back to raw load-file (full JVM privileges) for
   ;; operators who knowingly run full-power pipeline files. See
   ;; chengis.dsl.sandbox / chengis.dsl.core/load-pipeline-file.
   :dsl {:eval-mode :sandboxed
         :timeout-ms 5000}
   :docker {:host "unix:///var/run/docker.sock"
            :default-timeout 600000
            :pull-policy :if-not-present}
   :distributed {:enabled false
                 :mode :master
                 :auth-token nil
                 :auth-scheme :hmac    ;; :hmac (HMAC-SHA256 signed) or :bearer (simple token)
                 :heartbeat-timeout-ms 90000
                 :agent {:port 9090
                         :labels #{}
                         :max-builds 2
                         :tls {:keystore nil
                               :keystore-password nil
                               :require-tls false}}
                 :dispatch {:fallback-local false
                            :queue-enabled false
                            :max-retries 3
                            :retry-backoff-ms 1000
                            :circuit-breaker-threshold 5
                            :circuit-breaker-reset-ms 60000
                            :orphan-check-interval-ms 120000
                            :artifact-transfer true}
                 ;; K8s provisioner (CHG-FEAT-004 PR2). Defaults match
                 ;; chengis.distributed.k8s-provisioner/default-* constants;
                 ;; env overlays via CHENGIS_K8S_*.
                 :k8s {:namespace nil          ;; nil → resolved from SA/kubeconfig/"default"
                       :agent-image nil        ;; nil → "chengis/agent:latest"
                       :agent-port nil         ;; nil → :distributed :agent :port (9090)
                       :cpu-request nil        ;; nil → "500m"
                       :memory-request nil     ;; nil → "512Mi"
                       :cpu-limit nil          ;; nil → "2000m"
                       :memory-limit nil       ;; nil → "2Gi"
                       :poll-interval-ms nil   ;; nil → 1000
                       :poll-timeout-ms nil    ;; nil → 120000
                       :server nil             ;; nil → in-cluster / kubeconfig
                       :insecure? false
                       ;; Queue-depth autoscaler (CHG-FEAT-004 PR3). Opt-in;
                       ;; defaults to off so non-K8s deployments are unaffected.
                       :autoscale {:enabled false
                                   :interval-seconds 30
                                   :threshold 5
                                   :max-per-tick 1
                                   :max-provisioned 5}
                       ;; Orphan-Job reconciliation loop (CHG-FEAT-004 PR4).
                       ;; Opt-in. When enabled, a Chime-driven tick diffs
                       ;; cluster Jobs against the K8s provisioner's local
                       ;; state and releases orphans on both sides.
                       :orphan-cleanup {:enabled false
                                        :interval-seconds 60
                                        :grace-seconds 60}}}
   :auth {:enabled false
          :session-secret nil
          :jwt-secret nil
          :jwt-expiry-hours 24
          :jwt-rotation-hours nil
          :seed-admin-password "admin"
          :session-max-age 86400
          :lockout {:enabled true
                    :max-attempts 5
                    :lockout-minutes 30}}
   :https {:enabled false
           :port 8443
           :keystore nil
           :keystore-password nil
           :hsts true
           :redirect-http true}
   :audit {:enabled true
           :retention-days 90
           :buffer-size 1024}
   :metrics {:enabled false
             :path "/metrics"
             :auth-required false}
   :rate-limit {:enabled false
                :requests-per-minute 60
                :auth-requests-per-minute 10
                :webhook-requests-per-minute 120}
   :org-quotas {:default-api-rpm 300
                :default-webhook-rpm 600
                :default-max-concurrent-builds 5
                :default-max-builds-per-hour 50
                :default-max-artifact-storage-mb 5120}
   :webhook {:secret nil}
   :environment "development"
   :security {:cors {:enabled false
                     :allowed-origins ["*"]
                     :allowed-methods ["GET" "POST" "PUT" "DELETE"]
                     :max-age 3600}
              ;; CSP (FE-003). Tailwind (FE-001) + htmx (FE-002) are self-hosted,
              ;; and the few inline scripts/styles are externalized or carry a
              ;; per-request nonce ({NONCE} is substituted by chengis.web.csp).
              ;;   script-src/style-src: 'self' only, no 'unsafe-inline'.
              ;;   *-src-attr: 'unsafe-inline' so legacy inline event handlers
              ;;     (onclick=...) and inline style attrs keep working without
              ;;     a 38-handler refactor. Real injected <script> tags from
              ;;     XSS are blocked — attackers can't forge the right nonce.
              :csp {:enabled true
                    :directives {:default-src      "'none'"
                                 :script-src       "'self' 'nonce-{NONCE}'"
                                 :script-src-attr  "'unsafe-inline'"
                                 :style-src        "'self' 'nonce-{NONCE}'"
                                 :style-src-attr   "'unsafe-inline'"
                                 :img-src          "'self' data:"
                                 :font-src         "'self'"
                                 :connect-src      "'self'"
                                 :object-src       "'none'"
                                 :base-uri         "'self'"
                                 :form-action      "'self'"
                                 :frame-ancestors  "'none'"}}}
   :retention {:enabled false
               :interval-hours 24
               :builds-days 90
               :build-logs-days 30
               :audit-days 90
               :webhook-events-days 30
               :queue-completed-hours 168}
   :scm {:github {:token nil
                  :context "chengis/build"
                  :base-url "https://api.github.com"}
         :gitlab {:token nil
                  :base-url "https://gitlab.com"}
         :gitea {:token nil
                 :base-url nil}           ;; e.g., "https://gitea.example.com"
         :bitbucket {:username nil
                     :app-password nil
                     :base-url "https://api.bitbucket.org/2.0"}}
   :oidc {:enabled false
          :issuer-url nil         ;; e.g., "https://keycloak.example.com/realms/chengis"
          :client-id nil
          :client-secret nil
          :callback-url nil       ;; Explicit callback URL (recommended behind proxy); nil = auto-detect from headers
          :scopes "openid profile email"
          :role-claim nil         ;; e.g., "realm_access.roles" (dot-separated path)
          :role-mapping {}        ;; e.g., {"chengis-admin" "admin", "chengis-dev" "developer"}
          :default-role "viewer"
          :auto-create-users true ;; JIT provision users on first OIDC login
          :provider-name nil}     ;; Display name, e.g., "Okta" (auto-detected if nil)
   :multi-tenancy {:default-org-slug "default"  ;; Slug of the auto-created default org
                   :auto-assign-default true}   ;; Auto-assign new users to default org
   :approvals {:enabled true
               :default-timeout-minutes 1440
               :poll-interval-ms 5000}
   :templates {:enabled true
               :max-depth 3}
   :matrix {:max-combinations 25}
   :parallel-stages {:max-concurrent 4}
   :thread-pools {:build-executor-threads 8     ;; core.async thread pool for parallel steps
                  :max-parallel-steps 8          ;; max concurrent steps within a stage
                  :queue-processor-threads 1     ;; queue processor daemon threads
                  :subscriber-cleanup-interval-ms 1800000}  ;; 30 min
   :cache {:root "cache"
           :max-size-gb 10
           :retention-days 30}
   ;; Pipeline shared-library cache (CHG-FEAT-005 PR2). Resolved imports
   ;; clone into <cache-dir>/<library-name>/<sha>/... so cache entries are
   ;; immutable + addressable by commit. :cache-dir nil → falls back to
   ;; "target/library-cache" in dev / "/var/lib/chengis/libraries" in prod;
   ;; resolver namespace picks the default based on (production? cfg).
   :libraries {:cache-dir nil}
   :tracing {:sample-rate 1.0
             :retention-days 7}
   :analytics {:aggregation-interval-hours 6
               :retention-days 365}
   :cost-attribution {:default-cost-per-hour 1.0}
   :billing {:aggregation-interval-hours 1
             :retention-months 24}
   :github-app {:app-id nil
                :private-key nil
                :private-key-path nil
                :webhook-secret nil
                :client-id nil
                :client-secret nil
                :callback-url nil}
   :flaky-detection {:flakiness-threshold 0.15
                     :min-runs 5
                     :lookback-builds 30}
   :deduplication {:window-minutes 10}
   ;; Phase 6: Advanced SCM & Workflow
   :cron {:poll-interval-seconds 60
          :missed-run-threshold-minutes 10}
   :auto-merge {:require-all-checks true
                :merge-method "merge"        ;; "merge", "squash", or "rebase"
                :delete-branch-after false}
   ;; Phase 7: Supply Chain Security
   :sbom {:tool "syft"
          :formats ["cyclonedx-json"]
          :timeout-ms 300000}
   :container-scanning {:scanner "trivy"
                        :severity-threshold "high"
                        :timeout-ms 600000
                        :ignore-unfixed true}
   :opa {:eval-timeout-ms 10000
         :binary-path "opa"}
   :signing {:tool "cosign"
             :key-ref nil
             :timeout-ms 60000}
   ;; Phase 8: Enterprise Identity & Access
   :saml {:enabled false
          :sp-entity-id nil        ;; Service Provider entity ID
          :idp-metadata-url nil    ;; IdP metadata URL for auto-configuration
          :idp-sso-url nil         ;; IdP Single Sign-On URL
          :idp-certificate nil     ;; IdP X.509 certificate (PEM)
          :acs-url nil             ;; Assertion Consumer Service URL (auto-detect if nil)
          :role-attribute nil      ;; SAML attribute for role mapping
          :role-mapping {}         ;; e.g., {"Admin" "admin", "Developer" "developer"}
          :default-role "viewer"
          :auto-create-users true
          :provider-name nil}      ;; Display name, e.g., "Okta SAML"
   :ldap {:enabled false
          :host "ldap://localhost"
          :port 389
          :use-ssl false
          :bind-dn nil
          :bind-password nil
          :user-base-dn nil
          :user-filter "(uid={0})"
          :username-attribute "uid"
          :email-attribute "mail"
          :display-name-attribute "cn"
          :group-base-dn nil
          :group-filter "(member={0})"
          :role-mapping {}
          :default-role "viewer"
          :auto-create-users true
          :sync-interval-minutes 60}
   :mfa {:enforce-for-admins false}
   :secret-rotation {:check-interval-hours 6
                     :default-interval-days 90}
   ;; Branch-lifecycle event log (CHG-FEAT-003 PR6). The reconcile path
   ;; emits :created / :updated / :archived / :resurrected events into
   ;; the `branch_events` table; the retention sweep (engine.retention)
   ;; prunes events older than :retention-days. Defaults match the spec:
   ;; enabled in case-of-doubt, 90-day retention. Opt-OUT via
   ;; CHENGIS_BRANCH_EVENTS_ENABLED=false for tight-disk deployments.
   :branch-events {:enabled true
                   :retention-days 90}
   ;; Multibranch scheduler (CHG-FEAT-003 PR3): periodically reconcile
   ;; branches for jobs with :multibranch :enabled true. The scheduler
   ;; is gated by :feature-flags :multibranch-scheduler so it's no-op
   ;; until explicitly turned on; default interval matches the spec
   ;; (5 minutes — long enough to be polite to the SCM, short enough to
   ;; catch rename/delete events that miss the webhook path).
   :multibranch-scheduler {:interval-seconds 300}
   ;; CHG-FEAT-004 PR5: K8s pod-event poller default interval (seconds).
   ;; The poller itself is gated by :feature-flags :k8s-event-poller so it
   ;; only starts when explicitly turned on; 15s is a reasonable polling
   ;; cadence for surfacing pod-lifecycle events without hammering the K8s
   ;; events endpoint.
   :k8s-event-poller {:interval-seconds 15}
   :ha {:enabled false
        :leader-poll-ms 15000
        :instance-id nil}      ;; defaults to "standalone" if nil; set from K8s pod name via CHENGIS_HA_INSTANCE_ID
   :feature-flags {:policy-engine false
                   :artifact-checksums false
                   :compliance-reports false
                   :distributed-dispatch false
                   :persistent-agents true
                   :parallel-stage-execution false
                   :docker-layer-cache false
                   :artifact-cache false
                   :build-result-cache false
                   :resource-aware-scheduling false
                   :incremental-artifacts false
                   :build-deduplication false
                   ;; Phase 5: Observability & Analytics
                   :tracing false
                   :build-analytics false
                   :browser-notifications false
                   :cost-attribution false
                   :flaky-test-detection false
                   ;; Phase 6: Advanced SCM & Workflow
                   :pr-status-checks false
                   :branch-overrides false
                   :monorepo-filtering false
                   :build-dependencies false
                   :cron-scheduling false
                   :webhook-replay false
                   :auto-merge false
                   ;; Phase 7: Supply Chain Security
                   :slsa-provenance false
                   :sbom-generation false
                   :container-scanning false
                   :opa-policies false
                   :license-scanning false
                   :artifact-signing false
                   :regulatory-dashboards false
                   ;; Phase 8: Enterprise Identity & Access
                   :saml false
                   :ldap false
                   :fine-grained-rbac false
                   :mfa-totp false
                   :cross-org-sharing false
                   :cloud-secret-backends false
                   :secret-rotation false
                   ;; CHG-FEAT-003 PR3: multibranch scheduler loop. Defaults
                   ;; on — the loop is cheap when no jobs opt into
                   ;; :multibranch :enabled true (it iterates jobs and
                   ;; short-circuits non-multibranch ones), and the
                   ;; per-tick skip prevents pile-up on slow upstreams.
                   :multibranch-scheduler true
                   ;; CHG-FEAT-004 PR5: K8s pod-lifecycle event poller.
                   ;; Defaults OFF — the poller talks to a K8s API server
                   ;; and is opt-in even on K8s-deployed masters.
                   :k8s-event-poller false
                   ;; Phase 10: Scale & Performance
                   :chunked-log-storage false
                   :cursor-pagination false
                   :db-partitioning false
                   :read-replicas false
                   :agent-connection-pooling false
                   :event-bus-backpressure false
                   :multi-region false
                   ;; Phase 11: Deployment & Release Orchestration
                   :environment-definitions false
                   :release-management false
                   :artifact-promotion false
                   :deployment-strategies false
                   :deployment-execution false
                   :environment-health-checks false
                   :deployment-dashboard false
                   ;; Phase 13: Commercialization (Phase A)
                   :sse-server-side-isolation true
                   :per-org-webhooks false
                   ;; Phase 13: Commercialization (Phase B)
                   :per-org-rate-limiting false
                   :per-org-concurrency-quotas false
                   :billing-meters false
                   :github-app false
                   ;; Phase C: Reliability + Trust
                   :jwt-key-rotation false
                   :tenant-retention false
                   :slo-alerting false
                   :disaster-recovery false
                   ;; Phase D: Developer Experience + Growth
                   :stripe-billing false
                   :org-management-ui false
                   :api-documentation false
                   :billing-portal false
                   ;; Phase 12: Infrastructure-as-Code
                   :infrastructure-as-code false
                   :terraform-execution false
                   :pulumi-execution false
                   :cloudformation-execution false
                   :iac-state-management false
                   :iac-cost-estimation false
                   :iac-policy-enforcement false}
   ;; Phase 12: Infrastructure-as-Code
   :iac {:terraform {:binary-path "terraform"
                     :timeout-ms 600000
                     :auto-init true
                     :parallelism 10}
         :pulumi {:binary-path "pulumi"
                  :timeout-ms 600000
                  :backend-url nil}
         :cloudformation {:binary-path "aws"
                          :timeout-ms 900000
                          :region nil
                          :capabilities ["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]}
         :state {:lock-timeout-ms 300000
                 :max-state-size-bytes 10485760
                 :history-limit 50}
         :plan {:require-approval false
                :max-plan-size-bytes 5242880}}
   :stripe {:secret-key nil
            :publishable-key nil
            :webhook-secret nil}
   :backup {:directory "backups"
            :interval-hours 24
            :keep-count 7
            :auto-verify true}
   :slo {:evaluation-interval-minutes 5
         :violation-retention-days 90
         :max-slos-per-org 20}
   :pagination {:default-page-size 50
                :max-page-size 200}
   :log-streaming {:chunk-size 1000
                   :flush-interval-ms 500}
   :partitioning {:retention-months 12
                  :future-partitions 3}
   :event-bus {:buffer-size 8192
               :critical-timeout-ms 5000}
   :deployment {:health-check {:timeout-ms 300000
                               :interval-ms 10000
                               :retries 3}
                :rollback {:auto-on-health-failure false}
                :concurrent-limit 1}
   :policies {:evaluation-timeout-ms 5000}
   :log {:level :info
         :format :text
         :file nil}})

;; ---------------------------------------------------------------------------
;; Environment variable configuration
;; ---------------------------------------------------------------------------

(def ^:private env-key-map
  "Explicit mapping of environment variable names to config paths.
   Only variables listed here are recognized — no automatic wildcard scanning."
  {"CHENGIS_DATABASE_TYPE"                       [:database :type]
   "CHENGIS_DATABASE_PATH"                      [:database :path]
   "CHENGIS_DATABASE_HOST"                      [:database :host]
   "CHENGIS_DATABASE_PORT"                      [:database :port]
   "CHENGIS_DATABASE_NAME"                      [:database :dbname]
   "CHENGIS_DATABASE_USER"                      [:database :user]
   "CHENGIS_DATABASE_PASSWORD"                  [:database :password]
   "CHENGIS_DATABASE_POOL_MAX"                  [:database :pool :maximum-pool-size]
   "CHENGIS_WORKSPACE_ROOT"                     [:workspace :root]
   "CHENGIS_WORKSPACE_PER_BRANCH_ENABLED"       [:workspace :per-branch]
   "CHENGIS_WORKSPACE_BRANCH_RETENTION_DAYS"    [:workspace :branch-retention-days]
   "CHENGIS_ARTIFACTS_ROOT"                     [:artifacts :root]
   "CHENGIS_SERVER_PORT"                        [:server :port]
   "CHENGIS_SERVER_HOST"                        [:server :host]
   "CHENGIS_SERVER_WORKER_THREADS"              [:server :worker-threads]
   "CHENGIS_SERVER_QUEUE_SIZE"                  [:server :queue-size]
   "CHENGIS_SERVER_MAX_BODY"                    [:server :max-body]
   "CHENGIS_AUTH_ENABLED"                       [:auth :enabled]
   "CHENGIS_AUTH_JWT_SECRET"                    [:auth :jwt-secret]
   "CHENGIS_AUTH_SESSION_SECRET"                [:auth :session-secret]
   "CHENGIS_AUTH_SEED_ADMIN_PASSWORD"           [:auth :seed-admin-password]
   "CHENGIS_SECRETS_MASTER_KEY"                 [:secrets :master-key]
   "CHENGIS_SECRETS_BACKEND"                    [:secrets :backend]
   "CHENGIS_SECRETS_VAULT_URL"                  [:secrets :vault :url]
   "CHENGIS_SECRETS_VAULT_TOKEN"                [:secrets :vault :token]
   "CHENGIS_SECRETS_VAULT_MOUNT"                [:secrets :vault :mount]
   "CHENGIS_SECRETS_VAULT_PREFIX"               [:secrets :vault :prefix]
   "CHENGIS_DISTRIBUTED_ENABLED"                [:distributed :enabled]
   "CHENGIS_DISTRIBUTED_MODE"                   [:distributed :mode]
   "CHENGIS_DISTRIBUTED_AUTH_TOKEN"             [:distributed :auth-token]
   "CHENGIS_DISTRIBUTED_AUTH_SCHEME"            [:distributed :auth-scheme]
   "CHENGIS_DISTRIBUTED_AGENT_TLS_KEYSTORE"     [:distributed :agent :tls :keystore]
   "CHENGIS_DISTRIBUTED_AGENT_TLS_REQUIRE"      [:distributed :agent :tls :require-tls]
   "CHENGIS_DISTRIBUTED_DISPATCH_QUEUE_ENABLED" [:distributed :dispatch :queue-enabled]
   "CHENGIS_DISTRIBUTED_HEARTBEAT_TIMEOUT_MS"  [:distributed :heartbeat-timeout-ms]
   "CHENGIS_DISTRIBUTED_FALLBACK_LOCAL"        [:distributed :dispatch :fallback-local]
   ;; K8s provisioner (CHG-FEAT-004 PR2). All optional — the K8s provisioner
   ;; is registered only when a K8s API server is resolvable.
   "CHENGIS_K8S_NAMESPACE"                      [:distributed :k8s :namespace]
   "CHENGIS_K8S_AGENT_IMAGE"                    [:distributed :k8s :agent-image]
   "CHENGIS_K8S_AGENT_PORT"                     [:distributed :k8s :agent-port]
   "CHENGIS_K8S_AGENT_RESOURCE_CPU_REQUEST"     [:distributed :k8s :cpu-request]
   "CHENGIS_K8S_AGENT_RESOURCE_MEMORY_REQUEST"  [:distributed :k8s :memory-request]
   "CHENGIS_K8S_AGENT_RESOURCE_CPU_LIMIT"       [:distributed :k8s :cpu-limit]
   "CHENGIS_K8S_AGENT_RESOURCE_MEMORY_LIMIT"    [:distributed :k8s :memory-limit]
   "CHENGIS_K8S_POLL_INTERVAL_MS"               [:distributed :k8s :poll-interval-ms]
   "CHENGIS_K8S_POLL_TIMEOUT_MS"                [:distributed :k8s :poll-timeout-ms]
   "CHENGIS_K8S_API_SERVER"                     [:distributed :k8s :server]
   "CHENGIS_K8S_INSECURE_SKIP_TLS"              [:distributed :k8s :insecure?]
   ;; Queue-depth autoscaler (CHG-FEAT-004 PR3). All optional — disabled
   ;; by default; opt in via CHENGIS_K8S_AUTOSCALE_ENABLED=true.
   "CHENGIS_K8S_AUTOSCALE_ENABLED"              [:distributed :k8s :autoscale :enabled]
   "CHENGIS_K8S_AUTOSCALE_INTERVAL_SECONDS"     [:distributed :k8s :autoscale :interval-seconds]
   "CHENGIS_K8S_AUTOSCALE_THRESHOLD"            [:distributed :k8s :autoscale :threshold]
   "CHENGIS_K8S_AUTOSCALE_MAX_PER_TICK"         [:distributed :k8s :autoscale :max-per-tick]
   "CHENGIS_K8S_AUTOSCALE_MAX_PROVISIONED"      [:distributed :k8s :autoscale :max-provisioned]
   ;; Orphan-Job reconciliation (CHG-FEAT-004 PR4). All optional;
   ;; disabled by default. Enable via CHENGIS_K8S_ORPHAN_CLEANUP_ENABLED=true.
   "CHENGIS_K8S_ORPHAN_CLEANUP_ENABLED"           [:distributed :k8s :orphan-cleanup :enabled]
   "CHENGIS_K8S_ORPHAN_CLEANUP_INTERVAL_SECONDS"  [:distributed :k8s :orphan-cleanup :interval-seconds]
   "CHENGIS_K8S_ORPHAN_CLEANUP_GRACE_SECONDS"     [:distributed :k8s :orphan-cleanup :grace-seconds]
   ;; K8s pod-lifecycle observability (CHG-FEAT-004 PR5). Both default off
   ;; (the poller talks to a K8s API server and is opt-in even on K8s
   ;; deployments). When enabled, the poller surfaces pod-lifecycle events
   ;; to the SSE bus and increments k8s_pod_events_total{reason}.
   "CHENGIS_K8S_EVENT_POLLER_ENABLED"           [:feature-flags :k8s-event-poller]
   "CHENGIS_K8S_EVENT_POLLER_INTERVAL_SECONDS"  [:k8s-event-poller :interval-seconds]
   "CHENGIS_FEATURE_DISTRIBUTED_DISPATCH"      [:feature-flags :distributed-dispatch]
   "CHENGIS_FEATURE_PERSISTENT_AGENTS"         [:feature-flags :persistent-agents]
   "CHENGIS_HA_ENABLED"                        [:ha :enabled]
   "CHENGIS_HA_LEADER_POLL_MS"                 [:ha :leader-poll-ms]
   "CHENGIS_HA_INSTANCE_ID"                    [:ha :instance-id]
   "CHENGIS_METRICS_ENABLED"                    [:metrics :enabled]
   "CHENGIS_LOG_LEVEL"                          [:log :level]
   "CHENGIS_LOG_FORMAT"                         [:log :format]
   "CHENGIS_RATE_LIMIT_ENABLED"                 [:rate-limit :enabled]
   "CHENGIS_WEBHOOK_SECRET"                     [:webhook :secret]
   "CHENGIS_ENVIRONMENT"                        [:environment]
   "CHENGIS_HTTPS_ENABLED"                      [:https :enabled]
   "CHENGIS_RETENTION_ENABLED"                  [:retention :enabled]
   "CHENGIS_SCM_GITHUB_TOKEN"                   [:scm :github :token]
   "CHENGIS_SCM_GITLAB_TOKEN"                   [:scm :gitlab :token]
   "CHENGIS_NOTIFICATIONS_EMAIL_HOST"           [:notifications :email :host]
   "CHENGIS_NOTIFICATIONS_EMAIL_PORT"           [:notifications :email :port]
   "CHENGIS_NOTIFICATIONS_EMAIL_FROM"           [:notifications :email :from]
   "CHENGIS_NOTIFICATIONS_SLACK_DEFAULT_WEBHOOK" [:notifications :slack :default-webhook]
   "CHENGIS_MATRIX_MAX_COMBINATIONS"            [:matrix :max-combinations]
   "CHENGIS_OIDC_ENABLED"                       [:oidc :enabled]
   "CHENGIS_OIDC_ISSUER_URL"                    [:oidc :issuer-url]
   "CHENGIS_OIDC_CLIENT_ID"                     [:oidc :client-id]
   "CHENGIS_OIDC_CLIENT_SECRET"                 [:oidc :client-secret]
   "CHENGIS_OIDC_CALLBACK_URL"                  [:oidc :callback-url]
   "CHENGIS_OIDC_SCOPES"                        [:oidc :scopes]
   "CHENGIS_OIDC_ROLE_CLAIM"                    [:oidc :role-claim]
   "CHENGIS_OIDC_DEFAULT_ROLE"                  [:oidc :default-role]
   "CHENGIS_OIDC_AUTO_CREATE_USERS"             [:oidc :auto-create-users]
   "CHENGIS_OIDC_PROVIDER_NAME"                 [:oidc :provider-name]
   "CHENGIS_SECRETS_FALLBACK_TO_LOCAL"           [:secrets :fallback-to-local]
   "CHENGIS_FEATURE_POLICY_ENGINE"              [:feature-flags :policy-engine]
   "CHENGIS_FEATURE_ARTIFACT_CHECKSUMS"         [:feature-flags :artifact-checksums]
   "CHENGIS_FEATURE_COMPLIANCE_REPORTS"         [:feature-flags :compliance-reports]
   "CHENGIS_POLICIES_EVALUATION_TIMEOUT_MS"     [:policies :evaluation-timeout-ms]
   ;; Phase 4: Build Performance & Caching
   "CHENGIS_FEATURE_PARALLEL_STAGES"            [:feature-flags :parallel-stage-execution]
   "CHENGIS_PARALLEL_STAGES_MAX"                [:parallel-stages :max-concurrent]
   "CHENGIS_BUILD_EXECUTOR_THREADS"             [:thread-pools :build-executor-threads]
   "CHENGIS_BUILD_POOL"                         [:thread-pools :build-pool]
   "CHENGIS_MAX_PARALLEL_STEPS"                 [:thread-pools :max-parallel-steps]
   "CHENGIS_FEATURE_DOCKER_LAYER_CACHE"         [:feature-flags :docker-layer-cache]
   "CHENGIS_FEATURE_ARTIFACT_CACHE"             [:feature-flags :artifact-cache]
   "CHENGIS_CACHE_ROOT"                         [:cache :root]
   "CHENGIS_CACHE_MAX_SIZE_GB"                  [:cache :max-size-gb]
   "CHENGIS_CACHE_RETENTION_DAYS"               [:cache :retention-days]
   "CHENGIS_LIBRARY_CACHE_DIR"                   [:libraries :cache-dir]
   ;; PR3: when true, missing Chengisfile.lock.edn becomes a hard error
   ;; instead of a warning. Recommended for production deployments —
   ;; unpinned builds are non-reproducible by definition.
   "CHENGIS_LIBRARY_LOCKFILE_REQUIRED"           [:libraries :lockfile-required?]
   "CHENGIS_FEATURE_BUILD_RESULT_CACHE"         [:feature-flags :build-result-cache]
   "CHENGIS_FEATURE_RESOURCE_SCHEDULING"        [:feature-flags :resource-aware-scheduling]
   "CHENGIS_FEATURE_INCREMENTAL_ARTIFACTS"      [:feature-flags :incremental-artifacts]
   "CHENGIS_FEATURE_BUILD_DEDUP"                [:feature-flags :build-deduplication]
   "CHENGIS_DEDUP_WINDOW_MINUTES"               [:deduplication :window-minutes]
   ;; Phase 5: Observability & Analytics
   "CHENGIS_FEATURE_TRACING"                    [:feature-flags :tracing]
   "CHENGIS_TRACING_SAMPLE_RATE"                [:tracing :sample-rate]
   "CHENGIS_TRACING_RETENTION_DAYS"             [:tracing :retention-days]
   "CHENGIS_FEATURE_BUILD_ANALYTICS"            [:feature-flags :build-analytics]
   "CHENGIS_ANALYTICS_INTERVAL_HOURS"           [:analytics :aggregation-interval-hours]
   "CHENGIS_ANALYTICS_RETENTION_DAYS"           [:analytics :retention-days]
   "CHENGIS_FEATURE_BROWSER_NOTIFICATIONS"      [:feature-flags :browser-notifications]
   "CHENGIS_FEATURE_COST_ATTRIBUTION"           [:feature-flags :cost-attribution]
   "CHENGIS_COST_PER_HOUR"                      [:cost-attribution :default-cost-per-hour]
   "CHENGIS_FEATURE_FLAKY_TEST_DETECTION"       [:feature-flags :flaky-test-detection]
   "CHENGIS_FLAKY_THRESHOLD"                    [:flaky-detection :flakiness-threshold]
   "CHENGIS_FLAKY_MIN_RUNS"                     [:flaky-detection :min-runs]
   "CHENGIS_FLAKY_LOOKBACK_BUILDS"              [:flaky-detection :lookback-builds]
   ;; Phase 6: Advanced SCM & Workflow
   "CHENGIS_FEATURE_PR_STATUS_CHECKS"           [:feature-flags :pr-status-checks]
   "CHENGIS_FEATURE_BRANCH_OVERRIDES"           [:feature-flags :branch-overrides]
   "CHENGIS_FEATURE_MONOREPO_FILTERING"         [:feature-flags :monorepo-filtering]
   "CHENGIS_FEATURE_BUILD_DEPENDENCIES"         [:feature-flags :build-dependencies]
   "CHENGIS_FEATURE_CRON_SCHEDULING"            [:feature-flags :cron-scheduling]
   "CHENGIS_FEATURE_WEBHOOK_REPLAY"             [:feature-flags :webhook-replay]
   "CHENGIS_FEATURE_AUTO_MERGE"                 [:feature-flags :auto-merge]
   "CHENGIS_CRON_POLL_INTERVAL_SECONDS"         [:cron :poll-interval-seconds]
   "CHENGIS_CRON_MISSED_RUN_THRESHOLD_MINUTES"  [:cron :missed-run-threshold-minutes]
   "CHENGIS_AUTO_MERGE_METHOD"                  [:auto-merge :merge-method]
   "CHENGIS_AUTO_MERGE_DELETE_BRANCH"           [:auto-merge :delete-branch-after]
   "CHENGIS_SCM_GITEA_TOKEN"                    [:scm :gitea :token]
   "CHENGIS_SCM_GITEA_BASE_URL"                 [:scm :gitea :base-url]
   "CHENGIS_SCM_BITBUCKET_USERNAME"             [:scm :bitbucket :username]
   "CHENGIS_SCM_BITBUCKET_APP_PASSWORD"         [:scm :bitbucket :app-password]
   ;; Phase 7: Supply Chain Security
   "CHENGIS_FEATURE_SLSA_PROVENANCE"            [:feature-flags :slsa-provenance]
   "CHENGIS_FEATURE_SBOM_GENERATION"            [:feature-flags :sbom-generation]
   "CHENGIS_FEATURE_CONTAINER_SCANNING"         [:feature-flags :container-scanning]
   "CHENGIS_FEATURE_OPA_POLICIES"               [:feature-flags :opa-policies]
   "CHENGIS_FEATURE_LICENSE_SCANNING"            [:feature-flags :license-scanning]
   "CHENGIS_FEATURE_ARTIFACT_SIGNING"            [:feature-flags :artifact-signing]
   "CHENGIS_FEATURE_REGULATORY_DASHBOARDS"       [:feature-flags :regulatory-dashboards]
   "CHENGIS_SBOM_TOOL"                           [:sbom :tool]
   "CHENGIS_SBOM_TIMEOUT_MS"                     [:sbom :timeout-ms]
   "CHENGIS_SCANNING_SCANNER"                    [:container-scanning :scanner]
   "CHENGIS_SCANNING_SEVERITY_THRESHOLD"         [:container-scanning :severity-threshold]
   "CHENGIS_SIGNING_TOOL"                        [:signing :tool]
   "CHENGIS_SIGNING_KEY_REF"                     [:signing :key-ref]
   "CHENGIS_OPA_BINARY_PATH"                     [:opa :binary-path]
   ;; Phase 8: Enterprise Identity & Access
   "CHENGIS_FEATURE_SAML"                         [:feature-flags :saml]
   "CHENGIS_FEATURE_LDAP"                         [:feature-flags :ldap]
   "CHENGIS_FEATURE_FINE_GRAINED_RBAC"            [:feature-flags :fine-grained-rbac]
   "CHENGIS_FEATURE_MFA_TOTP"                     [:feature-flags :mfa-totp]
   "CHENGIS_FEATURE_CROSS_ORG_SHARING"            [:feature-flags :cross-org-sharing]
   "CHENGIS_FEATURE_CLOUD_SECRET_BACKENDS"        [:feature-flags :cloud-secret-backends]
   "CHENGIS_FEATURE_SECRET_ROTATION"              [:feature-flags :secret-rotation]
   "CHENGIS_SAML_ENABLED"                         [:saml :enabled]
   "CHENGIS_SAML_SP_ENTITY_ID"                    [:saml :sp-entity-id]
   "CHENGIS_SAML_IDP_SSO_URL"                     [:saml :idp-sso-url]
   "CHENGIS_SAML_IDP_CERTIFICATE"                 [:saml :idp-certificate]
   "CHENGIS_SAML_ACS_URL"                         [:saml :acs-url]
   "CHENGIS_SAML_ROLE_ATTRIBUTE"                  [:saml :role-attribute]
   "CHENGIS_SAML_DEFAULT_ROLE"                    [:saml :default-role]
   "CHENGIS_SAML_PROVIDER_NAME"                   [:saml :provider-name]
   "CHENGIS_LDAP_ENABLED"                         [:ldap :enabled]
   "CHENGIS_LDAP_HOST"                            [:ldap :host]
   "CHENGIS_LDAP_PORT"                            [:ldap :port]
   "CHENGIS_LDAP_USE_SSL"                         [:ldap :use-ssl]
   "CHENGIS_LDAP_BIND_DN"                         [:ldap :bind-dn]
   "CHENGIS_LDAP_BIND_PASSWORD"                   [:ldap :bind-password]
   "CHENGIS_LDAP_USER_BASE_DN"                    [:ldap :user-base-dn]
   "CHENGIS_LDAP_USER_FILTER"                     [:ldap :user-filter]
   "CHENGIS_LDAP_DEFAULT_ROLE"                    [:ldap :default-role]
   "CHENGIS_SECRET_ROTATION_INTERVAL_HOURS"       [:secret-rotation :check-interval-hours]
   ;; CHG-FEAT-003 PR3 — multibranch scheduler
   "CHENGIS_MULTIBRANCH_SCHEDULER_ENABLED"        [:feature-flags :multibranch-scheduler]
   "CHENGIS_MULTIBRANCH_SCHEDULER_INTERVAL_SECONDS" [:multibranch-scheduler :interval-seconds]
   ;; CHG-FEAT-003 PR6 — branch-lifecycle event log
   "CHENGIS_BRANCH_EVENTS_ENABLED"                [:branch-events :enabled]
   "CHENGIS_BRANCH_EVENTS_RETENTION_DAYS"         [:branch-events :retention-days]
   ;; Phase 10: Scale & Performance
   "CHENGIS_FEATURE_CHUNKED_LOG_STORAGE"        [:feature-flags :chunked-log-storage]
   "CHENGIS_FEATURE_CURSOR_PAGINATION"           [:feature-flags :cursor-pagination]
   "CHENGIS_FEATURE_DB_PARTITIONING"             [:feature-flags :db-partitioning]
   "CHENGIS_FEATURE_READ_REPLICAS"               [:feature-flags :read-replicas]
   "CHENGIS_FEATURE_AGENT_CONNECTION_POOLING"    [:feature-flags :agent-connection-pooling]
   "CHENGIS_FEATURE_EVENT_BUS_BACKPRESSURE"      [:feature-flags :event-bus-backpressure]
   "CHENGIS_FEATURE_MULTI_REGION"                [:feature-flags :multi-region]
   "CHENGIS_PAGINATION_DEFAULT_PAGE_SIZE"        [:pagination :default-page-size]
   "CHENGIS_PAGINATION_MAX_PAGE_SIZE"            [:pagination :max-page-size]
   "CHENGIS_LOG_STREAMING_CHUNK_SIZE"            [:log-streaming :chunk-size]
   "CHENGIS_LOG_STREAMING_FLUSH_INTERVAL_MS"     [:log-streaming :flush-interval-ms]
   "CHENGIS_PARTITIONING_RETENTION_MONTHS"       [:partitioning :retention-months]
   "CHENGIS_PARTITIONING_FUTURE_PARTITIONS"      [:partitioning :future-partitions]
   "CHENGIS_DATABASE_REPLICA_HOST"               [:database :replica :host]
   "CHENGIS_DATABASE_REPLICA_PORT"               [:database :replica :port]
   "CHENGIS_DATABASE_REPLICA_DBNAME"             [:database :replica :dbname]
   "CHENGIS_DATABASE_REPLICA_USER"               [:database :replica :user]
   "CHENGIS_DATABASE_REPLICA_PASSWORD"           [:database :replica :password]
   "CHENGIS_DATABASE_REPLICA_POOL_SIZE"          [:database :replica :pool :maximum-pool-size]
   "CHENGIS_AGENT_POOL_CONNECTIONS_PER_AGENT"    [:distributed :connection-pool :max-connections-per-agent]
   "CHENGIS_AGENT_POOL_KEEPALIVE_MS"             [:distributed :connection-pool :keepalive-ms]
   "CHENGIS_EVENT_BUS_BUFFER_SIZE"               [:event-bus :buffer-size]
   "CHENGIS_EVENT_BUS_CRITICAL_TIMEOUT_MS"       [:event-bus :critical-timeout-ms]
   "CHENGIS_DISTRIBUTED_REGION"                  [:distributed :region]
   "CHENGIS_REGION_LOCALITY_WEIGHT"              [:distributed :region-locality-weight]
   ;; Phase 11: Deployment & Release Orchestration
   "CHENGIS_FEATURE_ENVIRONMENT_DEFINITIONS"     [:feature-flags :environment-definitions]
   "CHENGIS_FEATURE_RELEASE_MANAGEMENT"          [:feature-flags :release-management]
   "CHENGIS_FEATURE_ARTIFACT_PROMOTION"          [:feature-flags :artifact-promotion]
   "CHENGIS_FEATURE_DEPLOYMENT_STRATEGIES"       [:feature-flags :deployment-strategies]
   "CHENGIS_FEATURE_DEPLOYMENT_EXECUTION"        [:feature-flags :deployment-execution]
   "CHENGIS_FEATURE_ENVIRONMENT_HEALTH_CHECKS"   [:feature-flags :environment-health-checks]
   "CHENGIS_FEATURE_DEPLOYMENT_DASHBOARD"        [:feature-flags :deployment-dashboard]
   "CHENGIS_DEPLOYMENT_HEALTH_CHECK_TIMEOUT_MS"  [:deployment :health-check :timeout-ms]
   "CHENGIS_DEPLOYMENT_HEALTH_CHECK_INTERVAL_MS" [:deployment :health-check :interval-ms]
   "CHENGIS_DEPLOYMENT_HEALTH_CHECK_RETRIES"     [:deployment :health-check :retries]
   "CHENGIS_DEPLOYMENT_AUTO_ROLLBACK"            [:deployment :rollback :auto-on-health-failure]
   "CHENGIS_DEPLOYMENT_CONCURRENT_LIMIT"         [:deployment :concurrent-limit]
   ;; Phase 12: Infrastructure-as-Code
   "CHENGIS_FEATURE_INFRASTRUCTURE_AS_CODE"      [:feature-flags :infrastructure-as-code]
   "CHENGIS_FEATURE_TERRAFORM_EXECUTION"         [:feature-flags :terraform-execution]
   "CHENGIS_FEATURE_PULUMI_EXECUTION"            [:feature-flags :pulumi-execution]
   "CHENGIS_FEATURE_CLOUDFORMATION_EXECUTION"    [:feature-flags :cloudformation-execution]
   "CHENGIS_FEATURE_IAC_STATE_MANAGEMENT"        [:feature-flags :iac-state-management]
   "CHENGIS_FEATURE_IAC_COST_ESTIMATION"         [:feature-flags :iac-cost-estimation]
   "CHENGIS_FEATURE_IAC_POLICY_ENFORCEMENT"      [:feature-flags :iac-policy-enforcement]
   ;; Phase 13: Commercialization (Phase A)
   "CHENGIS_FEATURE_SSE_SERVER_SIDE_ISOLATION"   [:feature-flags :sse-server-side-isolation]
   "CHENGIS_FEATURE_PER_ORG_WEBHOOKS"            [:feature-flags :per-org-webhooks]
   ;; Phase 13: Commercialization (Phase B)
   "CHENGIS_FEATURE_PER_ORG_RATE_LIMITING"       [:feature-flags :per-org-rate-limiting]
   "CHENGIS_FEATURE_PER_ORG_CONCURRENCY_QUOTAS"  [:feature-flags :per-org-concurrency-quotas]
   "CHENGIS_FEATURE_BILLING_METERS"              [:feature-flags :billing-meters]
   "CHENGIS_FEATURE_GITHUB_APP"                  [:feature-flags :github-app]
   "CHENGIS_ORG_QUOTAS_DEFAULT_API_RPM"          [:org-quotas :default-api-rpm]
   "CHENGIS_ORG_QUOTAS_DEFAULT_WEBHOOK_RPM"      [:org-quotas :default-webhook-rpm]
   "CHENGIS_ORG_QUOTAS_DEFAULT_MAX_CONCURRENT"   [:org-quotas :default-max-concurrent-builds]
   "CHENGIS_ORG_QUOTAS_DEFAULT_MAX_PER_HOUR"     [:org-quotas :default-max-builds-per-hour]
   "CHENGIS_BILLING_AGGREGATION_INTERVAL_HOURS"  [:billing :aggregation-interval-hours]
   "CHENGIS_BILLING_RETENTION_MONTHS"            [:billing :retention-months]
   "CHENGIS_GITHUB_APP_ID"                       [:github-app :app-id]
   "CHENGIS_GITHUB_APP_PRIVATE_KEY"              [:github-app :private-key]
   "CHENGIS_GITHUB_APP_PRIVATE_KEY_PATH"         [:github-app :private-key-path]
   "CHENGIS_GITHUB_APP_WEBHOOK_SECRET"           [:github-app :webhook-secret]
   "CHENGIS_GITHUB_APP_CLIENT_ID"                [:github-app :client-id]
   "CHENGIS_GITHUB_APP_CLIENT_SECRET"            [:github-app :client-secret]
   "CHENGIS_GITHUB_APP_CALLBACK_URL"             [:github-app :callback-url]
   "CHENGIS_IAC_TERRAFORM_BINARY"                [:iac :terraform :binary-path]
   "CHENGIS_IAC_TERRAFORM_TIMEOUT_MS"            [:iac :terraform :timeout-ms]
   "CHENGIS_IAC_TERRAFORM_AUTO_INIT"             [:iac :terraform :auto-init]
   "CHENGIS_IAC_TERRAFORM_PARALLELISM"           [:iac :terraform :parallelism]
   "CHENGIS_IAC_PULUMI_BINARY"                   [:iac :pulumi :binary-path]
   "CHENGIS_IAC_PULUMI_TIMEOUT_MS"               [:iac :pulumi :timeout-ms]
   "CHENGIS_IAC_PULUMI_BACKEND_URL"              [:iac :pulumi :backend-url]
   "CHENGIS_IAC_CLOUDFORMATION_BINARY"           [:iac :cloudformation :binary-path]
   "CHENGIS_IAC_CLOUDFORMATION_TIMEOUT_MS"       [:iac :cloudformation :timeout-ms]
   "CHENGIS_IAC_CLOUDFORMATION_REGION"           [:iac :cloudformation :region]
   "CHENGIS_IAC_STATE_LOCK_TIMEOUT_MS"           [:iac :state :lock-timeout-ms]
   "CHENGIS_IAC_PLAN_REQUIRE_APPROVAL"           [:iac :plan :require-approval]
   ;; Phase C: Reliability + Trust
   "CHENGIS_FEATURE_JWT_KEY_ROTATION"          [:feature-flags :jwt-key-rotation]
   "CHENGIS_FEATURE_TENANT_RETENTION"          [:feature-flags :tenant-retention]
   "CHENGIS_FEATURE_SLO_ALERTING"              [:feature-flags :slo-alerting]
   "CHENGIS_FEATURE_DISASTER_RECOVERY"         [:feature-flags :disaster-recovery]
   "CHENGIS_AUTH_JWT_ROTATION_HOURS"            [:auth :jwt-rotation-hours]
   "CHENGIS_BACKUP_DIRECTORY"                  [:backup :directory]
   "CHENGIS_BACKUP_INTERVAL_HOURS"             [:backup :interval-hours]
   "CHENGIS_BACKUP_KEEP_COUNT"                 [:backup :keep-count]
   "CHENGIS_BACKUP_AUTO_VERIFY"                [:backup :auto-verify]
   "CHENGIS_SLO_EVALUATION_INTERVAL_MINUTES"   [:slo :evaluation-interval-minutes]
   "CHENGIS_SLO_VIOLATION_RETENTION_DAYS"      [:slo :violation-retention-days]
   ;; Phase D: Developer Experience + Growth
   "CHENGIS_FEATURE_STRIPE_BILLING"            [:feature-flags :stripe-billing]
   "CHENGIS_FEATURE_ORG_MANAGEMENT_UI"         [:feature-flags :org-management-ui]
   "CHENGIS_FEATURE_API_DOCUMENTATION"         [:feature-flags :api-documentation]
   "CHENGIS_FEATURE_BILLING_PORTAL"            [:feature-flags :billing-portal]
   "CHENGIS_STRIPE_SECRET_KEY"                 [:stripe :secret-key]
   "CHENGIS_STRIPE_PUBLISHABLE_KEY"            [:stripe :publishable-key]
   "CHENGIS_STRIPE_WEBHOOK_SECRET"             [:stripe :webhook-secret]})

(defn coerce-env-value
  "Coerce a string environment variable value to the appropriate type.
   Rules: \"true\"/\"false\" → boolean, pure digits → long,
   colon-prefixed → keyword, else string."
  [v]
  (cond
    (= v "true")  true
    (= v "false") false
    (re-matches #"\d+" v) (Long/parseLong v)
    (str/starts-with? v ":") (keyword (subs v 1))
    :else v))

(defn load-env-overrides
  "Read CHENGIS_* environment variables and return a partial config map.
   Only variables in env-key-map are recognized. Values are type-coerced."
  ([]
   (load-env-overrides (fn [k] (System/getenv k))))
  ([env-fn]
   (reduce-kv (fn [m env-key config-path]
                (if-let [v (env-fn env-key)]
                  (assoc-in m config-path (coerce-env-value v))
                  m))
              {} env-key-map)))

(defn deep-merge
  "Recursively merge maps. When both values are maps, merge them.
   Otherwise the later value wins. nil values are ignored."
  [& maps]
  (reduce (fn [result m]
            (if (nil? m)
              result
              (reduce-kv (fn [acc k v]
                           (let [existing (get acc k)]
                             (if (and (map? existing) (map? v))
                               (assoc acc k (deep-merge existing v))
                               (assoc acc k v))))
                         result m)))
          {} maps))

(defn load-config
  "Load configuration from config.edn on the classpath, merged with defaults,
   then overlaid with CHENGIS_* environment variables.
   Precedence: env vars > config.edn > defaults."
  ([]
   (let [file-config (when-let [resource (io/resource "config.edn")]
                       (edn/read-string {:readers {}} (slurp resource)))
         env-config (load-env-overrides)]
     (deep-merge default-config file-config env-config)))
  ([path]
   (let [file-config (edn/read-string {:readers {}} (slurp path))
         env-config (load-env-overrides)]
     (deep-merge default-config file-config env-config))))

(defn resolve-path
  "Resolve a potentially relative path against a base directory."
  [base path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getAbsolutePath f)
      (.getAbsolutePath (io/file base path)))))

(defn warn-insecure-defaults
  "Check a loaded config for insecure default values and print warnings.
   Called at server startup to alert operators of production risks.
   Warns about: auth disabled, default admin password, missing JWT secret,
   missing webhook secret, missing distributed auth token."
  [cfg]
  ;; CRITICAL: Warn when auth is entirely disabled (everyone is admin)
  (when-not (get-in cfg [:auth :enabled])
    (log/error "╔══════════════════════════════════════════════════════════════╗")
    (log/error "║  SECURITY WARNING: Authentication is DISABLED.              ║")
    (log/error "║  All users have unrestricted admin access.                  ║")
    (log/error "║  Set CHENGIS_AUTH_ENABLED=true for production use.          ║")
    (log/error "╚══════════════════════════════════════════════════════════════╝"))
  ;; Warn about insecure defaults when auth IS enabled
  (when (get-in cfg [:auth :enabled])
    (when (= "admin" (get-in cfg [:auth :seed-admin-password]))
      (log/warn "[SECURITY] Using default admin password 'admin'. Set CHENGIS_AUTH_SEED_ADMIN_PASSWORD."))
    (when (str/blank? (str (get-in cfg [:auth :jwt-secret])))
      (log/warn "[SECURITY] No JWT secret configured. Set CHENGIS_AUTH_JWT_SECRET for production.")))
  ;; Warn about missing distributed auth token
  (when (and (get-in cfg [:distributed :enabled])
             (str/blank? (str (get-in cfg [:distributed :auth-token]))))
    (log/error "[SECURITY] Distributed mode enabled but auth-token is not set. Agent communication is unauthenticated! Set CHENGIS_DISTRIBUTED_AUTH_TOKEN."))
  ;; Warn about missing webhook secret
  (when-not (get-in cfg [:webhook :secret])
    (log/warn "[SECURITY] Webhook secret not configured. All webhook requests will be rejected. Set CHENGIS_WEBHOOK_SECRET."))
  cfg)

(defn production?
  "Returns true if the environment is set to production."
  [cfg]
  (= "production" (:environment cfg)))

(declare postgresql?)

(defn validate-runtime-config!
  "Validate non-optional runtime configuration regardless of environment.
   Throws ex-info for invalid settings.

   Includes the product-vs-DB fit gate
   (`chengis.product/validate-database-fit!`): off-preferred combos
   (anvil + Postgres, chengis + SQLite) log a `[product-fit]` warning
   by default and throw under strict mode (CHENGIS_STRICT_PROFILE=true).
   Also resolves the effective capability set for subsystem queries.
   If no profile has been declared (e.g. test runner or REPL), both
   the fit check and the capability resolve are no-ops."
  [cfg]
  (when (and (get-in cfg [:distributed :enabled])
             (str/blank? (get-in cfg [:distributed :auth-token])))
    (throw (ex-info "Distributed mode enabled but :distributed :auth-token is not set. Set CHENGIS_DISTRIBUTED_AUTH_TOKEN or disable distributed mode."
                    {:type :config-validation-error
                     :field [:distributed :auth-token]})))
  ;; Discard the fit-report return value — the function logs / throws
  ;; as a side effect; existing callers (and their tests) expect a
  ;; nil-return contract from validate-runtime-config!.
  (product/validate-database-fit! cfg)
  ;; Resolve the effective capability set for the active profile and
  ;; stash it for subsystem queries (has-capability? / require-capability!).
  ;; No-op (returns nil) when no profile has been declared — tests and
  ;; ad-hoc scripts don't get a capability surface either.
  (when-some [p (product/profile)]
    (capability/set-active! cfg p)
    ;; Hard-reject capabilities whose :requires-database doesn't match
    ;; the configured DB type. Unlike the off-preferred-DB warn, this
    ;; is a structural correctness check — audit-chain literally
    ;; cannot function on SQLite, regardless of strict mode.
    (capability/validate-capability-requirements! cfg p))
  nil)

(defn validate-production-config!
  "Validate that production-critical security settings are properly configured.
   Throws ex-info when environment=production and any critical setting is missing.
   In development mode, these are only warnings (handled by warn-insecure-defaults).
   Call after validate-config! in server startup."
  [cfg]
  (when (production? cfg)
    (when-not (get-in cfg [:auth :enabled])
      (throw (ex-info "PRODUCTION FATAL: Authentication is disabled. Set CHENGIS_AUTH_ENABLED=true or switch to development mode."
                      {:type :config-validation-error :field [:auth :enabled]})))
    (when (str/blank? (str (get-in cfg [:auth :jwt-secret])))
      (throw (ex-info "PRODUCTION FATAL: JWT secret is not configured. Set CHENGIS_AUTH_JWT_SECRET."
                      {:type :config-validation-error :field [:auth :jwt-secret]})))
    (when (= "admin" (get-in cfg [:auth :seed-admin-password]))
      (throw (ex-info "PRODUCTION FATAL: Admin password is still the default 'admin'. Set CHENGIS_AUTH_SEED_ADMIN_PASSWORD."
                      {:type :config-validation-error :field [:auth :seed-admin-password]})))
    (when-not (get-in cfg [:webhook :secret])
      (throw (ex-info "PRODUCTION FATAL: Webhook secret is not configured. Set CHENGIS_WEBHOOK_SECRET."
                      {:type :config-validation-error :field [:webhook :secret]})))
    (when (str/blank? (str (get-in cfg [:secrets :master-key])))
      (throw (ex-info "PRODUCTION FATAL: Secrets master key is not configured. Set CHENGIS_SECRETS_MASTER_KEY."
                      {:type :config-validation-error :field [:secrets :master-key]})))
    (when-not (postgresql? cfg)
      (throw (ex-info "PRODUCTION FATAL: Production requires PostgreSQL. Set :database :type to 'postgresql'."
                      {:type :config-validation-error :field [:database :type]})))))

(defn sqlite?
  "Returns true if the database config specifies SQLite (the default)."
  [cfg]
  (not= "postgresql" (get-in cfg [:database :type])))

(defn postgresql?
  "Returns true if the database config specifies PostgreSQL."
  [cfg]
  (= "postgresql" (get-in cfg [:database :type])))
