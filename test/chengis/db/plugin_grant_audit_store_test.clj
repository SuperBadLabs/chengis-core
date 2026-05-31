(ns ^:integration chengis.db.plugin-grant-audit-store-test
  "Tests for the persistent plugin grant-audit trail (M3c): append, capability
   round-trip, org isolation, ordering, signed coercion, limit."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.plugin-grant-audit-store :as store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-plugin-grant-audit-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest record-and-list-roundtrip-test
  (testing "a recorded grant round-trips: capabilities vector + signed bool"
    (let [ds (conn/create-datasource test-db-path)]
      (store/record-grant-audit! ds {:org-id "org-1" :plugin-name "notifier"
                                     :trust-level :trusted
                                     :capabilities [:http :log]
                                     :signed? true :signed-by "abc123def4567890"})
      (let [[g] (store/list-grant-audit ds :org-id "org-1")]
        (is (= "notifier" (:plugin-name g)))
        (is (= "trusted" (:trust-level g)) "keyword trust-level stored as name")
        (is (= [:http :log] (:capabilities g)) "capabilities parsed back to a vector")
        (is (true? (:signed g)) "signed coerced to boolean")
        (is (= "abc123def4567890" (:signed-by g)))))))

(deftest unsigned-grant-test
  (testing "an unsigned grant records signed=false and nil signed-by"
    (let [ds (conn/create-datasource test-db-path)]
      (store/record-grant-audit! ds {:org-id "org-1" :plugin-name "sandboxed-one"
                                     :trust-level :sandboxed
                                     :capabilities [:log]
                                     :signed? false :signed-by nil})
      (let [[g] (store/list-grant-audit ds :org-id "org-1")]
        (is (false? (:signed g)))
        (is (nil? (:signed-by g)))
        (is (= "sandboxed" (:trust-level g)))))))

(deftest append-only-keeps-history-test
  (testing "reloading the same plugin appends a new row (history, not upsert)"
    (let [ds (conn/create-datasource test-db-path)]
      (store/record-grant-audit! ds {:org-id "org-1" :plugin-name "p"
                                     :trust-level :sandboxed :capabilities [:log]
                                     :signed? false})
      (store/record-grant-audit! ds {:org-id "org-1" :plugin-name "p"
                                     :trust-level :trusted :capabilities [:log :http]
                                     :signed? true :signed-by "k"})
      (let [rows (store/list-grant-audit ds :org-id "org-1")]
        (is (= 2 (count rows)) "both load events preserved")
        (is (every? #(= "p" (:plugin-name %)) rows))))))

(deftest org-isolation-test
  (testing "grants are scoped per org"
    (let [ds (conn/create-datasource test-db-path)]
      (store/record-grant-audit! ds {:org-id "org-1" :plugin-name "a"
                                     :trust-level :trusted :capabilities [] :signed? true})
      (store/record-grant-audit! ds {:org-id "org-2" :plugin-name "b"
                                     :trust-level :sandboxed :capabilities [] :signed? false})
      (is (= 1 (count (store/list-grant-audit ds :org-id "org-1"))))
      (is (= 1 (count (store/list-grant-audit ds :org-id "org-2"))))
      (is (= "a" (:plugin-name (first (store/list-grant-audit ds :org-id "org-1"))))))))

(deftest org-query-includes-global-but-not-other-orgs-test
  (testing "an org-scoped query surfaces global (NULL-org) startup grants but
            not other orgs' rows (Codex PR #180 P2 — loader records org_id nil)"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Global startup grant (loader passes :org-id nil)
      (store/record-grant-audit! ds {:org-id nil :plugin-name "startup-plugin"
                                     :trust-level :sandboxed :capabilities [:log] :signed? false})
      ;; This org's grant
      (store/record-grant-audit! ds {:org-id "default-org" :plugin-name "org-plugin"
                                     :trust-level :trusted :capabilities [:http] :signed? true :signed-by "k"})
      ;; A different org's grant — must stay hidden
      (store/record-grant-audit! ds {:org-id "other-org" :plugin-name "other-plugin"
                                     :trust-level :trusted :capabilities [] :signed? true :signed-by "k2"})
      (let [names (set (map :plugin-name (store/list-grant-audit ds :org-id "default-org")))]
        (is (contains? names "startup-plugin") "global NULL-scoped startup grant is visible")
        (is (contains? names "org-plugin") "the org's own grant is visible")
        (is (not (contains? names "other-plugin")) "another org's grant is NOT leaked")))))

(deftest limit-caps-rows-test
  (testing ":limit bounds the returned rows"
    (let [ds (conn/create-datasource test-db-path)]
      (dotimes [n 5]
        (store/record-grant-audit! ds {:org-id "org-1" :plugin-name (str "p" n)
                                       :trust-level :sandboxed :capabilities [] :signed? false}))
      (is (= 3 (count (store/list-grant-audit ds :org-id "org-1" :limit 3)))))))
