(ns chengis.product-test
  "Tests for `chengis.product` — preferred shape + optionality, with a
   strict-mode opt-in.

                    SQLite                     PostgreSQL
     :anvil    [:ok preferred]           [:off-preferred warn]
     :chengis  [:off-preferred warn]     [:ok preferred]

   In strict mode (`CHENGIS_STRICT_PROFILE=true` or `:product
   :strict-profile? true`), off-preferred combos throw instead of
   warning. Without a profile declared the gate is a no-op so the
   REPL, test runner, and ad-hoc scripts keep working."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.product :as p]))

;; Clear the profile after every test so the global atom doesn't bleed.
(use-fixtures :each
  (fn [t]
    (p/reset-for-test!)
    (try (t) (finally (p/reset-for-test!)))))

(defn- cfg
  ([db-type] (cfg db-type {}))
  ([db-type extra] (merge {:database {:type db-type}} extra)))

(defn- strict-cfg [db-type]
  (cfg db-type {:product {:strict-profile? true}}))

;; ---------------------------------------------------------------------------
;; Profile setter
;; ---------------------------------------------------------------------------

(deftest profile-setter-validation
  (testing "set-profile! accepts :anvil and :chengis"
    (p/set-profile! :anvil)
    (is (= :anvil (p/profile)))
    (is (true? (p/anvil?)))
    (is (false? (p/chengis?)))
    (p/set-profile! :chengis)
    (is (= :chengis (p/profile)))
    (is (true? (p/chengis?)))
    (is (false? (p/anvil?))))
  (testing "set-profile! rejects unknown profiles"
    (is (thrown-with-msg? Exception #"Unknown product profile"
                          (p/set-profile! :gitlab-compat)))))

(deftest assert-profile-requires-declaration
  (testing "assert-profile! throws when nothing has been declared"
    (is (thrown-with-msg? Exception #"No product profile declared"
                          (p/assert-profile!))))
  (testing "assert-profile! passes once a profile is set"
    (p/set-profile! :anvil)
    (is (nil? (p/assert-profile!)))))

;; ---------------------------------------------------------------------------
;; check-database-fit — pure function
;; ---------------------------------------------------------------------------

(deftest check-fit-anvil-sqlite-is-preferred
  (p/set-profile! :anvil)
  (let [r (p/check-database-fit (cfg "sqlite"))]
    (is (= :ok (:fit r)))
    (is (= :anvil (:profile r)))
    (is (= "sqlite" (:database-type r)))
    (is (= "sqlite" (:preferred r)))
    (is (nil? (:message r)))))

(deftest check-fit-anvil-postgres-is-off-preferred
  (p/set-profile! :anvil)
  (let [r (p/check-database-fit (cfg "postgresql"))]
    (is (= :off-preferred (:fit r)))
    (is (= "postgresql" (:database-type r)))
    (is (= "sqlite" (:preferred r)))
    (is (re-find #"anvil \+ PostgreSQL" (:message r)))
    (is (re-find #"off the preferred install path" (:message r)))
    (is (re-find #"supported but you own the" (:message r)))))

(deftest check-fit-chengis-postgres-is-preferred
  (p/set-profile! :chengis)
  (let [r (p/check-database-fit (cfg "postgresql"))]
    (is (= :ok (:fit r)))
    (is (= "postgresql" (:preferred r)))
    (is (nil? (:message r)))))

(deftest check-fit-chengis-sqlite-is-off-preferred
  (p/set-profile! :chengis)
  (let [r (p/check-database-fit (cfg "sqlite"))]
    (is (= :off-preferred (:fit r)))
    (is (re-find #"chengis \+ SQLite" (:message r)))
    (is (re-find #"fine for dev / demo" (:message r)))))

(deftest check-fit-no-profile
  (testing "no profile declared → :no-profile, no opinions either way"
    (is (nil? (p/profile)))
    (let [r (p/check-database-fit (cfg "postgresql"))]
      (is (= :no-profile (:fit r)))
      (is (nil? (:profile r)))
      (is (nil? (:message r))))))

(deftest check-fit-default-db-is-sqlite
  (testing "config without :database :type defaults to sqlite"
    (p/set-profile! :anvil)
    (is (= :ok (:fit (p/check-database-fit {}))))))

(deftest check-fit-case-insensitive
  (p/set-profile! :anvil)
  (is (= :ok (:fit (p/check-database-fit (cfg "SQLite")))))
  (is (= :off-preferred (:fit (p/check-database-fit (cfg "PostgreSQL"))))))

;; ---------------------------------------------------------------------------
;; validate-database-fit! — side-effecting, warn-by-default
;; ---------------------------------------------------------------------------

(deftest validate-fit-off-preferred-warns-by-default
  (testing "anvil+postgres logs a warn and returns the report — no throw"
    (p/set-profile! :anvil)
    (let [r (p/validate-database-fit! (cfg "postgresql"))]
      (is (= :off-preferred (:fit r)))
      (is (false? (:strict? r)))))
  (testing "chengis+sqlite same"
    (p/set-profile! :chengis)
    (let [r (p/validate-database-fit! (cfg "sqlite"))]
      (is (= :off-preferred (:fit r)))
      (is (false? (:strict? r))))))

(deftest validate-fit-preferred-is-quiet
  (testing "the preferred combo doesn't warn and doesn't throw"
    (p/set-profile! :anvil)
    (is (= :ok (:fit (p/validate-database-fit! (cfg "sqlite")))))
    (p/set-profile! :chengis)
    (is (= :ok (:fit (p/validate-database-fit! (cfg "postgresql")))))))

(deftest validate-fit-strict-throws-on-off-preferred
  (testing "strict mode via config flag throws on anvil+postgres"
    (p/set-profile! :anvil)
    (let [ex (try (p/validate-database-fit! (strict-cfg "postgresql"))
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"Strict mode rejected" (.getMessage ex)))
      (is (= :strict-product-fit-violation (:type (ex-data ex))))
      (is (= :anvil (:profile (ex-data ex))))
      (is (= "postgresql" (:database-type (ex-data ex))))))
  (testing "strict mode throws on chengis+sqlite"
    (p/set-profile! :chengis)
    (is (thrown-with-msg? Exception #"Strict mode rejected"
                          (p/validate-database-fit! (strict-cfg "sqlite"))))))

(deftest validate-fit-strict-still-quiet-on-preferred
  (testing "strict mode does not throw on the preferred combo"
    (p/set-profile! :anvil)
    (is (= :ok (:fit (p/validate-database-fit! (strict-cfg "sqlite")))))
    (p/set-profile! :chengis)
    (is (= :ok (:fit (p/validate-database-fit! (strict-cfg "postgresql")))))))

(deftest validate-fit-no-profile-is-noop
  (testing "no profile → no throw, no warning"
    (is (nil? (p/profile)))
    (is (= :no-profile (:fit (p/validate-database-fit! (cfg "postgresql")))))
    (is (= :no-profile (:fit (p/validate-database-fit! (strict-cfg "sqlite")))))))

(deftest explicit-profile-arg-overrides-global
  (testing "callers may pass an explicit profile (preferred in tests)"
    (is (nil? (p/profile)))
    (is (= :off-preferred
           (:fit (p/check-database-fit (cfg "postgresql") :anvil))))
    (is (= :off-preferred
           (:fit (p/check-database-fit (cfg "sqlite") :chengis))))
    (is (= :no-profile
           (:fit (p/check-database-fit (cfg "postgresql") nil))))))

;; ---------------------------------------------------------------------------
;; Backwards-compatible alias
;; ---------------------------------------------------------------------------

(deftest deprecated-validate-database-compatibility-still-works
  (testing "the old name routes to validate-database-fit! with the same semantics"
    (p/set-profile! :anvil)
    (is (= :off-preferred
           (:fit (p/validate-database-compatibility! (cfg "postgresql")))))
    (is (thrown-with-msg? Exception #"Strict mode"
                          (p/validate-database-compatibility! (strict-cfg "postgresql"))))))
