(ns ^:integration chengis.db.scan-store-test
  "Tests for scan-store CRUD operations and custom queries."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.scan-store :as scan-store]
            [next.jdbc]
            [clojure.java.io :as io]))

(def ^:private test-db-path "/tmp/chengis-scan-store-test.db")

(defn- setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; create-scan!
;; ---------------------------------------------------------------------------

(deftest create-scan-returns-row-test
  (let [ds  (conn/create-datasource test-db-path)
        row (scan-store/create-scan! ds
                                     {:build-id       "build-1"
                                      :job-id         "job-1"
                                      :org-id         "org-1"
                                      :scan-target    "image:latest"
                                      :scanner        "trivy"
                                      :scanner-version "0.45.0"
                                      :critical-count  2
                                      :high-count      5
                                      :medium-count    10
                                      :low-count       3
                                      :total-count     20
                                      :pass-threshold  "critical=0"
                                      :passed          0
                                      :results-json    "[]"})]
    (testing "create-scan! returns a map with an id"
      (is (some? (:id row))))
    (testing "fields are stored correctly"
      (is (= "build-1" (:build-id row)))
      (is (= "org-1" (:org-id row)))
      (is (= "trivy" (:scanner row)))
      (is (= 2 (:critical-count row)))
      (is (= 0 (:passed row))))))

(deftest create-scan-defaults-test
  (let [ds  (conn/create-datasource test-db-path)
        row (scan-store/create-scan! ds
                                     {:build-id    "build-defaults"
                                      :job-id      "job-1"
                                      :scan-target "image:latest"
                                      :scanner     "grype"})]
    (testing "org-id defaults to 'default-org'"
      (is (= "default-org" (:org-id row))))
    (testing "counts default to 0"
      (is (= 0 (:critical-count row)))
      (is (= 0 (:high-count row)))
      (is (= 0 (:total-count row))))
    (testing "passed defaults to 1"
      (is (= 1 (:passed row))))))

(deftest create-scan-boolean-passed-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "passed=true is stored as 1"
      (let [row (scan-store/create-scan! ds
                                         {:build-id "b-true" :job-id "j1"
                                          :scan-target "img" :scanner "trivy"
                                          :passed true})]
        (is (= 1 (:passed row)))))
    (testing "passed=false is stored as 0"
      (let [row (scan-store/create-scan! ds
                                         {:build-id "b-false" :job-id "j1"
                                          :scan-target "img2" :scanner "grype"
                                          :passed false})]
        (is (= 0 (:passed row)))))))

;; ---------------------------------------------------------------------------
;; get-scan
;; ---------------------------------------------------------------------------

(deftest get-scan-by-id-test
  (let [ds      (conn/create-datasource test-db-path)
        created (scan-store/create-scan! ds
                                         {:build-id "build-get" :job-id "j1" :org-id "org-a"
                                          :scan-target "img" :scanner "trivy"})
        found   (scan-store/get-scan ds (:id created))]
    (testing "get-scan returns the record by id"
      (is (some? found))
      (is (= (:id created) (:id found)))
      (is (= "org-a" (:org-id found))))))

(deftest get-scan-not-found-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-scan returns nil for unknown id"
      (is (nil? (scan-store/get-scan ds "nonexistent"))))))

(deftest get-scan-org-scoped-test
  (let [ds      (conn/create-datasource test-db-path)
        created (scan-store/create-scan! ds
                                         {:build-id "build-org" :job-id "j1" :org-id "org-alpha"
                                          :scan-target "img" :scanner "trivy"})]
    (testing "get-scan with matching org-id returns the record"
      (is (some? (scan-store/get-scan ds (:id created) :org-id "org-alpha"))))
    (testing "get-scan with wrong org-id returns nil"
      (is (nil? (scan-store/get-scan ds (:id created) :org-id "org-beta"))))))

;; ---------------------------------------------------------------------------
;; list-scans
;; ---------------------------------------------------------------------------

(deftest list-scans-basic-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-scans returns empty when none exist"
      (is (empty? (scan-store/list-scans ds))))

    (scan-store/create-scan! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-1"
                              :scan-target "img1" :scanner "trivy"})
    (scan-store/create-scan! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-2"
                              :scan-target "img2" :scanner "grype"})

    (testing "list-scans returns all records"
      (is (= 2 (count (scan-store/list-scans ds)))))))

(deftest list-scans-org-filter-test
  (let [ds (conn/create-datasource test-db-path)]
    (scan-store/create-scan! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-a"
                              :scan-target "img" :scanner "trivy"})
    (scan-store/create-scan! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-b"
                              :scan-target "img" :scanner "grype"})

    (testing "list-scans filters by org-id"
      (is (= 1 (count (scan-store/list-scans ds :org-id "org-a")))))))

(deftest list-scans-passed-filter-test
  (let [ds (conn/create-datasource test-db-path)]
    (scan-store/create-scan! ds
                             {:build-id "b-pass" :job-id "j1" :org-id "org-1"
                              :scan-target "img1" :scanner "trivy" :passed 1})
    (scan-store/create-scan! ds
                             {:build-id "b-fail" :job-id "j2" :org-id "org-1"
                              :scan-target "img2" :scanner "trivy" :passed 0})

    (testing "list-scans filters by passed=1"
      (is (= 1 (count (scan-store/list-scans ds :passed 1)))))
    (testing "list-scans filters by passed=0"
      (is (= 1 (count (scan-store/list-scans ds :passed 0)))))))

