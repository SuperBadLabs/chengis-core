(ns chengis.plugin.signing
  "Plugin provenance — Ed25519 detached-signature verification (M2a).

   A plugin earns the :trusted SCI context ONLY if a detached signature over its
   source verifies against one of the operator-configured trusted public keys.
   Unsigned or invalidly-signed plugins are forced to :sandboxed regardless of
   policy — policy alone can never grant the fast lane to unattested bytes. This
   is the enforcement behind the strategy doc's claim that 'policy can't be
   talked into giving a stranger the SCI lane'.

   Format:
   - A plugin `<name>.clj` is accompanied by `<name>.clj.sig` whose contents are
     the base64-encoded raw Ed25519 signature over the SIGNED PAYLOAD.
   - The signed payload is the `.clj` source bytes AND the sidecar `<name>.edn`
     manifest bytes (when present), length-prefixed (see `signed-payload`). The
     manifest is part of the payload because it drives the trust/capability
     grant — signing only the source would let a tampered manifest escalate
     privilege without invalidating the signature.
   - Trusted keys are configured at [:plugins :signing :public-keys] as a vector
     of base64-encoded X.509 SubjectPublicKeyInfo strings (the standard public
     key encoding — the body of a PEM 'PUBLIC KEY' block, newlines optional).

   TOCTOU: the loader reads the source + manifest ONCE, verifies that exact
   content, and evaluates that exact content — it never re-reads the file after
   verifying (which would allow a swap between check and use).

   Pure JDK (java.security Ed25519, available JDK 15+); no extra dependency.
   Verification only — signing is done offline by the operator with their
   private key; the server never holds it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.security KeyFactory MessageDigest Signature]
           [java.security.spec X509EncodedKeySpec]
           [java.util Base64]))

