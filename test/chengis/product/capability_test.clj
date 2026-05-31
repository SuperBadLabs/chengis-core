(ns chengis.product.capability-test
  "Tests for the capability registry — per-feature opt-in framework
   that backs the preferred-shape + optionality model.

   Matrix coverage:

     1. Profile defaults — :anvil and :chengis each have a sensible
        starter set; chengis is a strict superset of anvil for
        capabilities that exist on both.
     2. Explicit enable on anvil — operator opts in to an enterprise
        capability; subsystem load succeeds.
     3. Explicit disable on chengis — operator turns off a default
        chengis capability; subsystem hard-fail is visible.
     4. Env-var overrides — same semantics as config keys.
     5. Unknown capability references — logged at boot, ignored at
        resolution.
     6. has-capability? vs require-capability! semantics.
     7. Pre-boot guard — require-capability! before set-active!.
     8. with-capabilities macro for test isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.product :as product]
            [chengis.product.capability :as cap]))

(use-fixtures :each
  (fn [t]
    (cap/reset-for-test!)
    (product/reset-for-test!)
    (try (t)
         (finally
           (cap/reset-for-test!)
           (product/reset-for-test!)))))

(defn- cfg
  ([] {})
  ([enable disable]
   {:product {:capabilities {:enable (set enable)
                             :disable (set disable)}}}))

;; ---------------------------------------------------------------------------
;; 1. Profile defaults — the matrix
;; ---------------------------------------------------------------------------

(deftest anvil-default-set-is-the-team-tier
  (testing "anvil's defaults are the team-tier capabilities, no enterprise gates"
    (let [eff (cap/effective-set (cfg) :anvil)]
      ;; on for anvil:
      (is (contains? eff :local-secrets))
      (is (contains? eff :local-artifacts))
      ;; off for anvil:
      (is (not (contains? eff :multi-tenant)))
      (is (not (contains? eff :audit-chain)))
      (is (not (contains? eff :saml)))
      (is (not (contains? eff :ha-leader)))
      (is (not (contains? eff :hosted-saas)))
      (is (not (contains? eff :object-store-artifacts))))))

