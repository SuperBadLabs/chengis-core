(ns chengis.distributed.agent-auth
  "HMAC-SHA256 request signing and verification for agent communication.
   Provides replay protection via timestamp window + nonce deduplication.)

   Wire format:
     X-Chengis-Timestamp: epoch-seconds
     X-Chengis-Nonce: random-hex
     X-Chengis-Signature: HMAC-SHA256(secret, timestamp:nonce:sha256(body))

   Nonce replay cache:
     When :ds (datasource) is provided in opts, nonces are stored in the
     nonce_cache DB table — safe for multi-master deployments.
     Without :ds, falls back to in-process ConcurrentHashMap (single-node only).

   Backwards compatible: when auth-scheme is :bearer, falls back to
   simple Bearer token comparison."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.security MessageDigest SecureRandom]
           [java.time Instant Duration]
           [java.time.format DateTimeFormatter]
           [java.time ZoneOffset]
           [java.util Map$Entry]
           [java.util.concurrent ConcurrentHashMap]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private ^:const max-timestamp-drift-seconds 60)
(def ^:private ^:const max-nonce-cache-size 10000)

;; ---------------------------------------------------------------------------
;; Nonce dedup cache — in-memory fallback (single-node only)
;; ---------------------------------------------------------------------------

(defonce ^:private ^ConcurrentHashMap nonce-cache (ConcurrentHashMap. 256))

(defn- nonce-seen-memory?
  "Check if a nonce has been seen before using the in-process cache.
   Adds it to the cache if new. Evicts oldest entries when cache exceeds max size.
   NOT safe for multi-master deployment — use nonce-seen-db? when a datasource is available."
  [^String nonce]
  (let [already-present (.putIfAbsent ^ConcurrentHashMap nonce-cache
                                      nonce
                                      (long (System/currentTimeMillis)))]
    (when (> (.size ^ConcurrentHashMap nonce-cache) max-nonce-cache-size)
      ;; Simple eviction: remove entries older than 2x the timestamp window
      (let [cutoff (- (System/currentTimeMillis) (* 2 max-timestamp-drift-seconds 1000))]
        (doseq [^Map$Entry entry (.entrySet ^ConcurrentHashMap nonce-cache)]
          (when (< (long (.getValue entry)) cutoff)
            (.remove ^ConcurrentHashMap nonce-cache (.getKey entry))))))
    (some? already-present)))

(defn reset-nonce-cache!
  "Reset the in-memory nonce cache. For testing only."
  []
  (.clear ^ConcurrentHashMap nonce-cache))

;; ---------------------------------------------------------------------------
;; Nonce dedup cache — DB-backed (multi-master safe)
;; ---------------------------------------------------------------------------

(defn- cleanup-expired-nonces!
  "Delete nonce cache entries older than 2x the timestamp drift window."
  [ds]
  (try
    (let [cutoff-instant (.minus (Instant/now)
                                 (Duration/ofSeconds (* 2 max-timestamp-drift-seconds)))
          cutoff (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                          (.atZone cutoff-instant ZoneOffset/UTC))]
      (jdbc/execute! ds
                     (sql/format {:delete-from :nonce-cache
                                  :where [[:< :seen-at cutoff]]})))
    (catch Exception e
      (log/warn "Failed to clean up nonce cache entries:" (.getMessage e)))))

(defn- nonce-seen-db?
  "Check if a nonce has been seen before using the database.
   Inserts the nonce if new (returns false = not a replay).
   If insert fails due to a unique constraint, the nonce was already seen (replay → true).
   Falls back to in-memory cache on DB error to avoid breaking auth."
  [ds ^String nonce]
  (try
    (jdbc/execute-one! ds
                       (sql/format {:insert-into :nonce-cache
                                    :values [{:nonce nonce}]}))
    ;; Insert succeeded — nonce is fresh
    ;; Probabilistic cleanup: ~1% of requests to avoid hammering the DB
    (when (zero? (mod (System/currentTimeMillis) 100))
      (cleanup-expired-nonces! ds))
    false
    (catch Exception _
      ;; Unique constraint violation (or other DB error).
      ;; Assume replay — reject the request. If the DB is genuinely unavailable,
      ;; this is fail-closed behavior which is the safe default.
      true)))

(defn reset-nonce-cache-db!
  "Delete all entries from the DB nonce cache. For testing only."
  [ds]
  (jdbc/execute! ds (sql/format {:delete-from :nonce-cache})))

;; ---------------------------------------------------------------------------
;; Crypto primitives
;; ---------------------------------------------------------------------------

