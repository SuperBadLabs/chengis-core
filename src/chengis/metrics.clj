(ns chengis.metrics
  "Prometheus metrics registry and recording helpers.
   All record-* functions accept a registry as the first argument
   and no-op when nil, making metrics zero-overhead when disabled."
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm-collector]
            [iapetos.export :as export]
            [taoensso.timbre :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; ---------------------------------------------------------------------------
;; Registry initialization
;; ---------------------------------------------------------------------------

(defn init-registry
  "Create and return a Prometheus collector registry with all metrics registered.
   Includes JVM metrics (heap, GC, threads) plus custom application metrics."
  []
  (log/info "Initializing Prometheus metrics registry")
  (-> (prometheus/collector-registry)
      ;; JVM metrics — heap, GC, threads, classloader
      (jvm-collector/initialize)

      ;; HTTP metrics
      (prometheus/register
       (prometheus/histogram :http/request-duration-seconds
                             {:description "HTTP request duration in seconds"
                              :labels [:method :path :status]
                              :buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0]}))
      (prometheus/register
       (prometheus/counter :http/requests-total
                           {:description "Total HTTP requests"
                            :labels [:method :path :status]}))

      ;; Build metrics
      (prometheus/register
       (prometheus/gauge :builds/active
                         {:description "Currently executing builds"}))
      (prometheus/register
       (prometheus/counter :builds/total
                           {:description "Total builds completed"
                            :labels [:status]}))
      (prometheus/register
       (prometheus/histogram :builds/duration-seconds
                             {:description "Build wall-clock duration in seconds"
                              :labels [:status]
                              :buckets [1.0 5.0 10.0 30.0 60.0 120.0 300.0 600.0]}))

      ;; Stage/Step metrics
      (prometheus/register
       (prometheus/histogram :stages/duration-seconds
                             {:description "Pipeline stage duration in seconds"
                              :labels [:stage-name :status]
                              :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0 120.0]}))
      (prometheus/register
       (prometheus/histogram :steps/duration-seconds
                             {:description "Pipeline step duration in seconds"
                              :labels [:step-name :status]
                              :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0 120.0]}))

      ;; Event bus metrics
      (prometheus/register
       (prometheus/counter :events/published-total
                           {:description "Total events published to event bus"}))
      (prometheus/register
       (prometheus/counter :events/overflow-total
                           {:description "Events dropped due to channel overflow"}))

      ;; Dispatch metrics (Phase 3 — distributed queue)
      (prometheus/register
       (prometheus/gauge :queue/depth
                         {:description "Pending builds in dispatch queue"}))
      (prometheus/register
       (prometheus/gauge :queue/oldest-pending-seconds
                         {:description "Age of oldest pending queue item in seconds"}))
      (prometheus/register
       (prometheus/counter :dispatch/total
                           {:description "Build dispatch attempts"
                            :labels [:result]}))
      (prometheus/register
       (prometheus/counter :dispatch/orphans-recovered-total
                           {:description "Orphaned builds recovered from dead agents"}))
      (prometheus/register
       (prometheus/gauge :agents/circuit-breaker-open
                         {:description "Number of agents with open circuit breakers"}))
      (prometheus/register
       (prometheus/counter :artifacts/transferred-total
                           {:description "Artifact transfers from agents"
                            :labels [:result]}))
      (prometheus/register
       (prometheus/gauge :agents/utilization-ratio
                         {:description "Active builds / total capacity across all agents"}))

      ;; Auth metrics
      (prometheus/register
       (prometheus/counter :auth/login-total
                           {:description "Login attempts"
                            :labels [:result]}))
      (prometheus/register
       (prometheus/counter :auth/token-auth-total
                           {:description "API token authentication attempts"
                            :labels [:result]}))
      (prometheus/register
       (prometheus/gauge :db/pool-active-connections
                         {:description "Active DB pool connections"}))
      (prometheus/register
       (prometheus/gauge :db/pool-idle-connections
                         {:description "Idle DB pool connections"}))
      (prometheus/register
       (prometheus/gauge :db/pool-pending-threads
                         {:description "Threads waiting for a DB connection"}))
      (prometheus/register
       (prometheus/gauge :db/pool-total-connections
                         {:description "Total DB pool connections"}))
      (prometheus/register
       (prometheus/gauge :db/pool-max-connections
                         {:description "Configured max DB pool size"}))

      ;; Phase 4: Rate limiting metrics
      (prometheus/register
       (prometheus/counter :rate-limit/rejected-total
                           {:description "Requests rejected by rate limiter"
                            :labels [:endpoint-type]}))
      ;; Phase B: Per-org rate limiting metrics
      (prometheus/register
       (prometheus/counter :org-rate-limit/rejected-total
                           {:description "Requests rejected by per-org rate limiter"
                            :labels [:org-id :endpoint-type]}))

      ;; Phase 4: Webhook metrics
      (prometheus/register
       (prometheus/counter :webhooks/received-total
                           {:description "Webhook events received"
                            :labels [:provider :status]}))
      (prometheus/register
       (prometheus/histogram :webhooks/processing-seconds
                             {:description "Webhook processing duration in seconds"
                              :buckets [0.01 0.05 0.1 0.25 0.5 1.0 2.5 5.0]}))

      ;; Phase 4: Token management metrics
      (prometheus/register
       (prometheus/counter :tokens/generated-total
                           {:description "API tokens generated"}))
      (prometheus/register
       (prometheus/counter :tokens/revoked-total
                           {:description "API tokens revoked"}))

      ;; Phase 4: Retention metrics
      (prometheus/register
       (prometheus/counter :retention/cleaned-total
                           {:description "Records cleaned by retention scheduler"
                            :labels [:resource-type]}))

      ;; Phase 4: Secret access metrics
      (prometheus/register
       (prometheus/counter :secrets/access-total
                           {:description "Secret access events"
                            :labels [:action]}))

      ;; Phase 5: Account lockout metrics
      (prometheus/register
       (prometheus/counter :auth/account-lockouts-total
                           {:description "Account lockout events"}))

      ;; Phase 5: Approval gate metrics
      (prometheus/register
       (prometheus/counter :approvals/requested-total
                           {:description "Approval gates created"}))
      (prometheus/register
       (prometheus/counter :approvals/resolved-total
                           {:description "Approval gates resolved"
                            :labels [:result]}))

      ;; Phase 5: SCM status check metrics
      (prometheus/register
       (prometheus/counter :scm/status-reports-total
                           {:description "SCM status reports sent"
                            :labels [:provider :result]}))

      ;; Phase 6: Artifact integrity metrics
      (prometheus/register
       (prometheus/counter :artifacts/checksum-verified-total
                           {:description "Artifact checksum verifications"
                            :labels [:result]}))

      ;; Phase 6: Compliance reporting metrics
      (prometheus/register
       (prometheus/counter :compliance/reports-generated-total
                           {:description "Compliance reports generated"
                            :labels [:report-type]}))
      (prometheus/register
       (prometheus/counter :compliance/hash-chain-verifications-total
                           {:description "Hash chain integrity verifications"
                            :labels [:result]}))

      ;; Phase 6: Policy engine metrics
      (prometheus/register
       (prometheus/counter :policies/evaluated-total
                           {:description "Policy evaluations"
                            :labels [:policy-type :result]}))
      (prometheus/register
       (prometheus/counter :policies/denied-total
                           {:description "Builds/stages blocked by policy"
                            :labels [:policy-type]}))
      (prometheus/register
       (prometheus/histogram :policies/evaluation-duration-seconds
                             {:description "Policy evaluation duration"
                              :buckets [0.001 0.005 0.01 0.025 0.05 0.1 0.25]}))

      ;; Phase 5 (Observability): Tracing metrics
      (prometheus/register
       (prometheus/counter :tracing/spans-created-total
                           {:description "Total trace spans created"}))
      (prometheus/register
       (prometheus/histogram :tracing/span-duration-seconds
                             {:description "Trace span duration in seconds"
                              :buckets [0.001 0.005 0.01 0.05 0.1 0.5 1.0 5.0 30.0 60.0 300.0]}))

      ;; Phase C: Backup metrics
      (prometheus/register
       (prometheus/counter :backups/total
                           {:description "Total backups created"
                            :labels [:trigger :status]}))
      (prometheus/register
       (prometheus/gauge :backups/last-size-bytes
                         {:description "Size of last backup in bytes"}))
      (prometheus/register
       (prometheus/histogram :backups/duration-seconds
                             {:description "Backup creation duration in seconds"
                              :buckets [1.0 5.0 10.0 30.0 60.0 120.0 300.0]}))

      ;; Phase C: SLO metrics
      (prometheus/register
       (prometheus/counter :slo/evaluations-total
                           {:description "Total SLO evaluations performed"
                            :labels [:metric]}))
      (prometheus/register
       (prometheus/counter :slo/violations-total
                           {:description "Total SLO violations recorded"
                            :labels [:metric]}))

      ;; Phase 5 (Observability): Analytics metrics
      (prometheus/register
       (prometheus/counter :analytics/aggregation-runs-total
                           {:description "Analytics aggregation runs completed"}))
      (prometheus/register
       (prometheus/histogram :analytics/aggregation-duration-seconds
                             {:description "Analytics aggregation duration in seconds"
                              :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0]}))

      ;; Phase D: Billing portal metrics
      (prometheus/register
       (prometheus/counter :billing/portal-views-total
                           {:description "Billing portal page views"
                            :labels [:page]}))
      (prometheus/register
       (prometheus/counter :billing/plan-changes-total
                           {:description "Plan upgrade/downgrade/cancel events"
                            :labels [:action]}))

      ;; Phase 2 SLO/SLI metrics
      ;; Build dispatch latency — SLO: p99 < 50ms
      (prometheus/register
       (prometheus/histogram :builds/dispatch-latency-seconds
                             {:description "Time from build trigger to agent dispatch (seconds); SLO p99 < 50ms"
                              :buckets [0.001 0.005 0.010 0.025 0.050 0.100 0.250 0.500]}))

      ;; SSE delivery latency — SLO: p99 < 500ms
      (prometheus/register
       (prometheus/histogram :sse/delivery-seconds
                             {:description "Server-Sent Event delivery latency from event creation to client receipt (seconds); SLO p99 < 500ms"
                              :labels [:event-type]
                              :buckets [0.005 0.010 0.025 0.050 0.100 0.250 0.500 1.000 2.500]}))

      ;; Outbound webhook delivery — SLO: 99.5% delivered within 60s
      (prometheus/register
       (prometheus/counter :webhooks/outbound-total
                           {:description "Outbound webhook delivery attempts"
                            :labels [:status]}))
      (prometheus/register
       (prometheus/histogram :webhooks/outbound-delivery-seconds
                             {:description "Outbound webhook delivery latency in seconds; SLO 99.5% within 60s"
                              :buckets [0.100 0.500 1.0 2.5 5.0 10.0 30.0 60.0 120.0]}))

      ;; TLS certificate expiry — for cert-expiry alerting
      (prometheus/register
       (prometheus/gauge :tls/cert-expiry-seconds
                         {:description "Seconds until TLS certificate expires (per domain)"
                          :labels [:domain]}))

      ;; CHG-OPS-006: Active SSE subscribers — total count, no labels.
      ;; Updated at scrape time (see metrics-handler) from
      ;; chengis.web.sse/active-connection-count so the gauge is always
      ;; the live in-process value rather than an event-driven counter.
      (prometheus/register
       (prometheus/gauge :sse/active-subscribers
                         {:description "Active SSE connections across all builds"}))

      ;; CHG-FEAT-003 PR3: Multibranch scheduler — tick + error counters.
      ;; `multibranch_scheduler_ticks_total` increments once per tick fired
      ;; by the chime-based scheduler. `multibranch_scheduler_errors_total`
      ;; increments per-job when reconcile throws — labelled by job-id so
      ;; ops can see which job's discovery is failing without trawling
      ;; logs.
      (prometheus/register
       (prometheus/counter :multibranch-scheduler/ticks-total
                           {:description "Multibranch scheduler ticks fired (CHG-FEAT-003 PR3)"}))
      (prometheus/register
       (prometheus/counter :multibranch-scheduler/errors-total
                           {:description "Multibranch scheduler per-job reconcile errors (CHG-FEAT-003 PR3)"
                            :labels [:job]}))
      (prometheus/register
       (prometheus/counter :multibranch-scheduler/jobs-reconciled-total
                           {:description "Multibranch scheduler successful per-job reconciles (CHG-FEAT-003 PR3)"}))
      (prometheus/register
       (prometheus/counter :multibranch-scheduler/skipped-inflight-total
                           {:description "Multibranch scheduler ticks skipped because previous tick still running (CHG-FEAT-003 PR3)"}))

      ;; CHG-FEAT-004 PR5: K8s pod-lifecycle observability.
      ;;
      ;; Label-cardinality budget:
      ;;   k8s_pod_events_total{reason} — `reason` is the K8s Event reason
      ;;     field. That field is drawn from a bounded vocabulary defined in
      ;;     the kubelet / controller-manager source (~30 well-known values:
      ;;     Scheduled, Pulling, Pulled, Created, Started, Killing, Failed,
      ;;     BackOff, ImagePullBackOff, OOMKilled, FailedScheduling, …).
      ;;     We deliberately do NOT include pod-name / namespace / build-id
      ;;     as labels — those would blow cardinality unbounded across a fleet.
      ;;   k8s_provision_failures_total{status_code,reason} — `status_code`
      ;;     is the bucketed HTTP class for the K8s API response ("4xx" / "5xx"
      ;;     / a small set of well-known codes), NOT the raw int. `reason` is
      ;;     the ex-info :reason keyword (also bounded). Combined cardinality
      ;;     budget: ≤ ~30 codes × ~10 reasons ≈ 300 series.
      (prometheus/register
       (prometheus/counter :k8s/pod-events-total
                           {:description "K8s Pod lifecycle events observed by the poller, by reason (CHG-FEAT-004 PR5)"
                            :labels [:reason]}))
      (prometheus/register
       (prometheus/histogram :k8s/provision-duration-seconds
                             {:description "KubernetesProvisioner provision! latency from call-entry to pod-Ready (CHG-FEAT-004 PR5)"
                              :buckets [0.5 1.0 2.0 5.0 10.0 30.0 60.0 120.0]}))
      (prometheus/register
       (prometheus/counter :k8s/provision-failures-total
                           {:description "KubernetesProvisioner provision! failures, by bucketed HTTP status_code and ex-info reason (CHG-FEAT-004 PR5)"
                            :labels [:status_code :reason]}))
      (prometheus/register
       (prometheus/counter :k8s/event-poller-ticks-total
                           {:description "K8s event-poller ticks fired (CHG-FEAT-004 PR5)"}))
      (prometheus/register
       (prometheus/counter :k8s/event-poller-errors-total
                           {:description "K8s event-poller tick errors (HTTP / parse) (CHG-FEAT-004 PR5)"}))
      (prometheus/register
       (prometheus/counter :k8s/event-poller-skipped-total
                           {:description "K8s event-poller ticks skipped, by reason {disabled,not_leader,inflight} (CHG-FEAT-004 PR5)"
                            :labels [:reason]}))

      ;; CHG-OPS-007: SSE subscribe rejections from the global shed switch.
      ;; Incremented on each NEW subscribe rejected with HTTP 503 while
      ;; the shed is active. Lets operators see shed-rejected traffic in
      ;; Prometheus during/after a storm-mitigation event — separate from
      ;; the per-build 429 path (which has no metric today and is not in
      ;; scope for this ticket).
      (prometheus/register
       (prometheus/counter :sse/subscribe-shed-total
                           {:description "SSE subscribes rejected by the global shed switch (CHG-OPS-007)"}))

      ;; CHG-FEAT-004 PR3: K8s queue-depth autoscaler counters.
      ;; Registered up-front so /metrics surfaces them at 0 even before
      ;; the autoscaler enables — operators wiring dashboards don't need
      ;; the scheduler to be running to find the series.
      (prometheus/register
       (prometheus/counter :k8s-autoscale/ticks-total
                           {:description "K8s autoscaler tick attempts (CHG-FEAT-004 PR3)"}))
      (prometheus/register
       (prometheus/counter :k8s-autoscale/provisions-total
                           {:description "Successful provisions issued by the K8s autoscaler"}))
      (prometheus/register
       (prometheus/counter :k8s-autoscale/skipped-total
                           {:description "K8s autoscaler ticks that skipped (with reason)"
                            :labels [:reason]}))
      (prometheus/register
       (prometheus/counter :k8s-autoscale/errors-total
                           {:description "K8s autoscaler per-tick errors"}))

      ;; CHG-FEAT-004 PR4: K8s orphan-cleanup loop counters. Registered
      ;; up-front so /metrics surfaces them at 0 even before the loop
      ;; enables — operators wiring dashboards don't need the scheduler
      ;; to be running to find the series.
      (prometheus/register
       (prometheus/counter :k8s-orphan-cleanup/ticks-total
                           {:description "K8s orphan-cleanup tick attempts (CHG-FEAT-004 PR4)"}))
      (prometheus/register
       (prometheus/counter :k8s-orphan-cleanup/cluster-orphans-total
                           {:description "Jobs released from cluster as cluster-orphans (CHG-FEAT-004 PR4)"}))
      (prometheus/register
       (prometheus/counter :k8s-orphan-cleanup/registry-orphans-total
                           {:description "Provisioner state entries removed as registry-orphans (CHG-FEAT-004 PR4)"}))
      (prometheus/register
       (prometheus/counter :k8s-orphan-cleanup/errors-total
                           {:description "K8s orphan-cleanup per-item errors (CHG-FEAT-004 PR4)"
                            :labels [:phase]}))
      (prometheus/register
       (prometheus/counter :k8s-orphan-cleanup/skipped-total
                           {:description "K8s orphan-cleanup ticks skipped (CHG-FEAT-004 PR4)"
                            :labels [:reason]}))))

