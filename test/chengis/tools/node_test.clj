(ns chengis.tools.node-test
  "Acceptance tests for the Node.js installer (CC2-EX3b.2).

   Network stubbed via with-redefs. Includes a SHASUMS256-style
   aggregate-digest test: the installer must pull the digest for its
   target tarball name out of an opaque text blob, not fail-open on
   missing entries."
  (:require [babashka.process :as p]
            [chengis.tools :as tools]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.node :as node]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each
  (fn [t]
    (tools/clear-registry!)
    (let [root (-> (Files/createTempDirectory "node-test-cache"
                                              (into-array FileAttribute []))
                   .toFile .getCanonicalPath)]
      (with-redefs [tools/default-cache-root (fn [] root)]
        (t)))
    (tools/clear-registry!)))

(defn- mk-fake-node-tarball!
  "Build a tar.gz with the shape nodejs.org ships: `node-vX.Y.Z-<os>-<arch>/bin/node`."
  [version os-tok arch-tok]
  (let [src (-> (Files/createTempDirectory "fake-node-src"
                                           (into-array FileAttribute []))
                .toFile)
        root-name (str "node-v" version "-" os-tok "-" arch-tok)
        bin-node (io/file src root-name "bin" "node")]
    (.mkdirs (.getParentFile bin-node))
    (spit bin-node "#!/bin/sh\necho fake-node $@\n")
    (.setExecutable bin-node true false)
    (let [out-dir (-> (Files/createTempDirectory "fake-node-out"
                                                 (into-array FileAttribute []))
                      .toFile)
          tar-name (str root-name ".tar.gz")
          tarball (io/file out-dir tar-name)]
      @(p/process ["tar" "-czf" (.getCanonicalPath tarball)
                   "-C" (.getCanonicalPath src) root-name]
                  {:out :string :err :string})
      {:path (.getCanonicalPath tarball)
       :sha256 (checksum/sha256 tarball)
       :tar-name tar-name})))

(defn- mk-shasums-blob
  "Build a SHASUMS256.txt-shaped multi-line digest list. Mixes the
   target's digest with a couple of decoy lines so the parser has to
   actually match by filename, not just take the first entry."
  [{:keys [target-name target-sha]}]
  (str/join "\n"
            [(str "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  node-v18.0.0-linux-x64.tar.xz")
             (str target-sha "  " target-name)
             (str "cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe  node-v20.10.0-linux-x64.tar.xz")
             ""]))

(defn- with-stubbed-network
  [{:keys [version os-tok arch-tok latest-version]} body-fn]
  (let [{:keys [path sha256 tar-name]} (mk-fake-node-tarball! version os-tok arch-tok)
        shasums (mk-shasums-blob {:target-name tar-name :target-sha sha256})
        shasums-bytes (.getBytes shasums "UTF-8")]
    (with-redefs [platform/os   (fn [] :linux)
                  platform/arch (fn [] :x64)
                  http/fetch-bytes (fn [url & _]
                                     (if (str/ends-with? url "SHASUMS256.txt")
                                       shasums-bytes
                                       (throw (ex-info (str "unexpected fetch-bytes " url) {}))))
                  http/fetch-json (fn [url & _]
                                    (if (str/ends-with? url "index.json")
                                      [{"version" (str "v" (or latest-version version))}]
                                      (throw (ex-info (str "unexpected fetch-json " url) {}))))
                  http/download-to-file (fn [_url dest-file & _]
                                          (io/copy (io/file path)
                                                   (io/file dest-file))
                                          (.getCanonicalPath (io/file dest-file)))]
      (body-fn))))

;; ---------------------------------------------------------------------------

(deftest supports-explicit-and-latest
  (let [n (node/node-installer)]
    (is (tools/supports? n {:kind :node :version "20.10.0"}))
    (is (tools/supports? n {:kind :node :version "latest"}))
    (is (tools/supports? n {:kind :node :version "20" :latest? true}))
    (is (not (tools/supports? n {:kind :node :version "20.10"}))
        "X.Y is rejected — Node URLs use full X.Y.Z")))

(deftest install-explicit-version
  (with-stubbed-network
    {:version "20.10.0" :os-tok "linux" :arch-tok "x64"}
    (fn []
      (let [n (node/node-installer)
            d {:kind :node :version "20.10.0" :raw "node:20.10.0"}
            r (tools/install n d)]
        (is (= :ok (:result r)) (str "install failed: " (:explain r)))
        (let [bin (io/file (:path r) "bin" "node")]
          (is (.exists bin))
          (is (.canExecute bin)))))))

(deftest install-latest-resolves-and-installs
  (with-stubbed-network
    {:version "20.10.0" :os-tok "linux" :arch-tok "x64"
     :latest-version "20.10.0"}
    (fn []
      (let [n (node/node-installer)
            d {:kind :node :version "latest" :raw "node:latest"}
            r (tools/install n d)]
        (is (= :ok (:result r)) (str "install failed: " (:explain r)))))))

(deftest resolve-via-registry-end-to-end
  (with-stubbed-network
    {:version "20.10.0" :os-tok "linux" :arch-tok "x64"}
    (fn []
      (tools/register-installer! (node/node-installer))
      (let [r (tools/resolve! "node:20.10.0")]
        (is (= :ok (:result r)))
        (is (= :node (:installer r)))
        (is (.exists (io/file (:path r) "bin" "node")))))))

(deftest install-fails-if-shasums-missing-target-line
  (testing "if SHASUMS256.txt has no line for our target tarball,
            install! reports :failed — does NOT silently accept the
            file without verification"
    (let [{:keys [path]} (mk-fake-node-tarball! "20.10.0" "linux" "x64")
          ;; SHASUMS for a DIFFERENT tarball — our target isn't listed
          shasums (str "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd"
                       "  node-v20.10.0-linux-x64.tar.xz\n")
          shasums-bytes (.getBytes shasums "UTF-8")]
      (with-redefs [platform/os   (fn [] :linux)
                    platform/arch (fn [] :x64)
                    http/fetch-bytes (fn [_url & _] shasums-bytes)
                    http/download-to-file (fn [_url dest-file & _]
                                            (io/copy (io/file path)
                                                     (io/file dest-file))
                                            (.getCanonicalPath
                                             (io/file dest-file)))]
        (let [r (tools/install (node/node-installer)
                               {:kind :node :version "20.10.0"
                                :raw "node:20.10.0"})]
          (is (= :failed (:result r)))
          (is (re-find #"SHASUMS|entry" (or (:explain r) ""))))))))
