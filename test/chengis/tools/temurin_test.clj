(ns chengis.tools.temurin-test
  "Acceptance tests for the Eclipse Temurin JDK installer (CC2-EX3b).

   Hermetic — network calls stubbed via `with-redefs` on
   `chengis.tools.http/{fetch-json,download-to-file}` and
   `chengis.tools.platform/{os,arch}`. The 'install' path produces
   a fake JDK tarball locally and walks the same archive-extract +
   checksum-verify pipeline as a real download would. This is the
   load-bearing test that the protocol shape is correct end-to-end
   for the headline contract:

      (tools/resolve! \"jdk_17_latest\") → {:result :ok :path P}
      (io/file P \"bin\" \"java\") exists.

   A separate ^:integration test (TBD) hits api.adoptium.net for real
   when env CHENGIS_TOOLS_INTEGRATION is set."
  (:require [babashka.process :as p]
            [chengis.tools :as tools]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [chengis.tools.temurin :as temurin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Cache root override + registry cleanup
;; ---------------------------------------------------------------------------

(def ^:dynamic *cache-root* nil)

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (let [root (-> (Files/createTempDirectory "temurin-test-cache"
                                              (into-array FileAttribute []))
                   .toFile
                   .getCanonicalPath)]
      (binding [*cache-root* root]
        (with-redefs [tools/default-cache-root (fn [] root)]
          (t))))
    (tools/clear-registry!)))

;; ---------------------------------------------------------------------------
;; Fixture: build a fake JDK tarball that the stub HTTP layer "downloads"
;; ---------------------------------------------------------------------------

(defn- mk-fake-jdk-tarball!
  "Create a tar.gz with the shape Adoptium ships:
       jdk-FEATURE.X.Y+Z/bin/java   (executable stub)
       jdk-FEATURE.X.Y+Z/release    (informational)
   Returns {:path STRING :sha256 STRING}."
  [feature]
  (let [src (-> (Files/createTempDirectory "fake-jdk-src"
                                           (into-array FileAttribute []))
                .toFile)
        root-name (str "jdk-" feature ".0.1+1")
        bin-java (io/file src root-name "bin" "java")]
    (.mkdirs (.getParentFile bin-java))
    (spit bin-java "#!/bin/sh\necho fake-java $@\n")
    (.setExecutable bin-java true false)
    (spit (io/file src root-name "release") (str "JAVA_VERSION=" feature ".0.1\n"))
    (let [out-dir (-> (Files/createTempDirectory "fake-jdk-out"
                                                 (into-array FileAttribute []))
                      .toFile)
          tarball (io/file out-dir (str "OpenJDK" feature
                                        "-jdk_x64_linux_hotspot.tar.gz"))]
      @(p/process ["tar" "-czf" (.getCanonicalPath tarball)
                   "-C" (.getCanonicalPath src) root-name]
                  {:out :string :err :string})
      {:path (.getCanonicalPath tarball)
       :sha256 (checksum/sha256 tarball)})))

;; ---------------------------------------------------------------------------
;; Stubs
;; ---------------------------------------------------------------------------

(defn- stub-adoptium-payload
  "Build the JSON-shaped payload Adoptium returns for /v3/assets/feature_releases/{N}/ga."
  [{:keys [feature link checksum]}]
  [{"version_data" {"semver" (str feature ".0.1+1")}
    "binaries"
    [{"package"
      {"link" link
       "checksum" checksum
       "name" (str "OpenJDK" feature "-jdk_x64_linux_hotspot.tar.gz")}}]}])

(defn- with-stubbed-network
  "Pin platform to linux/x64, redirect fetch-json to a canned payload,
   redirect download-to-file to a local file copy from the prepared
   tarball."
  [{:keys [feature]} body-fn]
  (let [{:keys [path sha256]} (mk-fake-jdk-tarball! feature)
        link (str "https://stub.example.com/" (.getName (io/file path)))]
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-json (fn [_url & _]
                                    (stub-adoptium-payload
                                     {:feature feature
                                      :link link
                                      :checksum sha256}))
                  http/download-to-file (fn [_url dest-file & _]
                                          (io/copy (io/file path)
                                                   (io/file dest-file))
                                          (.getCanonicalPath (io/file dest-file)))]
      (body-fn))))

;; ---------------------------------------------------------------------------
;; supports? + cache-key
;; ---------------------------------------------------------------------------

(deftest supports-jdk-descriptors
  (let [t (temurin/temurin-installer)]
    (is (tools/supports? t {:kind :jdk :version "17"}))
    (is (tools/supports? t {:kind :jdk :version "21.0.1+12"}))
    (is (not (tools/supports? t {:kind :maven :version "3.9"}))
        "non-:jdk kinds are not handled by Temurin")
    (is (not (tools/supports? t {:kind :jdk :version ""}))
        "blank version → unsupported (would parse to nil major)")))

(deftest cache-key-encodes-os-arch
  (let [t (temurin/temurin-installer {:os :linux :arch :x64})
        k (tools/cache-key t {:kind :jdk :version "17"})]
    (is (= "temurin/jdk-17-linux-x64" k)
        "cache key encodes version + os + arch so cross-arch runs don't collide")))

;; ---------------------------------------------------------------------------
;; install + resolve!
;; ---------------------------------------------------------------------------

(deftest install-fresh-produces-real-jdk
  (with-stubbed-network
    {:feature 17}
    (fn []
      (let [t (temurin/temurin-installer)
            d {:kind :jdk :version "17" :raw "jdk_17_latest"}
            r (tools/install t d)]
        (is (= :ok (:result r)) (str "install failed: " (:explain r)))
        (let [java-bin (io/file (:path r) "bin" "java")]
          (is (.exists java-bin)
              "extracted JDK has bin/java at the cache root")
          (is (.canExecute java-bin)
              "bin/java is executable"))))))

(deftest second-install-hits-cache
  (testing "after a fresh install, locate/install returns the same path
            without re-downloading (cache hit)"
    (with-stubbed-network
      {:feature 17}
      (fn []
        (let [t (temurin/temurin-installer)
              d {:kind :jdk :version "17" :raw "jdk_17_latest"}
              first (tools/install t d)
              calls (atom 0)]
          (is (= :ok (:result first)))
          ;; Re-stub fetch-json so a second call increments the counter
          (with-redefs [http/fetch-json (fn [_url & _]
                                          (swap! calls inc)
                                          (throw (ex-info "shouldn't be called"
                                                          {:n @calls})))]
            (let [located (tools/locate t d)]
              (is (string? located)
                  "locate returns the cache path without touching network")
              (is (zero? @calls)
                  "no API call needed on cache hit"))))))))

(deftest resolve-via-registry-end-to-end
  (testing "register Temurin → resolve! the headline descriptor → :ok"
    (with-stubbed-network
      {:feature 17}
      (fn []
        (tools/register-installer! (temurin/temurin-installer))
        (let [r (tools/resolve! "jdk_17_latest")]
          (is (= :ok (:result r)))
          (is (= :temurin (:installer r)))
          (is (false? (:cached? r))
              "first resolve is :cached? false")
          (is (string? (:path r)))
          (is (.exists (io/file (:path r) "bin" "java"))))))))

(deftest resolve-second-call-marks-cached
  (with-stubbed-network
    {:feature 21}
    (fn []
      (tools/register-installer! (temurin/temurin-installer))
      (let [first (tools/resolve! "jdk_21_latest")
            second (tools/resolve! "jdk_21_latest")]
        (is (= :ok (:result first)))
        (is (= :ok (:result second)))
        (is (true? (:cached? second))
            "second resolve hits cache → :cached? true")
        (is (= (:path first) (:path second)))))))

;; ---------------------------------------------------------------------------
;; Negative paths
;; ---------------------------------------------------------------------------

(deftest checksum-mismatch-surfaces-as-failed
  (testing "if the downloaded file's digest doesn't match the API
            payload's, install! reports :failed with a checksum/mismatch
            explain — NEVER silently accepts the file"
    (let [{:keys [path]} (mk-fake-jdk-tarball! 17)
          link (str "https://stub.example.com/" (.getName (io/file path)))
          ;; Lie about the checksum.
          bad-sha "0000000000000000000000000000000000000000000000000000000000000000"]
      (with-redefs [platform/os   (fn [] :linux)
                    platform/arch (fn [] :x64)
                    http/fetch-json (fn [_url & _]
                                      (stub-adoptium-payload
                                       {:feature 17 :link link
                                        :checksum bad-sha}))
                    http/download-to-file (fn [_url dest-file & _]
                                            (io/copy (io/file path)
                                                     (io/file dest-file))
                                            (.getCanonicalPath
                                             (io/file dest-file)))]
        (let [t (temurin/temurin-installer)
              r (tools/install t {:kind :jdk :version "17" :raw "jdk_17_latest"})]
          (is (= :failed (:result r)))
          (is (re-find #"checksum|mismatch|sha"
                       (str/lower-case (or (:explain r) "")))
              (str "explain should name the failure: " (:explain r))))))))

(deftest install-fails-loud-on-empty-api-payload
  (testing "Adoptium returning no usable release → :failed, not silent"
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-json (fn [_url & _] [])]
      (let [t (temurin/temurin-installer)
            r (tools/install t {:kind :jdk :version "17"
                                :raw "jdk_17_latest"})]
        (is (= :failed (:result r)))))))