(defn- as-label
  "Coerce a keyword, symbol, or string to a Prometheus label string.
   Prevents ClassCastException when callers accidentally pass strings."
  [x]
  (if (string? x) x (name x)))

;; ---------------------------------------------------------------------------
;; Record helpers — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-http-request!
  "Record an HTTP request metric (duration histogram + counter)."
  [registry method path status duration-s]
  (when registry
    (let [labels {:method (name method) :path path :status (str status)}]
      (prometheus/observe (registry :http/request-duration-seconds labels) duration-s)
      (prometheus/inc (registry :http/requests-total labels)))))

(defn record-build-start!
  "Increment the active builds gauge."
  [registry]
  (when registry
    (prometheus/inc (registry :builds/active))))

(defn record-build-end!
  "Decrement active builds gauge and record build completion."
  [registry status duration-s]
  (when registry
    (let [status-str (name status)]
      (prometheus/dec (registry :builds/active))
      (prometheus/inc (registry :builds/total {:status status-str}))
      (prometheus/observe (registry :builds/duration-seconds {:status status-str}) duration-s))))

(defn record-stage-duration!
  "Record a stage execution duration."
  [registry stage-name status duration-s]
  (when registry
    (prometheus/observe (registry :stages/duration-seconds
                                  {:stage-name (str stage-name)
                                   :status (name status)})
                        duration-s)))

