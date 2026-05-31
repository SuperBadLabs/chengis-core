(ns ^:integration chengis.plugin.loader-trust-test
  "Tests for plugin loader trust enforcement: allowed, blocked, and
   backward-compatible (no DB) loading.

   Post-M1b: external plugins are evaluated through the SCI runtime, not
   `load-file`. A loaded plugin is observed by the side effect it produces via
   the host API (registering a notifier), NOT by a host namespace appearing —
   SCI plugins never create host-visible namespaces."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.plugin-policy-store :as plugin-policy-store]
            [chengis.db.plugin-grant-audit-store :as grant-audit-store]
            [chengis.plugin.loader]
            [chengis.plugin.registry :as registry]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-loader-trust-test.db")
(def test-plugin-dir "/tmp/chengis-loader-trust-plugins")

;; SCI plugin source: registers a notifier of the given type via the host API.
(defn- plugin-src [notifier-kw]
  (str "(require '[chengis.plugin.host :as h]) "
       "(h/register-notifier! " notifier-kw
       " (fn [br cfg] {:status :sent}))"))

(defn setup [f]
  (registry/reset-registry!)
  ;; Setup DB
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Setup plugin directory with SCI-style test plugins
  (let [dir (io/file test-plugin-dir)]
    (.mkdirs dir)
    (spit (io/file dir "allowed-test.clj") (plugin-src ":allowed-test"))
    (spit (io/file dir "blocked-test.clj") (plugin-src ":blocked-test")))
  (f)
  ;; Cleanup
  (registry/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (doseq [^java.io.File plugin-file (.listFiles ^java.io.File (io/file test-plugin-dir))]
    (.delete plugin-file))
  (.delete (io/file test-plugin-dir)))

(use-fixtures :each setup)

(defn- load-external! [& args]
  (apply (var-get #'chengis.plugin.loader/load-external-plugins!) args))

(deftest allowed-plugin-loads-test
  (testing "plugin with allowed=true policy loads and registers via the host API"
    (let [ds (conn/create-datasource test-db-path)]
      (plugin-policy-store/set-plugin-policy! ds
                                              {:org-id nil :plugin-name "allowed-test"
                                               :trust-level "trusted" :allowed true :created-by "test"})
      (load-external! test-plugin-dir :ds ds :org-id nil)
      (is (some? (registry/get-notifier :allowed-test))
          "allowed-test should have registered its notifier")
      ;; M3c — the load is persisted to the grant-audit trail.
      (let [rows (grant-audit-store/list-grant-audit ds :org-id nil)
            row  (first (filter #(= "allowed-test" (:plugin-name %)) rows))]
        (is (some? row) "an allowed-test grant-audit row was persisted")
        (is (= "sandboxed" (:trust-level row))
            "unsigned plugin is recorded as sandboxed even under a trusted policy")
        (is (false? (:signed row)) "recorded as unsigned (no .sig present)")))))

(deftest blocked-plugin-skipped-test
  (testing "plugin without allowed policy is skipped (never evaluated)"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Only allow 'allowed-test', NOT 'blocked-test'
      (plugin-policy-store/set-plugin-policy! ds
                                              {:org-id nil :plugin-name "allowed-test"
                                               :trust-level "trusted" :allowed true :created-by "test"})
      (load-external! test-plugin-dir :ds ds :org-id nil)
      (is (nil? (registry/get-notifier :blocked-test))
          "blocked-test must NOT have registered anything")
      (is (some? (registry/get-notifier :allowed-test))
          "allowed-test should still load"))))

(deftest no-db-loads-all-plugins-test
  (testing "when no DB is provided, all external plugins load (backward compat),
            but in the sandboxed context"
    (load-external! test-plugin-dir)
    (is (some? (registry/get-notifier :allowed-test))
        "allowed-test should load without DB enforcement")
    (is (some? (registry/get-notifier :blocked-test))
        "blocked-test should also load without DB enforcement")))
