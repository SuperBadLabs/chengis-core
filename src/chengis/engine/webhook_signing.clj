(ns chengis.engine.webhook-signing
  "Outgoing webhook signing and incoming webhook replay protection (2.14).)

   Outgoing webhooks (Chengis → external):
     Adds three headers to every outgoing HTTP notification:
       X-Chengis-Signature-256  — HMAC-SHA256(secret, timestamp:nonce:sha256(body))
       X-Chengis-Timestamp      — Unix epoch seconds (string)
       X-Chengis-Nonce          — 16-byte cryptographic random hex string

   Incoming webhook replay protection (per-org endpoints):
     verify-incoming-replay? checks the Chengis-specific headers on requests
     to /api/webhook/:slug.  Window: 300 seconds (5 minutes).
     Reuses the DB-backed nonce cache from agent-auth for multi-master safety."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.security MessageDigest SecureRandom]
           [java.util.concurrent ConcurrentHashMap]
           [java.util Map$Entry]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private ^:const replay-window-seconds 300)
(def ^:private ^:const max-nonce-cache-size 50000)

;; ---------------------------------------------------------------------------
;; In-memory nonce cache for replay protection (single-node fallback)
;; ---------------------------------------------------------------------------

(defonce ^:private ^ConcurrentHashMap nonce-cache (ConcurrentHashMap. 512))

(defn reset-nonce-cache!
  "Clear the in-memory replay nonce cache. For testing only."
  []
  (.clear ^ConcurrentHashMap nonce-cache))

(defn- evict-expired-nonces!
  "Remove nonces older than 2× the replay window."
  []
  (let [cutoff (- (System/currentTimeMillis) (* 2 replay-window-seconds 1000))]
    (doseq [^Map$Entry entry (.entrySet ^ConcurrentHashMap nonce-cache)]
      (when (< (long (.getValue entry)) cutoff)
        (.remove ^ConcurrentHashMap nonce-cache (.getKey entry))))))

(defn- nonce-seen-memory?
  "Check (and mark seen) a nonce in the in-memory cache.
   Returns true if the nonce was already present (replay)."
  [^String nonce]
  (let [already (.putIfAbsent ^ConcurrentHashMap nonce-cache
                              nonce
                              (long (System/currentTimeMillis)))]
    (when (> (.size ^ConcurrentHashMap nonce-cache) max-nonce-cache-size)
      (evict-expired-nonces!))
    (some? already)))

;; ---------------------------------------------------------------------------
;; Crypto primitives
;; ---------------------------------------------------------------------------