(defn- b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) ^String (str/replace s #"\s" "")))

(defn load-public-key
  "Parse a base64 X.509 SubjectPublicKeyInfo string into an Ed25519 PublicKey.
   Returns nil (with a warning) if it can't be parsed."
  [^String b64]
  (try
    (.generatePublic (KeyFactory/getInstance "Ed25519")
                     (X509EncodedKeySpec. (b64-decode b64)))
    (catch Exception e
      (log/warn "Ignoring invalid plugin signing public-key:" (.getMessage e))
      nil)))

(defn key-id
  "Stable short fingerprint for a configured public key: the first 16 lowercase
   hex chars of the SHA-256 of its decoded X.509 SPKI bytes. Lets operators and
   audit logs name a key ('which key signed this?', 'revoke key abcd…') without
   echoing the whole blob. Returns nil for an unparseable key."
  [^String b64]
  (try
    (let [spki (b64-decode b64)
          dig  (.digest (MessageDigest/getInstance "SHA-256") spki)]
      (->> (take 8 dig)
           (map #(format "%02x" (bit-and (int %) 0xff)))
           (apply str)))
    (catch Exception _ nil)))

(defn normalize-key
  "Coerce one configured signing-key entry into a canonical map
     {:key <base64 SPKI> :status :active|:revoked :id <key-id>
      :label .. :added .. :revoked-at .. :reason ..}
   so the config can carry key lifecycle metadata. A plain string entry is a
   bare active key (the original config shape — fully backward compatible). Map
   entries may add :label/:status/:reason etc.; only `:status :revoked` retires
   a key. :id is always (re)derived from the key bytes — operators can't mislabel
   which physical key a row points at. Returns nil for entries with no usable
   :key string."
  [entry]
  (let [m (cond (string? entry) {:key entry}
                (map? entry)    entry
                :else           nil)]
    (when (and m (string? (:key m)))
      (let [status (if (= :revoked (some-> (:status m) name keyword)) :revoked :active)]
        (assoc m :status status :id (or (key-id (:key m)) (:id m)))))))

(defn normalize-keys
  "Normalize the configured signing keys (a seq of base64 strings and/or maps)
   into canonical key maps (see `normalize-key`), dropping entries with no
   usable :key."
  [configured]
  (->> (or configured []) (keep normalize-key) vec))

(defn active-public-keys
  "Base64 SPKI strings for the keys that are currently :active (not :revoked).
   This is the single revocation chokepoint: flip a key to `:status :revoked`
   in config and it stops verifying signatures immediately, while its row stays
   for provenance/audit. Accepts the same mixed string/map config that
   `normalize-keys` does — so a plain string vector still yields all keys active."
  [configured]
  (->> (normalize-keys configured)
       (filter #(= :active (:status %)))
       (mapv :key)))

(defn verify
  "True if `sig` (raw Ed25519 signature bytes) over `data` verifies against
   `public-key` (a java.security.PublicKey)."
  [^bytes data ^bytes sig public-key]
  (try
    (let [s (Signature/getInstance "Ed25519")]
      (.initVerify s public-key)
      (.update s data)
      (.verify s sig))
    (catch Exception _ false)))

(defn signed-payload
  "The canonical byte payload a plugin signature must cover: the `.clj` source
   bytes and the sidecar manifest bytes (nil if no manifest), length-prefixed so
   the boundary between them is unambiguous. Both the offline signer and the
   verifier MUST build the payload identically."
  ^bytes [^bytes clj-bytes edn-bytes]
  (let [edn  (or edn-bytes (byte-array 0))
        baos (ByteArrayOutputStream.)
        dos  (DataOutputStream. baos)]
    (.writeInt dos (alength clj-bytes))
    (.write dos clj-bytes)
    (.writeInt dos (alength ^bytes edn))
    (.write dos ^bytes edn)
    (.flush dos)
    (.toByteArray baos)))

(defn read-signature
  "Read and base64-decode the sibling `<clj>.sig`; nil if absent/unreadable."
  ^bytes [^java.io.File clj-file]
  (let [sig-file (io/file (str (.getAbsolutePath clj-file) ".sig"))]
    (when (.isFile sig-file)
      (try (b64-decode (slurp sig-file))
           (catch Exception e
             (log/warn "Unreadable plugin signature" (.getName sig-file) ":" (.getMessage e))
             nil)))))

(defn verifying-key-id
  "The `key-id` of the FIRST currently-active configured key whose signature
   verifies the (clj + manifest) payload, or nil if none do. `public-keys` is
   the mixed string/map signing-key config; revoked keys are skipped. This is
   the provenance hook — the loader records 'who signed' from this id. Use with
   bytes you've already read once (TOCTOU-safe; no re-read between check & use)."
  [^bytes clj-bytes edn-bytes ^bytes sig public-keys]
  (when (and sig clj-bytes)
    (let [payload (signed-payload clj-bytes edn-bytes)]
      (some (fn [b64]
              (when-let [pk (load-public-key b64)]
                (when (verify payload sig pk) (key-id b64))))
            (active-public-keys public-keys)))))

(defn verify-payload?
  "True iff `sig` verifies the (clj + manifest) `signed-payload` against ANY
   currently-active configured key (revoked keys are ignored — see
   `active-public-keys`). Use this with bytes you have already read once
   (avoids re-reading the file between verify and use)."
  [^bytes clj-bytes edn-bytes ^bytes sig public-keys]
  (boolean (verifying-key-id clj-bytes edn-bytes sig public-keys)))

(defn verified-file?
  "Convenience for callers that don't already hold the bytes: read `clj-file`,
   its sibling manifest (if any) and `.sig`, then verify the combined payload
   against `public-keys`. NOTE: re-reads files, so it is NOT TOCTOU-safe — the
   loader uses `verify-payload?` with already-read bytes instead."
  [^java.io.File clj-file public-keys]
  (let [sig (read-signature clj-file)]
    (boolean
     (when sig
       (let [clj-bytes (.getBytes (slurp clj-file) "UTF-8")
             mf        (io/file (.getParentFile clj-file)
                                (str/replace (.getName clj-file) #"\.clj$" ".edn"))
             edn-bytes (when (.isFile mf) (.getBytes (slurp mf) "UTF-8"))]
         (verify-payload? clj-bytes edn-bytes sig public-keys))))))
