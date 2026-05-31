(ns chengis.product.capability
  "Named feature gates — the framework that makes the preferred-shape
   + optionality model from `chengis.product` actually crisp.

   A capability is a named feature with three pieces of metadata:
     :description — one-liner shown in the boot log + --list-capabilities
     :default-for — set of profiles where it's on by default
     :note        — optional — additional caveat / pointer

   At boot, the **effective capability set** for a (cfg, profile) pair
   is computed as:

     effective = (profile-defaults ∪ explicit-enables) − explicit-disables

   Explicit enables / disables come from config (`:product
   :capabilities :enable #{…}` / `:disable #{…}`) or env vars
   (`CHENGIS_ENABLE_CAPABILITIES=audit-chain,saml` /
   `CHENGIS_DISABLE_CAPABILITIES=cost-analytics`).

   Subsystems use two predicates:

     (has-capability? :foo)     — boolean, for graceful-degradation paths
     (require-capability! :foo) — throws if absent, for hard-required paths

   A subsystem that requires audit-chain (which only makes sense on
   Postgres) does:

     (require-capability! :audit-chain)
     ;; if we got here, audit-chain is in the effective set; on anvil
     ;; that means the operator turned it on explicitly and accepted
     ;; the operational story.

   Strict mode (`CHENGIS_STRICT_PROFILE=true`) doesn't change capability
   semantics — capabilities are opt-in either way. Strict mode is
   about hard-rejecting *configuration* shapes (anvil-on-PG / chengis-
   on-SQLite) rather than gating *feature* loading."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private registry
  "capability-key → metadata map.
   Populated by `register-capability!` at the bottom of this namespace
   and (optionally) by product code or plugins at load time."
  (atom {}))

(defn register-capability!
  "Register a capability with its metadata. Re-registering the same key
   overwrites — that's intentional, so plugins can adjust defaults if
   needed (with auditable consequences).

   Required keys: :description, :default-for (set of profiles).
   Optional: :note."
  [k {:keys [description default-for] :as meta}]
  (when-not (keyword? k)
    (throw (ex-info "Capability key must be a keyword" {:key k})))
  (when (str/blank? description)
    (throw (ex-info "Capability needs :description" {:key k})))
  (when-not (set? default-for)
    (throw (ex-info "Capability :default-for must be a set of profiles"
                    {:key k :default-for default-for})))
  (swap! registry assoc k (select-keys meta [:description :default-for :note])))

(defn registry-snapshot
  "Return the current registry as plain data (for tests, CLI display)."
  []
  @registry)

(defn known? [k]
  (contains? @registry k))

;; ---------------------------------------------------------------------------
;; Effective-set computation — pure
;; ---------------------------------------------------------------------------

