(ns chengis.plugin.signing-test
  "Unit tests for Ed25519 plugin-signature verification (M2a)."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.signing :as signing]
            [clojure.java.io :as io])
  (:import [java.security KeyPairGenerator Signature]
           [java.util Base64]))

(defn- gen-keypair [] (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(defn- pub->b64 [kp] (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic kp))))
(defn- sign-bytes ^bytes [kp ^bytes data]
  (let [s (Signature/getInstance "Ed25519")]
    (.initSign s (.getPrivate kp))
    (.update s data)
    (.sign s)))

(defn- with-temp-dir [f]
  (let [dir (io/file (str "/tmp/chengis-signing-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try (f dir)
         (finally (doseq [^java.io.File c (.listFiles dir)] (.delete c)) (.delete dir)))))

(deftest verify-roundtrip
  (testing "a valid signature verifies; a tampered payload does not"
    (let [kp   (gen-keypair)
          data (.getBytes "plugin source" "UTF-8")
          sig  (sign-bytes kp data)
          pk   (signing/load-public-key (pub->b64 kp))]
      (is (signing/verify data sig pk))
      (is (not (signing/verify (.getBytes "tampered" "UTF-8") sig pk))))))

(deftest verify-rejects-wrong-key
  (testing "a signature does not verify against a different key"
    (let [kp1  (gen-keypair)
          kp2  (gen-keypair)
          data (.getBytes "x" "UTF-8")
          sig  (sign-bytes kp1 data)]
      (is (not (signing/verify data sig (signing/load-public-key (pub->b64 kp2))))))))

(deftest load-public-key-bad-input-is-nil
  (testing "garbage key material yields nil, not an exception"
    (is (nil? (signing/load-public-key "not-a-key")))))

(defn- sign-file!
  "Sign the combined (clj + sibling manifest) payload, writing <clj>.sig."
  [kp ^java.io.File clj-file]
  (let [edn-file  (io/file (clojure.string/replace (.getAbsolutePath clj-file) #"\.clj$" ".edn"))
        clj-bytes (.getBytes (slurp clj-file) "UTF-8")
        edn-bytes (when (.isFile edn-file) (.getBytes (slurp edn-file) "UTF-8"))
        payload   (signing/signed-payload clj-bytes edn-bytes)]
    (spit (io/file (str (.getAbsolutePath clj-file) ".sig"))
          (.encodeToString (Base64/getEncoder) (sign-bytes kp payload)))))

(deftest verified-file-happy-and-failure-modes
  (with-temp-dir
    (fn [dir]
      (let [kp   (gen-keypair)
            keys [(pub->b64 kp)]
            clj  (io/file dir "p.clj")]
        (spit clj "(+ 1 2)")
        (sign-file! kp clj)
        (testing "valid sibling .sig verifies against a configured key"
          (is (signing/verified-file? clj keys)))
        (testing "no configured keys => not verified"
          (is (not (signing/verified-file? clj []))))
        (testing "tampering the source invalidates the signature"
          (spit clj "(+ 1 3)")
          (is (not (signing/verified-file? clj keys)))
          (spit clj "(+ 1 2)"))
        (testing "missing .sig => not verified"
          (.delete (io/file dir "p.clj.sig"))
          (is (not (signing/verified-file? clj keys))))))))

(deftest signature-covers-the-manifest
  (testing "tampering the sidecar manifest invalidates the signature (P2 fix)"
    (with-temp-dir
      (fn [dir]
        (let [kp   (gen-keypair)
              keys [(pub->b64 kp)]
              clj  (io/file dir "m.clj")
              edn  (io/file dir "m.edn")]
          (spit clj "(+ 1 2)")
          (spit edn (pr-str {:capabilities [:log]}))
          (sign-file! kp clj)                       ; signs clj + manifest
          (is (signing/verified-file? clj keys)
              "clj + manifest both intact => verifies")
          ;; tamper ONLY the manifest (e.g. try to add a capability)
          (spit edn (pr-str {:capabilities [:log :secrets]}))
          (is (not (signing/verified-file? clj keys))
              "manifest tamper invalidates the signature even though .clj is unchanged"))))))