(defn record-step-duration!
  "Record a step execution duration."
  [registry step-name status duration-s]
  (when registry
    (prometheus/observe (registry :steps/duration-seconds
                                  {:step-name (str step-name)
                                   :status (name status)})
                        duration-s)))

(defn record-event-published!
  "Increment the events published counter."
  [registry]
  (when registry
    (prometheus/inc (registry :events/published-total))))

(defn record-event-overflow!
  "Increment the events overflow counter."
  [registry]
  (when registry
    (prometheus/inc (registry :events/overflow-total))))

(defn record-login!
  "Record a login attempt with result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :auth/login-total {:result (name result)}))))

(defn record-token-auth!
  "Record an API token auth attempt with result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :auth/token-auth-total {:result (name result)}))))

;; ---------------------------------------------------------------------------
;; Phase 3: Dispatch & queue metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-dispatch!
  "Record a build dispatch attempt with result (:success, :failure, :no-agent, :retry)."
  [registry result]
  (when registry
    (prometheus/inc (registry :dispatch/total {:result (name result)}))))

(defn record-queue-depth!
  "Set the current queue depth gauge."
  [registry depth]
  (when registry
    (prometheus/set (registry :queue/depth) (double depth))))

(defn record-queue-oldest-pending!
  "Set the age of the oldest pending queue item in seconds."
  [registry age-seconds]
  (when registry
    (prometheus/set (registry :queue/oldest-pending-seconds) (double age-seconds))))

(defn record-orphan-recovery!
  "Increment the orphan recovery counter."
  [registry count]
  (when registry
    (dotimes [_ count]
      (prometheus/inc (registry :dispatch/orphans-recovered-total)))))

(defn record-circuit-breaker-open!
  "Set the count of agents with open circuit breakers."
  [registry count]
  (when registry
    (prometheus/set (registry :agents/circuit-breaker-open) (double count))))

(defn record-artifact-transfer!
  "Record an artifact transfer result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :artifacts/transferred-total {:result (as-label result)}))))

(defn record-agent-utilization!
  "Set the agent utilization ratio (active-builds / total-capacity)."
  [registry ratio]
  (when registry
    (prometheus/set (registry :agents/utilization-ratio) (double ratio))))

;; ---------------------------------------------------------------------------
;; Phase 4: Rate limiting, webhook, token, retention, secret metrics
;; ---------------------------------------------------------------------------

(defn record-rate-limit-rejected!
  "Record a rate-limited request rejection."
  [registry endpoint-type]
  (when registry
    (prometheus/inc (registry :rate-limit/rejected-total
                              {:endpoint-type (as-label endpoint-type)}))))

(defn record-org-rate-limit-rejected!
  "Record a per-org rate-limited request rejection."
  [registry org-id endpoint-type]
  (when registry
    (prometheus/inc (registry :org-rate-limit/rejected-total
                              {:org-id (as-label org-id)
                               :endpoint-type (as-label endpoint-type)}))))

(defn record-webhook-received!
  "Record a received webhook event."
  [registry provider status]
  (when registry
    (prometheus/inc (registry :webhooks/received-total
                              {:provider (as-label provider) :status (as-label status)}))))

(defn record-webhook-processing!
  "Record webhook processing duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :webhooks/processing-seconds) duration-s)))

(defn record-token-generated!
  "Record an API token generation."
  [registry]
  (when registry
    (prometheus/inc (registry :tokens/generated-total))))

(defn record-token-revoked!
  "Record an API token revocation."
  [registry]
  (when registry
    (prometheus/inc (registry :tokens/revoked-total))))

(defn record-retention-cleaned!
  "Record retention cleanup count for a resource type."
  [registry resource-type count]
  (when registry
    (dotimes [_ count]
      (prometheus/inc (registry :retention/cleaned-total
                                {:resource-type (as-label resource-type)})))))

(defn record-secret-access!
  "Record a secret access event."
  [registry action]
  (when registry
    (prometheus/inc (registry :secrets/access-total {:action (name action)}))))

;; ---------------------------------------------------------------------------
;; Phase 5: Account lockout metrics
;; ---------------------------------------------------------------------------

(defn record-account-lockout!
  "Record an account lockout event."
  [registry]
  (when registry
    (prometheus/inc (registry :auth/account-lockouts-total))))

(defn record-approval-requested!
  "Record an approval gate creation."
  [registry]
  (when registry
    (prometheus/inc (registry :approvals/requested-total))))

(defn record-approval-resolved!
  "Record an approval gate resolution."
  [registry result]
  (when registry
    (prometheus/inc (registry :approvals/resolved-total {:result (or result "unknown")}))))

(defn record-scm-status-report!
  "Record an SCM status report."
  [registry provider result]
  (when registry
    (prometheus/inc (registry :scm/status-reports-total
                              {:provider (or provider "unknown")
                               :result (or result "unknown")}))))

;; ---------------------------------------------------------------------------
;; Phase 6: Artifact, compliance, and policy metrics
;; ---------------------------------------------------------------------------

(defn record-artifact-checksum!
  "Record an artifact checksum verification result (:match, :mismatch, :skipped)."
  [registry result]
  (when registry
    (prometheus/inc (registry :artifacts/checksum-verified-total
                              {:result (as-label result)}))))

(defn record-compliance-report!
  "Record a compliance report generation."
  [registry report-type]
  (when registry
    (prometheus/inc (registry :compliance/reports-generated-total
                              {:report-type (as-label report-type)}))))

(defn record-hash-chain-verification!
  "Record a hash chain integrity verification result (:valid or :invalid)."
  [registry result]
  (when registry
    (prometheus/inc (registry :compliance/hash-chain-verifications-total
                              {:result (as-label result)}))))

(defn record-policy-evaluation!
  "Record a policy evaluation with type and result."
  [registry policy-type result]
  (when registry
    (prometheus/inc (registry :policies/evaluated-total
                              {:policy-type (as-label policy-type)
                               :result (as-label result)}))))

(defn record-policy-denial!
  "Record a build/stage blocked by policy."
  [registry policy-type]
  (when registry
    (prometheus/inc (registry :policies/denied-total
                              {:policy-type (as-label policy-type)}))))

(defn record-policy-duration!
  "Record policy evaluation duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :policies/evaluation-duration-seconds) duration-s)))

;; ---------------------------------------------------------------------------
;; Phase 5: Tracing and analytics metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-tracing-span-created!
  "Record a trace span creation."
  [registry]
  (when registry
    (prometheus/inc (registry :tracing/spans-created-total))))

(defn record-tracing-span-duration!
  "Record a trace span duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :tracing/span-duration-seconds) duration-s)))

(defn record-analytics-aggregation-run!
  "Record an analytics aggregation run."
  [registry]
  (when registry
    (prometheus/inc (registry :analytics/aggregation-runs-total))))

(defn record-analytics-aggregation-duration!
  "Record an analytics aggregation duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :analytics/aggregation-duration-seconds) duration-s)))

;; ---------------------------------------------------------------------------
;; Phase C: Backup and SLO metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-backup-created!
  "Record a backup creation with trigger (manual/scheduled) and status."
  [registry trigger status]
  (when registry
    (prometheus/inc (registry :backups/total
                              {:trigger (as-label trigger)
                               :status (as-label status)}))))

(defn record-backup-size!
  "Set the last backup size in bytes."
  [registry size-bytes]
  (when registry
    (prometheus/set (registry :backups/last-size-bytes) (double size-bytes))))

(defn record-backup-duration!
  "Record backup creation duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :backups/duration-seconds) duration-s)))

(defn record-slo-evaluation!
  "Record an SLO evaluation for a given metric type."
  [registry metric]
  (when registry
    (prometheus/inc (registry :slo/evaluations-total {:metric (as-label metric)}))))

(defn record-slo-violation!
  "Record an SLO violation for a given metric type."
  [registry metric]
  (when registry
    (prometheus/inc (registry :slo/violations-total {:metric (as-label metric)}))))

;; ---------------------------------------------------------------------------
;; Phase D: Billing portal metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-billing-portal-view!
  "Record a billing portal page view."
  [registry page]
  (when registry
    (prometheus/inc (registry :billing/portal-views-total {:page (as-label page)}))))

(defn record-billing-plan-change!
  "Record a plan change event (upgrade, downgrade, cancel)."
  [registry action]
  (when registry
    (prometheus/inc (registry :billing/plan-changes-total {:action (as-label action)}))))

;; ---------------------------------------------------------------------------
;; Phase 2 SLO/SLI helpers — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-build-dispatch-latency!
  "Record build dispatch latency in seconds (SLO: p99 < 50ms)."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :builds/dispatch-latency-seconds) duration-s)))

