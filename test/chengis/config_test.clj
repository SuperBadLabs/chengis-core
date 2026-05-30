(ns chengis.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [chengis.config :as config]))

(deftest coerce-env-value-test
  (testing "boolean coercion"
    (is (true? (config/coerce-env-value "true")))
    (is (false? (config/coerce-env-value "false"))))

  (testing "numeric coercion"
    (is (= 8080 (config/coerce-env-value "8080")))
    (is (= 0 (config/coerce-env-value "0")))
    (is (= 9999 (config/coerce-env-value "9999"))))

  (testing "keyword coercion"
    (is (= :info (config/coerce-env-value ":info")))
    (is (= :master (config/coerce-env-value ":master")))
    (is (= :json (config/coerce-env-value ":json"))))

  (testing "string passthrough"
    (is (= "hello" (config/coerce-env-value "hello")))
    (is (= "/data/chengis.db" (config/coerce-env-value "/data/chengis.db")))
    (is (= "smtp.example.com" (config/coerce-env-value "smtp.example.com")))
    ;; Not pure digits — stays as string
    (is (= "8080a" (config/coerce-env-value "8080a")))
    ;; Empty string
    (is (= "" (config/coerce-env-value "")))))

(deftest deep-merge-test
  (testing "basic map merge"
    (is (= {:a 1 :b 2}
           (config/deep-merge {:a 1} {:b 2}))))

  (testing "nested map merge"
    (is (= {:server {:port 9090 :host "localhost"}}
           (config/deep-merge {:server {:port 8080 :host "localhost"}}
                              {:server {:port 9090}}))))

  (testing "deeply nested merge"
    (is (= {:distributed {:enabled true
                          :dispatch {:queue-enabled true
                                     :max-retries 3}}}
           (config/deep-merge {:distributed {:enabled false
                                             :dispatch {:queue-enabled false
                                                        :max-retries 3}}}
                              {:distributed {:enabled true
                                             :dispatch {:queue-enabled true}}}))))

  (testing "non-map value overwrites"
    (is (= {:a "new"}
           (config/deep-merge {:a "old"} {:a "new"}))))

  (testing "nil values ignored"
    (is (= {:a 1}
           (config/deep-merge {:a 1} nil)))
    (is (= {:a 1}
           (config/deep-merge nil {:a 1}))))

  (testing "three-way merge"
    (is (= {:a 1 :b 2 :c 3}
           (config/deep-merge {:a 1} {:b 2} {:c 3}))))

  (testing "three-way nested merge with overrides"
    (is (= {:server {:port 9999 :host "0.0.0.0"}}
           (config/deep-merge {:server {:port 8080 :host "0.0.0.0"}}
                              {:server {:port 9090}}
                              {:server {:port 9999}})))))

(deftest load-env-overrides-test
  (testing "no env vars set returns empty map"
    (let [result (config/load-env-overrides (constantly nil))]
      (is (= {} result))))

  (testing "single env var"
    (let [env-fn (fn [k] (when (= k "CHENGIS_SERVER_PORT") "9090"))
          result (config/load-env-overrides env-fn)]
      (is (= 9090 (get-in result [:server :port])))
      (is (nil? (get-in result [:auth :enabled])))))

  (testing "multiple env vars"
    (let [env-fn (fn [k]
                   (case k
                     "CHENGIS_SERVER_PORT" "9090"
                     "CHENGIS_AUTH_ENABLED" "true"
                     "CHENGIS_DATABASE_PATH" "/data/chengis.db"
                     "CHENGIS_LOG_LEVEL" ":debug"
                     nil))
          result (config/load-env-overrides env-fn)]
      (is (= 9090 (get-in result [:server :port])))
      (is (true? (get-in result [:auth :enabled])))
      (is (= "/data/chengis.db" (get-in result [:database :path])))
      (is (= :debug (get-in result [:log :level])))))

  (testing "deeply nested env var"
    (let [env-fn (fn [k]
                   (when (= k "CHENGIS_DISTRIBUTED_DISPATCH_QUEUE_ENABLED") "true"))
          result (config/load-env-overrides env-fn)]
      (is (true? (get-in result [:distributed :dispatch :queue-enabled]))))))

(deftest load-config-precedence-test
  (testing "defaults are present without config.edn or env vars"
    ;; load-config always returns at minimum the defaults
    (let [cfg (config/load-config)]
      (is (= 8080 (get-in cfg [:server :port])))
      (is (false? (get-in cfg [:auth :enabled])))
      (is (= "chengis.db" (get-in cfg [:database :path]))))))

