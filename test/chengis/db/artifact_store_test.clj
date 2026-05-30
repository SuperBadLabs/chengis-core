(ns ^:integration chengis.db.artifact-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.artifact-store :as artifact-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-artifact-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; save-artifact! + list-artifacts
;; ---------------------------------------------------------------------------

(deftest save-and-list-artifacts-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save-artifact! returns the inserted artifact map"
      (let [artifact (artifact-store/save-artifact! ds
                                                    {:build-id "build-1"
                                                     :filename "app.jar"
                                                     :path "/tmp/artifacts/app.jar"
                                                     :size-bytes 1024
                                                     :content-type "application/java-archive"
                                                     :sha256-hash "abc123def456"})]
        (is (some? (:id artifact)))
        (is (= "build-1" (:build-id artifact)))
        (is (= "app.jar" (:filename artifact)))
        (is (= 1024 (:size-bytes artifact)))
        (is (= "abc123def456" (:sha256-hash artifact)))))

    (testing "list-artifacts returns saved artifacts for a build"
      (let [artifacts (artifact-store/list-artifacts ds "build-1")]
        (is (= 1 (count artifacts)))
        (is (= "app.jar" (:filename (first artifacts))))
        (is (= 1024 (:size-bytes (first artifacts))))))

    (testing "list-artifacts returns empty for unknown build"
      (is (empty? (artifact-store/list-artifacts ds "nonexistent"))))))

