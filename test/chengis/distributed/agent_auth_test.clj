(ns ^:integration chengis.distributed.agent-auth-test
  "Tests for HMAC-SHA256 agent request signing and verification (A.4).
   Covers sign/verify round-trip, tamper detection, expired timestamps,
   nonce replay rejection, and bearer fallback.
   SEC-02: DB-backed nonce cache tests ensure multi-master replay protection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.agent-auth :as agent-auth]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reset nonce cache between tests to prevent cross-test contamination
;; ---------------------------------------------------------------------------

(defn- reset-nonces [f]
  (agent-auth/reset-nonce-cache!)
  (f)
  (agent-auth/reset-nonce-cache!))

(use-fixtures :each reset-nonces)

;; ---------------------------------------------------------------------------
;; Signing
;; ---------------------------------------------------------------------------

(deftest sign-request-produces-three-headers-test
  (testing "sign-request returns all three HMAC headers"
    (let [secret "test-secret"
          body (.getBytes "hello" "UTF-8")
          headers (agent-auth/sign-request secret body)]
      (is (contains? headers "X-Chengis-Timestamp"))
      (is (contains? headers "X-Chengis-Nonce"))
      (is (contains? headers "X-Chengis-Signature"))
      (is (string? (get headers "X-Chengis-Timestamp")))
      (is (string? (get headers "X-Chengis-Nonce")))
      (is (string? (get headers "X-Chengis-Signature"))))))

(deftest sign-request-deterministic-with-fixed-inputs-test
  (testing "sign-request produces same signature for same inputs"
    (let [secret "test-secret"
          body (.getBytes "payload" "UTF-8")
          opts {:timestamp "1700000000" :nonce "deadbeefcafebabe0000000000000000"}
          h1 (agent-auth/sign-request secret body opts)
          h2 (agent-auth/sign-request secret body opts)]
      (is (= (get h1 "X-Chengis-Signature")
             (get h2 "X-Chengis-Signature"))))))

(deftest sign-request-different-secrets-produce-different-signatures-test
  (testing "Different secrets produce different signatures"
    (let [body (.getBytes "payload" "UTF-8")
          opts {:timestamp "1700000000" :nonce "deadbeefcafebabe0000000000000000"}
          h1 (agent-auth/sign-request "secret-a" body opts)
          h2 (agent-auth/sign-request "secret-b" body opts)]
      (is (not= (get h1 "X-Chengis-Signature")
                (get h2 "X-Chengis-Signature"))))))

(deftest sign-request-nil-body-handled-test
  (testing "sign-request handles nil body gracefully"
    (let [headers (agent-auth/sign-request "test-secret" nil)]
      (is (some? (get headers "X-Chengis-Signature"))))))

;; ---------------------------------------------------------------------------
;; Verify: round-trip
;; ---------------------------------------------------------------------------

(deftest verify-round-trip-test
  (testing "sign then verify succeeds"
    (let [secret "round-trip-secret"
          body (.getBytes "{\"build-id\":\"b1\"}" "UTF-8")
          headers (agent-auth/sign-request secret body)
          ;; Construct a request map with lowercased headers (Ring convention)
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (true? (agent-auth/verify-request secret req body))))))

(deftest verify-round-trip-empty-body-test
  (testing "sign then verify succeeds with empty body"
    (let [secret "empty-body-secret"
          body (byte-array 0)
          headers (agent-auth/sign-request secret body)
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (true? (agent-auth/verify-request secret req body))))))

;; ---------------------------------------------------------------------------
;; Verify: tamper detection
;; ---------------------------------------------------------------------------

(deftest verify-tampered-body-fails-test
  (testing "Tampered body fails verification"
    (let [secret "tamper-secret"
          original-body (.getBytes "{\"build-id\":\"b1\"}" "UTF-8")
          headers (agent-auth/sign-request secret original-body)
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}
          tampered-body (.getBytes "{\"build-id\":\"HACKED\"}" "UTF-8")]
      (is (false? (agent-auth/verify-request secret req tampered-body))))))

(deftest verify-wrong-secret-fails-test
  (testing "Wrong secret fails verification"
    (let [body (.getBytes "payload" "UTF-8")
          headers (agent-auth/sign-request "secret-a" body)
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (false? (agent-auth/verify-request "secret-b" req body))))))

;; ---------------------------------------------------------------------------
;; Verify: timestamp window
;; ---------------------------------------------------------------------------

(deftest verify-expired-timestamp-fails-test
  (testing "Expired timestamp (>60s) fails verification"
    (let [secret "ts-secret"
          body (.getBytes "payload" "UTF-8")
          old-ts (str (- (quot (System/currentTimeMillis) 1000) 120))
          headers (agent-auth/sign-request secret body {:timestamp old-ts})
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (false? (agent-auth/verify-request secret req body))))))

