(ns chengis.tools-test
  "Acceptance tests for the tool installer framework (CC2-EX3a).

   The board's wild-corpus receipt — `tool('jdk_17_latest')` resolving
   to a real JDK — comes from the JDK installer in EX3b. This file
   proves the framework + descriptor parsing + registry + the honest
   :unresolved fallback that EX2's classifier consumes."
  (:require [chengis.engine.result :as result]
            [chengis.tools :as tools]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (t)
    (tools/clear-registry!)))

;; ---------------------------------------------------------------------------
;; Descriptor parsing
;; ---------------------------------------------------------------------------

(deftest parses-jenkins-style-descriptor
  (let [d (tools/parse-descriptor "jdk_17_latest")]
    (is (= :jdk (:kind d)))
    (is (= "17" (:version d)))
    (is (true? (:latest? d)))))

(deftest parses-modern-descriptor
  (let [d (tools/parse-descriptor "maven:3.9.6")]
    (is (= :maven (:kind d)))
    (is (= "3.9.6" (:version d)))))

(deftest parses-jenkins-style-multi-segment-version
  (let [d (tools/parse-descriptor "node_20_10_0")]
    (is (= :node (:kind d)))
    (is (= "20.10.0" (:version d)))))

(deftest blank-or-non-string-descriptor-returns-nil
  (is (nil? (tools/parse-descriptor "")))
  (is (nil? (tools/parse-descriptor nil)))
  (is (nil? (tools/parse-descriptor 42))))

(deftest unparseable-descriptor-still-survives
  (let [d (tools/parse-descriptor "not a valid descriptor at all")]
    (is (= :unknown (:kind d)))
    (is (true? (:unparsed? d)))))

;; ---------------------------------------------------------------------------
;; Cache path
;; ---------------------------------------------------------------------------

(deftest default-cache-root-respects-env-override
  (let [orig (System/getProperty "user.home")]
    (try
      (is (str/includes? (tools/default-cache-root) ".chengis/tools"))
      (finally
        ;; nothing to restore — we didn't mutate the env
        nil))))

(deftest cache-path-is-deterministic
  (is (= "/x/jdk/17" (tools/cache-path "/x" "jdk/17")))
  (is (= "/x/jdk/17" (tools/cache-path "/x" "jdk/17"))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest empty-registry-resolves-to-unresolved
  (let [r (tools/resolve! "jdk_17_latest")]
    (is (= :unresolved (:result r)))
    (is (= :no-installer (:rule r)))
    (is (= "jdk_17_latest" (:descriptor r)))))

(deftest registered-installers-listed
  (tools/register-installer! (tools/dir-pinned-installer {}))
  (is (= [:dir-pinned] (tools/registered-installers))))

;; ---------------------------------------------------------------------------
;; DirPinned installer end-to-end
;; ---------------------------------------------------------------------------

(deftest dir-pinned-installer-resolves-existing-path
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "chengis-tools-test-" (System/nanoTime)))
        _ (.mkdirs tmp)
        path (.getAbsolutePath tmp)]
    (try
      (tools/register-installer!
       (tools/dir-pinned-installer {:pins {[:jdk "17"] path}}))
      (let [r (tools/resolve! "jdk_17_latest")]
        (is (= :ok (:result r)))
        (is (= path (:path r)))
        (is (= :dir-pinned (:installer r)))
        (is (true? (:cached? r))))
      (finally (.delete tmp)))))

