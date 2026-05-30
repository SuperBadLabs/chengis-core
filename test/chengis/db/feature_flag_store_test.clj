(ns ^:integration chengis.db.feature-flag-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.feature-flag-store :as ff-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-feature-flag-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; upsert + get — global flag
;; ---------------------------------------------------------------------------

(deftest upsert-and-get-global-flag-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create a global flag and retrieve it"
      (let [flag (ff-store/upsert-flag! ds {:flag-name "my-feature" :enabled true})]
        (is (some? (:id flag)))
        (is (= "my-feature" (:flag-name flag)))
        (is (true? (:enabled flag)))
        (is (= 100 (:percentage-rollout flag)))
        (is (nil? (:org-id-override flag)))))

    (testing "get-flag without org-id returns global row"
      (let [flag (ff-store/get-flag ds "my-feature")]
        (is (= "my-feature" (:flag-name flag)))
        (is (true? (:enabled flag)))))))

;; ---------------------------------------------------------------------------
;; upsert — update existing
;; ---------------------------------------------------------------------------

(deftest upsert-updates-flag-test
  (let [ds (conn/create-datasource test-db-path)]
    (ff-store/upsert-flag! ds {:flag-name "toggle-me" :enabled false :percentage-rollout 50})
    (let [updated (ff-store/upsert-flag! ds {:flag-name "toggle-me" :enabled true :percentage-rollout 80})]
      (is (true? (:enabled updated)))
      (is (= 80 (:percentage-rollout updated))))))

;; ---------------------------------------------------------------------------
;; org override resolution
;; ---------------------------------------------------------------------------

(deftest org-override-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Global: disabled
    (ff-store/upsert-flag! ds {:flag-name "beta" :enabled false})
    ;; Org override: enabled for org-1 only
    (ff-store/upsert-flag! ds {:flag-name "beta" :enabled true :org-id-override "org-1"})

    (testing "org-1 gets its override (enabled)"
      (is (true? (:enabled (ff-store/get-flag ds "beta" "org-1")))))

    (testing "org-2 falls back to global (disabled)"
      (is (false? (:enabled (ff-store/get-flag ds "beta" "org-2")))))

    (testing "no org falls back to global (disabled)"
      (is (false? (:enabled (ff-store/get-flag ds "beta")))))))

;; ---------------------------------------------------------------------------
;; list-flags
;; ---------------------------------------------------------------------------

(deftest list-flags-test
  (let [ds (conn/create-datasource test-db-path)]
    (ff-store/upsert-flag! ds {:flag-name "flag-a" :enabled true})
    (ff-store/upsert-flag! ds {:flag-name "flag-b" :enabled false :org-id-override "org-x"})
    (let [all (ff-store/list-flags ds)]
      (is (>= (count all) 2))
      (is (some #(= "flag-a" (:flag-name %)) all))
      (is (some #(= "flag-b" (:flag-name %)) all)))

    (testing "list-flags filtered to org includes global + org rows"
      (let [for-org (ff-store/list-flags ds "org-x")]
        (is (some #(= "flag-a" (:flag-name %)) for-org))  ;; global
        (is (some #(= "flag-b" (:flag-name %)) for-org)))) ;; org override
    ))

;; ---------------------------------------------------------------------------
;; delete-flag!
;; ---------------------------------------------------------------------------

(deftest delete-flag-test
  (let [ds (conn/create-datasource test-db-path)]
    (ff-store/upsert-flag! ds {:flag-name "deletable" :enabled true})
    (is (some? (ff-store/get-flag ds "deletable")))
    (let [n (ff-store/delete-flag! ds "deletable")]
      (is (pos? n)))
    (is (nil? (ff-store/get-flag ds "deletable")))))

(deftest delete-org-override-test
  (let [ds (conn/create-datasource test-db-path)]
    (ff-store/upsert-flag! ds {:flag-name "mixed" :enabled false})
    (ff-store/upsert-flag! ds {:flag-name "mixed" :enabled true :org-id-override "org-a"})
    ;; Delete only the org override
    (ff-store/delete-flag! ds "mixed" "org-a")
    ;; Global row still exists
    (is (some? (ff-store/get-flag ds "mixed")))
    ;; Org now falls back to global (disabled)
    (is (false? (:enabled (ff-store/get-flag ds "mixed" "org-a"))))))