(deftest list-scans-limit-offset-test
  (let [ds (conn/create-datasource test-db-path)]
    (doseq [i (range 5)]
      (scan-store/create-scan! ds
                               {:build-id    (str "build-" i)
                                :job-id      "j1"
                                :org-id      "org-1"
                                :scan-target (str "img-" i)
                                :scanner     "trivy"}))

    (testing "list-scans respects limit"
      (is (= 3 (count (scan-store/list-scans ds :limit 3)))))

    (testing "list-scans respects offset"
      (is (= 2 (count (scan-store/list-scans ds :limit 10 :offset 3)))))))

;; ---------------------------------------------------------------------------
;; get-build-scans
;; ---------------------------------------------------------------------------

(deftest get-build-scans-test
  (let [ds (conn/create-datasource test-db-path)]
    (scan-store/create-scan! ds
                             {:build-id "build-multi" :job-id "j1" :org-id "org-1"
                              :scan-target "img1" :scanner "trivy"})
    (scan-store/create-scan! ds
                             {:build-id "build-multi" :job-id "j1" :org-id "org-1"
                              :scan-target "img2" :scanner "grype"})
    (scan-store/create-scan! ds
                             {:build-id "build-other" :job-id "j2" :org-id "org-1"
                              :scan-target "img1" :scanner "trivy"})

    (testing "get-build-scans returns all scans for a build"
      (is (= 2 (count (scan-store/get-build-scans ds "build-multi")))))

    (testing "get-build-scans returns empty for unknown build"
      (is (empty? (scan-store/get-build-scans ds "nonexistent"))))))

(deftest get-build-scans-org-scoped-test
  (let [ds (conn/create-datasource test-db-path)]
    (scan-store/create-scan! ds
                             {:build-id "build-org" :job-id "j1" :org-id "org-alpha"
                              :scan-target "img" :scanner "trivy"})

    (testing "get-build-scans with matching org-id returns result"
      (is (= 1 (count (scan-store/get-build-scans ds "build-org" :org-id "org-alpha")))))

    (testing "get-build-scans with wrong org-id returns empty"
      (is (empty? (scan-store/get-build-scans ds "build-org" :org-id "org-beta"))))))

;; ---------------------------------------------------------------------------
;; count-scans
;; ---------------------------------------------------------------------------

(deftest count-scans-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count-scans returns 0 when empty"
      (is (= 0 (scan-store/count-scans ds))))

    (scan-store/create-scan! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-1"
                              :scan-target "img" :scanner "trivy"})
    (scan-store/create-scan! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-2"
                              :scan-target "img" :scanner "grype"})

    (testing "count-scans returns total"
      (is (= 2 (scan-store/count-scans ds))))

    (testing "count-scans scopes to org-id"
      (is (= 1 (scan-store/count-scans ds :org-id "org-1")))
      (is (= 0 (scan-store/count-scans ds :org-id "org-unknown"))))))

;; ---------------------------------------------------------------------------
;; delete-scan!
;; ---------------------------------------------------------------------------

(deftest delete-scan-test
  (let [ds      (conn/create-datasource test-db-path)
        created (scan-store/create-scan! ds
                                         {:build-id "build-del" :job-id "j1" :org-id "org-1"
                                          :scan-target "img" :scanner "trivy"})]
    (scan-store/delete-scan! ds (:id created))
    (testing "delete-scan! removes the record"
      (is (nil? (scan-store/get-scan ds (:id created)))))))

;; ---------------------------------------------------------------------------
;; get-scan-summary
;; ---------------------------------------------------------------------------

(deftest get-scan-summary-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "summary is all zeros when empty"
      (let [s (scan-store/get-scan-summary ds)]
        (is (= 0 (:total s)))
        (is (= 0 (:passed s)))
        (is (= 0 (:failed s)))))

    (scan-store/create-scan! ds
                             {:build-id "b1" :job-id "j1" :org-id "org-1"
                              :scan-target "img1" :scanner "trivy" :passed 1})
    (scan-store/create-scan! ds
                             {:build-id "b2" :job-id "j2" :org-id "org-1"
                              :scan-target "img2" :scanner "trivy" :passed 1})
    (scan-store/create-scan! ds
                             {:build-id "b3" :job-id "j3" :org-id "org-1"
                              :scan-target "img3" :scanner "grype" :passed 0})

    (testing "get-scan-summary aggregates totals"
      (let [s (scan-store/get-scan-summary ds)]
        (is (= 3 (:total s)))
        (is (= 2 (:passed s)))
        (is (= 1 (:failed s)))))

    (testing "get-scan-summary scopes to org-id"
      (let [s (scan-store/get-scan-summary ds :org-id "org-unknown")]
        (is (= 0 (:total s)))))))

;; ---------------------------------------------------------------------------
;; cleanup-old-scans!
;; ---------------------------------------------------------------------------

(deftest cleanup-old-scans-test
  (let [ds (conn/create-datasource test-db-path)]
    (scan-store/create-scan! ds
                             {:build-id "b-recent" :job-id "j1" :org-id "org-1"
                              :scan-target "img" :scanner "trivy"})

    (testing "cleanup with large retention keeps recent records"
      (is (= 0 (scan-store/cleanup-old-scans! ds 365))))

    ;; Backdate the record so cleanup can find it
    (next.jdbc/execute-one! ds
                            ["UPDATE vulnerability_scans SET created_at = '2024-01-01 00:00:00' WHERE build_id = 'b-recent'"])

    (testing "cleanup deletes records beyond retention period"
      (is (pos? (scan-store/cleanup-old-scans! ds 30))))

    (testing "deleted records are gone"
      (is (empty? (scan-store/get-build-scans ds "b-recent"))))))
