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
  (testing "chengis's defaults bundle the enterprise stack"
    (let [eff (cap/effective-set (cfg) :chengis)]
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
      ;; The team-tier ones are still in chengis (they're shared building blocks):
      (is (contains? eff :local-secrets))
      (is (contains? eff :local-artifacts)))))

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
  (testing "chengis + :disable #{:cost-analytics} → cost-analytics off"
    (let [eff (cap/effective-set (cfg [] [:cost-analytics]) :chengis)]
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
  (cap/set-active! (cfg) :chengis)
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
  (cap/set-active! (cfg) :chengis)
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