(deftest env-key-map-coverage-test
  (testing "all env-key-map entries produce valid config paths"
    ;; Verify that every mapped path produces a non-error assoc-in
    (let [env-fn (fn [_k] "test-value")
          result (config/load-env-overrides env-fn)]
      ;; Should have entries for every key in the map
      (is (= "test-value" (get-in result [:database :path])))
      (is (= "test-value" (get-in result [:server :host])))
      (is (= "test-value" (get-in result [:secrets :master-key])))
      (is (= "test-value" (get-in result [:distributed :auth-token])))
      (is (= "test-value" (get-in result [:scm :github :token])))
      (is (= "test-value" (get-in result [:scm :gitlab :token])))
      (is (= "test-value" (get-in result [:notifications :email :host])))
      (is (= "test-value" (get-in result [:notifications :email :from])))
      (is (= "test-value" (get-in result [:notifications :slack :default-webhook]))))))

(deftest matrix-config-defaults-test
  (testing "matrix max-combinations has default"
    (let [cfg (config/load-config)]
      (is (= 25 (get-in cfg [:matrix :max-combinations]))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation testing remediation: Assert every boolean default value
;; in default-config to kill true↔false mutation survivors.
;; ---------------------------------------------------------------------------

(deftest default-config-boolean-defaults-test
  (let [cfg config/default-config]

    (testing "top-level service toggles default to disabled"
      (is (false? (get-in cfg [:scheduler :enabled])))
      (is (false? (get-in cfg [:auth :enabled])))
      (is (false? (get-in cfg [:https :enabled])))
      (is (false? (get-in cfg [:metrics :enabled])))
      (is (false? (get-in cfg [:rate-limit :enabled])))
      (is (false? (get-in cfg [:retention :enabled])))
      (is (false? (get-in cfg [:cleanup :enabled])))
      (is (false? (get-in cfg [:oidc :enabled])))
      (is (false? (get-in cfg [:saml :enabled])))
      (is (false? (get-in cfg [:ldap :enabled])))
      (is (false? (get-in cfg [:ha :enabled])))
      (is (false? (get-in cfg [:distributed :enabled])))
      (is (false? (get-in cfg [:security :cors :enabled])))
      (is (false? (get-in cfg [:metrics :auth-required]))))

    (testing "service toggles that default to enabled"
      (is (true? (get-in cfg [:notifications :email :tls])))
      (is (true? (get-in cfg [:audit :enabled])))
      (is (true? (get-in cfg [:security :csp :enabled])))
      (is (true? (get-in cfg [:https :hsts])))
      (is (true? (get-in cfg [:https :redirect-http])))
      (is (true? (get-in cfg [:auth :lockout :enabled])))
      (is (true? (get-in cfg [:approvals :enabled])))
      (is (true? (get-in cfg [:templates :enabled])))
      (is (true? (get-in cfg [:oidc :auto-create-users])))
      (is (true? (get-in cfg [:saml :auto-create-users])))
      (is (true? (get-in cfg [:ldap :auto-create-users])))
      (is (true? (get-in cfg [:multi-tenancy :auto-assign-default])))
      (is (true? (get-in cfg [:auto-merge :require-all-checks])))
      (is (true? (get-in cfg [:container-scanning :ignore-unfixed])))
      (is (true? (get-in cfg [:distributed :dispatch :artifact-transfer])))
      (is (true? (get-in cfg [:iac :terraform :auto-init]))))

    (testing "secrets defaults"
      (is (false? (get-in cfg [:secrets :fallback-to-local]))))

    (testing "distributed dispatch defaults"
      (is (false? (get-in cfg [:distributed :dispatch :fallback-local])))
      (is (false? (get-in cfg [:distributed :dispatch :queue-enabled]))))

    (testing "LDAP defaults"
      (is (false? (get-in cfg [:ldap :use-ssl]))))

    (testing "MFA defaults"
      (is (false? (get-in cfg [:mfa :enforce-for-admins]))))

    (testing "auto-merge defaults"
      (is (false? (get-in cfg [:auto-merge :delete-branch-after]))))

    (testing "deployment defaults"
      (is (false? (get-in cfg [:deployment :rollback :auto-on-health-failure]))))

    (testing "IaC plan defaults"
      (is (false? (get-in cfg [:iac :plan :require-approval]))))))

(deftest default-config-feature-flags-test
  (let [flags (get-in config/default-config [:feature-flags])]

    (testing "flags that default to true"
      (is (true? (:persistent-agents flags)))
      (is (true? (:sse-server-side-isolation flags))))

    (testing "all other feature flags default to false"
      (let [expected-false-flags
            [:policy-engine :artifact-checksums :compliance-reports
             :distributed-dispatch :parallel-stage-execution
             :docker-layer-cache :artifact-cache :build-result-cache
             :resource-aware-scheduling :incremental-artifacts
             :build-deduplication
             ;; Phase 5: Observability
             :tracing :build-analytics :browser-notifications
             :cost-attribution :flaky-test-detection
             ;; Phase 6: Advanced SCM
             :pr-status-checks :branch-overrides :monorepo-filtering
             :build-dependencies :cron-scheduling :webhook-replay :auto-merge
             ;; Phase 7: Supply Chain
             :slsa-provenance :sbom-generation :container-scanning
             :opa-policies :license-scanning :artifact-signing
             :regulatory-dashboards
             ;; Phase 8: Enterprise Identity
             :saml :ldap :fine-grained-rbac :mfa-totp
             :cross-org-sharing :cloud-secret-backends :secret-rotation
             ;; Phase 10: Scale
             :chunked-log-storage :cursor-pagination :db-partitioning
             :read-replicas :agent-connection-pooling
             :event-bus-backpressure :multi-region
             ;; Phase 11: Deployment
             :environment-definitions :release-management
             :artifact-promotion :deployment-strategies
             :deployment-execution :environment-health-checks
             :deployment-dashboard
             ;; Phase 12: IaC
             :infrastructure-as-code :terraform-execution
             :pulumi-execution :cloudformation-execution
             :iac-state-management :iac-cost-estimation
             :iac-policy-enforcement
             ;; Phase 13: Commercialization
             :per-org-webhooks]]
        (doseq [flag expected-false-flags]
          (is (false? (get flags flag))
              (str "Feature flag " flag " should default to false")))))))

(deftest default-config-warn-insecure-defaults-test
  (testing "warn-insecure-defaults detects default admin password"
    (let [cfg (assoc-in config/default-config [:auth :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "default admin password"))))

  (testing "warn-insecure-defaults detects missing JWT secret"
    (let [cfg (assoc-in config/default-config [:auth :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "JWT secret"))))

  (testing "warn-insecure-defaults detects missing distributed auth token"
    (let [cfg (assoc-in config/default-config [:distributed :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "auth-token"))))

  (testing "warns loudly when auth is disabled (default config)"
    (let [output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults config/default-config)))]
      (is (str/includes? output "Authentication is DISABLED")
          "Should warn about auth being disabled in default config")))

  (testing "warn-insecure-defaults returns the config"
    (is (= config/default-config
           (config/warn-insecure-defaults config/default-config)))))

;; ---------------------------------------------------------------------------
;; Phase 4 mutation testing remediation: config edge cases
;; ---------------------------------------------------------------------------

(deftest deep-merge-map-detection-test
  (testing "deep-merge: non-map overwrites map (exercises map? check)"
    (is (= {:a "string"}
           (config/deep-merge {:a {:nested true}} {:a "string"})))
    (is (= {:a {:nested true}}
           (config/deep-merge {:a "string"} {:a {:nested true}}))))

  (testing "deep-merge with empty maps"
    ;; When both sides are maps, they merge — empty map doesn't clear keys
    (is (= {:a {:x 1}}
           (config/deep-merge {:a {:x 1}} {:a {}})))
    (is (= {}
           (config/deep-merge {} {}))))

  (testing "deep-merge: both nil returns empty map"
    (is (= {} (config/deep-merge nil nil)))))

(deftest resolve-path-test
  (testing "absolute path is returned as-is"
    (let [result (config/resolve-path "/base" "/absolute/path")]
      (is (= "/absolute/path" result))))

  (testing "relative path is resolved against base"
    (let [result (config/resolve-path "/base/dir" "relative/path")]
      (is (str/ends-with? result "relative/path"))
      (is (str/includes? result "base/dir")))))

(deftest warn-insecure-defaults-custom-password-test
  (testing "no admin password warning when custom password is set"
    (let [cfg (-> config/default-config
                  (assoc-in [:auth :enabled] true)
                  (assoc-in [:auth :seed-admin-password] "secure-pass-123")
                  (assoc-in [:auth :jwt-secret] "my-jwt-secret"))
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (not (str/includes? output "admin password")))))

  (testing "no distributed warning when auth token is set"
    (let [cfg (-> config/default-config
                  (assoc-in [:distributed :enabled] true)
                  (assoc-in [:distributed :auth-token] "secret-token"))
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (not (str/includes? output "auth-token")))))

  (testing "warn-insecure-defaults with auth enabled but blank jwt secret"
    (let [cfg (-> config/default-config
                  (assoc-in [:auth :enabled] true)
                  (assoc-in [:auth :seed-admin-password] "secure-pass")
                  (assoc-in [:auth :jwt-secret] ""))
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "JWT secret"))))

  (testing "warn-insecure-defaults with auth enabled and nil jwt secret"
    (let [cfg (-> config/default-config
                  (assoc-in [:auth :enabled] true)
                  (assoc-in [:auth :seed-admin-password] "secure-pass")
                  (assoc-in [:auth :jwt-secret] nil))
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "JWT secret")))))

(deftest default-config-numeric-defaults-test
  (let [cfg config/default-config]
    (testing "server defaults"
      (is (= 8080 (get-in cfg [:server :port]))))

    (testing "database pool defaults"
      (is (= 2 (get-in cfg [:database :pool :minimum-idle])))
      (is (= 10 (get-in cfg [:database :pool :maximum-pool-size]))))

    (testing "distributed numeric defaults"
      (is (= 90000 (get-in cfg [:distributed :heartbeat-timeout-ms])))
      (is (= 3 (get-in cfg [:distributed :dispatch :max-retries])))
      (is (= 1000 (get-in cfg [:distributed :dispatch :retry-backoff-ms])))
      (is (= 5 (get-in cfg [:distributed :dispatch :circuit-breaker-threshold])))
      (is (= 60000 (get-in cfg [:distributed :dispatch :circuit-breaker-reset-ms])))
      (is (= 120000 (get-in cfg [:distributed :dispatch :orphan-check-interval-ms]))))

    (testing "auth numeric defaults"
      (is (= 24 (get-in cfg [:auth :jwt-expiry-hours])))
      (is (= 86400 (get-in cfg [:auth :session-max-age])))
      (is (= 5 (get-in cfg [:auth :lockout :max-attempts])))
      (is (= 30 (get-in cfg [:auth :lockout :lockout-minutes]))))

    (testing "rate-limit defaults"
      (is (= 60 (get-in cfg [:rate-limit :requests-per-minute])))
      (is (= 10 (get-in cfg [:rate-limit :auth-requests-per-minute])))
      (is (= 120 (get-in cfg [:rate-limit :webhook-requests-per-minute]))))

    (testing "pagination defaults"
      (is (= 50 (get-in cfg [:pagination :default-page-size])))
      (is (= 200 (get-in cfg [:pagination :max-page-size]))))

    (testing "event-bus defaults"
      (is (= 8192 (get-in cfg [:event-bus :buffer-size])))
      (is (= 5000 (get-in cfg [:event-bus :critical-timeout-ms]))))

    (testing "partitioning defaults"
      (is (= 12 (get-in cfg [:partitioning :retention-months])))
      (is (= 3 (get-in cfg [:partitioning :future-partitions]))))

    (testing "IaC numeric defaults"
      (is (= 600000 (get-in cfg [:iac :terraform :timeout-ms])))
      (is (= 10 (get-in cfg [:iac :terraform :parallelism])))
      (is (= 300000 (get-in cfg [:iac :state :lock-timeout-ms])))
      (is (= 50 (get-in cfg [:iac :state :history-limit]))))))

(deftest default-config-string-defaults-test
  (let [cfg config/default-config]
    (testing "server string defaults"
      (is (= "0.0.0.0" (get-in cfg [:server :host]))))

    (testing "database string defaults"
      (is (= "sqlite" (get-in cfg [:database :type])))
      (is (= "chengis.db" (get-in cfg [:database :path])))
      (is (= "localhost" (get-in cfg [:database :host])))
      (is (= "chengis" (get-in cfg [:database :dbname]))))

    (testing "secrets defaults"
      (is (= "local" (get-in cfg [:secrets :backend])))
      (is (= "secret" (get-in cfg [:secrets :vault :mount])))
      (is (= "chengis/" (get-in cfg [:secrets :vault :prefix]))))

    (testing "docker defaults"
      (is (= "unix:///var/run/docker.sock" (get-in cfg [:docker :host])))
      (is (= :if-not-present (get-in cfg [:docker :pull-policy]))))

    (testing "auto-merge string defaults"
      (is (= "merge" (get-in cfg [:auto-merge :merge-method]))))

    (testing "oidc string defaults"
      (is (= "openid profile email" (get-in cfg [:oidc :scopes])))
      (is (= "viewer" (get-in cfg [:oidc :default-role]))))

    (testing "saml/ldap default roles"
      (is (= "viewer" (get-in cfg [:saml :default-role])))
      (is (= "viewer" (get-in cfg [:ldap :default-role]))))

    (testing "IaC binary defaults"
      (is (= "terraform" (get-in cfg [:iac :terraform :binary-path])))
      (is (= "pulumi" (get-in cfg [:iac :pulumi :binary-path])))
      (is (= "aws" (get-in cfg [:iac :cloudformation :binary-path]))))))

(deftest sqlite-postgresql-detection-test
  (testing "sqlite? returns true for default config"
    (is (true? (config/sqlite? config/default-config))))

  (testing "postgresql? returns false for default config"
    (is (false? (config/postgresql? config/default-config))))

  (testing "sqlite? returns false for postgresql config"
    (let [cfg (assoc-in config/default-config [:database :type] "postgresql")]
      (is (false? (config/sqlite? cfg)))))

  (testing "postgresql? returns true for postgresql config"
    (let [cfg (assoc-in config/default-config [:database :type] "postgresql")]
      (is (true? (config/postgresql? cfg)))))

  (testing "sqlite? and postgresql? are mutually exclusive"
    (is (not= (config/sqlite? config/default-config)
              (config/postgresql? config/default-config)))))

;; ---------------------------------------------------------------------------
;; A.1: Webhook config defaults + env var
;; ---------------------------------------------------------------------------

(deftest webhook-config-defaults-test
  (testing "webhook section exists in default config"
    (is (contains? config/default-config :webhook))
    (is (map? (:webhook config/default-config))))

  (testing "webhook secret defaults to nil"
    (is (nil? (get-in config/default-config [:webhook :secret])))))

(deftest webhook-secret-env-override-test
  (testing "CHENGIS_WEBHOOK_SECRET env var overrides webhook secret"
    (let [env-fn (fn [k] (when (= k "CHENGIS_WEBHOOK_SECRET") "my-webhook-secret"))
          result (config/load-env-overrides env-fn)]
      (is (= "my-webhook-secret" (get-in result [:webhook :secret]))))))

;; ---------------------------------------------------------------------------
;; A.2: Environment config + production validation
;; ---------------------------------------------------------------------------

(deftest environment-config-defaults-test
  (testing "environment defaults to development"
    (is (= "development" (:environment config/default-config)))))

(deftest environment-env-override-test
  (testing "CHENGIS_ENVIRONMENT env var overrides environment"
    (let [env-fn (fn [k] (when (= k "CHENGIS_ENVIRONMENT") "production"))
          result (config/load-env-overrides env-fn)]
      (is (= "production" (:environment result))))))

(deftest production-detection-test
  (testing "production? returns true for production environment"
    (is (true? (config/production? {:environment "production"}))))

  (testing "production? returns false for development environment"
    (is (false? (config/production? {:environment "development"}))))

  (testing "production? returns false for nil environment"
    (is (false? (config/production? {:environment nil}))))

  (testing "production? returns false for default config"
    (is (false? (config/production? config/default-config)))))

(deftest validate-production-config-passes-development-test
  (testing "validate-production-config! passes in development mode regardless of security settings"
    ;; Should NOT throw for any of these in development mode
    (is (nil? (config/validate-production-config! {:environment "development"
                                                   :auth {:enabled false}})))
    (is (nil? (config/validate-production-config! {:environment "development"
                                                   :auth {:enabled true :jwt-secret nil}})))))

(deftest validate-runtime-config-distributed-token-test
  (testing "validate-runtime-config! throws when distributed enabled and token missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"auth-token"
                          (config/validate-runtime-config!
                           {:distributed {:enabled true}}))))

  (testing "validate-runtime-config! throws when distributed enabled and token blank"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"auth-token"
                          (config/validate-runtime-config!
                           {:distributed {:enabled true :auth-token "   "}}))))

  (testing "validate-runtime-config! passes when distributed token is present"
    (is (nil? (config/validate-runtime-config!
               {:distributed {:enabled true :auth-token "secret"}}))))

  (testing "validate-runtime-config! passes when distributed mode is disabled"
    (is (nil? (config/validate-runtime-config!
               {:distributed {:enabled false}})))))