(defn record-sse-delivery!
  "Record SSE delivery latency in seconds for a given event-type (SLO: p99 < 500ms)."
  [registry event-type duration-s]
  (when registry
    (prometheus/observe (registry :sse/delivery-seconds {:event-type (as-label event-type)}) duration-s)))

(defn record-outbound-webhook!
  "Record an outbound webhook delivery attempt.
   status should be :success, :failure, or :timeout."
  [registry status]
  (when registry
    (prometheus/inc (registry :webhooks/outbound-total {:status (as-label status)}))))

(defn record-outbound-webhook-delivery!
  "Record outbound webhook delivery latency in seconds (SLO: 99.5% within 60s)."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :webhooks/outbound-delivery-seconds) duration-s)))

(defn record-cert-expiry!
  "Set the TLS certificate expiry gauge for a domain in seconds from now."
  [registry domain expiry-seconds]
  (when registry
    (prometheus/set (registry :tls/cert-expiry-seconds {:domain domain}) (double expiry-seconds))))

(defn record-multibranch-scheduler-tick!
  "Increment the multibranch scheduler tick counter (one per tick fired)."
  [registry]
  (when registry
    (prometheus/inc (registry :multibranch-scheduler/ticks-total))))

(defn record-multibranch-scheduler-error!
  "Increment the per-job error counter — one per job whose reconcile
   raised in the tick. job-id is labelled."
  [registry job-id]
  (when registry
    (prometheus/inc (registry :multibranch-scheduler/errors-total
                              {:job (as-label (or job-id "unknown"))}))))

