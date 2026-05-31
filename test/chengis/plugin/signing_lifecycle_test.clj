(ns chengis.plugin.signing-lifecycle-test
  "Key lifecycle: rotation + revocation over the configured signing keys (M3a)."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.signing :as signing])
  (:import [java.security KeyPairGenerator Signature]
           [java.util Base64]))

(defn- gen-keypair [] (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))
(defn- pub->b64 [kp] (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic kp))))
(defn- sign-bytes ^bytes [kp ^bytes data]
  (let [s (Signature/getInstance "Ed25519")]
    (.initSign s (.getPrivate kp))
    (.update s data)
    (.sign s)))

(deftest key-id-is-stable-and-distinct
  (testing "key-id is deterministic per key and differs across keys"
    (let [a (pub->b64 (gen-keypair))
          b (pub->b64 (gen-keypair))]
      (is (= (signing/key-id a) (signing/key-id a)) "stable")
      (is (= 16 (count (signing/key-id a))) "16 hex chars")
      (is (not= (signing/key-id a) (signing/key-id b)) "distinct keys, distinct ids")
      (is (nil? (signing/key-id "not-a-key")) "garbage => nil"))))

(deftest normalize-accepts-strings-and-maps
  (testing "plain string => active; map carries lifecycle; id always re-derived"
    (let [k (pub->b64 (gen-keypair))]
      (is (= :active (:status (signing/normalize-key k))))
      (is (= (signing/key-id k) (:id (signing/normalize-key k))))
      (let [m (signing/normalize-key {:key k :status :revoked :label "old" :id "bogus"})]
        (is (= :revoked (:status m)))
        (is (= "old" (:label m)))
        (is (= (signing/key-id k) (:id m)) "id re-derived, operator label ignored"))
      (testing "string status also honored"
        (is (= :revoked (:status (signing/normalize-key {:key k :status "revoked"})))))
      (testing "non-key entries dropped"
        (is (nil? (signing/normalize-key {:label "no key here"})))
        (is (nil? (signing/normalize-key 42)))))))

(deftest active-public-keys-filters-revoked
  (let [a (pub->b64 (gen-keypair))
        b (pub->b64 (gen-keypair))]
    (testing "bare strings are all active (backward compatible)"
      (is (= [a b] (signing/active-public-keys [a b]))))
    (testing "revoked maps are excluded"
      (is (= [a] (signing/active-public-keys
                  [{:key a :status :active}
                   {:key b :status :revoked :reason "leaked"}]))))
    (testing "mixed string + map config"
      (is (= [a b] (signing/active-public-keys [a {:key b :status :active}]))))))

(deftest verify-honors-revocation
  (let [kp   (gen-keypair)
        kb64 (pub->b64 kp)
        clj  (.getBytes "(+ 1 2)" "UTF-8")
        sig  (sign-bytes kp (signing/signed-payload clj nil))]
    (testing "active key verifies and reports its key-id"
      (is (signing/verify-payload? clj nil sig [kb64]))
      (is (= (signing/key-id kb64)
             (signing/verifying-key-id clj nil sig [kb64]))))
    (testing "revoking the only signing key fails verification"
      (let [cfg [{:key kb64 :status :revoked}]]
        (is (not (signing/verify-payload? clj nil sig cfg)))
        (is (nil? (signing/verifying-key-id clj nil sig cfg)))))))

(deftest rotation-overlap
  (testing "during rotation both keys verify their own signatures; revoke retires the old"
    (let [old   (gen-keypair)
          new   (gen-keypair)
          ob64  (pub->b64 old)
          nb64  (pub->b64 new)
          clj   (.getBytes "(plugin)" "UTF-8")
          old-sig (sign-bytes old (signing/signed-payload clj nil))
          new-sig (sign-bytes new (signing/signed-payload clj nil))
          ;; overlap window: both keys active
          overlap [{:key ob64 :status :active} {:key nb64 :status :active}]]
      (is (signing/verify-payload? clj nil old-sig overlap) "old sig still trusted during overlap")
      (is (signing/verify-payload? clj nil new-sig overlap) "new sig trusted during overlap")
      ;; cutover: old key revoked, plugin must be re-signed with new key
      (let [after [{:key ob64 :status :revoked :reason "rotated"} {:key nb64 :status :active}]]
        (is (not (signing/verify-payload? clj nil old-sig after))
            "an old-key signature stops verifying once the old key is revoked")
        (is (signing/verify-payload? clj nil new-sig after)
            "the new-key signature keeps verifying")))))
