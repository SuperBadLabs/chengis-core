(ns chengis.engine.webhook-signing-test
  "Tests for outgoing webhook signing and incoming replay protection (2.14)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.set :as set]
            [chengis.engine.webhook-signing :as sut]))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(defn reset-nonce-cache [f]
  (sut/reset-nonce-cache!)
  (f)
  (sut/reset-nonce-cache!))

(use-fixtures :each reset-nonce-cache)

;; ---------------------------------------------------------------------------
;; sign-outgoing-headers
;; ---------------------------------------------------------------------------

(deftest sign-outgoing-headers-returns-required-headers
  (let [headers (sut/sign-outgoing-headers "secret" (.getBytes "body" "UTF-8"))]
    (testing "all required headers are present"
      (is (contains? headers "X-Chengis-Signature-256"))
      (is (contains? headers "X-Chengis-Timestamp"))
      (is (contains? headers "X-Chengis-Nonce")))
    (testing "signature has sha256= prefix"
      (is (.startsWith (get headers "X-Chengis-Signature-256") "sha256=")))
    (testing "nonce is 32 hex chars (16 bytes)"
      (is (= 32 (count (get headers "X-Chengis-Nonce")))))
    (testing "timestamp is numeric"
      (is (number? (Long/parseLong (get headers "X-Chengis-Timestamp")))))))

(deftest sign-outgoing-headers-throws-on-blank-secret
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/sign-outgoing-headers "" (.getBytes "body" "UTF-8")))))

(deftest sign-outgoing-headers-nil-body-uses-empty-bytes
  (let [h1 (sut/sign-outgoing-headers "s" nil {:timestamp "1234" :nonce "aabbcc"})
        h2 (sut/sign-outgoing-headers "s" (byte-array 0) {:timestamp "1234" :nonce "aabbcc"})]
    (is (= (get h1 "X-Chengis-Signature-256")
           (get h2 "X-Chengis-Signature-256"))
        "nil body and empty body produce same signature")))

(deftest sign-outgoing-headers-deterministic-with-fixed-opts
  (let [body  (.getBytes "hello" "UTF-8")
        h1 (sut/sign-outgoing-headers "secret" body {:timestamp "1000" :nonce "deadbeef"})
        h2 (sut/sign-outgoing-headers "secret" body {:timestamp "1000" :nonce "deadbeef"})]
    (is (= h1 h2) "same inputs produce same headers")))

(deftest sign-outgoing-headers-differs-on-different-secret
  (let [body (.getBytes "hello" "UTF-8")
        opts {:timestamp "1000" :nonce "deadbeef"}
        h1   (sut/sign-outgoing-headers "secret1" body opts)
        h2   (sut/sign-outgoing-headers "secret2" body opts)]
    (is (not= (get h1 "X-Chengis-Signature-256")
              (get h2 "X-Chengis-Signature-256"))
        "different secrets produce different signatures")))

;; ---------------------------------------------------------------------------
;; verify-outgoing-signature
;; ---------------------------------------------------------------------------

(deftest verify-outgoing-signature-accepts-valid-request
  (let [secret "test-secret"
        body   (.getBytes "payload" "UTF-8")
        now-ts (str (quot (System/currentTimeMillis) 1000))
        headers (-> (sut/sign-outgoing-headers secret body {:timestamp now-ts})
                    (clojure.set/rename-keys
                     {"X-Chengis-Signature-256" "x-chengis-signature-256"
                      "X-Chengis-Timestamp"     "x-chengis-timestamp"
                      "X-Chengis-Nonce"         "x-chengis-nonce"}))
        req {:headers headers}]
    (is (true? (sut/verify-outgoing-signature secret req body)))))

(deftest verify-outgoing-signature-rejects-wrong-secret
  (let [body   (.getBytes "payload" "UTF-8")
        now-ts (str (quot (System/currentTimeMillis) 1000))
        headers (-> (sut/sign-outgoing-headers "correct-secret" body {:timestamp now-ts})
                    (clojure.set/rename-keys
                     {"X-Chengis-Signature-256" "x-chengis-signature-256"
                      "X-Chengis-Timestamp"     "x-chengis-timestamp"
                      "X-Chengis-Nonce"         "x-chengis-nonce"}))
        req {:headers headers}]
    (is (false? (sut/verify-outgoing-signature "wrong-secret" req body)))))

(deftest verify-outgoing-signature-rejects-missing-headers
  (let [body (.getBytes "payload" "UTF-8")
        req  {:headers {}}]
    (is (false? (sut/verify-outgoing-signature "secret" req body)))))

(deftest verify-outgoing-signature-rejects-stale-timestamp
  (let [secret  "test-secret"
        body    (.getBytes "payload" "UTF-8")
        old-ts  (str (- (quot (System/currentTimeMillis) 1000) 400))  ; 400 seconds ago
        headers (-> (sut/sign-outgoing-headers secret body {:timestamp old-ts})
                    (clojure.set/rename-keys
                     {"X-Chengis-Signature-256" "x-chengis-signature-256"
                      "X-Chengis-Timestamp"     "x-chengis-timestamp"
                      "X-Chengis-Nonce"         "x-chengis-nonce"}))
        req {:headers headers}]
    (is (false? (sut/verify-outgoing-signature secret req body)))))

(deftest verify-outgoing-signature-rejects-nonce-replay
  (let [secret  "test-secret"
        body    (.getBytes "payload" "UTF-8")
        now-ts  (str (quot (System/currentTimeMillis) 1000))
        headers (-> (sut/sign-outgoing-headers secret body {:timestamp now-ts :nonce "unique-nonce-42"})
                    (clojure.set/rename-keys
                     {"X-Chengis-Signature-256" "x-chengis-signature-256"
                      "X-Chengis-Timestamp"     "x-chengis-timestamp"
                      "X-Chengis-Nonce"         "x-chengis-nonce"}))
        req {:headers headers}]
    ;; First request is valid
    (is (true? (sut/verify-outgoing-signature secret req body)))
    ;; Replay is rejected
    (is (false? (sut/verify-outgoing-signature secret req body)))))

;; ---------------------------------------------------------------------------
;; verify-incoming-replay?
;; ---------------------------------------------------------------------------

(deftest verify-incoming-replay-allows-requests-without-chengis-headers
  (testing "GitHub/GitLab webhooks without Chengis replay headers are allowed"
    (let [req {:headers {"x-github-event" "push"
                         "x-hub-signature-256" "sha256=abc"}}]
      (is (true? (sut/verify-incoming-replay? req))))))

(deftest verify-incoming-replay-accepts-fresh-request-with-valid-headers
  (let [now-ts (str (quot (System/currentTimeMillis) 1000))
        req {:headers {"x-chengis-timestamp" now-ts
                       "x-chengis-nonce"     "fresh-nonce-99"}}]
    (is (true? (sut/verify-incoming-replay? req)))))

(deftest verify-incoming-replay-rejects-stale-timestamp
  (let [old-ts (str (- (quot (System/currentTimeMillis) 1000) 400))
        req {:headers {"x-chengis-timestamp" old-ts
                       "x-chengis-nonce"     "nonce-abc"}}]
    (is (false? (sut/verify-incoming-replay? req)))))

(deftest verify-incoming-replay-rejects-duplicate-nonce
  (let [now-ts (str (quot (System/currentTimeMillis) 1000))
        req {:headers {"x-chengis-timestamp" now-ts
                       "x-chengis-nonce"     "duplicate-nonce-xyz"}}]
    (is (true? (sut/verify-incoming-replay? req)))
    (is (false? (sut/verify-incoming-replay? req)) "second request with same nonce is rejected")))
