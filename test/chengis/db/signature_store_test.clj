(ns ^:integration chengis.db.signature-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.signature-store :as signature-store]
            [next.jdbc]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-signature-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; create-signature! + get-build-signatures
;; ---------------------------------------------------------------------------

(deftest create-and-get-signatures-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-signature! returns the created row map"
      (let [sig (signature-store/create-signature! ds
                                                   {:artifact-id "art-1"
                                                    :build-id "build-1"
                                                    :job-id "job-1"
                                                    :org-id "org-1"
                                                    :signer "cosign"
                                                    :key-reference "gcr.io/keys/signing-key"
                                                    :signature-value "MEUCIQD..."
                                                    :target-digest "sha256:abc123"})]
        (is (some? (:id sig)))
        (is (= "build-1" (:build-id sig)))
        (is (= "cosign" (:signer sig)))
        (is (= 0 (:verified sig)))))

    (testing "get-build-signatures returns signatures for a build"
      (let [sigs (signature-store/get-build-signatures ds "build-1")]
        (is (= 1 (count sigs)))
        (is (= "cosign" (:signer (first sigs))))
        (is (= "sha256:abc123" (:target-digest (first sigs))))))

    (testing "get-build-signatures returns empty for unknown build"
      (is (empty? (signature-store/get-build-signatures ds "nonexistent"))))))

(deftest get-build-signatures-org-scoping-test
  (let [ds (conn/create-datasource test-db-path)]
    (signature-store/create-signature! ds
                                       {:artifact-id "a1" :build-id "b-scoped" :job-id "j1"
                                        :org-id "org-alpha" :signer "gpg" :key-reference "key1"
                                        :signature-value "sig1" :target-digest "sha256:111"})

    (testing "get-build-signatures with matching org-id returns signatures"
      (is (= 1 (count (signature-store/get-build-signatures ds "b-scoped" :org-id "org-alpha")))))

    (testing "get-build-signatures with wrong org-id returns empty"
      (is (empty? (signature-store/get-build-signatures ds "b-scoped" :org-id "org-beta"))))

    (testing "get-build-signatures without org-id returns all"
      (is (= 1 (count (signature-store/get-build-signatures ds "b-scoped")))))))