(deftest validate-production-config-auth-disabled-throws-test
  (testing "validate-production-config! throws in production when auth disabled"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Authentication is disabled"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled false}})))))

(deftest validate-production-config-jwt-secret-blank-throws-test
  (testing "validate-production-config! throws in production when JWT secret blank"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"JWT secret"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled true
                                   :jwt-secret nil
                                   :seed-admin-password "strong"}
                            :webhook {:secret "wh-secret"}})))))

(deftest validate-production-config-default-password-throws-test
  (testing "validate-production-config! throws in production when admin password is 'admin'"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"default 'admin'"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled true
                                   :jwt-secret "my-jwt-secret"
                                   :seed-admin-password "admin"}
                            :webhook {:secret "wh-secret"}})))))

(deftest validate-production-config-webhook-secret-nil-throws-test
  (testing "validate-production-config! throws in production when webhook secret nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Webhook secret"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled true
                                   :jwt-secret "my-jwt-secret"
                                   :seed-admin-password "strong-pass"}
                            :webhook {:secret nil}})))))

(deftest validate-production-config-master-key-blank-throws-test
  (testing "validate-production-config! throws in production when secrets master key is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Secrets master key"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled true
                                   :jwt-secret "my-jwt-secret"
                                   :seed-admin-password "strong-pass"}
                            :webhook {:secret "wh-secret"}
                            :secrets {:master-key nil}
                            :database {:type "postgresql"}})))))

