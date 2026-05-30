(ns chengis.plugin.manifest-test
  "Tests for the plugin capability-manifest format and validation (M1c)."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.manifest :as manifest]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; validate
;; ---------------------------------------------------------------------------

(deftest validate-keeps-known-drops-unknown-capabilities
  (testing "known capabilities pass; unknown ones are dropped with a warning"
    (let [m (manifest/validate {:capabilities [:http :log :filesystem :wormhole]}
                               "p")]
      (is (= #{:http :log} (:capabilities m)))
      (is (some #(re-find #"unknown capabilities" %) (:warnings m))))))

(deftest validate-empty-manifest-is-most-restrictive
  (testing "missing/empty manifest grants nothing and warns nothing"
    (let [m (manifest/validate {} "p")]
      (is (= #{} (:capabilities m)))
      (is (nil? (:requests-trust m)))
      (is (empty? (:warnings m))))))

(deftest validate-requests-trust
  (testing ":requests-trust accepts the two valid keywords"
    (is (= :sandboxed (:requests-trust (manifest/validate {:requests-trust :sandboxed} "p"))))
    (is (= :trusted (:requests-trust (manifest/validate {:requests-trust :trusted} "p")))))
  (testing "an invalid :requests-trust is ignored with a warning"
    (let [m (manifest/validate {:requests-trust :god-mode} "p")]
      (is (nil? (:requests-trust m)))
      (is (some #(re-find #"requests-trust" %) (:warnings m))))))

(deftest validate-name-mismatch-warns
  (testing "a :name that disagrees with the file name is flagged (possible spoof)"
    (let [m (manifest/validate {:name "evil-twin"} "honest-plugin")]
      (is (some #(re-find #"does not match" %) (:warnings m)))))
  (testing "a matching :name is fine"
    (is (empty? (:warnings (manifest/validate {:name "p"} "p"))))))

(deftest validate-passthrough-metadata
  (testing "descriptive fields pass through"
    (let [m (manifest/validate {:name "p" :version "2.1.0" :description "d"
                                :provides [:notifier :step]} "p")]
      (is (= "2.1.0" (:version m)))
      (is (= "d" (:description m)))
      (is (= [:notifier :step] (:provides m))))))

;; ---------------------------------------------------------------------------
;; effective-trust — least privilege; manifest can only LOWER
;; ---------------------------------------------------------------------------

(deftest effective-trust-matrix
  (testing "policy is the ceiling; manifest can only restrict"
    ;; policy trusted, no request -> trusted
    (is (= :trusted   (manifest/effective-trust :trusted nil)))
    ;; policy trusted, plugin self-restricts -> sandboxed
    (is (= :sandboxed (manifest/effective-trust :trusted :sandboxed)))
    ;; policy trusted, plugin redundantly requests trusted -> trusted
    (is (= :trusted   (manifest/effective-trust :trusted :trusted)))
    ;; policy sandboxed, plugin tries to request trusted -> STILL sandboxed
    (is (= :sandboxed (manifest/effective-trust :sandboxed :trusted)))
    ;; policy sandboxed, no request -> sandboxed
    (is (= :sandboxed (manifest/effective-trust :sandboxed nil)))))

;; ---------------------------------------------------------------------------
;; read-manifest — sidecar file IO, never throws
;; ---------------------------------------------------------------------------

(defn- with-temp-dir [f]
  (let [dir (io/file (str "/tmp/chengis-manifest-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (try (f dir)
         (finally
           (doseq [^java.io.File c (.listFiles dir)] (.delete c))
           (.delete dir)))))

(deftest read-manifest-absent-returns-empty
  (with-temp-dir
    (fn [dir]
      (let [clj (io/file dir "noman.clj")]
        (spit clj "(require '[chengis.plugin.host :as h])")
        (testing "no sidecar => empty, most-restrictive manifest"
          (let [m (manifest/read-manifest clj)]
            (is (= #{} (:capabilities m)))
            (is (empty? (:warnings m)))))))))

(deftest read-manifest-valid-sidecar
  (with-temp-dir
    (fn [dir]
      (let [clj (io/file dir "good.clj")]
        (spit clj "(require '[chengis.plugin.host :as h])")
        (spit (io/file dir "good.edn")
              (pr-str {:name "good" :version "1.0.0" :capabilities [:http :log]
                       :requests-trust :sandboxed}))
        (testing "valid sidecar parses and validates"
          (let [m (manifest/read-manifest clj)]
            (is (= #{:http :log} (:capabilities m)))
            (is (= :sandboxed (:requests-trust m)))
            (is (empty? (:warnings m)))))))))

(deftest read-manifest-bad-edn-is-downgraded-not-thrown
  (with-temp-dir
    (fn [dir]
      (let [clj (io/file dir "bad.clj")]
        (spit clj "(require '[chengis.plugin.host :as h])")
        (spit (io/file dir "bad.edn") "{:capabilities [:http")  ; truncated EDN
        (testing "unreadable EDN => empty manifest + warning, no throw"
          (let [m (manifest/read-manifest clj)]
            (is (= #{} (:capabilities m)))
            (is (seq (:warnings m)))))))))
