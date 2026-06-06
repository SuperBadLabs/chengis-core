(ns chengis.tools.checksum-test
  "Verify the streaming SHA-256 / SHA-512 implementations against
   known test vectors, plus the verify! contract: pass on match,
   throw ex-info on mismatch, refuse to verify a blank expected
   (no-silent-success contract)."
  (:require [chengis.tools.checksum :as ck]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-file-with [content]
  (let [f (-> (Files/createTempFile "ck-test" ".bin"
                                    (into-array FileAttribute []))
              .toFile)]
    (spit f content)
    f))

;; ---------------------------------------------------------------------------
;; Known test vectors
;; ---------------------------------------------------------------------------

(def ^:private empty-sha256
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(def ^:private empty-sha512
  "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e")

(def ^:private abc-sha256
  ;; SHA-256 of "abc" per NIST FIPS 180-4 §A.1
  "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

(deftest sha256-of-empty-string
  (let [f (tmp-file-with "")]
    (is (= empty-sha256 (ck/sha256 f)))))

(deftest sha512-of-empty-string
  (let [f (tmp-file-with "")]
    (is (= empty-sha512 (ck/sha512 f)))))

(deftest sha256-of-abc
  (let [f (tmp-file-with "abc")]
    (is (= abc-sha256 (ck/sha256 f)))))

;; ---------------------------------------------------------------------------
;; verify!
;; ---------------------------------------------------------------------------

(deftest verify-passes-on-match
  (let [f (tmp-file-with "abc")]
    (is (= (.getCanonicalPath f) (ck/verify! f abc-sha256 :sha256)))))

(deftest verify-passes-on-case-insensitive-match
  (testing "Adoptium publishes lowercase; if an installer passes
            an uppercase digest in via a transformation accident,
            verify! should still accept the byte-equivalence"
    (let [f (tmp-file-with "abc")]
      (is (some? (ck/verify! f (.toUpperCase abc-sha256) :sha256))))))

(deftest verify-throws-on-mismatch
  (let [f (tmp-file-with "abc")
        bad "0000000000000000000000000000000000000000000000000000000000000000"]
    (try
      (ck/verify! f bad :sha256)
      (is false "verify! should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :checksum/mismatch (:type (ex-data e))))
        (is (= bad (:expected (ex-data e))))
        (is (= abc-sha256 (:got (ex-data e))))))))

(deftest verify-refuses-blank-expected
  (testing "the no-silent-success contract — verify! must refuse to
            accept any file when the expected digest is blank"
    (let [f (tmp-file-with "abc")]
      (doseq [bad [nil "" "   "]]
        (try
          (ck/verify! f bad :sha256)
          (is false (str "verify! accepted blank expected: " (pr-str bad)))
          (catch clojure.lang.ExceptionInfo e
            (is (= :checksum/missing-expected (:type (ex-data e))))))))))

(deftest verify-throws-on-unknown-algo
  (let [f (tmp-file-with "abc")]
    (try
      (ck/verify! f abc-sha256 :md5)
      (is false "should have rejected :md5")
      (catch clojure.lang.ExceptionInfo e
        (is (= :checksum/unknown-algo (:type (ex-data e))))))))