(deftest validate-production-config-sqlite-throws-test
  (testing "validate-production-config! throws in production when database type is not PostgreSQL"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires PostgreSQL"
                          (config/validate-production-config!
                           {:environment "production"
                            :auth {:enabled true
                                   :jwt-secret "my-jwt-secret"
                                   :seed-admin-password "strong-pass"}
                            :webhook {:secret "wh-secret"}
                            :secrets {:master-key "super-secret-master-key"}
                            :database {:type "sqlite"}})))))

(deftest validate-production-config-fully-configured-passes-test
  (testing "validate-production-config! passes when everything is properly configured"
    (is (nil? (config/validate-production-config!
               {:environment "production"
                :auth {:enabled true
                       :jwt-secret "my-jwt-secret-key-12345"
                       :seed-admin-password "str0ng-p@ss!"}
                :webhook {:secret "my-webhook-secret"}
                :secrets {:master-key "master-key-12345678901234567890123456789012"}
                :database {:type "postgresql"}})))))

;; ---------------------------------------------------------------------------
;; Strengthened tests to push mutation score: coerce-env-value edge cases
;; ---------------------------------------------------------------------------

(deftest coerce-env-value-case-sensitivity-test
  (testing "boolean coercion is case-sensitive — only lowercase matches"
    (is (= "True"  (config/coerce-env-value "True")))
    (is (= "False" (config/coerce-env-value "False")))
    (is (= "TRUE"  (config/coerce-env-value "TRUE")))
    (is (= "FALSE" (config/coerce-env-value "FALSE"))))

  (testing "true and false are distinct boolean values"
    (is (not= (config/coerce-env-value "true") (config/coerce-env-value "false")))
    (is (true?  (config/coerce-env-value "true")))
    (is (false? (config/coerce-env-value "false"))))

  (testing "negative numbers stay as strings — leading dash fails digit match"
    (is (= "-1"    (config/coerce-env-value "-1")))
    (is (= "-9090" (config/coerce-env-value "-9090"))))

  (testing "decimal numbers stay as strings — dot fails pure digit match"
    (is (= "3.14"  (config/coerce-env-value "3.14")))
    (is (= "0.5"   (config/coerce-env-value "0.5"))))

  (testing "mixed alphanumeric stays as string"
    (is (= "123abc" (config/coerce-env-value "123abc")))
    (is (= "abc123" (config/coerce-env-value "abc123"))))

  (testing "single-digit numbers coerce to Long"
    (is (= 1 (config/coerce-env-value "1")))
    (is (= 5 (config/coerce-env-value "5")))
    (is (instance? Long (config/coerce-env-value "42"))))

  (testing "keyword: subs removes exactly the leading colon"
    ;; ':info' → :info, not ::info or :nfo
    (is (= :info    (config/coerce-env-value ":info")))
    (is (= :debug   (config/coerce-env-value ":debug")))
    (is (= :warning (config/coerce-env-value ":warning")))
    ;; Multi-segment keyword
    (is (= (keyword "if-not-present") (config/coerce-env-value ":if-not-present"))))

  (testing "coerce returns Long (not Integer) for numeric strings"
    (is (= Long (type (config/coerce-env-value "8080"))))
    (is (= Long (type (config/coerce-env-value "0"))))))

