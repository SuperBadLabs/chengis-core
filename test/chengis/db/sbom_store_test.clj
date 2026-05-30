(ns ^:integration chengis.db.sbom-store-test
  "Tests for sbom-store CRUD operations and custom queries."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.sbom-store :as sbom-store]
            [next.jdbc]
            [clojure.java.io :as io]))

(def ^:private test-db-path "/tmp/chengis-sbom-store-test.db")

(defn- setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; create-sbom!
;; ---------------------------------------------------------------------------

(deftest create-sbom-returns-row-test
  (let [ds  (conn/create-datasource test-db-path)
        row (sbom-store/create-sbom! ds
                                     {:build-id        "build-1"
                                      :job-id          "job-1"
                                      :org-id          "org-1"
                                      :sbom-format     "cyclonedx"
                                      :sbom-version    "1.4"
                                      :component-count 42
                                      :content-hash    "sha256:abc"
                                      :sbom-content    "{\"components\":[]}"
                                      :tool-name       "syft"
                                      :tool-version    "0.90.0"})]
    (testing "create-sbom! returns a map with an id"
      (is (some? (:id row))))
    (testing "fields are stored correctly"
      (is (= "build-1" (:build-id row)))
      (is (= "org-1" (:org-id row)))
      (is (= "cyclonedx" (:sbom-format row)))
      (is (= 42 (:component-count row))))))

(deftest create-sbom-defaults-test
  (let [ds  (conn/create-datasource test-db-path)
        row (sbom-store/create-sbom! ds
                                     {:build-id     "build-defaults"
                                      :job-id       "job-1"
                                      :sbom-format  "spdx"
                                      :sbom-content "{}"})]
    (testing "org-id defaults to 'default-org'"
      (is (= "default-org" (:org-id row))))
    (testing "component-count defaults to 0"
      (is (= 0 (:component-count row))))))

;; ---------------------------------------------------------------------------
;; get-sbom
;; ---------------------------------------------------------------------------

(deftest get-sbom-by-id-test
  (let [ds      (conn/create-datasource test-db-path)
        created (sbom-store/create-sbom! ds
                                         {:build-id "build-get" :job-id "j1" :org-id "org-a"
                                          :sbom-format "cyclonedx" :sbom-content "{}"})
        found   (sbom-store/get-sbom ds (:id created))]
    (testing "get-sbom returns the record by id"
      (is (some? found))
      (is (= (:id created) (:id found)))
      (is (= "org-a" (:org-id found))))))

(deftest get-sbom-not-found-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-sbom returns nil for unknown id"
      (is (nil? (sbom-store/get-sbom ds "nonexistent"))))))

(deftest get-sbom-org-scoped-test
  (let [ds      (conn/create-datasource test-db-path)
        created (sbom-store/create-sbom! ds
                                         {:build-id "build-org" :job-id "j1" :org-id "org-alpha"
                                          :sbom-format "spdx" :sbom-content "{}"})]
    (testing "get-sbom with matching org-id returns the record"
      (is (some? (sbom-store/get-sbom ds (:id created) :org-id "org-alpha"))))
    (testing "get-sbom with wrong org-id returns nil"
      (is (nil? (sbom-store/get-sbom ds (:id created) :org-id "org-beta"))))))

;; ---------------------------------------------------------------------------
;; list-sboms
;; ---------------------------------------------------------------------------

(deftest list-sboms-basic-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-sboms returns empty when none exist"
      (is (empty? (sbom-store/list-sboms ds))))

    (sbom-store/create-sbom! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-2"
                              :sbom-format "spdx" :sbom-content "{}"})

    (testing "list-sboms returns all records"
      (is (= 2 (count (sbom-store/list-sboms ds)))))))

(deftest list-sboms-org-filter-test
  (let [ds (conn/create-datasource test-db-path)]
    (sbom-store/create-sbom! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-a"
                              :sbom-format "cyclonedx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-b"
                              :sbom-format "spdx" :sbom-content "{}"})

    (testing "list-sboms filters by org-id"
      (is (= 1 (count (sbom-store/list-sboms ds :org-id "org-a"))))
      (is (= "org-a" (:org-id (first (sbom-store/list-sboms ds :org-id "org-a"))))))))

(deftest list-sboms-job-filter-test
  (let [ds (conn/create-datasource test-db-path)]
    (sbom-store/create-sbom! ds
                             {:build-id "b1" :job-id "job-a" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "b2" :job-id "job-b" :org-id "org-1"
                              :sbom-format "spdx" :sbom-content "{}"})

    (testing "list-sboms filters by job-id"
      (is (= 1 (count (sbom-store/list-sboms ds :job-id "job-a"))))
      (is (= 0 (count (sbom-store/list-sboms ds :job-id "job-unknown")))))))

