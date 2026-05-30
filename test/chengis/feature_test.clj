(ns chengis.feature-test
  "Tests for chengis.feature runtime flag resolution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.feature-flag-store :as feature-flag-store]
            [chengis.feature :as feature]))

(defn clear-flag-cache [f]
  (feature/invalidate-cache!)
  (f)
  (feature/invalidate-cache!))

(use-fixtures :each clear-flag-cache)

;; ---------------------------------------------------------------------------
;; Helpers — build minimal system stubs
;; ---------------------------------------------------------------------------

(defn- config-system
  "System with only config-based flags (no DB)."
  [flags]
  {:db nil :config {:feature-flags flags}})

;; ---------------------------------------------------------------------------
;; Config fallback (no DB)
;; ---------------------------------------------------------------------------

(deftest enabled-from-config-only-test
  (testing "returns true when config flag is true and no DB"
    (is (true?  (feature/enabled? (config-system {:my-flag true})  :my-flag))))

  (testing "returns false when config flag is false and no DB"
    (is (false? (feature/enabled? (config-system {:my-flag false}) :my-flag))))

  (testing "returns false when config flag missing and no DB"
    (is (false? (feature/enabled? (config-system {}) :my-flag)))))

;; ---------------------------------------------------------------------------
;; Percentage rollout — deterministic bucketing
;; ---------------------------------------------------------------------------

(deftest percentage-rollout-all-test
  (testing "100% rollout always passes"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] {:flag-name "pct-100" :enabled true :percentage-rollout 100})]
      (is (true?  (feature/enabled? {:db ::ds :config {}} :pct-100 "org-1")))
      (feature/invalidate-cache!)
      (is (true?  (feature/enabled? {:db ::ds :config {}} :pct-100 "org-2"))))))

(deftest percentage-rollout-none-test
  (testing "0% rollout never passes"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] {:flag-name "pct-0" :enabled true :percentage-rollout 0})]
      (is (false? (feature/enabled? {:db ::ds :config {}} :pct-0 "org-1")))
      (feature/invalidate-cache!)
      (is (false? (feature/enabled? {:db ::ds :config {}} :pct-0 "org-2"))))))

(deftest percentage-rollout-deterministic-test
  (testing "same org always gets same bucket"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] {:flag-name "pct-50" :enabled true :percentage-rollout 50})]
      (let [system  {:db ::ds :config {}}
            result1 (feature/enabled? system :pct-50 "org-stable")
            _       (feature/invalidate-cache! "pct-50")
            result2 (feature/enabled? system :pct-50 "org-stable")]
        (is (= result1 result2) "Same org must get consistent result")))))

;; ---------------------------------------------------------------------------
;; DB row takes precedence over config
;; ---------------------------------------------------------------------------

(deftest db-overrides-config-test
  (testing "DB-enabled flag wins over config-disabled"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] {:flag-name "db-on" :enabled true :percentage-rollout 100})]
      (is (true? (feature/enabled?
                  {:db ::ds :config {:feature-flags {:db-on false}}}
                  :db-on "org-1")))))

  (testing "DB-disabled flag wins over config-enabled"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] {:flag-name "db-off" :enabled false :percentage-rollout 100})]
      (is (false? (feature/enabled?
                   {:db ::ds :config {:feature-flags {:db-off true}}}
                   :db-off "org-1"))))))

;; ---------------------------------------------------------------------------
;; DB failure falls back to config
;; ---------------------------------------------------------------------------

(deftest db-failure-falls-back-to-config-test
  (testing "DB error falls back to config value"
    (with-redefs [chengis.db.feature-flag-store/get-flag
                  (fn [_ _ _] (throw (Exception. "DB unavailable")))]
      (is (true?  (feature/enabled?
                   {:db ::ds :config {:feature-flags {:fallback-flag true}}}
                   :fallback-flag "org-1")))
      (is (false? (feature/enabled?
                   {:db ::ds :config {:feature-flags {:fallback-flag false}}}
                   :fallback-flag "org-1"))))))

;; ---------------------------------------------------------------------------
;; require-flag!
;; ---------------------------------------------------------------------------

(deftest require-flag-enabled-test
  (testing "returns true when flag enabled"
    (is (true? (feature/require-flag! (config-system {:feat true}) :feat)))))

(deftest require-flag-disabled-throws-test
  (testing "throws when flag disabled"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Feature not enabled"
                          (feature/require-flag! (config-system {:feat false}) :feat))))

  (testing "exception data includes type, flag, org-id"
    (try
      (feature/require-flag! (config-system {}) :missing-feat "org-x")
      (catch clojure.lang.ExceptionInfo e
        (is (= :feature-disabled (:type (ex-data e))))
        (is (= :missing-feat (:flag (ex-data e))))
        (is (= "org-x" (:org-id (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; all-flags
;; ---------------------------------------------------------------------------

(deftest all-flags-merges-config-and-db-test
  (testing "all-flags includes config flags when DB is nil"
    (let [result (feature/all-flags (config-system {:f1 true :f2 false}))]
      (is (true?  (get result :f1)))
      (is (false? (get result :f2))))))