;; ---------------------------------------------------------------------------
;; load-env-overrides: end-to-end type coercion through to config map
;; ---------------------------------------------------------------------------

(deftest load-env-overrides-boolean-false-coercion-test
  (testing "string 'false' coerces to boolean false (not skipped by if-let)"
    (let [env-fn (fn [k] (when (= k "CHENGIS_AUTH_ENABLED") "false"))
          result (config/load-env-overrides env-fn)]
      (is (false? (get-in result [:auth :enabled])))
      ;; Confirm it's actually present in the map, not just absent/nil
      (is (contains? (:auth result) :enabled))))

  (testing "string 'true' coerces to boolean true"
    (let [env-fn (fn [k] (when (= k "CHENGIS_AUTH_ENABLED") "true"))
          result (config/load-env-overrides env-fn)]
      (is (true? (get-in result [:auth :enabled])))))

  (testing "distributed enabled false — boolean, not string"
    (let [env-fn (fn [k] (when (= k "CHENGIS_DISTRIBUTED_ENABLED") "false"))
          result (config/load-env-overrides env-fn)]
      (is (false? (get-in result [:distributed :enabled]))))))

(deftest load-env-overrides-numeric-coercion-test
  (testing "database port coerces to Long"
    (let [env-fn (fn [k] (when (= k "CHENGIS_DATABASE_PORT") "5433"))
          result (config/load-env-overrides env-fn)]
      (is (= 5433 (get-in result [:database :port])))
      (is (instance? Long (get-in result [:database :port])))))

  (testing "email port coerces to Long"
    (let [env-fn (fn [k] (when (= k "CHENGIS_NOTIFICATIONS_EMAIL_PORT") "465"))
          result (config/load-env-overrides env-fn)]
      (is (= 465 (get-in result [:notifications :email :port])))))

  (testing "matrix max-combinations coerces to Long"
    (let [env-fn (fn [k] (when (= k "CHENGIS_MATRIX_MAX_COMBINATIONS") "50"))
          result (config/load-env-overrides env-fn)]
      (is (= 50 (get-in result [:matrix :max-combinations]))))))