(deftest multiple-signatures-per-build-test
  (let [ds (conn/create-datasource test-db-path)]
    (signature-store/create-signature! ds
                                       {:artifact-id "a1" :build-id "build-multi" :job-id "j1" :org-id "org-1"
                                        :signer "cosign" :key-reference "k1" :signature-value "s1"
                                        :target-digest "sha256:aaa"})
    (signature-store/create-signature! ds
                                       {:artifact-id "a2" :build-id "build-multi" :job-id "j1" :org-id "org-1"
                                        :signer "gpg" :key-reference "k2" :signature-value "s2"
                                        :target-digest "sha256:bbb"})

    (testing "get-build-signatures returns all signatures for a build"
      (let [sigs (signature-store/get-build-signatures ds "build-multi")]
        (is (= 2 (count sigs)))
        (is (= #{"cosign" "gpg"} (set (map :signer sigs))))))))

;; ---------------------------------------------------------------------------
;; list-signatures
;; ---------------------------------------------------------------------------

(deftest list-signatures-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-signatures returns empty when none exist"
      (is (empty? (signature-store/list-signatures ds))))

    ;; Insert signatures
    (signature-store/create-signature! ds
                                       {:artifact-id "a1" :build-id "b1" :job-id "j1" :org-id "org-1"
                                        :signer "cosign" :key-reference "k1" :signature-value "s1"
                                        :target-digest "sha256:111"})
    (signature-store/create-signature! ds
                                       {:artifact-id "a2" :build-id "b2" :job-id "j1" :org-id "org-1"
                                        :signer "gpg" :key-reference "k2" :signature-value "s2"
                                        :target-digest "sha256:222"})
    (signature-store/create-signature! ds
                                       {:artifact-id "a3" :build-id "b3" :job-id "j2" :org-id "org-2"
                                        :signer "cosign" :key-reference "k3" :signature-value "s3"
                                        :target-digest "sha256:333"})

    (testing "list-signatures returns all"
      (is (= 3 (count (signature-store/list-signatures ds)))))

    (testing "list-signatures filters by org-id"
      (is (= 2 (count (signature-store/list-signatures ds :org-id "org-1"))))
      (is (= 1 (count (signature-store/list-signatures ds :org-id "org-2")))))

    (testing "list-signatures filters by job-id"
      (is (= 2 (count (signature-store/list-signatures ds :job-id "j1"))))
      (is (= 1 (count (signature-store/list-signatures ds :job-id "j2")))))

    (testing "list-signatures filters by verified status"
      (is (= 3 (count (signature-store/list-signatures ds :verified 0))))
      (is (= 0 (count (signature-store/list-signatures ds :verified 1)))))

    (testing "list-signatures respects limit and offset"
      (is (= 2 (count (signature-store/list-signatures ds :limit 2))))
      (is (= 1 (count (signature-store/list-signatures ds :limit 2 :offset 2)))))))

;; ---------------------------------------------------------------------------
;; verify-signature!
;; ---------------------------------------------------------------------------

(deftest verify-signature-test
  (let [ds (conn/create-datasource test-db-path)
        sig (signature-store/create-signature! ds
                                               {:artifact-id "a1" :build-id "build-verify" :job-id "j1"
                                                :org-id "org-1" :signer "cosign" :key-reference "k1"
                                                :signature-value "s1" :target-digest "sha256:abc"})]

    (testing "signature starts unverified"
      (let [fetched (first (signature-store/get-build-signatures ds "build-verify"))]
        (is (= 0 (:verified fetched)))
        (is (nil? (:verified-at fetched)))))

    (testing "verify-signature! marks as verified"
      (signature-store/verify-signature! ds (:id sig))
      (let [fetched (first (signature-store/get-build-signatures ds "build-verify"))]
        (is (= 1 (:verified fetched)))
        (is (some? (:verified-at fetched)))))

    (testing "verified signature appears in verified filter"
      (is (= 1 (count (signature-store/list-signatures ds :verified 1)))))))

(deftest verify-signature-org-scoping-test
  (let [ds (conn/create-datasource test-db-path)
        sig (signature-store/create-signature! ds
                                               {:artifact-id "a1" :build-id "b-org-verify" :job-id "j1"
                                                :org-id "org-alpha" :signer "gpg" :key-reference "k1"
                                                :signature-value "s1" :target-digest "sha256:xyz"})]

    (testing "verify with wrong org-id does not verify"
      (signature-store/verify-signature! ds (:id sig) :org-id "org-beta")
      (let [fetched (first (signature-store/get-build-signatures ds "b-org-verify"))]
        (is (= 0 (:verified fetched)))))

    (testing "verify with correct org-id verifies"
      (signature-store/verify-signature! ds (:id sig) :org-id "org-alpha")
      (let [fetched (first (signature-store/get-build-signatures ds "b-org-verify"))]
        (is (= 1 (:verified fetched)))))))

;; ---------------------------------------------------------------------------
;; cleanup-old-signatures!
;; ---------------------------------------------------------------------------

(deftest cleanup-old-signatures-test
  (let [ds (conn/create-datasource test-db-path)]
    (signature-store/create-signature! ds
                                       {:artifact-id "a1" :build-id "b-recent" :job-id "j1" :org-id "org-1"
                                        :signer "cosign" :key-reference "k1" :signature-value "s1"
                                        :target-digest "sha256:aaa"})

    (testing "cleanup with very large retention keeps recent records"
      (let [deleted (signature-store/cleanup-old-signatures! ds 365)]
        (is (= 0 deleted))
        (is (= 1 (count (signature-store/get-build-signatures ds "b-recent"))))))

    ;; Backdate the record to 2 years ago so cleanup can find it
    (next.jdbc/execute-one! ds
                            ["UPDATE artifact_signatures SET created_at = '2024-01-01 00:00:00' WHERE build_id = 'b-recent'"])

    (testing "cleanup deletes old records beyond retention period"
      (let [deleted (signature-store/cleanup-old-signatures! ds 30)]
        (is (pos? deleted))
        (is (empty? (signature-store/get-build-signatures ds "b-recent")))))))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(deftest create-signature-defaults-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-signature! defaults org-id to 'default-org'"
      (let [sig (signature-store/create-signature! ds
                                                   {:artifact-id "a1" :build-id "b-def" :job-id "j1"
                                                    :signer "cosign" :key-reference "k1"
                                                    :signature-value "s1" :target-digest "sha256:def"})]
        (is (= "default-org" (:org-id sig)))))

    (testing "create-signature! defaults verified to 0"
      (let [sig (signature-store/create-signature! ds
                                                   {:artifact-id "a2" :build-id "b-def2" :job-id "j1"
                                                    :signer "gpg" :key-reference "k2"
                                                    :signature-value "s2" :target-digest "sha256:def2"})]
        (is (= 0 (:verified sig)))))))

;; ---------------------------------------------------------------------------
;; Full round-trip with verify + cleanup
;; ---------------------------------------------------------------------------

(deftest full-round-trip-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "full signature lifecycle"
      ;; Create
      (let [sig (signature-store/create-signature! ds
                                                   {:artifact-id "art-rt" :build-id "build-rt" :job-id "job-rt"
                                                    :org-id "org-rt" :signer "cosign"
                                                    :key-reference "gcr.io/keys/rt-key"
                                                    :signature-value "MEUCIQD-roundtrip"
                                                    :target-digest "sha256:roundtrip"})]
        (is (some? (:id sig)))

        ;; Read
        (let [sigs (signature-store/get-build-signatures ds "build-rt")]
          (is (= 1 (count sigs)))
          (is (= 0 (:verified (first sigs)))))

        ;; List
        (is (= 1 (count (signature-store/list-signatures ds :org-id "org-rt"))))

        ;; Verify
        (signature-store/verify-signature! ds (:id sig))
        (let [verified (first (signature-store/get-build-signatures ds "build-rt"))]
          (is (= 1 (:verified verified)))
          (is (some? (:verified-at verified))))

        ;; Backdate and cleanup
        (next.jdbc/execute-one! ds
                                ["UPDATE artifact_signatures SET created_at = '2024-01-01 00:00:00' WHERE build_id = 'build-rt'"])
        (let [deleted (signature-store/cleanup-old-signatures! ds 30)]
          (is (pos? deleted))
          (is (empty? (signature-store/get-build-signatures ds "build-rt"))))))))
