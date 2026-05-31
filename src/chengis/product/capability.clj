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
            [taoensso.timbre :as log]
            [chengis.product :as product]))

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
   Optional:
     :note               — extra caveat shown in listings
     :requires-database  — string like \"postgresql\" / \"sqlite\";
                           validate-capability-requirements! throws if
                           the capability is in the effective set but
                           the configured DB doesn't match. Use this
                           for capabilities whose semantics depend on
                           PG features (audit-chain seq_num invariants,
                           advisory locks, partitioning, …).
     :implied-by-feature-flag — set of legacy feature-flag keys (in
                           `:feature-flags`) that, when truthy, force
                           this capability into the effective set
                           even if the profile's defaults wouldn't.
                           Bridges existing feature-flag-gated modules
                           to the new capability model so that turning
                           on a UI module (e.g. :deployment-dashboard)
                           also includes the migrations and
                           subsystems the UI depends on, without
                           requiring operators to dual-configure."
  [k {:keys [description default-for] :as meta}]
  (when-not (keyword? k)
    (throw (ex-info "Capability key must be a keyword" {:key k})))
  (when (str/blank? description)
    (throw (ex-info "Capability needs :description" {:key k})))
  (when-not (set? default-for)
    (throw (ex-info "Capability :default-for must be a set of profiles"
                    {:key k :default-for default-for})))
  (swap! registry assoc k
         (select-keys meta [:description :default-for :note
                            :requires-database :implied-by-feature-flag])))

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

(defn- cfg-database [cfg]
  (some-> (get-in cfg [:database :type] "sqlite") str/lower-case))

(defn- cap-fits-db?
  "True iff the capability's `:requires-database` (if any) matches the
   actual configured DB type. Capabilities without a `:requires-database`
   key fit any DB."
  [cap-key actual-db]
  (let [req (get-in @registry [cap-key :requires-database])]
    (or (nil? req) (= req actual-db))))

(defn- default-set-for-profile [profile]
  (->> @registry
       (filter (fn [[_ m]] (contains? (:default-for m) profile)))
       (map key)
       set))