(deftest load-env-overrides-keyword-coercion-test
  (testing "log level coerces to keyword"
    (let [env-fn (fn [k] (when (= k "CHENGIS_LOG_LEVEL") ":warn"))
          result (config/load-env-overrides env-fn)]
      (is (= :warn (get-in result [:log :level])))))

  (testing "log format coerces to keyword"
    (let [env-fn (fn [k] (when (= k "CHENGIS_LOG_FORMAT") ":json"))
          result (config/load-env-overrides env-fn)]
      (is (= :json (get-in result [:log :format])))))

  (testing "distributed mode coerces to keyword"
    (let [env-fn (fn [k] (when (= k "CHENGIS_DISTRIBUTED_MODE") ":agent"))
          result (config/load-env-overrides env-fn)]
      (is (= :agent (get-in result [:distributed :mode]))))))

(deftest load-env-overrides-feature-flag-test
  (testing "feature flag tracing env var coerces to true"
    (let [env-fn (fn [k] (when (= k "CHENGIS_FEATURE_TRACING") "true"))
          result (config/load-env-overrides env-fn)]
      (is (true? (get-in result [:feature-flags :tracing])))))

  (testing "feature flag set to false — boolean, not string"
    (let [env-fn (fn [k] (when (= k "CHENGIS_FEATURE_PERSISTENT_AGENTS") "false"))
          result (config/load-env-overrides env-fn)]
      (is (false? (get-in result [:feature-flags :persistent-agents]))))))