(defn record-multibranch-scheduler-job-reconciled!
  "Increment the successful-reconcile counter — one per job whose reconcile
   completed without throwing in the tick."
  [registry]
  (when registry
    (prometheus/inc (registry :multibranch-scheduler/jobs-reconciled-total))))

(defn record-multibranch-scheduler-skipped-inflight!
  "Increment the in-flight skip counter — one per tick skipped because
   the previous tick was still running."
  [registry]
  (when registry
    (prometheus/inc (registry :multibranch-scheduler/skipped-inflight-total))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-004 PR5: K8s pod-lifecycle observability helpers
;; ---------------------------------------------------------------------------

(def ^:private well-known-status-codes
  "Bounded set of HTTP status codes we permit verbatim as the `status_code`
   label on `k8s_provision_failures_total`. Anything else falls back to the
   class bucket (4xx / 5xx / unknown). Keeps the label space finite — a hot
   k8s-apiserver returning a stream of distinct ints would otherwise explode
   per-status time series."
  #{400 401 403 404 409 410 422 429 500 502 503 504})

(defn bucket-status-code
  "Coerce a K8s API response status (int / string / nil) into a label-safe
   string suitable for the `k8s_provision_failures_total{status_code}` label.

   Output domain (bounded):
     - Well-known codes: \"400\" \"401\" \"403\" \"404\" \"409\" \"410\"
       \"422\" \"429\" \"500\" \"502\" \"503\" \"504\"
     - Class bucket otherwise: \"4xx\" \"5xx\"
     - \"unknown\" for nil / non-numeric / out-of-range.

   This is the public coercion the provisioner uses on its failure path so
   the cardinality budget stays inside the design envelope."
  [code]
  (cond
    (nil? code) "unknown"
    (integer? code) (cond
                      (contains? well-known-status-codes code) (str code)
                      (and (>= code 400) (< code 500)) "4xx"
                      (and (>= code 500) (< code 600)) "5xx"
                      :else "unknown")
    (string? code) (try (bucket-status-code (Long/parseLong code))
                        (catch Exception _ "unknown"))
    :else "unknown"))

(defn record-k8s-pod-event!
  "Increment the per-reason pod-lifecycle event counter. `reason` may be a
   string or keyword; nil → \"unknown\"."
  [registry reason]
  (when registry
    (prometheus/inc (registry :k8s/pod-events-total
                              {:reason (if reason (as-label reason) "unknown")}))))

(defn record-k8s-provision-duration!
  "Record one observation on the k8s_provision_duration_seconds histogram.
   MUST be called on BOTH success and failure paths so the histogram reflects
   total provision latency, not just the happy path."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :k8s/provision-duration-seconds) (double duration-s))))

(defn record-k8s-provision-failure!
  "Increment the provision-failure counter. `status-code` is bucketed via
   `bucket-status-code`; `reason` is coerced to a label string."
  [registry status-code reason]
  (when registry
    (prometheus/inc
     (registry :k8s/provision-failures-total
               {:status_code (bucket-status-code status-code)
                :reason (if reason (as-label reason) "unknown")}))))

(defn record-k8s-event-poller-tick!
  "Increment the poller-tick counter — one per tick fired (regardless of
   whether the tick made an HTTP call or short-circuited as skipped)."
  [registry]
  (when registry
    (prometheus/inc (registry :k8s/event-poller-ticks-total))))

(defn record-k8s-event-poller-error!
  "Increment the poller-error counter — one per tick whose HTTP/parse phase
   threw (the tick itself is soft-failed; the counter is the observable)."
  [registry]
  (when registry
    (prometheus/inc (registry :k8s/event-poller-errors-total))))

(defn record-k8s-event-poller-skipped!
  "Increment the skipped-tick counter with reason ∈ #{:disabled :not-leader
   :inflight}. The label is lower-snake_case so it matches Prometheus
   convention (`{reason=\"not_leader\"}` not `\"not-leader\"`)."
  [registry reason]
  (when registry
    (let [r (case reason
              :disabled    "disabled"
              :not-leader  "not_leader"
              :inflight    "inflight"
              (as-label (or reason "unknown")))]
      (prometheus/inc (registry :k8s/event-poller-skipped-total
                                {:reason r})))))

(defn record-sse-shed-rejected!
  "Increment the sse_subscribe_shed_total counter — one rejected NEW
   subscribe while the CHG-OPS-007 global shed is active. No-op when the
   registry is nil."
  [registry]
  (when registry
    (prometheus/inc (registry :sse/subscribe-shed-total))))

(defn record-sse-active-subscribers!
  "Sample the live SSE active-connection count and set the
   sse_active_subscribers gauge (CHG-OPS-006). Called at scrape time from
   metrics-handler so the gauge always reflects the live in-process value
   without requiring an event-driven increment/decrement contract.

   Uses requiring-resolve to read chengis.web.sse/active-connection-count
   lazily — the metrics ns must not statically require web.* (would create
   a circular dep, since web.* depends on metrics)."
  [registry]
  (when registry
    (try
      (let [active-fn (requiring-resolve 'chengis.web.sse/active-connection-count)]
        (when active-fn
          (prometheus/set (registry :sse/active-subscribers)
                          (double (active-fn)))))
      (catch Exception e
        (log/debug "Failed to sample SSE active subscribers for metrics:"
                   (.getMessage e))))))

(defn record-db-pool-stats!
  "Capture Hikari pool stats into gauges.
   No-ops for non-Hikari datasources (e.g., SQLite)."
  [registry datasource]
  (when (and registry (instance? HikariDataSource datasource))
    (try
      (let [^HikariDataSource hikari datasource
            mx (.getHikariPoolMXBean hikari)]
        (when mx
          (prometheus/set (registry :db/pool-active-connections)
                          (double (.getActiveConnections mx)))
          (prometheus/set (registry :db/pool-idle-connections)
                          (double (.getIdleConnections mx)))
          (prometheus/set (registry :db/pool-pending-threads)
                          (double (.getThreadsAwaitingConnection mx)))
          (prometheus/set (registry :db/pool-total-connections)
                          (double (.getTotalConnections mx)))
          (prometheus/set (registry :db/pool-max-connections)
                          (double (.getMaximumPoolSize hikari)))))
      (catch Exception e
        (log/debug "Failed to sample DB pool stats for metrics:" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Metrics endpoint handler
;; ---------------------------------------------------------------------------

(defn metrics-handler
  "Ring handler that returns Prometheus metrics in text exposition format.
   Optionally samples DB pool gauges when datasource is provided."
  ([registry] (metrics-handler registry nil))
  ([registry datasource]
   (fn [_req]
     (record-db-pool-stats! registry datasource)
     (record-sse-active-subscribers! registry)
     {:status 200
      :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
      :body (export/text-format registry)})))
