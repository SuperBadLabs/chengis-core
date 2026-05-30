(ns chengis.util-test
  "Unit tests for shared utility functions. Pure — no DB, runs in the :unit tier."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.util :as util]))

(deftest generate-id-test
  (testing "returns a 36-char UUID string"
    (let [id (util/generate-id)]
      (is (string? id))
      (is (= 36 (count id)))
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id))))
  (testing "successive ids differ"
    (is (not= (util/generate-id) (util/generate-id)))))

(deftest serialize-edn-test
  (testing "nil in -> nil out"
    (is (nil? (util/serialize-edn nil))))
  (testing "data serializes to a readable string"
    (is (= "{:a 1}" (util/serialize-edn {:a 1})))
    (is (= "[1 2 3]" (util/serialize-edn [1 2 3]))))
  (testing "empty collection is non-nil (distinguishes from nil-input branch)"
    (is (= "{}" (util/serialize-edn {})))
    (is (= "[]" (util/serialize-edn [])))))

(deftest deserialize-edn-test
  (testing "nil in -> nil out"
    (is (nil? (util/deserialize-edn nil))))
  (testing "string parses to data"
    (is (= {:a 1} (util/deserialize-edn "{:a 1}")))
    (is (= [1 2 3] (util/deserialize-edn "[1 2 3]"))))
  (testing "round-trips with serialize"
    (let [data {:build-id "abc" :status :running :n 42}]
      (is (= data (util/deserialize-edn (util/serialize-edn data)))))))

(deftest ensure-keyword-test
  (testing "keyword passes through unchanged"
    (is (= :running (util/ensure-keyword :running))))
  (testing "string coerces to keyword"
    (is (= :running (util/ensure-keyword "running")))
    (is (keyword? (util/ensure-keyword "x"))))
  (testing "nil passes through (else branch, not coerced to keyword)"
    (is (nil? (util/ensure-keyword nil))))
  (testing "non-string/keyword passes through unchanged (else branch)"
    (is (= 42 (util/ensure-keyword 42)))))

(deftest format-size-test
  (testing "nil -> em dash"
    (is (= "—" (util/format-size nil))))
  (testing "bytes below 1 KiB"
    (is (= "0 B" (util/format-size 0)))
    (is (= "1023 B" (util/format-size 1023))))
  (testing "KiB boundary: 1024 crosses into KB"
    (is (= "1.0 KB" (util/format-size 1024)))
    (is (= "1.5 KB" (util/format-size 1536))))
  (testing "MiB boundary"
    (is (= "1023.0 KB" (util/format-size (- (* 1024 1024) 1024))))
    (is (= "1.0 MB" (util/format-size (* 1024 1024)))))
  (testing "GiB boundary"
    (is (= "1.0 MB" (util/format-size (* 1024 1024))))
    (is (= "1.00 GB" (util/format-size (* 1024 1024 1024))))
    (is (= "2.50 GB" (util/format-size (long (* 2.5 1024 1024 1024)))))))

(deftest resolve-token-test
  (let [env (fn [k] (when (= k "MY_TOKEN") "from-env"))]
    (testing ":token key absent -> env fallback"
      (is (= "from-env" (util/resolve-token {} "MY_TOKEN" env)))
      (is (= "from-env" (util/resolve-token {:other 1} "MY_TOKEN" env))))
    (testing ":token present and nil -> env fallback"
      (is (= "from-env" (util/resolve-token {:token nil} "MY_TOKEN" env))))
    (testing ":token present and non-nil -> use config value (even blank)"
      (is (= "cfg-val" (util/resolve-token {:token "cfg-val"} "MY_TOKEN" env)))
      (is (= "" (util/resolve-token {:token ""} "MY_TOKEN" env))))
    (testing "nil config behaves like absent key -> env fallback"
      (is (= "from-env" (util/resolve-token nil "MY_TOKEN" env))))
    (testing "absent env var resolves to nil"
      (is (nil? (util/resolve-token {} "MISSING" env))))))

(deftest sha256-hex-test
  (testing "nil in -> nil out"
    (is (nil? (util/sha256-hex nil))))
  (testing "known digests (lowercase hex, zero-padded)"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (util/sha256-hex "")))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (util/sha256-hex "abc"))))
  (testing "always 64 hex chars and deterministic"
    (let [h (util/sha256-hex "some-api-token")]
      (is (= 64 (count h)))
      (is (re-matches #"[0-9a-f]{64}" h))
      (is (= h (util/sha256-hex "some-api-token")))))
  (testing "different inputs differ"
    (is (not= (util/sha256-hex "token-a") (util/sha256-hex "token-b")))))