(deftest chengis-default-set-bundles-the-enterprise-features
  (testing "chengis defaults under postgresql bundle the full enterprise stack"
    ;; Use postgresql to keep PG-only defaults in the effective set —
    ;; under sqlite, audit-chain / multi-tenant / ha-leader /
    ;; partitioned-logs get demoted (see chengis-on-sqlite-demotes-pg-caps
    ;; below).
    (let [eff (cap/effective-set {:database {:type "postgresql"}} :chengis)]
      (is (contains? eff :multi-tenant))
      (is (contains? eff :audit-chain))
      (is (contains? eff :saml))
      (is (contains? eff :ldap))
      (is (contains? eff :oidc))
      (is (contains? eff :totp))
      (is (contains? eff :ha-leader))
      (is (contains? eff :approval-gates))
      (is (contains? eff :opa-policies))
      (is (contains? eff :sbom-provenance))
      (is (contains? eff :multi-region-agents))
      (is (contains? eff :cost-analytics))
      (is (contains? eff :flaky-detection))
      (is (contains? eff :partitioned-logs))
      (is (contains? eff :hosted-saas))
      (is (contains? eff :vault-secrets))
      (is (contains? eff :cloud-secrets))
      (is (contains? eff :object-store-artifacts))
      ;; The three capabilities added in the 4c follow-up:
      (is (contains? eff :iac))
      (is (contains? eff :deployments))
      (is (contains? eff :secret-rotation))
      ;; The team-tier ones are still in chengis (they're shared building blocks):
      (is (contains? eff :local-secrets))
      (is (contains? eff :local-artifacts)))))

(deftest chengis-on-sqlite-demotes-pg-caps-but-boots
  (testing "chengis + sqlite (dev/demo path) silently demotes PG-only defaults"
    (let [eff (cap/effective-set {:database {:type "sqlite"}} :chengis)]
      ;; Demoted (PG-only):
      (is (not (contains? eff :audit-chain)))
      (is (not (contains? eff :ha-leader)))
      (is (not (contains? eff :multi-tenant)))
      (is (not (contains? eff :partitioned-logs)))
      ;; Still on (no PG requirement):
      (is (contains? eff :saml))
      (is (contains? eff :ldap))
      (is (contains? eff :oidc))
      (is (contains? eff :totp))
      (is (contains? eff :approval-gates))
      (is (contains? eff :opa-policies))
      (is (contains? eff :sbom-provenance))
      (is (contains? eff :hosted-saas))
      (is (contains? eff :iac))
      (is (contains? eff :deployments))
      (is (contains? eff :secret-rotation))
      (is (contains? eff :local-secrets))
      (is (contains? eff :local-artifacts)))))

(deftest defaults-demoted-by-db-helper
  (testing "anvil + sqlite has nothing to demote (no PG-only caps in anvil defaults)"
    (is (empty? (cap/defaults-demoted-by-db {:database {:type "sqlite"}} :anvil))))
  (testing "anvil + postgresql also nothing to demote"
    (is (empty? (cap/defaults-demoted-by-db {:database {:type "postgresql"}} :anvil))))
  (testing "chengis + sqlite demotes the 4 PG-only defaults"
    (is (= #{:audit-chain :ha-leader :multi-tenant :partitioned-logs}
           (cap/defaults-demoted-by-db {:database {:type "sqlite"}} :chengis))))
  (testing "chengis + postgresql demotes nothing"
    (is (empty? (cap/defaults-demoted-by-db {:database {:type "postgresql"}} :chengis)))))

(deftest report-surfaces-demoted-by-db
  (let [r (cap/report {:database {:type "sqlite"}} :chengis)]
    (is (contains? (:demoted-by-db r) :audit-chain))
    (is (contains? (:demoted-by-db r) :ha-leader))
    (is (= 4 (count (:demoted-by-db r))))
    (is (not (contains? (:effective r) :audit-chain))
        "demoted caps must not appear in :effective")))

;; ---------------------------------------------------------------------------
;; 8b. Feature-flag → capability bridge (Codex P2 round 2)
;; ---------------------------------------------------------------------------
;;
;; The deploy module registers itself with `:flag :deployment-dashboard`
;; today. Once the migration filter is wired through migratus, an Anvil
;; install with `:feature-flags :deployment-dashboard true` would have
;; its deploy routes loaded against `environments`/`releases`/`deployments`
;; tables that the filter (correctly per the capability model) would have
;; skipped.
;;
;; The bridge: a truthy entry in `:feature-flags` implies the matching
;; capability via `:implied-by-feature-flag`. Disable wins over implication
;; (so :disable still works), but defaults can be augmented by flag
;; implications.

(deftest implied-by-flags-pure-function
  (testing "no flags → no implications"
    (is (empty? (cap/implied-by-feature-flags {}))))
  (testing ":deployment-dashboard flag implies :deployments"
    (is (= #{:deployments}
           (cap/implied-by-feature-flags {:feature-flags {:deployment-dashboard true}}))))
  (testing "falsy flags don't imply anything"
    (is (empty? (cap/implied-by-feature-flags {:feature-flags {:deployment-dashboard false}})))
    (is (empty? (cap/implied-by-feature-flags {:feature-flags {:deployment-dashboard nil}})))))

(deftest deployments-cap-registered-with-flag-implication
  (let [m (get (cap/registry-snapshot) :deployments)]
    (is (contains? (:implied-by-feature-flag m) :deployment-dashboard))))

(deftest anvil-plus-deploy-flag-implies-deployments-cap
  (testing "anvil install with the legacy :deployment-dashboard flag pulls :deployments into effective"
    (let [cfg {:feature-flags {:deployment-dashboard true}}
          eff (cap/effective-set cfg :anvil)]
      (is (contains? eff :deployments))
      ;; anvil's regular defaults are still there
      (is (contains? eff :local-secrets))
      ;; chengis-only caps that aren't flag-implied still off
      (is (not (contains? eff :saml)))
      (is (not (contains? eff :audit-chain))))))

(deftest disable-wins-over-flag-implication
  (testing "explicit :disable beats :implied-by-feature-flag — operator stays in control"
    (let [cfg {:feature-flags {:deployment-dashboard true}
               :product {:capabilities {:disable #{:deployments}}}}]
      (is (not (contains? (cap/effective-set cfg :anvil) :deployments))))))

(deftest report-surfaces-implied-by-flag
  (let [r (cap/report {:feature-flags {:deployment-dashboard true}} :anvil)]
    (is (contains? (:implied-by-flag r) :deployments))
    (is (contains? (:effective r) :deployments))))

(deftest format-listing-marks-flag-implied-caps
  (let [out (cap/format-listing {:cfg {:feature-flags {:deployment-dashboard true}}
                                 :profile :anvil})]
    (is (re-find #":deployments\s+on \(flag\)" out)
        "deployments should be marked 'on (flag)' on anvil + :deployment-dashboard")))

(deftest all-legacy-flag-bridges-registered
  ;; Codex P2 rounds 2/4/5 surfaced this — any flag-gated module whose
  ;; handlers query OR writers populate a capability-tagged table needs
  ;; the capability's :implied-by-feature-flag set to include the flag.
  ;; Each entry below pins one such bridge so a future PR can't remove
  ;; one accidentally. Capabilities can be implied by multiple flags
  ;; (e.g. :cost-analytics is implied by the READER flag
  ;; :build-analytics AND the WRITER flag :cost-attribution).
  (let [reg (cap/registry-snapshot)
        bridges [[:deployments      :deployment-dashboard]
                 [:iac              :infrastructure-as-code]
                 [:secret-rotation  :secret-rotation]
                 [:sbom-provenance  :slsa-provenance]
                 [:opa-policies     :slsa-provenance]
                 [:cost-analytics   :build-analytics]    ;; reader (dashboard)
                 [:cost-analytics   :cost-attribution]   ;; writer (engine/cost.clj)
                 [:multi-tenant     :org-management-ui]]]
    (doseq [[cap-key flag] bridges]
      (is (contains? (:implied-by-feature-flag (get reg cap-key)) flag)
          (str cap-key " is not bridged to flag " flag)))))

(deftest legacy-flags-imply-multiple-caps-when-needed
  (testing ":slsa-provenance flag implies both :sbom-provenance and :opa-policies"
    (let [cfg {:feature-flags {:slsa-provenance true}}]
      (is (= #{:sbom-provenance :opa-policies}
             (cap/implied-by-feature-flags cfg))))))

(deftest each-bridge-pulls-cap-into-effective-on-anvil
  ;; Round-tripping the full bridge set: each flag, alone, on anvil,
  ;; should pull just the matching capability into effective.
  (doseq [[cap-key flag] {:deployments      :deployment-dashboard
                          :iac              :infrastructure-as-code
                          :secret-rotation  :secret-rotation
                          :cost-analytics   :build-analytics
                          :multi-tenant     :org-management-ui}]
    (let [cfg {:feature-flags {flag true}
               :database {:type "postgresql"}}  ;; PG so :multi-tenant doesn't get demoted
          eff (cap/effective-set cfg :anvil)]
      (is (contains? eff cap-key)
          (str "flag " flag " did not imply cap " cap-key " on anvil/PG")))))

;; ---------------------------------------------------------------------------
;; 8c. Flag-implied caps respect :requires-database (Codex P2 round 6)
;; ---------------------------------------------------------------------------
;;
;; Without the round-6 fix, a SQLite install that turned on
;; `:org-management-ui` (which implies `:multi-tenant`, which
;; `:requires-database "postgresql"`) would crash at boot — the
;; flag-implication landed the cap in effective, the validator saw
;; it, and threw. The fix: flag implications get the same
;; demote-on-DB-mismatch treatment as defaults. Validator throws ONLY
;; on explicit operator enables.

(deftest flag-implied-pg-only-cap-demoted-on-sqlite
  (testing "anvil + SQLite + :org-management-ui flag → :multi-tenant DEMOTED, no throw at boot"
    (let [cfg {:database {:type "sqlite"}
               :feature-flags {:org-management-ui true}}
          eff (cap/effective-set cfg :anvil)]
      (is (not (contains? eff :multi-tenant))
          "multi-tenant requires postgresql; on sqlite the flag-implication must demote it")))
  (testing "the validator does NOT throw for this case (operator didn't explicitly enable the cap)"
    (let [cfg {:database {:type "sqlite"}
               :feature-flags {:org-management-ui true}}]
      (is (nil? (cap/validate-capability-requirements! cfg :anvil))))))

(deftest flag-implied-fitting-cap-still-lands-in-effective
  (testing "anvil + PG + :org-management-ui → :multi-tenant in effective (DB fits)"
    (let [cfg {:database {:type "postgresql"}
               :feature-flags {:org-management-ui true}}
          eff (cap/effective-set cfg :anvil)]
      (is (contains? eff :multi-tenant)))))

(deftest report-surfaces-implied-demoted-by-db
  (let [cfg {:database {:type "sqlite"}
             :feature-flags {:org-management-ui true}}
        r (cap/report cfg :anvil)]
    (is (contains? (:implied-demoted-by-db r) :multi-tenant))
    (is (not (contains? (:implied-by-flag r) :multi-tenant))
        ":implied-by-flag should only carry caps that survived demotion")
    (is (not (contains? (:effective r) :multi-tenant)))))

(deftest implied-demoted-by-db-helper
  (testing "anvil + SQLite + :org-management-ui flag → multi-tenant in implied-demoted set"
    (let [cfg {:database {:type "sqlite"}
               :feature-flags {:org-management-ui true}}]
      (is (= #{:multi-tenant} (cap/implied-demoted-by-db cfg)))))
  (testing "anvil + PG + :org-management-ui → nothing demoted"
    (is (empty? (cap/implied-demoted-by-db
                 {:database {:type "postgresql"}
                  :feature-flags {:org-management-ui true}}))))
  (testing "flag for a cap WITHOUT :requires-database → nothing demoted on any DB"
    (is (empty? (cap/implied-demoted-by-db
                 {:database {:type "sqlite"}
                  :feature-flags {:deployment-dashboard true}})))))

(deftest explicit-enable-of-pg-cap-on-sqlite-still-throws
  ;; Round-6 fix must NOT relax the explicit-enable behavior. An
  ;; operator who explicitly opts in to a PG-only cap on SQLite still
  ;; gets a loud boot failure — that's the documented-op-error case.
  (testing "anvil + SQLite + :enable #{:multi-tenant} → still throws"
    (let [cfg {:database {:type "sqlite"}
               :product {:capabilities {:enable #{:multi-tenant}}}}]
      (is (thrown-with-msg? Exception #":multi-tenant requires database=postgresql"
                            (cap/validate-capability-requirements! cfg :anvil)))))
  (testing "with BOTH explicit enable AND flag implication, the explicit enable still throws"
    (let [cfg {:database {:type "sqlite"}
               :feature-flags {:org-management-ui true}
               :product {:capabilities {:enable #{:multi-tenant}}}}]
      (is (thrown-with-msg? Exception #":multi-tenant requires database=postgresql"
                            (cap/validate-capability-requirements! cfg :anvil))))))

(deftest no-profile-yields-empty-effective-set
  (testing "no profile = no opinion; nothing enabled"
    (is (= #{} (cap/effective-set (cfg) nil)))))

;; ---------------------------------------------------------------------------
;; 2. Explicit enable on anvil
;; ---------------------------------------------------------------------------

(deftest anvil-can-opt-in-to-vault
  (testing "anvil + :enable #{:vault-secrets} → vault is in the effective set"
    (let [eff (cap/effective-set (cfg [:vault-secrets] []) :anvil)]
      (is (contains? eff :vault-secrets))
      ;; other anvil defaults still there:
      (is (contains? eff :local-secrets)))))

(deftest anvil-can-opt-in-to-multiple-capabilities
  (let [eff (cap/effective-set (cfg [:vault-secrets :saml :audit-chain] []) :anvil)]
    (is (contains? eff :vault-secrets))
    (is (contains? eff :saml))
    (is (contains? eff :audit-chain))))

;; ---------------------------------------------------------------------------
;; 3. Explicit disable on chengis
;; ---------------------------------------------------------------------------

(deftest chengis-can-opt-out-of-cost-analytics
  (testing "chengis + postgresql + :disable #{:cost-analytics} → cost-analytics off"
    ;; pin :postgresql so audit-chain isn't demoted out of the comparison
    (let [eff (cap/effective-set
               (assoc (cfg [] [:cost-analytics]) :database {:type "postgresql"})
               :chengis)]
      (is (not (contains? eff :cost-analytics)))
      ;; rest of the bundle intact:
      (is (contains? eff :audit-chain))
      (is (contains? eff :saml)))))

(deftest chengis-disable-beats-enable
  (testing "if a capability appears in both :enable and :disable, disable wins"
    (let [eff (cap/effective-set (cfg [:cost-analytics] [:cost-analytics]) :chengis)]
      (is (not (contains? eff :cost-analytics))))))

;; ---------------------------------------------------------------------------
;; 4. report — source attribution
;; ---------------------------------------------------------------------------

(deftest report-attributes-explicit-enables-to-config
  (let [r (cap/report (cfg [:vault-secrets] []) :anvil)]
    (is (= :anvil (:profile r)))
    (is (contains? (:effective r) :vault-secrets))
    (is (contains? (:effective r) :local-secrets))
    (is (= :config (get-in r [:explicitly-enabled :vault-secrets])))
    (is (not (contains? (:explicitly-enabled r) :local-secrets))
        "default-on capabilities are not flagged as explicitly-enabled")))

(deftest report-attributes-explicit-disables-to-config
  (let [r (cap/report (cfg [] [:cost-analytics]) :chengis)]
    (is (not (contains? (:effective r) :cost-analytics)))
    (is (= :config (get-in r [:explicitly-disabled :cost-analytics])))))

(deftest report-flags-unknown-references
  (let [r (cap/report (cfg [:totally-made-up-feature] []) :anvil)]
    (is (contains? (:unknown-references r) :totally-made-up-feature))
    (is (not (contains? (:effective r) :totally-made-up-feature)))))

;; ---------------------------------------------------------------------------
;; 5. set-active! + has-capability? / require-capability!
;; ---------------------------------------------------------------------------

(deftest has-capability-returns-nil-before-set-active
  (testing "subsystems before boot get nil — they should treat as disabled"
    (is (nil? (cap/has-capability? :saml)))
    (is (nil? (cap/has-capability? :multi-tenant)))))

(deftest set-active-stores-the-effective-set
  ;; pin :postgresql so audit-chain isn't demoted out of effective
  (cap/set-active! (assoc (cfg) :database {:type "postgresql"}) :chengis)
  (is (true? (cap/has-capability? :saml)))
  (is (true? (cap/has-capability? :audit-chain)))
  (is (false? (cap/has-capability? :totally-fake)))
  (testing "anvil profile keeps saml off after set-active!"
    (cap/reset-for-test!)
    (cap/set-active! (cfg) :anvil)
    (is (false? (cap/has-capability? :saml))
        "after boot resolves, has-capability? returns the bool — not nil")
    (is (true? (cap/has-capability? :local-secrets)))))

(deftest require-capability-throws-before-set-active
  (let [ex (try (cap/require-capability! :saml)
                (catch Exception e e))]
    (is (instance? clojure.lang.ExceptionInfo ex))
    (is (re-find #"called before capabilities were resolved" (.getMessage ex)))
    (is (= :capabilities-not-resolved (:type (ex-data ex))))))

(deftest require-capability-throws-on-unknown-key
  (cap/set-active! (cfg) :chengis)
  (let [ex (try (cap/require-capability! :nope-not-a-thing)
                (catch Exception e e))]
    (is (= :unknown-capability (:type (ex-data ex))))))

(deftest require-capability-throws-when-not-enabled
  (cap/set-active! (cfg) :anvil)
  (let [ex (try (cap/require-capability! :saml)
                (catch Exception e e))]
    (is (re-find #"not enabled for profile :anvil" (.getMessage ex)))
    (is (= :capability-not-enabled (:type (ex-data ex))))
    (is (= :saml (:capability (ex-data ex))))
    (is (= :anvil (:profile (ex-data ex))))))

(deftest require-capability-passes-when-enabled
  ;; pin :postgresql so audit-chain isn't demoted
  (cap/set-active! (assoc (cfg) :database {:type "postgresql"}) :chengis)
  (is (nil? (cap/require-capability! :saml)))
  (is (nil? (cap/require-capability! :audit-chain))))

(deftest require-capability-passes-when-opted-in-on-anvil
  (cap/set-active! (cfg [:saml] []) :anvil)
  (is (nil? (cap/require-capability! :saml))))

;; ---------------------------------------------------------------------------
;; 6. with-capabilities macro — test isolation
;; ---------------------------------------------------------------------------

(deftest with-capabilities-installs-and-restores
  (cap/set-active! (cfg) :anvil)
  (is (false? (cap/has-capability? :saml)) "off in anvil-default")
  (cap/with-capabilities :anvil #{:saml}
    (is (true? (cap/has-capability? :saml)))
    (is (nil? (cap/require-capability! :saml))))
  (is (false? (cap/has-capability? :saml))
      "restored to the anvil-default after the macro exits"))

;; ---------------------------------------------------------------------------
;; 7. Registry inventory
;; ---------------------------------------------------------------------------

(deftest registered-capabilities-have-descriptions
  (testing "every registered capability has a non-blank description"
    (doseq [{:keys [key description default-for]} (cap/all-capabilities)]
      (is (string? description) (str "missing description on " key))
      (is (set? default-for) (str "missing :default-for on " key)))))

(deftest hosted-saas-is-chengis-only
  (let [m (get (cap/registry-snapshot) :hosted-saas)]
    (is (= #{:chengis} (:default-for m)))))

;; ---------------------------------------------------------------------------
;; 8. New capabilities (4c follow-up)
;; ---------------------------------------------------------------------------

(deftest iac-deployments-secret-rotation-registered
  (testing ":iac, :deployments, :secret-rotation are registered for :chengis"
    (let [reg (cap/registry-snapshot)]
      (doseq [k [:iac :deployments :secret-rotation]]
        (let [m (get reg k)]
          (is (some? m) (str k " not registered"))
          (is (contains? (:default-for m) :chengis)
              (str k " not default-for :chengis"))
          (is (not (contains? (:default-for m) :anvil))
              (str k " should not be default-for :anvil")))))))

(deftest new-capabilities-opt-in-on-anvil
  (testing "anvil + :enable #{:iac} works"
    (is (contains? (cap/effective-set (cfg [:iac] []) :anvil) :iac)))
  (testing "anvil + :enable #{:deployments :secret-rotation} works"
    (let [eff (cap/effective-set (cfg [:deployments :secret-rotation] []) :anvil)]
      (is (contains? eff :deployments))
      (is (contains? eff :secret-rotation)))))

;; ---------------------------------------------------------------------------
;; 9. :requires-database enforcement (4d follow-up)
;; ---------------------------------------------------------------------------

(defn- db-cfg
  ([db-type] {:database {:type db-type}})
  ([db-type enable] {:database {:type db-type}
                     :product {:capabilities {:enable (set enable)}}}))

(deftest pg-only-capabilities-tagged
  (testing "the four PG-only capabilities carry :requires-database \"postgresql\""
    (let [reg (cap/registry-snapshot)]
      (doseq [k [:audit-chain :multi-tenant :ha-leader :partitioned-logs]]
        (is (= "postgresql" (:requires-database (get reg k)))
            (str k " is missing :requires-database \"postgresql\""))))))

(deftest non-pg-capabilities-have-no-requirement
  (testing "auth modules / governance / artifacts don't carry a DB requirement"
    (let [reg (cap/registry-snapshot)]
      (doseq [k [:saml :ldap :oidc :totp
                 :approval-gates :opa-policies :sbom-provenance
                 :object-store-artifacts :vault-secrets :cloud-secrets
                 :cost-analytics :flaky-detection
                 :iac :deployments :secret-rotation
                 :local-secrets :local-artifacts]]
        (is (nil? (:requires-database (get reg k)))
            (str k " should NOT have a :requires-database tag"))))))

(deftest unmet-requirements-pure-function
  (testing "anvil + sqlite, no opt-ins → no unmet (no PG-only caps in effective set)"
    (let [unmet (cap/unmet-capability-requirements
                 (db-cfg "sqlite")
                 (cap/effective-set (db-cfg "sqlite") :anvil))]
      (is (empty? unmet))))
  (testing "chengis + sqlite, no opt-ins → no unmet (PG-only defaults silently demoted)"
    (let [unmet (cap/unmet-capability-requirements
                 (db-cfg "sqlite")
                 (cap/effective-set (db-cfg "sqlite") :chengis))]
      (is (empty? unmet)
          "after demotion, defaults that don't fit must not appear in the unmet list")))
  (testing "anvil + sqlite + :enable #{:audit-chain} → audit-chain in unmet list"
    (let [cfg-with (db-cfg "sqlite" [:audit-chain])
          unmet (cap/unmet-capability-requirements
                 cfg-with (cap/effective-set cfg-with :anvil))]
      (is (= 1 (count unmet)))
      (is (= :audit-chain (-> unmet first :capability)))
      (is (= "postgresql" (-> unmet first :required)))
      (is (= "sqlite" (-> unmet first :actual)))))
  (testing "chengis + postgresql → no unmet"
    (is (empty? (cap/unmet-capability-requirements
                 (db-cfg "postgresql")
                 (cap/effective-set (db-cfg "postgresql") :chengis))))))

(deftest validate-requirements-throws-only-on-explicit-mismatch
  (testing "chengis + sqlite, no opt-ins → boots cleanly (PG defaults demoted)"
    (is (nil? (cap/validate-capability-requirements!
               (db-cfg "sqlite") :chengis))
        "the dev/demo `chengis serve` path must not throw"))
  (testing "anvil + sqlite + :enable #{:audit-chain} → boot fails loud"
    (let [ex (try (cap/validate-capability-requirements!
                   (db-cfg "sqlite" [:audit-chain]) :anvil)
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #":audit-chain requires database=postgresql" (.getMessage ex)))
      (is (re-find #"CHENGIS_DISABLE_CAPABILITIES=audit-chain" (.getMessage ex)))
      (is (= :capability-requirement-unmet (:type (ex-data ex))))))
  (testing "chengis + sqlite + :enable #{:audit-chain} → still throws (operator opted in explicitly)"
    (let [ex (try (cap/validate-capability-requirements!
                   (db-cfg "sqlite" [:audit-chain]) :chengis)
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #":audit-chain" (.getMessage ex)))))
  (testing "anvil + postgresql + :enable #{:audit-chain} → no throw"
    (is (nil? (cap/validate-capability-requirements!
               (db-cfg "postgresql" [:audit-chain]) :anvil))))
  (testing "no profile = no-op even with mismatched DB"
    (is (nil? (cap/validate-capability-requirements!
               (db-cfg "sqlite" [:audit-chain]) nil)))))

(deftest validate-requirements-lists-all-explicit-mismatches
  (testing "the error message enumerates every explicit-enable mismatch"
    ;; Force four explicit enables on the wrong DB so the validator surfaces all of them.
    (let [explicit (db-cfg "sqlite" [:audit-chain :ha-leader :multi-tenant :partitioned-logs])
          ex (try (cap/validate-capability-requirements! explicit :anvil)
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (let [msg (.getMessage ex)]
        (is (re-find #":audit-chain"      msg))
        (is (re-find #":multi-tenant"     msg))
        (is (re-find #":ha-leader"        msg))
        (is (re-find #":partitioned-logs" msg)))
      (is (= 4 (count (:unmet (ex-data ex))))))))

;; ---------------------------------------------------------------------------
;; 10. format-listing (M4 — --list-capabilities CLI output)
;; ---------------------------------------------------------------------------

(deftest format-listing-without-profile-shows-inventory
  (let [out (cap/format-listing)]
    (is (string? out))
    (is (re-find #"Capabilities — \d+ registered" out))
    (is (re-find #":audit-chain" out))
    (is (re-find #":local-secrets" out))
    ;; without profile, state column is "—"
    (is (re-find #":local-secrets\s+—" out))))

(deftest format-listing-with-anvil-profile-marks-state
  (let [out (cap/format-listing {:cfg {} :profile :anvil})]
    (is (re-find #"profile=:anvil" out))
    (is (re-find #":local-secrets\s+on " out))  ; anvil-default
    (is (re-find #":audit-chain\s+off " out)))) ; not in anvil-default

(deftest format-listing-with-chengis-profile-shows-bundle-on
  (testing "chengis + postgresql: full bundle on"
    (let [out (cap/format-listing {:cfg {:database {:type "postgresql"}}
                                   :profile :chengis})]
      (is (re-find #"profile=:chengis" out))
      (is (re-find #":audit-chain\s+on " out))
      (is (re-find #":multi-tenant\s+on " out))))
  (testing "chengis + sqlite (dev): PG-only caps show as 'off (db-mismatch)' and a Demoted section appears"
    (let [out (cap/format-listing {:cfg {:database {:type "sqlite"}}
                                   :profile :chengis})]
      (is (re-find #":audit-chain\s+off \(db-mismatch\)" out))
      (is (re-find #":ha-leader\s+off \(db-mismatch\)" out))
      (is (re-find #"Demoted \(DB mismatch\)" out))
      ;; non-PG caps still on:
      (is (re-find #":saml\s+on " out)))))

(deftest format-listing-marks-explicit-overrides
  ;; pin DB so non-PG caps don't get demoted out of the comparison
  (let [out (cap/format-listing {:cfg (merge (cfg [:saml] [:cost-analytics])
                                             {:database {:type "postgresql"}})
                                 :profile :chengis})]
    (is (re-find #":saml\s+on " out))
    ;; cost-analytics is default-on for chengis but explicitly disabled
    (is (re-find #":cost-analytics\s+off \(explicit\)" out))))

(deftest format-listing-shows-requires-db-tag
  (let [out (cap/format-listing {:cfg {} :profile :chengis})]
    (is (re-find #":audit-chain.*requires-db=postgresql" out))
    (is (re-find #":ha-leader.*requires-db=postgresql" out))))

(deftest format-listing-surfaces-unknown-keys
  (let [out (cap/format-listing {:cfg (cfg [:totally-made-up] [])
                                 :profile :chengis})]
    (is (re-find #"unknown capability keys" out))
    (is (re-find #":totally-made-up" out))))
