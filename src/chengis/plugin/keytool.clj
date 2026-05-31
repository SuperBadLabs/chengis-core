(ns chengis.plugin.keytool
  "Operator-side Ed25519 key generation and detached signing for plugins — the
   PRIVATE-key counterpart to chengis.plugin.signing (which is verification only).

   The CI server NEVER calls into this namespace. It exists for the
   `chengis sign-plugin` operator CLI and for tests that round-trip a
   sign -> verify. Keeping the private-key code out of chengis.plugin.signing
   preserves that file's invariant: the running server holds PUBLIC keys only,
   so a server compromise can never forge a plugin signature.

   Key distribution / provenance (the operator workflow):
   1. `keygen` — generate a keypair offline. The private key (PKCS#8 base64)
      stays on the signer's machine / secrets manager; the public key (X.509
      SPKI base64) goes into the server config at
      [:plugins :signing :public-keys].
   2. `sign` — sign a plugin's source + manifest, producing <plugin>.clj.sig.
   3. Rotation — add a new public key alongside the old (both verify during the
      overlap), re-sign with the new key, then mark the old key
      `{:status :revoked}` in config. Revocation is a config edit, enforced at
      verification time by chengis.plugin.signing/active-public-keys.

   Pure JDK (java.security Ed25519, JDK 15+); no extra dependency."
  (:require [chengis.plugin.signing :as signing]
            [clojure.string :as str])
  (:import [java.security KeyFactory KeyPair KeyPairGenerator PrivateKey Signature]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64]))

(defn- b64-encode ^String [^bytes b]
  (.encodeToString (Base64/getEncoder) b))

(defn- b64-decode ^bytes [^String s]
  (.decode (Base64/getDecoder) ^String (str/replace s #"\s" "")))

(defn generate-keypair
  "Generate a fresh Ed25519 KeyPair."
  ^KeyPair []
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))

(defn public-key-b64
  "Base64 X.509 SubjectPublicKeyInfo for `kp`'s public key — the exact string
   form chengis.plugin.signing/load-public-key expects in server config."
  ^String [^KeyPair kp]
  (b64-encode (.getEncoded (.getPublic kp))))

(defn private-key-b64
  "Base64 PKCS#8 for `kp`'s private key. This is the operator-held secret; it
   must never appear in server config."
  ^String [^KeyPair kp]
  (b64-encode (.getEncoded (.getPrivate kp))))

(defn load-private-key
  "Parse a base64 PKCS#8 string into an Ed25519 PrivateKey."
  ^PrivateKey [^String b64]
  (.generatePrivate (KeyFactory/getInstance "Ed25519")
                    (PKCS8EncodedKeySpec. (b64-decode b64))))

(defn sign-payload
  "Raw Ed25519 signature bytes over `data` with `private-key`."
  ^bytes [^bytes data ^PrivateKey private-key]
  (let [s (Signature/getInstance "Ed25519")]
    (.initSign s private-key)
    (.update s data)
    (.sign s)))

(defn sign-plugin
  "Sign a plugin's canonical payload (source + manifest, length-prefixed exactly
   as chengis.plugin.signing/signed-payload builds it) with `private-key`.
   Returns the base64 raw signature — the contents to write to <plugin>.clj.sig.
   `edn-bytes` is nil when the plugin ships no manifest."
  ^String [^bytes clj-bytes edn-bytes ^PrivateKey private-key]
  (b64-encode (sign-payload (signing/signed-payload clj-bytes edn-bytes) private-key)))
