(ns chengis.tools.node
  "Node.js installer — fourth concrete installer in the CC2-EX3b family.

   Supports Jenkinsfile-style `node_20_10_0` and modern `node:20.10.0`
   descriptors. `node:latest` and `_latest` flavors land via
   nodejs.org's index.json (lists every release, sorted newest first).

   Distribution shape:
     - nodejs.org publishes per-version directories at
       https://nodejs.org/dist/v<ver>/, each containing tarballs and an
       aggregate SHASUMS256.txt with one digest per file.
     - We pick the tar.gz (`node-v<ver>-<os>-<arch>.tar.gz`) over the
       tar.xz to avoid adding xz support — tarballs are larger but
       extraction is one tar invocation.

   The contract: `(tools/resolve! \"node:20.10.0\")` returns
   `{:result :ok :path <abs>}` where `(io/file <abs> \"bin\" \"node\")`
   exists and is executable.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [chengis.tools :as tools]
            [chengis.tools.archive :as archive]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private nodejs-base "https://nodejs.org/dist")
(def ^:private nodejs-index (str nodejs-base "/index.json"))

(defn- valid-version?
  "Node version: X.Y.Z (the tar.gz path uses exactly this form)."
  [v]
  (and (string? v)
       (boolean (re-matches #"^\d+\.\d+\.\d+$" v))))

(defn- os->node [os]
  (case os
    :linux "linux"
    :mac   "darwin"
    nil))

(defn- arch->node [arch]
  (case arch
    :x64     "x64"
    :aarch64 "arm64"
    nil))

(defn- tarball-name [version os-tok arch-tok]
  (str "node-v" version "-" os-tok "-" arch-tok ".tar.gz"))

(defn- version-dir-url [version]
  (str nodejs-base "/v" version))

(defn- tarball-url [version os-tok arch-tok]
  (str (version-dir-url version) "/" (tarball-name version os-tok arch-tok)))

(defn- shasums-url [version]
  (str (version-dir-url version) "/SHASUMS256.txt"))

(defn- cache-key-for [version os arch]
  (str "node/" version "-" (name os) "-" (name arch)))

(defn- locate-node-bin [root]
  (let [f (io/file root "bin" "node")]
    (when (and (.exists f) (.canExecute f))
      (.getCanonicalPath (io/file root)))))

(defn- resolve-version
  "If `:latest` / `latest`, hit index.json and pick the first entry's
   version (sans leading `v`). Otherwise pass through. Returns nil
   on resolution failure."
  [descriptor]
  (let [v (:version descriptor)]
    (cond
      (and (string? v) (= "latest" (str/lower-case v)))
      (try
        (let [payload (http/fetch-json nodejs-index)
              first-ver (get (first payload) "version")]
          (when (string? first-ver)
            (let [trimmed (if (str/starts-with? first-ver "v")
                            (subs first-ver 1)
                            first-ver)]
              (when (valid-version? trimmed) trimmed))))
        (catch Throwable _ nil))

      (:latest? descriptor)
      (try
        (let [payload (http/fetch-json nodejs-index)
              first-ver (get (first payload) "version")]
          (when (string? first-ver)
            (let [trimmed (if (str/starts-with? first-ver "v")
                            (subs first-ver 1)
                            first-ver)]
              (when (valid-version? trimmed) trimmed))))
        (catch Throwable _ nil))

      (valid-version? v) v

      :else nil)))

(defn- parse-shasums-for
  "Pull the SHA-256 for `target-name` from nodejs.org's aggregate
   SHASUMS256.txt. Each line is `<hex>  <filename>`. Returns the
   lowercased hex or nil."
  [shasums-raw target-name]
  (->> (str/split-lines (or shasums-raw ""))
       (some (fn [line]
               (let [parts (str/split (str/trim line) #"\s+" 2)]
                 (when (and (= 2 (count parts))
                            (= target-name (str/trim (second parts))))
                   (.toLowerCase ^String (first parts))))))))

(defrecord NodeInstaller [config]
  tools/Installer

  (installer-id [_] :node)

  (supports? [_ {:keys [kind version] :as descriptor}]
    (and (= :node kind)
         (or (valid-version? version)
             (:latest? descriptor)
             (and (string? version)
                  (= "latest" (str/lower-case (or version "")))))))

  (cache-key [this descriptor]
    (cache-key-for (or (:version descriptor) "latest")
                   (or (:os config) (platform/os))
                   (or (:arch config) (platform/arch))))

  (locate [this descriptor]
    (let [root (tools/cache-path (tools/default-cache-root)
                                 (tools/cache-key this descriptor))]
      (locate-node-bin root)))

  (install [this descriptor]
    (let [os   (or (:os config) (platform/os))
          arch (or (:arch config) (platform/arch))
          os-tok (os->node os)
          arch-tok (arch->node arch)]
      (cond
        (not (and os-tok arch-tok))
        {:result :unsupported
         :explain (str "node: cannot target os=" os " arch=" arch)}

        :else
        (try
          (if-let [concrete (resolve-version descriptor)]
            (let [cache-key (cache-key-for concrete os arch)
                  root-dir (tools/cache-path (tools/default-cache-root)
                                             cache-key)
                  root-file (io/file root-dir)]
              (if-let [existing (locate-node-bin root-dir)]
                (do (log/info "node: cache hit at" existing)
                    {:result :ok :path existing})
                (let [tar-name (tarball-name concrete os-tok arch-tok)
                      _ (log/info "node: fetching SHASUMS for" concrete)
                      shasums-raw (String. ^bytes (http/fetch-bytes (shasums-url concrete))
                                           "UTF-8")
                      sha (parse-shasums-for shasums-raw tar-name)]
                  (cond
                    (nil? sha)
                    {:result :failed
                     :explain (str "node: SHASUMS256.txt missing entry for "
                                   tar-name)}

                    :else
                    (let [tarball (io/file root-file tar-name)]
                      (log/info "node: downloading" (tarball-url concrete os-tok arch-tok))
                      (.mkdirs root-file)
                      (http/download-to-file (tarball-url concrete os-tok arch-tok)
                                             tarball)
                      (log/info "node: verifying SHA-256")
                      (checksum/verify! tarball sha :sha256)
                      (log/info "node: extracting to" root-dir)
                      (archive/extract! {:archive (.getCanonicalPath tarball)
                                         :dest root-dir
                                         ;; tarball wraps in
                                         ;; `node-v<ver>-<os>-<arch>/`
                                         :strip-components 1})
                      (.delete tarball)
                      (if-let [p (locate-node-bin root-dir)]
                        {:result :ok :path p}
                        {:result :failed
                         :explain (str "node: extracted to " root-dir
                                       " but bin/node not present / not executable")}))))))
            {:result :failed
             :explain (str "node: could not resolve version from "
                           (pr-str descriptor))})
          (catch Throwable t
            {:result :failed
             :explain (str "node: install threw: "
                           (.getClass t) " " (.getMessage t))}))))))

(defn node-installer
  ([] (node-installer {}))
  ([config] (->NodeInstaller config)))
