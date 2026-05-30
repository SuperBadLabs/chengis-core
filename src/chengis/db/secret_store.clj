(ns chengis.db.secret-store
  "Encrypted secrets storage for CI/CD pipelines.
   Secrets are encrypted at rest using AES-256-GCM. The master key comes from
   config (:secrets :master-key) or the CHENGIS_SECRET_KEY environment variable.)

   Key derivation (SEC-04):
     v2 (current): HKDF with HMAC-SHA256 (RFC 5869). New secrets use this scheme.
                   Encrypted values are prefixed with \"v2:\".
     v1 (legacy):  Raw SHA-256 hash. Used only for decrypting existing secrets.
                   No prefix (or handled transparently on read)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.db.secret-audit :as secret-audit]
            [chengis.metrics :as metrics]
            [chengis.util :as util]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [javax.crypto Cipher Mac SecretKey]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]
           [java.security SecureRandom]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Encryption helpers (AES-256-GCM)
;; ---------------------------------------------------------------------------

(def ^:private gcm-tag-bits 128)
(def ^:private iv-bytes 12)
(def ^:private v2-prefix "v2:")

;; ---------------------------------------------------------------------------
;; Key derivation
;; ---------------------------------------------------------------------------

(defn- derive-key-sha256
  "Derive a 256-bit AES key using raw SHA-256.
   LEGACY — used only for decrypting existing v1 secrets. Do not use for new data."
  ^SecretKey [^String master-key-str]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        hash (.digest digest (.getBytes master-key-str "UTF-8"))]
    (SecretKeySpec. hash "AES")))

(defn- derive-key-hkdf
  "Derive a 256-bit AES key using HKDF (RFC 5869) with HMAC-SHA256.
   Extract step: PRK = HMAC-SHA256(salt, IKM)
   Expand step:  OKM = HMAC-SHA256(PRK, info || 0x01)
   Produces exactly 32 bytes — one HMAC-SHA256 block, sufficient for AES-256."
  ^SecretKey [^String master-key-str]
  (let [ikm (.getBytes master-key-str "UTF-8")
        salt (.getBytes "chengis-key-derivation-v2" "UTF-8")
        info (.getBytes "chengis-aes-256-gcm-key" "UTF-8")
        ;; Extract
        mac (Mac/getInstance "HmacSHA256")
        _ (.init mac (SecretKeySpec. salt "HmacSHA256"))
        prk (.doFinal mac ikm)
        ;; Expand — one block (32 bytes) is all we need for AES-256
        _ (.init mac (SecretKeySpec. prk "HmacSHA256"))
        _ (.update mac info)
        okm (.doFinal mac (byte-array [0x01]))]
    (SecretKeySpec. okm "AES")))

(defn- get-master-keys
  "Derive both v1 (SHA-256) and v2 (HKDF) keys from the configured master key string.
   Returns {:v1 SecretKey :v2 SecretKey}.
   Logs a warning if using the insecure default key."
  [config]
  (let [explicit-key (or (get-in config [:secrets :master-key])
                         (System/getenv "CHENGIS_SECRET_KEY"))
        key-str (or explicit-key "chengis-dev-secret-key-change-me")]
    (when-not explicit-key
      (log/warn "SECURITY WARNING: Using default secret master key. Set CHENGIS_SECRET_KEY or :secrets :master-key for production!"))
    {:v1 (derive-key-sha256 key-str)
     :v2 (derive-key-hkdf key-str)}))

;; ---------------------------------------------------------------------------
;; Encrypt / decrypt
;; ---------------------------------------------------------------------------

(defn- encrypt-gcm
  "Encrypt plaintext using AES-256-GCM with key. Returns Base64-encoded IV+ciphertext."
  ^String [^SecretKey key ^String plaintext]
  (let [iv (byte-array iv-bytes)
        _ (.nextBytes (SecureRandom.) iv)
        cipher (Cipher/getInstance "AES/GCM/NoPadding")
        spec (GCMParameterSpec. gcm-tag-bits iv)]
    (.init cipher Cipher/ENCRYPT_MODE key spec)
    (let [ciphertext (.doFinal cipher (.getBytes plaintext "UTF-8"))
          combined (byte-array (+ iv-bytes (alength ciphertext)))]
      (System/arraycopy iv 0 combined 0 iv-bytes)
      (System/arraycopy ciphertext 0 combined iv-bytes (alength ciphertext))
      (.encodeToString (Base64/getEncoder) combined))))