(deftest save-multiple-artifacts-test
  (let [ds (conn/create-datasource test-db-path)]
    (artifact-store/save-artifact! ds
                                   {:build-id "build-2" :filename "a.jar" :path "/p/a.jar" :size-bytes 100
                                    :content-type "application/java-archive" :sha256-hash "aaa"})
    (artifact-store/save-artifact! ds
                                   {:build-id "build-2" :filename "b.tar.gz" :path "/p/b.tar.gz" :size-bytes 200
                                    :content-type "application/gzip" :sha256-hash "bbb"})
    (artifact-store/save-artifact! ds
                                   {:build-id "build-other" :filename "c.zip" :path "/p/c.zip" :size-bytes 300
                                    :content-type "application/zip" :sha256-hash "ccc"})

    (testing "list-artifacts returns only artifacts for the given build"
      (let [artifacts (artifact-store/list-artifacts ds "build-2")]
        (is (= 2 (count artifacts)))
        (is (= #{"a.jar" "b.tar.gz"} (set (map :filename artifacts))))))

    (testing "list-artifacts returns artifacts sorted by filename"
      (let [artifacts (artifact-store/list-artifacts ds "build-2")]
        (is (= ["a.jar" "b.tar.gz"] (mapv :filename artifacts)))))))

;; ---------------------------------------------------------------------------
;; get-artifact
;; ---------------------------------------------------------------------------

(deftest get-artifact-test
  (let [ds (conn/create-datasource test-db-path)]
    (artifact-store/save-artifact! ds
                                   {:build-id "build-3" :filename "deploy.sh" :path "/p/deploy.sh"
                                    :size-bytes 512 :content-type "text/x-shellscript" :sha256-hash "deadbeef"})

    (testing "get-artifact returns the artifact by build-id and filename"
      (let [artifact (artifact-store/get-artifact ds "build-3" "deploy.sh")]
        (is (some? artifact))
        (is (= "deploy.sh" (:filename artifact)))
        (is (= 512 (:size-bytes artifact)))
        (is (= "deadbeef" (:sha256-hash artifact)))))

    (testing "get-artifact returns nil for unknown filename"
      (is (nil? (artifact-store/get-artifact ds "build-3" "missing.txt"))))

    (testing "get-artifact returns nil for unknown build-id"
      (is (nil? (artifact-store/get-artifact ds "unknown" "deploy.sh"))))))

;; ---------------------------------------------------------------------------
;; verify-artifact-hash
;; ---------------------------------------------------------------------------

(deftest verify-artifact-hash-test
  (let [ds       (conn/create-datasource test-db-path)
        tmp-file (io/file "/tmp/chengis-artifact-test-file.txt")]

    ;; Create a temp file with known content
    (spit tmp-file "hello world")
    (try
      (let [;; Compute real SHA-256 for "hello world"
            digest    (java.security.MessageDigest/getInstance "SHA-256")
            _         (.update digest (.getBytes "hello world" "UTF-8"))
            real-hash (format "%064x" (BigInteger. 1 (.digest digest)))]

        (artifact-store/save-artifact! ds
                                       {:build-id "build-hash" :filename "test.txt"
                                        :path (.getAbsolutePath tmp-file)
                                        :size-bytes 11 :content-type "text/plain"
                                        :sha256-hash real-hash})

        (testing "verify-artifact-hash returns valid=true for matching hash"
          (let [result (artifact-store/verify-artifact-hash ds "build-hash" "test.txt")]
            (is (true? (:valid result)))
            (is (= real-hash (:expected result)))
            (is (= real-hash (:computed result)))))

        ;; Now save with wrong hash
        (artifact-store/save-artifact! ds
                                       {:build-id "build-hash2" :filename "test.txt"
                                        :path (.getAbsolutePath tmp-file)
                                        :size-bytes 11 :content-type "text/plain"
                                        :sha256-hash "0000000000000000000000000000000000000000000000000000000000000000"})

        (testing "verify-artifact-hash returns valid=false for mismatched hash"
          (let [result (artifact-store/verify-artifact-hash ds "build-hash2" "test.txt")]
            (is (false? (:valid result))))))

      (finally
        (.delete tmp-file))))

  (let [ds (conn/create-datasource test-db-path)]
    (testing "verify-artifact-hash returns nil valid for missing artifact"
      (let [result (artifact-store/verify-artifact-hash ds "nonexistent" "nope.txt")]
        (is (nil? (:valid result)))
        (is (= "Artifact not found" (:reason result)))))

    (testing "verify-artifact-hash returns nil valid when no hash stored"
      (artifact-store/save-artifact! ds
                                     {:build-id "build-nohash" :filename "nohash.txt"
                                      :path "/tmp/nohash.txt" :size-bytes 0 :content-type "text/plain"})
      (let [result (artifact-store/verify-artifact-hash ds "build-nohash" "nohash.txt")]
        (is (nil? (:valid result)))
        (is (= "No hash stored (pre-checksum artifact)" (:reason result)))))))

;; ---------------------------------------------------------------------------
;; delete-artifacts-for-build!
;; ---------------------------------------------------------------------------

(deftest delete-artifacts-for-build-test
  (let [ds (conn/create-datasource test-db-path)]
    (artifact-store/save-artifact! ds
                                   {:build-id "build-del" :filename "a.jar" :path "/p/a" :size-bytes 10
                                    :content-type "application/java-archive" :sha256-hash "aaa"})
    (artifact-store/save-artifact! ds
                                   {:build-id "build-del" :filename "b.jar" :path "/p/b" :size-bytes 20
                                    :content-type "application/java-archive" :sha256-hash "bbb"})
    (artifact-store/save-artifact! ds
                                   {:build-id "build-keep" :filename "c.jar" :path "/p/c" :size-bytes 30
                                    :content-type "application/java-archive" :sha256-hash "ccc"})

    (testing "delete-artifacts-for-build! removes all artifacts for the build"
      (artifact-store/delete-artifacts-for-build! ds "build-del")
      (is (empty? (artifact-store/list-artifacts ds "build-del"))))

    (testing "delete does not affect other builds"
      (is (= 1 (count (artifact-store/list-artifacts ds "build-keep")))))

    (testing "delete is idempotent on empty"
      (artifact-store/delete-artifacts-for-build! ds "build-del")
      (is (empty? (artifact-store/list-artifacts ds "build-del"))))))

;; ---------------------------------------------------------------------------
;; Round-trip: save -> list -> get -> delete -> list-empty
;; ---------------------------------------------------------------------------

(deftest full-round-trip-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "full artifact lifecycle"
      ;; Save
      (let [saved (artifact-store/save-artifact! ds
                                                 {:build-id "build-rt" :filename "release.zip"
                                                  :path "/artifacts/release.zip" :size-bytes 4096
                                                  :content-type "application/zip" :sha256-hash "cafebabe"})]
        (is (some? (:id saved)))

        ;; List
        (let [artifacts (artifact-store/list-artifacts ds "build-rt")]
          (is (= 1 (count artifacts)))
          (is (= "release.zip" (:filename (first artifacts)))))

        ;; Get
        (let [artifact (artifact-store/get-artifact ds "build-rt" "release.zip")]
          (is (= "cafebabe" (:sha256-hash artifact)))
          (is (= 4096 (:size-bytes artifact))))

        ;; Delete
        (artifact-store/delete-artifacts-for-build! ds "build-rt")

        ;; Verify empty
        (is (empty? (artifact-store/list-artifacts ds "build-rt")))
        (is (nil? (artifact-store/get-artifact ds "build-rt" "release.zip")))))))
