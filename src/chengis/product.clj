(ns chengis.product
  "Product profile — which product is booting on top of chengis-core.

   The shared engine library (chengis-core) is consumed by two products:

     :anvil    — Jenkins-compatible OSS, single-team, single-binary
                 install. PREFERS SQLite. CAN run on Postgres if the
                 operator asks for it.
     :chengis  — enterprise tier, multi-tenant, audited, HA.
                 PREFERS Postgres. CAN run on SQLite for dev/demo
                 (production is gated separately by
                 `validate-production-config!`).

   The model is **preferred shape + optionality**, not forbidden
   combos. Each product has a default that ships in the marquee
   install-class story; off-preferred configs boot with a clear
   `[product-fit]` warning so the operator knows they're off the
   supported hot path. Strict mode (`CHENGIS_STRICT_PROFILE=true`)
   re-enables hard rejection — for callers who want the boundary
   enforced, e.g. the Chengis hosted SaaS control plane.

   Each product's `-main` declares its profile at boot via
   `(set-profile! :anvil)` or `(set-profile! :chengis)`. The same
   profile drives the planned capability registry (per-feature opt-in
   flags) — see `chengis.product.capability`.

   See `docs/architecture/anvil-vs-chengis-boundary.md` for the
   product-level rationale (bundling, support, defaults)."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Profile state
;; ---------------------------------------------------------------------------

(def ^:private valid-profiles
  "The product profiles chengis-core recognizes. Adding a new product
   means extending this set and updating the boundary doc + tests."
  #{:anvil :chengis})

(def ^:private profile-state
  "Holds the active profile, or `nil` when nothing has declared one
   (e.g. test runners, REPL sessions, ad-hoc scripts). Subsystems that
   require a profile should call `assert-profile!`."
  (atom nil))

(defn set-profile!
  "Declare the active product profile. Called once from each product's
   `-main` before anything else loads config or touches the DB."
  [p]
  (when-not (contains? valid-profiles p)
    (throw (ex-info (str "Unknown product profile: " (pr-str p)
                         ". Must be one of " (pr-str valid-profiles))
                    {:type :unknown-profile
                     :profile p
                     :valid valid-profiles})))
  (reset! profile-state p))

(defn profile
  "The active product profile, or `nil` if none has been set yet."
  []
  @profile-state)

(defn anvil? []     (= :anvil (profile)))
(defn chengis? []   (= :chengis (profile)))

(defn assert-profile!
  "Throw if no profile has been declared. Use at the top of subsystems
   that need to dispatch on profile."
  []
  (when (nil? (profile))
    (throw (ex-info "No product profile declared. Call (chengis.product/set-profile! :anvil) or (set-profile! :chengis) from your product's -main before booting subsystems."
                    {:type :no-profile}))))

(defn reset-for-test!
  "Clear the profile. Intended for use in test fixtures only — do not
   call from product code."
  []
  (reset! profile-state nil))

;; ---------------------------------------------------------------------------
;; DB-type fit — preferred shape + optionality
;;
;;                    SQLite                    PostgreSQL
;;     :anvil    [:ok preferred]           [:warn off-preferred]
;;     :chengis  [:warn off-preferred]     [:ok preferred]
;;
;; `check-database-fit` returns a fit-report map describing where the
;; (profile, db-type) tuple sits in this matrix. Off-preferred combos
;; are documented and supported but log a `[product-fit]` warning so
;; the operator knows they're off the marquee install-class story.
;;
;; `validate-database-fit!` is the side-effecting wrapper that logs the
;; warning and — in **strict mode** — throws instead. Strict mode is
;; opt-in via `CHENGIS_STRICT_PROFILE=true` (env) or `:product
;; :strict-profile? true` (config). It exists for callers who want the
;; boundary enforced: the Chengis hosted SaaS control plane,
;; production deployments that want to fail loudly rather than warn.
;;
;; Called from `chengis.config/validate-runtime-config!` so every
;; config-loader path picks it up. Callers may pass an explicit
;; profile (preferred in tests).
;; ---------------------------------------------------------------------------

(def ^:private boundary-doc-link
  "See docs/architecture/anvil-vs-chengis-boundary.md for the rationale.")

(defn- db-type [cfg]
  (str/lower-case (str (get-in cfg [:database :type] "sqlite"))))

(defn- strict-mode?
  "True iff the operator asked for hard rejection of off-preferred
   combos. Either `CHENGIS_STRICT_PROFILE=true` (env) or `:product
   :strict-profile? true` in config flips this on."
  [cfg]
  (let [env (some-> (System/getenv "CHENGIS_STRICT_PROFILE")
                    str/lower-case)]
    (or (= env "true")
        (= env "1")
        (true? (get-in cfg [:product :strict-profile?])))))

(def ^:private preferred-db
  {:anvil   "sqlite"
   :chengis "postgresql"})

(defn- fit-message [profile db]
  (case [profile db]
    [:anvil "postgresql"]
    (str "[product-fit] anvil + PostgreSQL: off the preferred install path. "
         "Anvil ships with SQLite as the default — single binary, single "
         "writer, 5-minute install. Postgres is supported but you own the "
         "operational story (provisioning, backups, schema apply). The "
         "Chengis enterprise tier bundles Postgres + multi-tenancy + audit "
         "+ HA + SAML + SLA support.\n"
         boundary-doc-link)

    [:chengis "sqlite"]
    (str "[product-fit] chengis + SQLite: off the preferred install path. "
         "Chengis is built around Postgres for multi-tenant, audit, HA, "
         "and leader-elected singletons; SQLite is fine for dev / demo / "
         "single-user evaluation but cannot enforce the audit-chain "
         "seq_num invariants under concurrent writers. Production runs "
         "should set CHENGIS_DATABASE_TYPE=postgresql (and is enforced "
         "by validate-production-config!).\n"
         boundary-doc-link)

    nil))

(defn check-database-fit
  "Return a fit-report map for the given config + profile. Pure
   function — does not log, does not throw, does not read env.

     {:profile         :anvil | :chengis | nil
      :database-type   STRING            ; e.g. \"sqlite\" / \"postgresql\"
      :preferred       STRING            ; the preferred db for the profile
      :fit             :ok | :off-preferred | :no-profile
      :message         STRING?           ; explanation if off-preferred
      :strict?         BOOL              ; whether strict mode is on}"
  ([cfg] (check-database-fit cfg (profile)))
  ([cfg active-profile]
   (let [db   (db-type cfg)
         pref (get preferred-db active-profile)
         fit  (cond
                (nil? active-profile)  :no-profile
                (= db pref)            :ok
                :else                  :off-preferred)]
     (cond-> {:profile active-profile
              :database-type db
              :preferred pref
              :fit fit
              :strict? (strict-mode? cfg)}
       (= fit :off-preferred)
       (assoc :message (fit-message active-profile db))))))

(defn validate-database-fit!
  "Side-effecting fit check: warn on off-preferred, throw if strict
   mode is on. No-op when no profile is declared.

   Called from `chengis.config/validate-runtime-config!`. Returns the
   fit-report for callers that want it."
  ([cfg] (validate-database-fit! cfg (profile)))
  ([cfg active-profile]
   (let [report (check-database-fit cfg active-profile)]
     (when (= :off-preferred (:fit report))
       (if (:strict? report)
         (throw (ex-info
                 (str "Strict mode rejected off-preferred database config.\n"
                      (:message report))
                 (merge {:type :strict-product-fit-violation}
                        (dissoc report :message))))
         (log/warn (:message report))))
     report)))

;; Backwards-compatible alias for callers using the previous API.
(defn validate-database-compatibility!
  "Deprecated alias for `validate-database-fit!`. The previous behavior
   (hard reject) is preserved by setting strict mode."
  ([cfg] (validate-database-fit! cfg))
  ([cfg active-profile] (validate-database-fit! cfg active-profile)))
