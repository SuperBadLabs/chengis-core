(ns chengis.tools.gradle-test
  "Acceptance tests for the Gradle installer (CC2-EX3b.2).

   Network stubbed via with-redefs. Gradle distributes as zip, so the
   fixture builds a zip rather than tar.gz."
  (:require [babashka.process :as p]
            [chengis.tools :as tools]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.gradle :as gradle]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (let [root (-> (Files/createTempDirectory "gradle-test-cache"
                                              (into-array FileAttribute []))
                   .toFile .getCanonicalPath)]
      (with-redefs [tools/default-cache-root (fn [] root)]
        (t)))
    (tools/clear-registry!)))

(defn- mk-fake-gradle-zip!
  "Build a zip with the shape gradle.org ships: `gradle-X.Y/bin/gradle`."
  [version]
  (let [src (-> (Files/createTempDirectory "fake-gradle-src"
                                           (into-array FileAttribute []))
                .toFile)
        root-name (str "gradle-" version)
        bin-gradle (io/file src root-name "bin" "gradle")]
    (.mkdirs (.getParentFile bin-gradle))
    (spit bin-gradle "#!/bin/sh\necho fake-gradle $@\n")
    (.setExecutable bin-gradle true false)
    (let [out-dir (-> (Files/createTempDirectory "fake-gradle-out"
                                                 (into-array FileAttribute []))
                      .toFile)
          zipfile (io/file out-dir (str "gradle-" version "-bin.zip"))]
      @(p/process ["zip" "-rq" (.getCanonicalPath zipfile) root-name]
                  {:dir (.getCanonicalPath src) :out :string :err :string})
      {:path (.getCanonicalPath zipfile)
       :sha256 (checksum/sha256 zipfile)})))

(defn- with-stubbed-network
  [{:keys [version current-version]} body-fn]
  (let [{:keys [path sha256]} (mk-fake-gradle-zip! version)
        sha-bytes (.getBytes (str sha256 "\n") "UTF-8")]
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-bytes (fn [url & _]
                                     (if (str/ends-with? url ".sha256")
                                       sha-bytes
                                       (throw (ex-info (str "unexpected fetch-bytes " url) {}))))
                  http/fetch-json (fn [url & _]
                                    (if (str/includes? url "/versions/current")
                                      {"version" (or current-version version)}
                                      (throw (ex-info (str "unexpected fetch-json " url) {}))))
                  http/download-to-file (fn [_url dest-file & _]
                                          (io/copy (io/file path)
                                                   (io/file dest-file))
                                          (.getCanonicalPath (io/file dest-file)))]
      (body-fn))))

;; ---------------------------------------------------------------------------

(deftest supports-explicit-and-latest
  (let [g (gradle/gradle-installer)]
    (is (tools/supports? g {:kind :gradle :version "8.5"}))
    (is (tools/supports? g {:kind :gradle :version "8.5.1"}))
    (is (tools/supports? g {:kind :gradle :version "latest"}))
    (is (tools/supports? g {:kind :gradle :version "8" :latest? true}))
    (is (not (tools/supports? g {:kind :maven :version "3.9.6"})))))

(deftest install-explicit-version
  (with-stubbed-network
    {:version "8.5"}
    (fn []
      (let [g (gradle/gradle-installer)
            d {:kind :gradle :version "8.5" :raw "gradle_8_5"}
            r (tools/install g d)]
        (is (= :ok (:result r)) (str "install failed: " (:explain r)))
        (let [bin (io/file (:path r) "bin" "gradle")]
          (is (.exists bin))
          (is (.canExecute bin)))))))

(deftest install-latest-resolves-and-installs
  (testing ":latest descriptor hits /versions/current then installs the
            concrete version it points to"
    (with-stubbed-network
      {:version "8.5" :current-version "8.5"}
      (fn []
        (let [g (gradle/gradle-installer)
              d {:kind :gradle :version "latest" :raw "gradle_latest"}
              r (tools/install g d)]
          (is (= :ok (:result r)) (str "install failed: " (:explain r))))))))

(deftest resolve-via-registry-end-to-end
  (with-stubbed-network
    {:version "8.5"}
    (fn []
      (tools/register-installer! (gradle/gradle-installer))
      (let [r (tools/resolve! "gradle_8_5")]
        (is (= :ok (:result r)))
        (is (= :gradle (:installer r)))
        (is (.exists (io/file (:path r) "bin" "gradle")))))))

(deftest checksum-mismatch-surfaces-as-failed
  (let [{:keys [path]} (mk-fake-gradle-zip! "8.5")
        bad (apply str (repeat 64 \0))
        sha-bytes (.getBytes (str bad "\n") "UTF-8")]
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-bytes (fn [_url & _] sha-bytes)
                  http/download-to-file (fn [_url dest-file & _]
                                          (io/copy (io/file path)
                                                   (io/file dest-file))
                                          (.getCanonicalPath (io/file dest-file)))]
      (let [r (tools/install (gradle/gradle-installer)
                             {:kind :gradle :version "8.5" :raw "gradle_8_5"})]
        (is (= :failed (:result r)))
        (is (re-find #"checksum|mismatch|sha"
                     (str/lower-case (or (:explain r) ""))))))))