(defn- decrypt-gcm
  "Decrypt a Base64-encoded AES-256-GCM payload. Returns plaintext."
  ^String [^SecretKey key ^String encoded]
  (let [combined (.decode (Base64/getDecoder) encoded)
        iv (byte-array iv-bytes)
        ciphertext (byte-array (- (alength combined) iv-bytes))]
    (System/arraycopy combined 0 iv 0 iv-bytes)
    (System/arraycopy combined iv-bytes ciphertext 0 (alength ciphertext))
    (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")
          spec (GCMParameterSpec. gcm-tag-bits iv)]
      (.init cipher Cipher/DECRYPT_MODE key spec)
      (String. (.doFinal cipher ciphertext) "UTF-8"))))

(defn- encrypt
  "Encrypt plaintext using AES-256-GCM with HKDF-derived key (v2 scheme).
   Returns a versioned string: \"v2:<base64(IV+ciphertext)>\"."
  ^String [keys ^String plaintext]
  (str v2-prefix (encrypt-gcm (:v2 keys) plaintext)))

(defn- decrypt
  "Decrypt a versioned encrypted string.
   \"v2:<...>\" → HKDF-derived key (current scheme).
   No prefix     → SHA-256-derived key (legacy v1 scheme, backward compat)."
  ^String [keys ^String stored]
  (if (clojure.string/starts-with? stored v2-prefix)
    (decrypt-gcm (:v2 keys) (subs stored (count v2-prefix)))
    (decrypt-gcm (:v1 keys) stored)))

;; ---------------------------------------------------------------------------
;; Audit helper
;; ---------------------------------------------------------------------------

(defn- audit-secret!
  "Log a secret access event (non-blocking, swallows errors)."
  [ds action secret-name scope {:keys [user-id ip-address registry org-id]}]
  (try
    (secret-audit/log-secret-access! ds
                                     {:secret-name secret-name :scope (or scope "global")
                                      :action action :user-id user-id :ip-address ip-address
                                      :org-id org-id})
    (metrics/record-secret-access! registry action)
    (catch Exception _)))

;; ---------------------------------------------------------------------------
;; CRUD operations
;; ---------------------------------------------------------------------------

(defn set-secret!
  "Create or update a secret. Scope is 'global' or a job-id.
   Optional :user-id, :ip-address, :registry, :org-id for audit logging and org scoping.
   Uses a transaction to prevent race conditions on concurrent upserts."
  [ds config secret-name value & {:keys [scope user-id ip-address registry org-id] :or {scope "global"}}]
  (let [keys (get-master-keys config)
        encrypted (encrypt keys value)
        where-conds (cond-> [:and [:= :scope scope] [:= :name secret-name]]
                      org-id (conj [:= :org-id org-id]))]
    (jdbc/with-transaction [tx ds]
      (let [existing (jdbc/execute-one! tx
                                        (sql/format {:select [:id]
                                                     :from :secrets
                                                     :where where-conds})
                                        {:builder-fn rs/as-unqualified-kebab-maps})]
        (if existing
          ;; Update
          (jdbc/execute-one! tx
                             (sql/format {:update :secrets
                                          :set {:encrypted-value encrypted
                                                :updated-at [:raw "CURRENT_TIMESTAMP"]}
                                          :where where-conds}))
          ;; Insert
          (jdbc/execute-one! tx
                             (sql/format {:insert-into :secrets
                                          :values [(cond-> {:id (util/generate-id)
                                                            :scope scope
                                                            :name secret-name
                                                            :encrypted-value encrypted}
                                                     org-id (assoc :org-id org-id))]})))))
    (audit-secret! ds :write secret-name scope
                   {:user-id user-id :ip-address ip-address :registry registry :org-id org-id})))