(deftest resolve-never-returns-empty-path-for-temurin
  (testing "the framework-level no-blank-path guarantee survives the
            Temurin installer path — buggy install returning {:result :ok
            :path \"\"} must be caught at the resolve! boundary"
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  ;; Make Temurin's API call fail fast so the test
                  ;; doesn't actually pull ~80MB from api.adoptium.net.
                  http/fetch-json (fn [_url & _]
                                    (throw (ex-info "stubbed: network disabled"
                                                    {:test :no-network})))]
      (let [bad-inst (reify tools/Installer
                       (installer-id [_] :bad)
                       (supports? [_ d] (= :jdk (:kind d)))
                       (cache-key [_ _] "bad/jdk-X")
                       (locate [_ _] nil)
                       (install [_ _] {:result :ok :path ""}))]
        (tools/register-installer! bad-inst)
        (tools/register-installer! (temurin/temurin-installer
                                    {:os :linux :arch :x64}))
        ;; The bad installer returns :ok with blank path; framework
        ;; falls through. Temurin then throws on fetch-json → :failed.
        ;; Both installers exhausted → :unresolved.
        (let [r (tools/resolve! "jdk_17_latest")]
          (is (= :unresolved (:result r))
              (str "framework must surface :unresolved, not propagate a blank-path :ok — got: "
                   (pr-str r))))))))