(defn- sha256-hex
  "Compute SHA-256 hash of a byte array, return as hex string."
  ^String [^bytes data]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash (.digest digest data)]
    (apply str (map #(format "%02x" %) (seq hash)))))

(defn- hmac-sha256
  "Compute HMAC-SHA256 of message using secret, return as hex string."
  ^String [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac (.getBytes message "UTF-8"))]
      (apply str (map #(format "%02x" %) (seq hash))))))

(defn- generate-nonce
  "Generate a cryptographically random 16-byte hex nonce."
  ^String []
  (let [random (SecureRandom.)
        bytes (byte-array 16)]
    (.nextBytes random bytes)
    (apply str (map #(format "%02x" %) (seq bytes)))))

(defn- constant-time-equals?
  "Constant-time string comparison to prevent timing attacks."
  [^String a ^String b]
  (when (and a b)
    (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

;; ---------------------------------------------------------------------------
;; Public API: signing
;; ---------------------------------------------------------------------------

(defn sign-request
  "Sign a request body with HMAC-SHA256.
   Returns a map of headers to add to the request:
     X-Chengis-Timestamp, X-Chengis-Nonce, X-Chengis-Signature"
  ([^String secret ^bytes body-bytes]
   (sign-request secret body-bytes nil))
  ([^String secret ^bytes body-bytes {:keys [timestamp nonce] :as _opts}]
   (let [ts (or timestamp (str (quot (System/currentTimeMillis) 1000)))
         n (or nonce (generate-nonce))
         body-hash (sha256-hex (or body-bytes (byte-array 0)))
         message (str ts ":" n ":" body-hash)
         signature (hmac-sha256 secret message)]
     {"X-Chengis-Timestamp" ts
      "X-Chengis-Nonce" n
      "X-Chengis-Signature" signature})))

;; ---------------------------------------------------------------------------
;; Public API: verification
;; ---------------------------------------------------------------------------

(defn verify-request
  "Verify an HMAC-signed request.
   Returns true if signature is valid, timestamp is within window, and nonce is fresh.
   Returns false and logs the reason on failure.

   opts map (optional 4th arg):
     :ds — JDBC datasource for DB-backed nonce deduplication (multi-master safe).
           When omitted, falls back to in-process cache (single-node only)."
  ([^String secret req ^bytes body-bytes]
   (verify-request secret req body-bytes nil))
  ([^String secret req ^bytes body-bytes opts]
   (let [headers (:headers req)
         ts-str (get headers "x-chengis-timestamp")
         nonce (get headers "x-chengis-nonce")
         signature (get headers "x-chengis-signature")
         ds (:ds opts)]
     (cond
       ;; Missing headers
       (or (str/blank? ts-str) (str/blank? nonce) (str/blank? signature))
       (do (log/debug "HMAC verification failed: missing headers"
                      {:has-timestamp (boolean ts-str)
                       :has-nonce (boolean nonce)
                       :has-signature (boolean signature)})
           false)

       ;; Timestamp validation
       (let [ts (try (Long/parseLong ts-str) (catch Exception _ nil))
             now (quot (System/currentTimeMillis) 1000)]
         (or (nil? ts)
             (> (abs (- now ts)) max-timestamp-drift-seconds)))
       (do (log/debug "HMAC verification failed: timestamp out of window"
                      {:timestamp ts-str})
           false)

       ;; Nonce replay check — DB-backed when datasource provided, in-memory otherwise
       (if ds
         (nonce-seen-db? ds nonce)
         (nonce-seen-memory? nonce))
       (do (log/warn "HMAC verification failed: nonce replay detected"
                     {:nonce nonce :backend (if ds :db :memory)})
           false)

       ;; Signature verification
       :else
       (let [body-hash (sha256-hex (or body-bytes (byte-array 0)))
             message (str ts-str ":" nonce ":" body-hash)
             expected (hmac-sha256 secret message)]
         (if (constant-time-equals? expected signature)
           true
           (do (log/debug "HMAC verification failed: signature mismatch")
               false)))))))

;; ---------------------------------------------------------------------------
;; Bearer token fallback (backwards compat)
;; ---------------------------------------------------------------------------

(defn verify-bearer-token
  "Verify a simple Bearer token. Returns true if token matches."
  [^String expected-token req]
  (let [expected-token (when-not (str/blank? expected-token) expected-token)]
    (if-not expected-token
      ;; No token configured — allow (backwards compat) but warn
      (do (log/warn "SECURITY: Accepted unauthenticated request — no auth-token configured!")
          true)
      (let [auth-header (get-in req [:headers "authorization"] "")
            provided-token (when (str/starts-with? auth-header "Bearer ")
                             (subs auth-header 7))]
        (if-not provided-token
          (do (log/debug "Missing Authorization header for Bearer auth")
              false)
          (boolean (constant-time-equals? expected-token provided-token)))))))

;; ---------------------------------------------------------------------------
;; Unified verification (selects scheme based on config)
;; ---------------------------------------------------------------------------

(defn verify-agent-request
  "Verify an incoming agent request using the configured auth scheme.
   Supports :hmac (default) and :bearer (backwards compat).

   When no secret/token is configured:
   - Default: allows request with loud warning (development convenience)
   - With :fail-closed? true option: rejects request (for master-side endpoints)

   Options map (5th arg):
     :fail-closed?  — when true, reject unauthenticated requests (no secret = deny)"
  ([auth-scheme secret req body-bytes]
   (verify-agent-request auth-scheme secret req body-bytes nil))
  ([auth-scheme secret req body-bytes opts]
   (let [fail-closed? (:fail-closed? opts false)
         ds (:ds opts)
         ;; Normalize blank/whitespace-only secrets to nil — treat as "no secret"
         secret (when-not (str/blank? secret) secret)]
     (case (or auth-scheme :hmac)
       :hmac
       (if-not secret
         (if fail-closed?
           (do (log/error "SECURITY: Rejecting unauthenticated request — no auth secret configured (fail-closed)!")
               false)
           (do (log/warn "SECURITY: No auth secret configured — accepting unauthenticated request!")
               true))
         ;; Try HMAC first; if HMAC headers are missing, check for Bearer fallback
         (if (get-in req [:headers "x-chengis-signature"])
           (verify-request secret req body-bytes {:ds ds})
           ;; Fall back to Bearer for backwards compatibility
           (verify-bearer-token secret req)))

       :bearer
       (if fail-closed?
         ;; Fail-closed: verify-bearer-token is already fail-closed when token is nil
         ;; BUT its "no expected token" path returns true (warn-only). Override that.
         (if-not secret
           (do (log/error "SECURITY: Rejecting unauthenticated request — no auth token (fail-closed)!")
               false)
           (verify-bearer-token secret req))
         (verify-bearer-token secret req))

       ;; Unknown scheme — reject
       (do (log/error "Unknown auth-scheme:" auth-scheme)
           false)))))