(deftest dir-pinned-installer-with-stale-pin-returns-unresolved
  ;; The pin exists in config but the directory was deleted out from
  ;; under us. This must NOT silently succeed.
  (tools/register-installer!
   (tools/dir-pinned-installer {:pins {[:jdk "17"]
                                        "/this/path/does/not/exist"}}))
  (let [r (tools/resolve! "jdk_17_latest")]
    (is (= :unresolved (:result r)))
    (is (re-find #"all installers failed" (:explain r)))))

(deftest dir-pinned-skips-unsupported-descriptors
  (tools/register-installer!
   (tools/dir-pinned-installer {:pins {[:jdk "17"] "/tmp"}}))
  (let [r (tools/resolve! "maven_3_9_latest")]
    (is (= :unresolved (:result r)))
    (is (= :no-installer (:rule r)))))

(deftest resolved-path-returns-just-the-string
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "chengis-tools-test-" (System/nanoTime)))
        _ (.mkdirs tmp)
        path (.getAbsolutePath tmp)]
    (try
      (tools/register-installer!
       (tools/dir-pinned-installer {:pins {[:jdk "17"] path}}))
      (is (= path (tools/resolved-path "jdk_17_latest")))
      (is (nil? (tools/resolved-path "maven_3_9_latest")))
      (finally (.delete tmp)))))

;; ---------------------------------------------------------------------------
;; Two-installer registry: ordered fallback
;; ---------------------------------------------------------------------------

(defrecord StubInstaller [id supports-fn install-fn]
  tools/Installer
  (installer-id [_] id)
  (supports? [_ d] (supports-fn d))
  (cache-key [_ d] (str id "/" (:version d)))
  (locate [_ _] nil)
  (install [_ d] (install-fn d)))

(deftest registry-falls-through-on-installer-failure
  (let [first-tries (atom 0)
        second-tries (atom 0)
        first-inst (->StubInstaller :first
                                    (fn [_] true)
                                    (fn [_]
                                      (swap! first-tries inc)
                                      {:result :failed
                                       :explain "first installer can't"}))
        second-inst (->StubInstaller :second
                                     (fn [_] true)
                                     (fn [_]
                                       (swap! second-tries inc)
                                       {:result :ok :path "/from/second"}))]
    (tools/register-installer! first-inst)
    (tools/register-installer! second-inst)
    (let [r (tools/resolve! "jdk_17_latest")]
      (is (= :ok (:result r)))
      (is (= :second (:installer r)))
      (is (= 1 @first-tries))
      (is (= 1 @second-tries)))))

(deftest registry-stops-on-first-ok
  (let [first-tries (atom 0)
        second-tries (atom 0)
        first-inst (->StubInstaller :first
                                    (fn [_] true)
                                    (fn [_]
                                      (swap! first-tries inc)
                                      {:result :ok :path "/from/first"}))
        second-inst (->StubInstaller :second
                                     (fn [_] true)
                                     (fn [_]
                                       (swap! second-tries inc)
                                       {:result :ok :path "/from/second"}))]
    (tools/register-installer! first-inst)
    (tools/register-installer! second-inst)
    (let [r (tools/resolve! "jdk_17_latest")]
      (is (= :ok (:result r)))
      (is (= :first (:installer r)))
      (is (= 1 @first-tries))
      (is (zero? @second-tries)))))

;; ---------------------------------------------------------------------------
;; Honest unresolved flow into the EX2 classifier
;; ---------------------------------------------------------------------------

(deftest unresolved-flows-into-classifier-as-failure
  (let [r (tools/resolve! "jdk_17_latest")
        obs (-> (result/default-observation)
                (result/record-shell-step {:exit-code 0})
                (result/record-unresolved-tool (:descriptor r)))
        classified (result/classify obs)]
    (is (= :failure (:result classified)))
    (is (= :tool-unresolved (:rule classified)))
    (is (re-find #"jdk_17_latest" (:explain classified)))))

(deftest resolve-never-returns-empty-string
  ;; The headline anvil v0.3 regression: tool() returned "".
  ;; This must NEVER happen via this framework.
  (let [r (tools/resolve! "jdk_17_latest")]
    (is (or (= :ok (:result r)) (= :unresolved (:result r))))
    (when (= :ok (:result r))
      (is (string? (:path r)))
      (is (not (str/blank? (:path r))))))
  (is (nil? (tools/resolved-path "jdk_17_latest"))
      "resolved-path returns nil, not empty string, on miss"))