(defn defaults-demoted-by-db
  "Pure: the subset of the profile's defaults that get demoted because
   their `:requires-database` doesn't match the configured DB.

   This is how anvil-on-Postgres and chengis-on-SQLite stay usable in
   their off-preferred configurations: the framework auto-disables
   PG-only defaults when the DB can't support them, instead of
   throwing at boot. (Explicit `:enable` requests for the same caps
   still throw — see `validate-capability-requirements!`.)"
  [cfg profile]
  (let [actual-db (cfg-database cfg)]
    (->> (default-set-for-profile profile)
         (remove #(cap-fits-db? % actual-db))
         set)))

(defn implied-by-feature-flags
  "Pure: capabilities forced into the effective set by truthy entries
   in `(:feature-flags cfg)`.

   Bridges legacy flag-gated modules (e.g. `:deployment-dashboard` →
   the deploy UI) to the new capability model. When the flag is on,
   the matching capability is implied — its migrations apply, its
   subsystems load — without requiring the operator to also set
   `:product :capabilities :enable #{:deployments}`.

   The map is one-way (flag implies capability, never the reverse):
   capabilities can still be opted out via `:disable`, which wins
   over both defaults and flag-implications.

   Pairs with `register-capability!`'s `:implied-by-feature-flag`
   metadata key.

   Note: this returns the raw implication set; `effective-set` then
   filters caps whose `:requires-database` doesn't match the
   configured DB (same as it does for defaults), so a legacy flag
   that implies a PG-only cap on a SQLite install demotes the cap
   instead of throwing at boot. See `implied-demoted-by-db`."
  [cfg]
  (let [flags (or (get cfg :feature-flags) {})]
    (->> @registry
         (keep (fn [[k m]]
                 (when (some (fn [f] (boolean (get flags f)))
                             (:implied-by-feature-flag m #{}))
                   k)))
         set)))

(defn implied-demoted-by-db
  "Pure: the subset of `implied-by-feature-flags` that gets demoted
   because their `:requires-database` doesn't match the configured DB.

   Same model as `defaults-demoted-by-db`: flag implications are
   framework-managed (the operator turned on a UI flag, not the
   capability itself), so a DB mismatch should silently demote rather
   than throw at boot. The validator only throws on EXPLICIT operator
   enables — see `validate-capability-requirements!`."
  [cfg]
  (let [actual-db (cfg-database cfg)]
    (->> (implied-by-feature-flags cfg)
         (remove #(cap-fits-db? % actual-db))
         set)))

(defn effective-set
  "Pure: compute the effective capability set for a (cfg, profile) pair.

   Returns just the set of enabled capability keys.

   Decision rule:
     1. Start with the profile's defaults from the registry.
     2. **Demote** defaults whose `:requires-database` doesn't match the
        configured DB. (chengis defaults include `:audit-chain` which
        requires PG; running `chengis serve` on SQLite for dev/demo
        silently drops audit-chain — boot stays clean.)
     3. Add **feature-flag implications** — any capability whose
        `:implied-by-feature-flag` set contains a truthy flag from
        `(:feature-flags cfg)`. Bridges legacy flag-gated modules
        (e.g. `:deployment-dashboard` → `:deployments` cap) so an
        operator who turned on a UI module doesn't have to dual-
        configure. **Also demoted** on DB mismatch (same as defaults):
        a flag-implied cap that can't run on this DB silently drops,
        rather than throwing at boot — the operator turned on a flag,
        not the capability itself, so the framework absorbs the gap.
     4. Add explicit enables from config + env. These are NOT demoted
        — if the operator asked for `:audit-chain` on SQLite,
        `validate-capability-requirements!` throws with a clear
        diagnostic. (That's the operator-set vs framework-set split.)
     5. Remove explicit disables from config + env. Disable wins over
        defaults, flag-implications, and even explicit enables.
     6. Unknown keys in enable/disable are filtered out — the registry
        is the source of truth for valid capability names. `report`
        surfaces unknown references for diagnostics.

   `report` returns the same answer with source attribution suitable
   for boot logging and --list-capabilities."
  [cfg profile]
  (let [known     (set (keys @registry))
        actual-db (cfg-database cfg)
        defaults  (default-set-for-profile profile)
        ;; Step 2: demote defaults whose DB requirement isn't met.
        fitting-defaults (set/select #(cap-fits-db? % actual-db) defaults)
        ;; Step 3: caps forced on by legacy feature flags, then
        ;; demoted by the same DB-fit rule so a flag-only enablement
        ;; of a PG-only cap on SQLite doesn't trip the validator.
        implied (implied-by-feature-flags cfg)
        fitting-implied (set/select #(cap-fits-db? % actual-db) implied)
        cfg-enable  (set (get-in cfg [:product :capabilities :enable] #{}))
        cfg-disable (set (get-in cfg [:product :capabilities :disable] #{}))
        env-enable  (or (read-env-set "CHENGIS_ENABLE_CAPABILITIES") #{})
        env-disable (or (read-env-set "CHENGIS_DISABLE_CAPABILITIES") #{})
        ;; Filter against the registry — unknown keys are noted in
        ;; `report` but never participate in the effective set.
        enabled     (set/intersection known (set/union cfg-enable env-enable))
        disabled    (set/intersection known (set/union cfg-disable env-disable))]
    (-> fitting-defaults
        (set/union fitting-implied)
        (set/union enabled)
        (set/difference disabled))))

(defn report
  "Pure: detailed breakdown of where each effective capability came
   from, for boot logging + `--list-capabilities`.

     {:profile             :anvil | :chengis | nil
      :effective           #{…}
      :default-for-profile #{…}
      :demoted-by-db       #{…}   ; defaults dropped because :requires-database mismatch
      :implied-by-flag     #{…}   ; caps forced on by a truthy :feature-flags entry that DO fit the DB
      :implied-demoted-by-db #{…} ; flag-implications that DON'T fit the DB → dropped (logged)
      :explicitly-enabled  {:cap-key :source} ; :source = :config | :env
      :explicitly-disabled {:cap-key :source}
      :unknown-references  #{…}   ; keys in enable/disable that aren't registered
      :missing-in-registry #{…}}  ; alias — same as :unknown-references"
  [cfg profile]
  (let [actual-db   (cfg-database cfg)
        defaults    (default-set-for-profile profile)
        demoted     (defaults-demoted-by-db cfg profile)
        raw-implied (implied-by-feature-flags cfg)
        ;; Split implied into the half that fits the DB (lands in
        ;; :implied-by-flag and effective) and the half that doesn't
        ;; (lands in :implied-demoted-by-db, NOT in effective).
        implied            (set/select #(cap-fits-db? % actual-db) raw-implied)
        implied-demoted    (set/select #(not (cap-fits-db? % actual-db)) raw-implied)
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
     :demoted-by-db       demoted
     :implied-by-flag     implied
     :implied-demoted-by-db implied-demoted
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
    (when (seq (:demoted-by-db r))
      ;; Auto-disabled defaults the configured DB can't support. Common
      ;; case: `chengis serve` on SQLite for dev/demo — audit-chain,
      ;; ha-leader, multi-tenant, partitioned-logs all silently drop.
      (log/info (format "[capabilities] demoted (DB mismatch, database=%s): %s — explicit :enable would throw; subsystems that require these will see them as off"
                        (cfg-database cfg)
                        (pr-str (vec (sort (:demoted-by-db r)))))))
    (when (seq (:implied-by-flag r))
      ;; Capabilities forced on by truthy entries in :feature-flags. Lets
      ;; legacy flag-gated modules (e.g. :deployment-dashboard → :deployments)
      ;; pull in their migrations + subsystems without dual-configuration.
      (log/info (format "[capabilities] implied by feature-flags: %s"
                        (pr-str (vec (sort (:implied-by-flag r)))))))
    (when (seq (:implied-demoted-by-db r))
      ;; Flag-implied capabilities that couldn't be honored because their
      ;; :requires-database didn't match. The operator turned on a flag
      ;; (e.g. :org-management-ui) that implies a PG-only cap
      ;; (:multi-tenant) on a SQLite install — we don't throw, we drop
      ;; the cap and warn so the operator knows the UI's full feature
      ;; set won't be available.
      (log/warn (format "[product-fit] feature flag implied caps that don't fit database=%s — dropped: %s. The legacy UI flag still loads, but capability-gated subsystems will be inactive."
                        (cfg-database cfg)
                        (pr-str (vec (sort (:implied-demoted-by-db r)))))))
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
;; :requires-database — capability ↔ DB requirement enforcement
;;
;; Some enterprise capabilities are PG-only by structural fact: audit-
;; chain seq_num invariants, advisory-locked leader election,
;; partitioned logs, multi-tenant FK ordering. The framework handles
;; mismatched configs two different ways:
;;
;;   1. **Default-on capabilities** whose `:requires-database` doesn't
;;      match the configured DB are silently DEMOTED by `effective-set`.
;;      Boot succeeds with an info log noting the demotion. This is
;;      how `chengis serve` on SQLite stays usable for dev/demo even
;;      though the default chengis bundle includes PG-only caps.
;;
;;   2. **Explicit `:enable` requests** for a cap whose
;;      `:requires-database` mismatches are an operator decision that
;;      can't work — those throw at boot via
;;      `validate-capability-requirements!`. Strict mode is irrelevant;
;;      this is structural correctness, not preferential posture.
;;
;; The split exists because (1) frees the framework from punishing
;; operators for default-set decisions they didn't make, while (2)
;; still surfaces "I asked for audit-chain on SQLite and it won't work"
;; loudly.
;; ---------------------------------------------------------------------------

(defn unmet-capability-requirements
  "Pure: enumerate capabilities whose `:requires-database` is
   unsatisfied AND that are present in the effective set.

   Because `effective-set` already DEMOTES default-on capabilities
   whose DB requirement isn't met, the only way a capability ends up
   both in `enabled-caps` and with an unmet `:requires-database` is
   if the operator explicitly `:enable`d it. So this returns the
   explicit-enable mismatches — exactly the cases
   `validate-capability-requirements!` should throw on."
  [cfg enabled-caps]
  (let [actual-db (cfg-database cfg)]
    (vec
     (for [k    (sort enabled-caps)
           :let [m (get @registry k)
                 req (:requires-database m)]
           :when (and req (not= req actual-db))]
       (cond-> {:capability k
                :required   req
                :actual     actual-db}
         (:note m) (assoc :note (:note m)))))))

(defn- format-unmet [unmet]
  (str/join "\n"
            (for [{:keys [capability required actual note]} unmet]
              (str "  - :" (name capability)
                   " requires database=" required
                   " but database=" actual " is configured"
                   (when note (str "\n    (" note ")"))))))

(defn validate-capability-requirements!
  "Side-effecting: throw if any *explicitly enabled* capability has an
   unmet `:requires-database`. Default-on capabilities that mismatch
   are silently demoted by `effective-set` and never reach this check
   — that's how `chengis serve` on SQLite stays usable in dev/demo.

   Always throws when an explicit-enable mismatch is found (strict
   mode is irrelevant — this is a structural correctness check, not a
   posture preference). No-op when no profile has been declared.

   Wired into `chengis.config/validate-runtime-config!` after
   `set-active!` so the check sees the resolved effective set."
  ([cfg] (validate-capability-requirements! cfg (product/profile)))
  ([cfg active-profile]
   (when active-profile
     (let [eff   (effective-set cfg active-profile)
           unmet (unmet-capability-requirements cfg eff)]
       (when (seq unmet)
         (throw (ex-info
                 (str "Capability requirements unmet for profile "
                      (pr-str active-profile) ":\n"
                      (format-unmet unmet)
                      "\nFix: switch the database (CHENGIS_DATABASE_TYPE=…) "
                      "or disable the capability (CHENGIS_DISABLE_CAPABILITIES="
                      (str/join "," (map (comp name :capability) unmet))
                      ").")
                 {:type :capability-requirement-unmet
                  :profile active-profile
                  :unmet unmet})))))))

;; ---------------------------------------------------------------------------
;; Listing — used by the --list-capabilities CLI on both products
;; ---------------------------------------------------------------------------

(declare all-capabilities)

(defn- pad-right [s n]
  (let [s (str s)
        diff (- n (count s))]
    (if (pos? diff) (str s (apply str (repeat diff " "))) s)))

(defn format-listing
  "Pure: build a human-readable listing of the capability registry,
   marking each entry with its effective state for `profile`.

   `:cfg` (optional) and `:profile` (optional) shape the listing:
     - With both: shows effective state per capability
       (on / off / on-explicit / off-explicit).
     - Without :profile: shows just the registry inventory.

   Returns a single string with a trailing newline."
  ([] (format-listing {}))
  ([{:keys [cfg profile]}]
   (let [caps (all-capabilities)
         eff  (when profile (effective-set (or cfg {}) profile))
         rpt  (when profile (report (or cfg {}) profile))
         lines
         (concat
          [(str "Capabilities — " (count caps) " registered"
                (when profile (str ", profile=" profile))
                (when profile (str ", effective=" (count eff))))
           (str (apply str (repeat 78 \-)))]
          (for [{:keys [key description default-for note requires-database]} caps
                :let [state (cond
                              (not profile)                                            "—"
                              (contains? (get-in rpt [:explicitly-enabled])  key)      "on (explicit)"
                              (contains? (get-in rpt [:explicitly-disabled]) key)      "off (explicit)"
                              (contains? (get-in rpt [:demoted-by-db]) key)            "off (db-mismatch)"
                              (contains? (get-in rpt [:implied-by-flag]) key)          "on (flag)"
                              (contains? eff key)                                      "on"
                              :else                                                    "off")]]
            (str
             "  " (pad-right (str ":" (name key)) 26)
             " " (pad-right state 20)
             "default-for=" (pr-str (vec (sort default-for)))
             (when requires-database (str "  requires-db=" requires-database))
             "\n    " description
             (when note (str "\n    " note))))
          (when (and profile (seq (:demoted-by-db rpt)))
            [""
             "Demoted (DB mismatch):"
             (str "  the following defaults were turned off because the configured database "
                  "doesn't satisfy their :requires-database — explicit :enable would throw at boot:")
             (str "  " (pr-str (vec (sort (:demoted-by-db rpt)))))])
          (when (and profile (seq (:unknown-references rpt)))
            [""
             "Warnings:"
             (str "  unknown capability keys in config/env (ignored): "
                  (pr-str (vec (sort (:unknown-references rpt)))))]))]
     (str (str/join "\n" lines) "\n"))))

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
                       :default-for       #{:chengis}
                       :requires-database "postgresql"
                       :implied-by-feature-flag #{:org-management-ui}
                       :note "Multi-tenant invariants (FK chains, isolation, audit ordering) assume PG semantics under sustained concurrent writers. The legacy `:org-management-ui` flag (module/org_management.clj) implies this cap so its org_invitations / org_quotas / org_branding routes find their tables."})

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
                       :default-for       #{:chengis}
                       :requires-database "postgresql"
                       :note "seq_num invariants don't hold on SQLite under sustained concurrent writers."})

(register-capability! :approval-gates
                      {:description "Manual approval gates between pipeline stages"
                       :default-for #{:chengis}})

(register-capability! :opa-policies
                      {:description "OPA policy enforcement"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:slsa-provenance}
                       :note "The legacy `:slsa-provenance` flag (module/supply_chain.clj) enables opa-policies pages alongside SBOM/provenance — bridging here keeps the migration filter aligned with the UI gate."})

(register-capability! :sbom-provenance
                      {:description "SBOM + provenance attestations + signing"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:slsa-provenance}
                       :note "Bridged to the legacy `:slsa-provenance` feature flag (module/supply_chain.clj) so supply-chain handler routes find sbom_records / provenance_attestations / signatures tables."})

(register-capability! :ha-leader
                      {:description "PG advisory-locked leader election for singleton services"
                       :default-for       #{:chengis}
                       :requires-database "postgresql"
                       :note "Advisory locks are PG-specific; no SQLite equivalent."})

(register-capability! :multi-region-agents
                      {:description "Region-aware agent dispatch"
                       :default-for #{:chengis}})

(register-capability! :cost-analytics
                      {:description "Per-build cost attribution + reporting"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:build-analytics :cost-attribution}
                       :note "Bridged to BOTH `:build-analytics` (module/analytics.clj cost-page READS build_cost_entries) AND `:cost-attribution` (engine/cost.clj WRITES build_cost_entries on every build completion when the flag is on). Either flag implies the cap so the migration filter keeps 042-build-cost-entries."})

(register-capability! :flaky-detection
                      {:description "Automated flaky-test detection from build history"
                       :default-for #{:chengis}})

(register-capability! :partitioned-logs
                      {:description "Partitioned build_log_chunks for high-volume log retention"
                       :default-for       #{:chengis}
                       :requires-database "postgresql"
                       :note "Declarative partitioning is PG-only; SQLite would have to fake it."})

(register-capability! :iac
                      {:description "Infrastructure-as-Code projects, states, cost estimates, dashboards"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:infrastructure-as-code}
                       :note "Bridged to the legacy `:infrastructure-as-code` flag (module/iac.clj) so /iac routes find their tables."})

(register-capability! :deployments
                      {:description "Environments, releases, artifact promotions, deployment strategies, health checks"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:deployment-dashboard}
                       :note "Bridged to the legacy :deployment-dashboard feature flag — an operator who turned the deploy UI on automatically gets the migrations + subsystems this cap controls, without dual-configuring."})

(register-capability! :secret-rotation
                      {:description "Secret rotation policies + versioned secret history"
                       :default-for #{:chengis}
                       :implied-by-feature-flag #{:secret-rotation}
                       :note "Bridged to the legacy `:secret-rotation` feature flag (read by module/enterprise_auth.clj to start the rotation scheduler) so the 057/058 migrations apply when the scheduler runs."})

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
