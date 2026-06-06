(ns chengis.tools.maven-test
  "Acceptance tests for the Apache Maven installer (CC2-EX3b.2).

   Hermetic — network calls stubbed via with-redefs on
   `chengis.tools.http/{fetch-bytes,download-to-file}`. The install path
   produces a fake Maven tarball locally with the conventional shape
   (`apache-maven-X.Y.Z/bin/mvn`) and walks the same archive-extract +
   checksum-verify pipeline as a real download would."
  (:require [babashka.process :as p]
            [chengis.tools :as tools]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.maven :as maven]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (let [root (-> (Files/createTempDirectory "maven-test-cache"
                                              (into-array FileAttribute []))
                   .toFile .getCanonicalPath)]
      (with-redefs [tools/default-cache-root (fn [] root)]
        (t)))
    (tools/clear-registry!)))

(defn- mk-fake-maven-tarball!
  "Build a tar.gz with the shape Apache ships: `apache-maven-X.Y.Z/bin/mvn`
   as an executable stub. Returns {:path :sha512}."
  [version]
  (let [src (-> (Files/createTempDirectory "fake-mvn-src"
                                           (into-array FileAttribute []))
                .toFile)
        root-name (str "apache-maven-" version)
        bin-mvn (io/file src root-name "bin" "mvn")]
    (.mkdirs (.getParentFile bin-mvn))
    (spit bin-mvn "#!/bin/sh\necho fake-mvn $@\n")
    (.setExecutable bin-mvn true false)
    (let [out-dir (-> (Files/createTempDirectory "fake-mvn-out"
                                                 (into-array FileAttribute []))
                      .toFile)
          tarball (io/file out-dir (str "apache-maven-" version "-bin.tar.gz"))]
      @(p/process ["tar" "-czf" (.getCanonicalPath tarball)
                   "-C" (.getCanonicalPath src) root-name]
                  {:out :string :err :string})
      {:path (.getCanonicalPath tarball)
       :sha512 (checksum/sha512 tarball)})))

(defn- with-stubbed-network
  "Stubs both fetch-bytes (used for sha512 download) and download-to-file
   (used for the tarball). The expected version's tarball+digest are
   prepared on disk; the stubs return whichever the install asks for."
  [{:keys [version]} body-fn]
  (let [{:keys [path sha512]} (mk-fake-maven-tarball! version)
        sha-bytes (.getBytes (str sha512
                                  "  apache-maven-" version "-bin.tar.gz\n")
                             "UTF-8")]
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-bytes (fn [url & _]
                                     (if (str/ends-with? url ".sha512")
                                       sha-bytes
                                       (throw (ex-info (str "unexpected fetch-bytes " url) {}))))
                  http/download-to-file (fn [_url dest-file & _]
                                          (io/copy (io/file path)
                                                   (io/file dest-file))
                                          (.getCanonicalPath (io/file dest-file)))]
      (body-fn))))

;; ---------------------------------------------------------------------------

(deftest supports-maven-descriptors
  (let [m (maven/maven-installer)]
    (is (tools/supports? m {:kind :maven :version "3.9.6"}))
    (is (tools/supports? m {:kind :maven :version "3.0.0"}))
    (is (not (tools/supports? m {:kind :jdk :version "17"})))
    (is (not (tools/supports? m {:kind :maven :version "3.9"}))
        "non X.Y.Z versions rejected — no URL constructed with attacker-controlled input")
    (is (not (tools/supports? m {:kind :maven :version "3.9.6/../../etc/passwd"}))
        "path traversal attempt rejected")))

(deftest cache-key-encodes-version-os-arch
  (let [m (maven/maven-installer {:os :linux :arch :x64})
        k (tools/cache-key m {:kind :maven :version "3.9.6"})]
    (is (= "maven/3.9.6-linux-x64" k))))

(deftest install-fresh-produces-real-mvn
  (with-stubbed-network
    {:version "3.9.6"}
    (fn []
      (let [m (maven/maven-installer)
            d {:kind :maven :version "3.9.6" :raw "maven_3_9_6"}
            r (tools/install m d)]
        (is (= :ok (:result r)) (str "install failed: " (:explain r)))
        (let [mvn-bin (io/file (:path r) "bin" "mvn")]
          (is (.exists mvn-bin))
          (is (.canExecute mvn-bin)))))))

(deftest resolve-via-registry-end-to-end
  (with-stubbed-network
    {:version "3.9.6"}
    (fn []
      (tools/register-installer! (maven/maven-installer))
      (let [r (tools/resolve! "maven_3_9_6")]
        (is (= :ok (:result r)))
        (is (= :maven (:installer r)))
        (is (false? (:cached? r)))
        (is (.exists (io/file (:path r) "bin" "mvn")))))))

(deftest second-resolve-marks-cached
  (with-stubbed-network
    {:version "3.9.6"}
    (fn []
      (tools/register-installer! (maven/maven-installer))
      (let [first (tools/resolve! "maven_3_9_6")
            second (tools/resolve! "maven_3_9_6")]
        (is (= :ok (:result first)))
        (is (= :ok (:result second)))
        (is (true? (:cached? second)))
        (is (= (:path first) (:path second)))))))

(deftest checksum-mismatch-surfaces-as-failed
  (testing "lying SHA-512 → :failed, never silent success"
    (let [{:keys [path]} (mk-fake-maven-tarball! "3.9.6")
          ;; 128 zeros — wrong digest
          bad (apply str (repeat 128 \0))
          sha-bytes (.getBytes (str bad "  apache-maven-3.9.6-bin.tar.gz\n")
                               "UTF-8")]
      (with-redefs [platform/os   (fn [] :linux)
                    platform/arch (fn [] :x64)
                    http/fetch-bytes (fn [_url & _] sha-bytes)
                    http/download-to-file (fn [_url dest-file & _]
                                            (io/copy (io/file path)
                                                     (io/file dest-file))
                                            (.getCanonicalPath
                                             (io/file dest-file)))]
        (let [r (tools/install (maven/maven-installer)
                               {:kind :maven :version "3.9.6"
                                :raw "maven_3_9_6"})]
          (is (= :failed (:result r)))
          (is (re-find #"checksum|mismatch|sha"
                       (str/lower-case (or (:explain r) "")))))))))