(deftest list-sboms-limit-offset-test
  (let [ds (conn/create-datasource test-db-path)]
    (doseq [i (range 5)]
      (sbom-store/create-sbom! ds
                               {:build-id     (str "build-" i)
                                :job-id       "j1"
                                :org-id       "org-1"
                                :sbom-format  "cyclonedx"
                                :sbom-content "{}"}))

    (testing "list-sboms respects limit"
      (is (= 3 (count (sbom-store/list-sboms ds :limit 3)))))

    (testing "list-sboms respects offset"
      (is (= 2 (count (sbom-store/list-sboms ds :limit 10 :offset 3)))))))

;; ---------------------------------------------------------------------------
;; get-build-sboms
;; ---------------------------------------------------------------------------

(deftest get-build-sboms-test
  (let [ds (conn/create-datasource test-db-path)]
    (sbom-store/create-sbom! ds
                             {:build-id "build-multi" :job-id "j1" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "build-multi" :job-id "j1" :org-id "org-1"
                              :sbom-format "spdx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "build-other" :job-id "j2" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})

    (testing "get-build-sboms returns all sboms for a build"
      (is (= 2 (count (sbom-store/get-build-sboms ds "build-multi")))))

    (testing "get-build-sboms returns empty for unknown build"
      (is (empty? (sbom-store/get-build-sboms ds "nonexistent"))))))

(deftest get-build-sboms-org-scoped-test
  (let [ds (conn/create-datasource test-db-path)]
    (sbom-store/create-sbom! ds
                             {:build-id "build-org" :job-id "j1" :org-id "org-alpha"
                              :sbom-format "cyclonedx" :sbom-content "{}"})

    (testing "get-build-sboms with matching org-id returns result"
      (is (= 1 (count (sbom-store/get-build-sboms ds "build-org" :org-id "org-alpha")))))

    (testing "get-build-sboms with wrong org-id returns empty"
      (is (empty? (sbom-store/get-build-sboms ds "build-org" :org-id "org-beta"))))))

;; ---------------------------------------------------------------------------
;; count-sboms
;; ---------------------------------------------------------------------------

(deftest count-sboms-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count-sboms returns 0 when empty"
      (is (= 0 (sbom-store/count-sboms ds))))

    (sbom-store/create-sbom! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})
    (sbom-store/create-sbom! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-2"
                              :sbom-format "spdx" :sbom-content "{}"})

    (testing "count-sboms returns total"
      (is (= 2 (sbom-store/count-sboms ds))))

    (testing "count-sboms scopes to org-id"
      (is (= 1 (sbom-store/count-sboms ds :org-id "org-1")))
      (is (= 0 (sbom-store/count-sboms ds :org-id "org-unknown"))))))

;; ---------------------------------------------------------------------------
;; delete-sbom!
;; ---------------------------------------------------------------------------

(deftest delete-sbom-test
  (let [ds      (conn/create-datasource test-db-path)
        created (sbom-store/create-sbom! ds
                                         {:build-id "build-del" :job-id "j1" :org-id "org-1"
                                          :sbom-format "cyclonedx" :sbom-content "{}"})]
    (sbom-store/delete-sbom! ds (:id created))
    (testing "delete-sbom! removes the record"
      (is (nil? (sbom-store/get-sbom ds (:id created)))))))

;; ---------------------------------------------------------------------------
;; cleanup-old-sboms!
;; ---------------------------------------------------------------------------

(deftest cleanup-old-sboms-test
  (let [ds (conn/create-datasource test-db-path)]
    (sbom-store/create-sbom! ds
                             {:build-id "b-recent" :job-id "j1" :org-id "org-1"
                              :sbom-format "cyclonedx" :sbom-content "{}"})

    (testing "cleanup with large retention keeps recent records"
      (is (= 0 (sbom-store/cleanup-old-sboms! ds 365))))

    ;; Backdate the record so cleanup can find it
    (next.jdbc/execute-one! ds
                            ["UPDATE sbom_reports SET created_at = '2024-01-01 00:00:00' WHERE build_id = 'b-recent'"])

    (testing "cleanup deletes records beyond retention period"
      (is (pos? (sbom-store/cleanup-old-sboms! ds 30))))

    (testing "deleted records are gone"
      (is (empty? (sbom-store/get-build-sboms ds "b-recent"))))))