(defn- read-env-set [env-name]
  (when-let [raw (System/getenv env-name)]
    (->> (str/split raw #",")
         (map str/trim)
         (remove str/blank?)
         (map keyword)
         set)))

(defn effective-set
  "Pure: compute the effective capability set for a (cfg, profile) pair.

   Returns just the set of enabled capability keys.

   Unknown keys (referenced in enable/disable but not registered) are
   filtered out of the effective set — the registry is the source of
   truth for valid capability names. `report` (below) surfaces the
   unknown references for diagnostics.

   `report` returns the same answer with source attribution suitable
   for boot logging and --list-capabilities."
  [cfg profile]
  (let [known    (set (keys @registry))
        defaults (->> @registry
                      (filter (fn [[_ m]] (contains? (:default-for m) profile)))
                      (map key)
                      set)
        cfg-enable  (set (get-in cfg [:product :capabilities :enable] #{}))
        cfg-disable (set (get-in cfg [:product :capabilities :disable] #{}))
        env-enable  (or (read-env-set "CHENGIS_ENABLE_CAPABILITIES") #{})
        env-disable (or (read-env-set "CHENGIS_DISABLE_CAPABILITIES") #{})
        ;; Filter against the registry — unknown keys are noted in
        ;; `report` but never participate in the effective set.
        enabled     (set/intersection known (set/union cfg-enable env-enable))
        disabled    (set/intersection known (set/union cfg-disable env-disable))]
    (-> defaults
        (set/union enabled)
        (set/difference disabled))))

(defn report
  "Pure: detailed breakdown of where each effective capability came
   from, for boot logging + `--list-capabilities`.

     {:profile             :anvil | :chengis | nil
      :effective           #{…}
      :default-for-profile #{…}
      :explicitly-enabled  {:cap-key :source} ; :source = :config | :env
      :explicitly-disabled {:cap-key :source}
      :unknown-references  #{…}   ; keys in enable/disable that aren't registered
      :missing-in-registry #{…}}  ; alias — same as :unknown-references"
  [cfg profile]
  (let [defaults    (->> @registry
                         (filter (fn [[_ m]] (contains? (:default-for m) profile)))
                         (map key)
                         set)
        cfg-enable  (set (get-in cfg [:product :capabilities :enable] #{}))
        cfg-disable (set (get-in cfg [:product :capabilities :disable] #{}))
        env-enable  (or (read-env-set "CHENGIS_ENABLE_CAPABILITIES") #{})
        env-disable (or (read-env-set "CHENGIS_DISABLE_CAPABILITIES") #{})
        eff         (effective-set cfg profile)
        ;; "explicitly-X" only counts entries that actually changed the
        ;; effective set vs. the profile defaults.
        explicitly-enabled
        (cond-> {}
          true (into (for [k (set/difference cfg-enable defaults)] [k :config]))
          true (into (for [k (set/difference env-enable defaults cfg-enable)] [k :env])))
        explicitly-disabled
        (cond-> {}
          true (into (for [k (set/intersection cfg-disable defaults)] [k :config]))
          true (into (for [k (set/intersection env-disable defaults)
                           :when (not (contains? cfg-disable k))] [k :env])))
        all-referenced (set/union cfg-enable cfg-disable env-enable env-disable)
        unknown        (set/difference all-referenced (set (keys @registry)))]
    {:profile             profile
     :effective           eff
     :default-for-profile defaults
     :explicitly-enabled  explicitly-enabled
     :explicitly-disabled explicitly-disabled
     :unknown-references  unknown
     :missing-in-registry unknown}))

;; ---------------------------------------------------------------------------
;; Active state — set once at boot, read by subsystems
;; ---------------------------------------------------------------------------

(def ^:private active-state
  "Holds the resolved effective set + report after `set-active!`. Nil
   until then so subsystems can detect 'boot hasn't run yet'."
  (atom nil))

(defn set-active!
  "Compute the effective set for (cfg, profile) and store it for
   subsystem queries. Called from `chengis.config/validate-runtime-config!`
   so any boot path that loads config also resolves capabilities.

   Returns the report map."
  [cfg profile]
  (let [r (report cfg profile)]
    (when (seq (:unknown-references r))
      (log/warn (str "[capabilities] Unknown capability keys referenced in "
                     "config/env (ignored): "
                     (pr-str (:unknown-references r)))))
    (log/info (format "[capabilities] profile=%s effective=%s explicit-on=%s explicit-off=%s"
                      (pr-str profile)
                      (pr-str (vec (sort (:effective r))))
                      (pr-str (vec (sort (keys (:explicitly-enabled r)))))
                      (pr-str (vec (sort (keys (:explicitly-disabled r)))))))
    (reset! active-state r)
    r))

(defn active-report
  "The last report computed by `set-active!`, or nil if boot hasn't
   resolved capabilities yet."
  []
  @active-state)

(defn reset-for-test!
  "Clear active state. Test-only."
  []
  (reset! active-state nil))

(defn install-state-for-test!
  "Replace the active state atom with the given report-shaped value.
   Test-only; lets `with-capabilities` (which expands in callers'
   namespaces) install fake state without reaching a private var."
  [state]
  (reset! active-state state))

(defn snapshot-state-for-test!
  "Read the current active state atom for save/restore in test macros."
  []
  @active-state)

;; ---------------------------------------------------------------------------
;; Subsystem predicates
;; ---------------------------------------------------------------------------

(defn has-capability?
  "Truthy iff the capability is in the active effective set. Returns
   nil if `set-active!` hasn't been called (REPL / test / ad-hoc
   scripts) — callers should default to *disabled* in that case so
   tests don't accidentally exercise enterprise paths."
  [k]
  (when-let [s @active-state]
    (contains? (:effective s) k)))

(defn require-capability!
  "Throw if the capability is missing from the effective set. Use at
   the top of subsystem files that cannot function without it.

   Throws also if `set-active!` hasn't been called — a subsystem that
   `require`s a capability must be loaded after boot wiring. Tests
   that want to bypass this should use `with-capabilities`."
  [k]
  (let [s @active-state]
    (cond
      (nil? s)
      (throw (ex-info
              (str "require-capability! :" (name k)
                   " called before capabilities were resolved.\n"
                   "Each product's -main calls chengis.product/set-profile! "
                   "and chengis.config/validate-runtime-config! (which "
                   "resolves capabilities) before subsystems load.")
              {:type :capabilities-not-resolved :capability k}))

      (not (known? k))
      (throw (ex-info
              (str "require-capability! :" (name k)
                   " — unknown capability. Register it with "
                   "(register-capability! ...) before requiring it.")
              {:type :unknown-capability :capability k}))

      (not (contains? (:effective s) k))
      (throw (ex-info
              (str "Capability :" (name k) " is not enabled for profile "
                   (pr-str (:profile s)) ".\n"
                   "Enable it via :product :capabilities :enable in config "
                   "or CHENGIS_ENABLE_CAPABILITIES=" (name k) ".")
              {:type :capability-not-enabled
               :capability k
               :profile (:profile s)
               :effective (:effective s)})))))

(defmacro with-capabilities
  "Test helper: temporarily install a fake effective set with the given
   profile + capability collection. Restores prior state afterward.

   Uses the public `install-state-for-test!` / `snapshot-state-for-test!`
   wrappers so the macro can expand cleanly in caller namespaces without
   touching this ns's private atom."
  [profile caps & body]
  `(let [prior# (snapshot-state-for-test!)]
     (try
       (install-state-for-test!
        {:profile             ~profile
         :effective           (set ~caps)
         :default-for-profile (set ~caps)
         :explicitly-enabled  {}
         :explicitly-disabled {}
         :unknown-references  #{}
         :missing-in-registry #{}})
       ~@body
       (finally
         (install-state-for-test! prior#)))))

;; ---------------------------------------------------------------------------
;; Built-in capabilities
;;
;; The matrix that the boundary doc describes, in code. Adding a new
;; capability means registering it here (so it's visible to
;; --list-capabilities and to the test that pins the inventory).
;;
;; Default-for sets reflect the marquee install-class story:
;;   :anvil       — local-team OSS, SQLite, single-binary
;;   :chengis     — enterprise tier, multi-tenant, audited, HA, SaaS
;; Anything PG-shaped goes :default-for #{:chengis}; an operator who
;; runs anvil-on-PG and wants e.g. :audit-chain enables it explicitly.
;; ---------------------------------------------------------------------------

(register-capability! :local-secrets
                      {:description "AES-256-GCM-in-DB secret backend with local master key"
                       :default-for #{:anvil :chengis}})

(register-capability! :vault-secrets
                      {:description "HashiCorp Vault secret backend"
                       :default-for #{:chengis}
                       :note "Pluggable on either product; not on by default on anvil."})

(register-capability! :cloud-secrets
                      {:description "AWS Secrets Manager / GCP Secret Manager / Azure Key Vault"
                       :default-for #{:chengis}})

(register-capability! :local-artifacts
                      {:description "Local-filesystem artifact storage"
                       :default-for #{:anvil :chengis}})

(register-capability! :object-store-artifacts
                      {:description "S3 / GCS / Azure Blob artifact storage"
                       :default-for #{:chengis}})

(register-capability! :multi-tenant
                      {:description "Organization-scoped resources, RBAC, org_members"
                       :default-for #{:chengis}
                       :note "Requires PostgreSQL — see chengis.product/check-database-fit."})

(register-capability! :saml
                      {:description "SAML 2.0 SSO"
                       :default-for #{:chengis}})

(register-capability! :ldap
                      {:description "LDAP / Active Directory auth"
                       :default-for #{:chengis}})

(register-capability! :oidc
                      {:description "OpenID Connect auth"
                       :default-for #{:chengis}})

(register-capability! :totp
                      {:description "TOTP-based MFA"
                       :default-for #{:chengis}})

(register-capability! :audit-chain
                      {:description "Hash-chained, tamper-evident audit_events"
                       :default-for #{:chengis}
                       :note "Requires PostgreSQL — seq_num invariants don't hold on SQLite under sustained concurrent writers."})

(register-capability! :approval-gates
                      {:description "Manual approval gates between pipeline stages"
                       :default-for #{:chengis}})

(register-capability! :opa-policies
                      {:description "OPA policy enforcement"
                       :default-for #{:chengis}})

(register-capability! :sbom-provenance
                      {:description "SBOM + provenance attestations + signing"
                       :default-for #{:chengis}})

(register-capability! :ha-leader
                      {:description "PG advisory-locked leader election for singleton services"
                       :default-for #{:chengis}
                       :note "Requires PostgreSQL."})

(register-capability! :multi-region-agents
                      {:description "Region-aware agent dispatch"
                       :default-for #{:chengis}})

(register-capability! :cost-analytics
                      {:description "Per-build cost attribution + reporting"
                       :default-for #{:chengis}})

(register-capability! :flaky-detection
                      {:description "Automated flaky-test detection from build history"
                       :default-for #{:chengis}})

(register-capability! :partitioned-logs
                      {:description "Partitioned build_log_chunks for high-volume log retention"
                       :default-for #{:chengis}})

(register-capability! :hosted-saas
                      {:description "Hosted-SaaS control-plane operations"
                       :default-for #{:chengis}
                       :note "Hard-no on anvil: anvil ships as software, never as a service."})

;; ---------------------------------------------------------------------------
;; Convenience for documentation / CLIs
;; ---------------------------------------------------------------------------

(defn all-capabilities
  "Vector of {:key :description :default-for :note} maps, sorted by key.
   Used by --list-capabilities."
  []
  (->> @registry
       (map (fn [[k m]] (merge {:key k} m)))
       (sort-by :key)
       vec))
