(ns chengis.plugin.keytool-test
  "Operator-side keygen + signing round-trips against the server verifier (M3a)."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.keytool :as keytool]
            [chengis.plugin.signing :as signing]))

(deftest keygen-produces-server-loadable-public-key
  (testing "the public key keytool emits parses with signing/load-public-key"
    (let [kp  (keytool/generate-keypair)
          pub (keytool/public-key-b64 kp)]
      (is (some? (signing/load-public-key pub)))
      (is (some? (signing/key-id pub))))))

(deftest sign-then-verify-roundtrip
  (testing "keytool signs source+manifest; the server verifier accepts it"
    (let [kp        (keytool/generate-keypair)
          pub       (keytool/public-key-b64 kp)
          priv      (keytool/load-private-key (keytool/private-key-b64 kp))
          clj-bytes (.getBytes "(register-notifier ...)" "UTF-8")
          edn-bytes (.getBytes (pr-str {:capabilities [:log]}) "UTF-8")
          sig-b64   (keytool/sign-plugin clj-bytes edn-bytes priv)
          sig       (.decode (java.util.Base64/getDecoder) sig-b64)]
      (is (signing/verify-payload? clj-bytes edn-bytes sig [pub])
          "sig over source+manifest verifies against the matching public key")
      (is (= (signing/key-id pub)
             (signing/verifying-key-id clj-bytes edn-bytes sig [pub])))
      (testing "manifest tamper invalidates the keytool signature too"
        (is (not (signing/verify-payload?
                  clj-bytes (.getBytes (pr-str {:capabilities [:log :secrets]}) "UTF-8")
                  sig [pub]))))
      (testing "a different key does not verify"
        (let [other (keytool/public-key-b64 (keytool/generate-keypair))]
          (is (not (signing/verify-payload? clj-bytes edn-bytes sig [other]))))))))

(deftest signs-source-only-when-no-manifest
  (testing "nil manifest bytes round-trip (plugin with no .edn)"
    (let [kp        (keytool/generate-keypair)
          pub       (keytool/public-key-b64 kp)
          priv      (keytool/load-private-key (keytool/private-key-b64 kp))
          clj-bytes (.getBytes "(+ 1 2)" "UTF-8")
          sig       (.decode (java.util.Base64/getDecoder)
                             (keytool/sign-plugin clj-bytes nil priv))]
      (is (signing/verify-payload? clj-bytes nil sig [pub])))))