(deftest verify-future-timestamp-fails-test
  (testing "Future timestamp (>60s ahead) fails verification"
    (let [secret "ts-secret"
          body (.getBytes "payload" "UTF-8")
          future-ts (str (+ (quot (System/currentTimeMillis) 1000) 120))
          headers (agent-auth/sign-request secret body {:timestamp future-ts})
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (false? (agent-auth/verify-request secret req body))))))

(deftest verify-current-timestamp-passes-test
  (testing "Current timestamp passes verification"
    (let [secret "ts-secret"
          body (.getBytes "payload" "UTF-8")
          now-ts (str (quot (System/currentTimeMillis) 1000))
          headers (agent-auth/sign-request secret body {:timestamp now-ts})
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (true? (agent-auth/verify-request secret req body))))))

;; ---------------------------------------------------------------------------
;; Verify: nonce replay
;; ---------------------------------------------------------------------------

(deftest verify-nonce-replay-rejected-test
  (testing "Same nonce used twice is rejected"
    (let [secret "nonce-secret"
          body (.getBytes "payload" "UTF-8")
          fixed-nonce "aaaa1111bbbb2222cccc3333dddd4444"
          now-ts (str (quot (System/currentTimeMillis) 1000))
          headers (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      ;; First use passes
      (is (true? (agent-auth/verify-request secret req body)))
      ;; Second use with same nonce fails (replay)
      (let [headers2 (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
            req2 {:headers (into {}
                                 (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers2))}]
        (is (false? (agent-auth/verify-request secret req2 body)))))))

(deftest verify-different-nonces-both-pass-test
  (testing "Different nonces both pass"
    (let [secret "nonce-secret"
          body (.getBytes "payload" "UTF-8")
          now-ts (str (quot (System/currentTimeMillis) 1000))
          ;; Two requests with different nonces should both pass
          h1 (agent-auth/sign-request secret body {:timestamp now-ts :nonce "0000111122223333aaaa0000111122223333"})
          h2 (agent-auth/sign-request secret body {:timestamp now-ts :nonce "4444555566667777bbbb4444555566667777"})
          r1 {:headers (into {} (map (fn [[k v]] [(clojure.string/lower-case k) v]) h1))}
          r2 {:headers (into {} (map (fn [[k v]] [(clojure.string/lower-case k) v]) h2))}]
      (is (true? (agent-auth/verify-request secret r1 body)))
      (is (true? (agent-auth/verify-request secret r2 body))))))

;; ---------------------------------------------------------------------------
;; Verify: missing headers
;; ---------------------------------------------------------------------------

(deftest verify-missing-timestamp-header-fails-test
  (testing "Missing timestamp header fails"
    (is (false? (agent-auth/verify-request "secret"
                                           {:headers {"x-chengis-nonce" "abc" "x-chengis-signature" "def"}}
                                           (byte-array 0))))))

(deftest verify-missing-nonce-header-fails-test
  (testing "Missing nonce header fails"
    (is (false? (agent-auth/verify-request "secret"
                                           {:headers {"x-chengis-timestamp" "123" "x-chengis-signature" "def"}}
                                           (byte-array 0))))))

(deftest verify-missing-signature-header-fails-test
  (testing "Missing signature header fails"
    (is (false? (agent-auth/verify-request "secret"
                                           {:headers {"x-chengis-timestamp" "123" "x-chengis-nonce" "abc"}}
                                           (byte-array 0))))))

;; ---------------------------------------------------------------------------
;; Bearer token fallback
;; ---------------------------------------------------------------------------

(deftest bearer-token-correct-passes-test
  (testing "Bearer token verification passes with correct token"
    (is (true? (agent-auth/verify-bearer-token
                "secret-123"
                {:headers {"authorization" "Bearer secret-123"}})))))

(deftest bearer-token-wrong-fails-test
  (testing "Bearer token verification fails with wrong token"
    (is (false? (agent-auth/verify-bearer-token
                 "secret-123"
                 {:headers {"authorization" "Bearer wrong-token"}})))))

(deftest bearer-token-missing-header-fails-test
  (testing "Bearer token verification fails with missing header"
    (is (false? (agent-auth/verify-bearer-token
                 "secret-123"
                 {:headers {}})))))

(deftest bearer-token-nil-expected-allows-test
  (testing "Bearer token verification allows when no expected token (backwards compat)"
    (is (true? (agent-auth/verify-bearer-token
                nil
                {:headers {}})))))

;; ---------------------------------------------------------------------------
;; Unified verify-agent-request
;; ---------------------------------------------------------------------------

(deftest verify-agent-request-hmac-round-trip-test
  (testing "verify-agent-request with :hmac scheme succeeds on valid request"
    (let [secret "unified-secret"
          body (.getBytes "{\"test\":true}" "UTF-8")
          headers (agent-auth/sign-request secret body)
          req {:headers (into {}
                              (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
      (is (true? (agent-auth/verify-agent-request :hmac secret req body))))))

(deftest verify-agent-request-bearer-fallback-in-hmac-mode-test
  (testing "verify-agent-request falls back to Bearer when HMAC headers missing in :hmac mode"
    (is (true? (agent-auth/verify-agent-request
                :hmac "secret-123"
                {:headers {"authorization" "Bearer secret-123"}}
                nil)))))

(deftest verify-agent-request-bearer-mode-test
  (testing "verify-agent-request with :bearer scheme uses Bearer auth"
    (is (true? (agent-auth/verify-agent-request
                :bearer "secret-123"
                {:headers {"authorization" "Bearer secret-123"}}
                nil)))))

(deftest verify-agent-request-nil-secret-allows-test
  (testing "verify-agent-request allows when no secret configured (backwards compat)"
    (is (true? (agent-auth/verify-agent-request :hmac nil {:headers {}} nil)))))

(deftest verify-agent-request-unknown-scheme-rejects-test
  (testing "verify-agent-request rejects unknown auth scheme"
    (is (false? (agent-auth/verify-agent-request :unknown "secret" {:headers {}} nil)))))

;; ---------------------------------------------------------------------------
;; SEC-02: DB-backed nonce cache (multi-master safe)
;; ---------------------------------------------------------------------------

(def ^:private test-db-path "/tmp/chengis-agent-auth-nonce-test.db")

(defn- setup-nonce-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(deftest db-nonce-replay-rejected-test
  (setup-nonce-db
   (fn []
     (let [ds (conn/create-datasource test-db-path)
           secret "nonce-db-secret"
           body (.getBytes "payload" "UTF-8")
           fixed-nonce "db1111bbbb2222cccc3333dddd4444ee"
           now-ts (str (quot (System/currentTimeMillis) 1000))
           headers (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
           req {:headers (into {}
                               (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
       (testing "DB-backed: first use passes"
         (is (true? (agent-auth/verify-request secret req body {:ds ds}))))
       (testing "DB-backed: second use with same nonce rejected (replay)"
         (let [headers2 (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
               req2 {:headers (into {}
                                    (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers2))}]
           (is (false? (agent-auth/verify-request secret req2 body {:ds ds})))))))))

(deftest db-nonce-different-nonces-both-pass-test
  (setup-nonce-db
   (fn []
     (let [ds (conn/create-datasource test-db-path)
           secret "nonce-db-secret"
           body (.getBytes "payload" "UTF-8")
           now-ts (str (quot (System/currentTimeMillis) 1000))]
       (testing "DB-backed: different nonces both pass"
         (let [h1 (agent-auth/sign-request secret body {:timestamp now-ts :nonce "db0000111122223333aaaa000011112222"})
               h2 (agent-auth/sign-request secret body {:timestamp now-ts :nonce "db4444555566667777bbbb444455556666"})
               r1 {:headers (into {} (map (fn [[k v]] [(clojure.string/lower-case k) v]) h1))}
               r2 {:headers (into {} (map (fn [[k v]] [(clojure.string/lower-case k) v]) h2))}]
           (is (true? (agent-auth/verify-request secret r1 body {:ds ds})))
           (is (true? (agent-auth/verify-request secret r2 body {:ds ds})))))))))

(deftest db-nonce-verify-agent-request-threads-ds-test
  (setup-nonce-db
   (fn []
     (let [ds (conn/create-datasource test-db-path)
           secret "agent-db-secret"
           body (.getBytes "{\"test\":true}" "UTF-8")
           fixed-nonce "agent1111bbbb2222cccc3333dddd4444"
           now-ts (str (quot (System/currentTimeMillis) 1000))
           headers (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
           req {:headers (into {}
                               (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers))}]
       (testing "verify-agent-request threads :ds into nonce check"
         (is (true? (agent-auth/verify-agent-request :hmac secret req body {:ds ds}))))
       (testing "same nonce rejected on second call via verify-agent-request"
         (let [headers2 (agent-auth/sign-request secret body {:timestamp now-ts :nonce fixed-nonce})
               req2 {:headers (into {}
                                    (map (fn [[k v]] [(clojure.string/lower-case k) v]) headers2))}]
           (is (false? (agent-auth/verify-agent-request :hmac secret req2 body {:ds ds})))))))))