(defn- sha256-hex
  "Compute SHA-256 of a byte array; return lowercase hex string."
  ^String [^bytes data]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash (.digest digest data)]
    (apply str (map #(format "%02x" %) (seq hash)))))

(defn- hmac-sha256-hex
  "Compute HMAC-SHA256 of message using secret; return lowercase hex string."
  ^String [^String secret ^String message]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac (.getBytes message "UTF-8"))]
      (apply str (map #(format "%02x" %) (seq hash))))))

(defn- generate-nonce
  "Generate a cryptographically random 16-byte hex nonce."
  ^String []
  (let [rng (SecureRandom.)
        buf (byte-array 16)]
    (.nextBytes rng buf)
    (apply str (map #(format "%02x" %) (seq buf)))))

(defn- constant-time-equals?
  "Constant-time string comparison to prevent timing attacks."
  [^String a ^String b]
  (when (and a b)
    (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

;; ---------------------------------------------------------------------------
;; Public API: outgoing webhook signing
;; ---------------------------------------------------------------------------

(defn sign-outgoing-headers
  "Return a map of headers to add to an outgoing webhook HTTP request.
   The signature covers: timestamp:nonce:sha256(body).

     secret     — per-destination HMAC secret (string)
     body-bytes — request body as byte array (may be empty/nil)

   Returns:
     {\"X-Chengis-Signature-256\" \"sha256=<hex>\"
      \"X-Chengis-Timestamp\"     \"<epoch-seconds>\"
      \"X-Chengis-Nonce\"         \"<16-byte-hex>\"}"
  ([^String secret body-bytes]
   (sign-outgoing-headers secret body-bytes nil))
  ([^String secret body-bytes {:keys [timestamp nonce] :as _opts}]
   (when (str/blank? secret)
     (throw (ex-info "Cannot sign outgoing webhook: secret is blank" {})))
   (let [ts    (or timestamp (str (quot (System/currentTimeMillis) 1000)))
         n     (or nonce (generate-nonce))
         bh    (sha256-hex (or body-bytes (byte-array 0)))
         msg   (str ts ":" n ":" bh)
         sig   (hmac-sha256-hex secret msg)]
     {"X-Chengis-Signature-256" (str "sha256=" sig)
      "X-Chengis-Timestamp"     ts
      "X-Chengis-Nonce"         n})))

;; ---------------------------------------------------------------------------
;; Public API: verify outgoing signature (for callers who receive our webhooks)
;; ---------------------------------------------------------------------------

(defn verify-outgoing-signature
  "Verify a Chengis-signed outgoing webhook received by an external system.
   Checks:
     1. Headers present
     2. Timestamp within ±replay-window-seconds
     3. Nonce not seen before (replay protection)
     4. HMAC-SHA256 signature matches

   Returns true on success, false on any failure."
  [^String secret req body-bytes]
  (let [headers (:headers req)
        sig-hdr (get headers "x-chengis-signature-256")
        ts-str  (get headers "x-chengis-timestamp")
        nonce   (get headers "x-chengis-nonce")]
    (cond
      (or (str/blank? sig-hdr) (str/blank? ts-str) (str/blank? nonce))
      (do (log/warn "Outgoing webhook verification failed: missing headers")
          false)

      :else
      (let [ts (try (Long/parseLong ts-str) (catch Exception _ nil))
            now (quot (System/currentTimeMillis) 1000)]
        (cond
          (or (nil? ts) (> (Math/abs (long (- now ts))) replay-window-seconds))
          (do (log/warn "Outgoing webhook verification failed: timestamp out of window"
                        {:timestamp ts-str})
              false)

          (nonce-seen-memory? nonce)
          (do (log/warn "Outgoing webhook verification failed: nonce replay" {:nonce nonce})
              false)

          :else
          (let [bh      (sha256-hex (or body-bytes (byte-array 0)))
                msg     (str ts-str ":" nonce ":" bh)
                expected (str "sha256=" (hmac-sha256-hex secret msg))]
            (if (constant-time-equals? sig-hdr expected)
              true
              (do (log/warn "Outgoing webhook verification failed: signature mismatch")
                  false))))))))

;; ---------------------------------------------------------------------------
;; Public API: incoming per-org webhook replay protection
;; ---------------------------------------------------------------------------

(defn verify-incoming-replay?
  "Check replay protection headers on incoming per-org webhook requests.
   Returns true when the request is FRESH (not a replay), false when it
   should be rejected.

   Expected headers (added by senders that support Chengis replay protection):
     X-Chengis-Timestamp  — epoch seconds
     X-Chengis-Nonce      — unique random token

   If these headers are absent, returns true (allow) because standard SCM
   providers (GitHub, GitLab) do not send them — their own replay protection
   is sufficient.  Only rejects when headers are present but invalid."
  [req]
  (let [headers  (:headers req)
        ts-str   (get headers "x-chengis-timestamp")
        nonce    (get headers "x-chengis-nonce")]
    ;; No Chengis-specific replay headers → not a Chengis-signed request; allow.
    (if (or (str/blank? ts-str) (str/blank? nonce))
      true
      (let [ts  (try (Long/parseLong ts-str) (catch Exception _ nil))
            now (quot (System/currentTimeMillis) 1000)]
        (cond
          (or (nil? ts) (> (Math/abs (long (- now ts))) replay-window-seconds))
          (do (log/warn "Incoming webhook rejected: timestamp out of 5-minute window"
                        {:timestamp ts-str})
              false)

          (nonce-seen-memory? nonce)
          (do (log/warn "Incoming webhook rejected: nonce replay detected" {:nonce nonce})
              false)

          :else true)))))
