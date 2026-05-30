(ns ^:integration chengis.db.provenance-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.provenance-store :as provenance-store]
            [next.jdbc]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-provenance-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; create-attestation! + get-attestation
;; ---------------------------------------------------------------------------

(deftest create-and-get-attestation-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-attestation! returns the created row map"
      (let [att (provenance-store/create-attestation! ds
                                                      {:build-id "build-1"
                                                       :job-id "job-1"
                                                       :org-id "org-1"
                                                       :slsa-level "L2"
                                                       :predicate-type "https://slsa.dev/provenance/v1"
                                                       :subject-json "{\"name\":\"app.jar\"}"
                                                       :predicate-json "{\"builder\":{\"id\":\"chengis\"}}"
                                                       :envelope-json "{\"payload\":\"...\"}"
                                                       :builder-id "chengis"
                                                       :build-type "chengis/pipeline/v1"
                                                       :source-repo "https://github.com/test/repo"
                                                       :source-branch "main"
                                                       :source-commit "abc123"})]
        (is (some? (:id att)))
        (is (= "build-1" (:build-id att)))
        (is (= "L2" (:slsa-level att)))
        (is (= "org-1" (:org-id att)))))

    (testing "get-attestation retrieves by build-id"
      (let [fetched (provenance-store/get-attestation ds "build-1")]
        (is (some? fetched))
        (is (= "build-1" (:build-id fetched)))
        (is (= "L2" (:slsa-level fetched)))
        (is (= "https://github.com/test/repo" (:source-repo fetched)))))

    (testing "get-attestation returns nil for unknown build-id"
      (is (nil? (provenance-store/get-attestation ds "nonexistent"))))))

(deftest get-attestation-org-scoping-test
  (let [ds (conn/create-datasource test-db-path)
        _att (provenance-store/create-attestation! ds
                                                   {:build-id "build-scoped" :job-id "j1" :org-id "org-alpha"
                                                    :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})]

    (testing "get-attestation with matching org-id returns the attestation"
      (is (some? (provenance-store/get-attestation ds "build-scoped" :org-id "org-alpha"))))

    (testing "get-attestation with wrong org-id returns nil"
      (is (nil? (provenance-store/get-attestation ds "build-scoped" :org-id "org-beta"))))

    (testing "get-attestation without org-id returns regardless"
      (is (some? (provenance-store/get-attestation ds "build-scoped"))))))

;; ---------------------------------------------------------------------------
;; list-attestations
;; ---------------------------------------------------------------------------

(deftest list-attestations-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-attestations returns empty when none exist"
      (is (empty? (provenance-store/list-attestations ds))))

    ;; Insert attestations
    (provenance-store/create-attestation! ds
                                          {:build-id "b1" :job-id "j1" :org-id "org-1"
                                           :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})
    (provenance-store/create-attestation! ds
                                          {:build-id "b2" :job-id "j1" :org-id "org-1"
                                           :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})
    (provenance-store/create-attestation! ds
                                          {:build-id "b3" :job-id "j2" :org-id "org-2"
                                           :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})

    (testing "list-attestations returns all"
      (is (= 3 (count (provenance-store/list-attestations ds)))))

    (testing "list-attestations filters by org-id"
      (is (= 2 (count (provenance-store/list-attestations ds :org-id "org-1"))))
      (is (= 1 (count (provenance-store/list-attestations ds :org-id "org-2")))))

    (testing "list-attestations filters by job-id"
      (is (= 2 (count (provenance-store/list-attestations ds :job-id "j1"))))
      (is (= 1 (count (provenance-store/list-attestations ds :job-id "j2")))))

    (testing "list-attestations respects limit and offset"
      (is (= 2 (count (provenance-store/list-attestations ds :limit 2))))
      (is (= 1 (count (provenance-store/list-attestations ds :limit 2 :offset 2)))))))

;; ---------------------------------------------------------------------------
;; delete-attestation!
;; ---------------------------------------------------------------------------