(deftest load-env-overrides-empty-string-test
  (testing "empty string env var is stored as empty string, not skipped"
    ;; if-let treats "" as truthy, so empty string should be set
    (let [env-fn (fn [k] (when (= k "CHENGIS_SERVER_HOST") ""))
          result (config/load-env-overrides env-fn)]
      (is (= "" (get-in result [:server :host])))
      (is (contains? (:server result) :host)))))

;; ---------------------------------------------------------------------------
;; deep-merge: zero/one arg and falsy-value preservation
;; ---------------------------------------------------------------------------

(deftest deep-merge-arity-test
  (testing "zero args returns empty map"
    (is (= {} (config/deep-merge))))

  (testing "single arg returns that map"
    (is (= {:a 1} (config/deep-merge {:a 1}))))

  (testing "single nil arg returns empty map"
    (is (= {} (config/deep-merge nil)))))

(deftest deep-merge-falsy-values-preserved-test
  (testing "false value in second map wins over true in first"
    (is (= {:enabled false}
           (config/deep-merge {:enabled true} {:enabled false}))))

  (testing "zero overwrites non-zero"
    (is (= {:port 0}
           (config/deep-merge {:port 8080} {:port 0}))))

  (testing "nil value in second map overwrites non-nil"
    (is (= {:secret nil}
           (config/deep-merge {:secret "abc"} {:secret nil})))))

;; ---------------------------------------------------------------------------
;; production? / sqlite? / postgresql? edge cases
;; ---------------------------------------------------------------------------

(deftest production-detection-edge-cases-test
  (testing "production? returns false for staging"
    (is (false? (config/production? {:environment "staging"}))))

  (testing "production? returns false for empty string"
    (is (false? (config/production? {:environment ""}))))

  (testing "production? only matches the exact string 'production'"
    (is (false? (config/production? {:environment "Production"})))
    (is (false? (config/production? {:environment "PRODUCTION"})))))

(deftest sqlite-postgresql-non-standard-type-test
  (testing "sqlite? returns true for any non-postgresql type"
    (let [mysql-cfg (assoc-in config/default-config [:database :type] "mysql")]
      (is (true? (config/sqlite? mysql-cfg)))))

  (testing "postgresql? returns false for mysql"
    (let [mysql-cfg (assoc-in config/default-config [:database :type] "mysql")]
      (is (false? (config/postgresql? mysql-cfg)))))

  (testing "postgresql? is exact match — 'PostgreSQL' (capital) is false"
    (let [cfg (assoc-in config/default-config [:database :type] "PostgreSQL")]
      (is (false? (config/postgresql? cfg)))
      (is (true? (config/sqlite? cfg))))))