(defn get-secret
  "Get a decrypted secret value. Returns nil if not found.
   Optional :user-id, :ip-address, :registry, :org-id for audit logging and org scoping."
  [ds config secret-name & {:keys [scope user-id ip-address registry org-id] :or {scope "global"}}]
  (let [where-conds (cond-> [:and [:= :scope scope] [:= :name secret-name]]
                      org-id (conj [:= :org-id org-id]))
        row (jdbc/execute-one! ds
                               (sql/format {:select [:encrypted-value]
                                            :from :secrets
                                            :where where-conds})
                               {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (audit-secret! ds :read secret-name scope
                     {:user-id user-id :ip-address ip-address :registry registry :org-id org-id})
      (let [keys (get-master-keys config)]
        (decrypt keys (:encrypted-value row))))))

(defn list-secret-names
  "List secret names (never values) for a scope. Returns a vector of name strings.
   When org-id is provided, scopes to that organization."
  [ds & {:keys [scope org-id] :or {scope "global"}}]
  (let [where-conds (cond-> [:and [:= :scope scope]]
                      org-id (conj [:= :org-id org-id]))]
    (mapv :name
          (jdbc/execute! ds
                         (sql/format {:select [:name]
                                      :from :secrets
                                      :where where-conds
                                      :order-by [[:name :asc]]})
                         {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn delete-secret!
  "Delete a secret. Returns true if deleted, false if not found.
   Optional :user-id, :ip-address, :registry, :org-id for audit logging and org scoping."
  [ds secret-name & {:keys [scope user-id ip-address registry org-id] :or {scope "global"}}]
  (let [where-conds (cond-> [:and [:= :scope scope] [:= :name secret-name]]
                      org-id (conj [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                                  (sql/format {:delete-from :secrets
                                               :where where-conds}))]
    (when (pos? (:next.jdbc/update-count result 0))
      (audit-secret! ds :delete secret-name scope
                     {:user-id user-id :ip-address ip-address :registry registry :org-id org-id}))
    (pos? (:next.jdbc/update-count result 0))))

(defn get-secrets-for-build
  "Get all secrets as a map of {name value} for a build.
   Dispatches to the registered SecretBackend plugin if one is available
   and the backend is not \"local\". Otherwise falls back to the local
   AES-256-GCM encrypted store.
   Merges global secrets with job-scoped secrets (job scope overrides global).
   Logs each secret access as :build-read.
   When org-id is provided, scopes secrets to that organization."
  [ds config job-id & {:keys [org-id]}]
  ;; Try plugin-based backend first (when configured as non-local)
  (let [backend-type (get-in config [:secrets :backend] "local")]
    (if (= "local" backend-type)
      ;; Local encrypted DB store (original behavior)
      (let [keys (get-master-keys config)
            scope-conds [:or [:= :scope "global"] [:= :scope job-id]]
            where-conds (if org-id
                          [:and scope-conds [:= :org-id org-id]]
                          scope-conds)
            rows (jdbc/execute! ds
                                (sql/format {:select [:scope :name :encrypted-value]
                                             :from :secrets
                                             :where where-conds
                                             :order-by [[:scope :asc]]}) ;; global first, then job overrides
                                {:builder-fn rs/as-unqualified-kebab-maps})]
        (doseq [row rows]
          (audit-secret! ds :build-read (:name row) (:scope row) {:org-id org-id}))
        (reduce (fn [m row]
                  (assoc m (:name row) (decrypt keys (:encrypted-value row))))
                {}
                rows))
      ;; External backend via plugin protocol
      (let [fallback? (get-in config [:secrets :fallback-to-local] false)
            fallback-fn (fn [reason]
                          (if fallback?
                            (do
                              (log/warn "Secret backend" backend-type reason "— falling back to local")
                              (get-secrets-for-build ds (assoc-in config [:secrets :backend] "local") job-id :org-id org-id))
                            (throw (ex-info (str "Secret backend " backend-type " " reason " (fallback disabled)")
                                            {:backend backend-type}))))]
        (try
          (let [get-backend (requiring-resolve 'chengis.plugin.registry/get-secret-backend)
                backend (get-backend backend-type)]
            (if backend
              (let [proto-fn (requiring-resolve 'chengis.plugin.protocol/fetch-secrets-for-build)
                    ;; Thread org-id into config so the backend can scope secrets per org
                    scoped-config (cond-> config org-id (assoc :org-id org-id))]
                (proto-fn backend job-id scoped-config))
              ;; Backend not registered
              (fallback-fn "not registered")))
          (catch clojure.lang.ExceptionInfo e
            ;; Re-throw our own exceptions (from fallback-fn)
            (throw e))
          (catch Exception e
            (log/error "Secret backend error:" (.getMessage e))
            (fallback-fn (str "error: " (.getMessage e)))))))))