(deftest delete-attestation-test
  (let [ds (conn/create-datasource test-db-path)
        att (provenance-store/create-attestation! ds
                                                  {:build-id "build-del" :job-id "j1" :org-id "org-1"
                                                   :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})]

    (testing "delete-attestation! removes the attestation"
      (provenance-store/delete-attestation! ds (:id att))
      (is (nil? (provenance-store/get-attestation ds "build-del"))))

    (testing "delete-attestation! is idempotent"
      (provenance-store/delete-attestation! ds (:id att))
      (is (nil? (provenance-store/get-attestation ds "build-del"))))))

(deftest delete-attestation-org-scoping-test
  (let [ds (conn/create-datasource test-db-path)
        att (provenance-store/create-attestation! ds
                                                  {:build-id "build-org-del" :job-id "j1" :org-id "org-alpha"
                                                   :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})]

    (testing "delete with wrong org-id does not delete"
      (provenance-store/delete-attestation! ds (:id att) :org-id "org-beta")
      (is (some? (provenance-store/get-attestation ds "build-org-del"))))

    (testing "delete with correct org-id deletes"
      (provenance-store/delete-attestation! ds (:id att) :org-id "org-alpha")
      (is (nil? (provenance-store/get-attestation ds "build-org-del"))))))

;; ---------------------------------------------------------------------------
;; cleanup-old-attestations!
;; ---------------------------------------------------------------------------

(deftest cleanup-old-attestations-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Insert some attestations — they'll have CURRENT_TIMESTAMP as created_at
    (provenance-store/create-attestation! ds
                                          {:build-id "b-recent" :job-id "j1" :org-id "org-1"
                                           :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})

    (testing "cleanup with very large retention keeps recent records"
      (let [deleted (provenance-store/cleanup-old-attestations! ds 365)]
        (is (= 0 deleted))
        (is (some? (provenance-store/get-attestation ds "b-recent")))))

    ;; Backdate the record to 2 years ago so cleanup can find it
    (next.jdbc/execute-one! ds
                            ["UPDATE provenance_attestations SET created_at = '2024-01-01 00:00:00' WHERE build_id = 'b-recent'"])

    (testing "cleanup deletes old records beyond retention period"
      (let [deleted (provenance-store/cleanup-old-attestations! ds 30)]
        (is (pos? deleted))
        (is (nil? (provenance-store/get-attestation ds "b-recent")))))))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(deftest create-attestation-defaults-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-attestation! applies defaults"
      (let [att (provenance-store/create-attestation! ds
                                                      {:build-id "build-defaults" :job-id "j1"
                                                       :subject-json "{}" :predicate-json "{}" :envelope-json "{}"})]
        (is (= "default-org" (:org-id att)))
        (is (= "L1" (:slsa-level att)))
        (is (= "https://slsa.dev/provenance/v1" (:predicate-type att)))
        (is (= "chengis" (:builder-id att)))
        (is (= "chengis/pipeline/v1" (:build-type att)))))))

;; ---------------------------------------------------------------------------
;; Full round-trip
;; ---------------------------------------------------------------------------

(deftest full-round-trip-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "full provenance attestation lifecycle"
      ;; Create
      (let [att (provenance-store/create-attestation! ds
                                                      {:build-id "build-rt" :job-id "j-rt" :org-id "org-rt"
                                                       :slsa-level "L3"
                                                       :subject-json "{\"name\":\"release.tar.gz\"}"
                                                       :predicate-json "{\"builder\":{}}"
                                                       :envelope-json "{\"payload\":\"sig\"}"
                                                       :source-repo "https://github.com/test/app"
                                                       :source-branch "release"
                                                       :source-commit "deadbeef"})]
        (is (some? (:id att)))

        ;; Read
        (let [fetched (provenance-store/get-attestation ds "build-rt")]
          (is (= "L3" (:slsa-level fetched)))
          (is (= "deadbeef" (:source-commit fetched))))

        ;; List
        (is (= 1 (count (provenance-store/list-attestations ds :org-id "org-rt"))))

        ;; Delete
        (provenance-store/delete-attestation! ds (:id att))
        (is (nil? (provenance-store/get-attestation ds "build-rt")))
        (is (empty? (provenance-store/list-attestations ds :org-id "org-rt")))))))