;; ---------------------------------------------------------------------------
;; warn-insecure-defaults: webhook secret warning path
;; ---------------------------------------------------------------------------

(deftest warn-insecure-defaults-webhook-warning-test
  (testing "warns when webhook secret is nil (default)"
    (let [cfg (assoc-in config/default-config [:auth :enabled] false)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "webhook") (str "Expected webhook warning, got: " output))))

  (testing "no webhook warning when secret is set"
    (let [cfg (-> config/default-config
                  (assoc-in [:auth :enabled] false)
                  (assoc-in [:webhook :secret] "my-secret"))
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (not (str/includes? output "webhook")))))

  (testing "warn-insecure-defaults returns the original config unchanged"
    (let [cfg (assoc-in config/default-config [:webhook :secret] "set")]
      (is (identical? cfg (config/warn-insecure-defaults cfg))))))

;; ---------------------------------------------------------------------------
;; load-config 2-arity: reads from explicit file path
;; ---------------------------------------------------------------------------

(deftest load-config-from-file-test
  (testing "load-config with file path merges with defaults"
    (let [tmp (java.io.File/createTempFile "chengis-config-test" ".edn")]
      (try
        (spit tmp "{:server {:port 9999} :auth {:enabled true}}")
        (let [cfg (config/load-config (.getAbsolutePath tmp))]
          ;; File overrides defaults
          (is (= 9999 (get-in cfg [:server :port])))
          (is (true? (get-in cfg [:auth :enabled])))
          ;; Defaults not in file are still present
          (is (= "0.0.0.0" (get-in cfg [:server :host])))
          (is (= "chengis.db" (get-in cfg [:database :path])))
          (is (= "sqlite" (get-in cfg [:database :type]))))
        (finally
          (.delete tmp)))))

  (testing "load-config with deeply nested file config"
    (let [tmp (java.io.File/createTempFile "chengis-config-test2" ".edn")]
      (try
        (spit tmp "{:distributed {:enabled true :auth-token \"file-token\"}}")
        (let [cfg (config/load-config (.getAbsolutePath tmp))]
          (is (true? (get-in cfg [:distributed :enabled])))
          (is (= "file-token" (get-in cfg [:distributed :auth-token])))
          ;; Other distributed defaults still present
          (is (= 90000 (get-in cfg [:distributed :heartbeat-timeout-ms]))))
        (finally
          (.delete tmp))))))

;; ---------------------------------------------------------------------------
;; validate-production-config!: webhook blank-string behavior
;; ---------------------------------------------------------------------------

(deftest validate-production-config-webhook-blank-string-test
  (testing "blank string webhook secret triggers webhook validation (truthy, passes when-not)"
    ;; "" is truthy in Clojure, so (when-not "") = false — validation passes
    ;; This pins the current behavior: only nil/false triggers the webhook error
    (is (nil? (config/validate-production-config!
               {:environment "production"
                :auth {:enabled true
                       :jwt-secret "my-secret"
                       :seed-admin-password "strong-pass"}
                :webhook {:secret ""}          ;; empty string is truthy
                :secrets {:master-key "master-key"}
                :database {:type "postgresql"}})))))

;; ---------------------------------------------------------------------------
;; validate-runtime-config!: nil auth-token vs empty string
;; ---------------------------------------------------------------------------

(deftest validate-runtime-config-nil-token-test
  (testing "nil auth-token when distributed enabled throws (str/blank? nil = true)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"auth-token"
                          (config/validate-runtime-config!
                           {:distributed {:enabled true :auth-token nil}}))))

  (testing "validate-runtime-config! passes for non-distributed nil"
    (is (nil? (config/validate-runtime-config! {}))))

  (testing "validate-runtime-config! passes when distributed key absent entirely"
    (is (nil? (config/validate-runtime-config! {:auth {:enabled true}})))))

;; ---------------------------------------------------------------------------
;; load-config defaults completeness: ensure precedence ordering works
;; ---------------------------------------------------------------------------

(deftest load-config-env-overrides-coerce-test
  (testing "load-config defaults contain expected types (not just truthy)"
    (let [cfg (config/load-config)]
      ;; Verify specific types, not just presence
      (is (instance? Long (get-in cfg [:server :port])))
      (is (string? (get-in cfg [:server :host])))
      (is (boolean? (get-in cfg [:auth :enabled])))
      (is (keyword? (get-in cfg [:log :level])))
      (is (string? (get-in cfg [:database :type]))))))
