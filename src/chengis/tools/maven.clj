(ns chengis.tools.maven
  "Apache Maven installer — second concrete installer in the CC2-EX3b
   family.

   Supports Jenkinsfile-style `maven_3_9_6` and modern `maven:3.9.6`
   descriptors. Resolves through dlcdn.apache.org's stable distribution
   path and installs into the chengis tool cache.

   The contract: `(tools/resolve! \"maven_3_9_6\")` returns
   `{:result :ok :path <abs>}` where `(io/file <abs> \"bin\" \"mvn\")`
   exists and is executable.

   v0.3.3 scope notes:
     - Explicit version only. `_latest` requires parsing Apache's
       HTML index (no JSON API), tracked as a follow-up.
     - Apache mirrors hashes via SHA-512 sibling files; we GET
       `<tarball>.sha512` alongside the tarball.
     - linux/mac × x64/aarch64 — same matrix as Temurin. Maven
       itself is architecture-agnostic (pure-JVM), but the tarball
       location is the same across arches anyway, so we pin the
       cache key per-arch only for consistency.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [chengis.tools :as tools]
            [chengis.tools.archive :as archive]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private apache-base "https://dlcdn.apache.org/maven/maven-3")

(defn- tarball-url [version]
  (str apache-base "/" version
       "/binaries/apache-maven-" version "-bin.tar.gz"))

(defn- checksum-url [version]
  (str (tarball-url version) ".sha512"))

(defn- valid-version?
  "Maven version must look like X.Y.Z. Reject anything else early so
   we never construct a URL with attacker-controlled path segments
   (`../etc/passwd@3.9.6` style)."
  [v]
  (and (string? v)
       (boolean (re-matches #"^\d+\.\d+\.\d+$" v))))

(defn- cache-key-for [version os arch]
  (str "maven/" version "-" (name os) "-" (name arch)))

(defn- locate-mvn-bin
  "Return absolute path of the install root if `<root>/bin/mvn`
   exists and is executable."
  [root]
  (let [f (io/file root "bin" "mvn")]
    (when (and (.exists f) (.canExecute f))
      (.getCanonicalPath (io/file root)))))

(defn- parse-apache-sha512
  "Apache's .sha512 file is usually just the hex digest, sometimes
   followed by whitespace + filename: `<hex>  apache-maven-X.Y.Z-bin.tar.gz`.
   Take the first whitespace-separated token."
  [raw]
  (let [s (str/trim (or raw ""))
        token (first (str/split s #"\s+"))]
    (when (and (string? token)
               (re-matches #"[0-9a-fA-F]{128}" (str token)))
      (.toLowerCase ^String token))))

(defrecord MavenInstaller [config]
  tools/Installer

  (installer-id [_] :maven)

  (supports? [_ {:keys [kind version]}]
    (and (= :maven kind)
         (valid-version? version)))

  (cache-key [this descriptor]
    (cache-key-for (:version descriptor)
                   (or (:os config) (platform/os))
                   (or (:arch config) (platform/arch))))

  (locate [this descriptor]
    (let [root (tools/cache-path (tools/default-cache-root)
                                 (tools/cache-key this descriptor))]
      (locate-mvn-bin root)))

  (install [this descriptor]
    (let [version (:version descriptor)
          os   (or (:os config) (platform/os))
          arch (or (:arch config) (platform/arch))]
      (cond
        (not (valid-version? version))
        {:result :unsupported
         :explain (str "maven: rejecting non X.Y.Z version " (pr-str version))}

        (not (#{:linux :mac} os))
        {:result :unsupported
         :explain (str "maven: os " os " not supported")}

        :else
        (let [root-dir (tools/cache-path (tools/default-cache-root)
                                         (tools/cache-key this descriptor))
              root-file (io/file root-dir)]
          (if-let [existing (locate-mvn-bin root-dir)]
            (do (log/info "maven: cache hit at" existing)
                {:result :ok :path existing})
            (try
              (let [sha-url (checksum-url version)
                    tar-url (tarball-url version)
                    _ (log/info "maven: fetching SHA-512 from" sha-url)
                    sha-raw (String. ^bytes (http/fetch-bytes sha-url) "UTF-8")
                    sha (parse-apache-sha512 sha-raw)]
                (cond
                  (nil? sha)
                  {:result :failed
                   :explain (str "maven: could not parse SHA-512 from "
                                 sha-url " — got " (pr-str (subs sha-raw 0
                                                                 (min 80 (count sha-raw)))))}

                  :else
                  (let [tarball-name (str "apache-maven-" version "-bin.tar.gz")
                        tarball (io/file root-file tarball-name)]
                    (log/info "maven: downloading" tar-url)
                    (.mkdirs root-file)
                    (http/download-to-file tar-url tarball)
                    (log/info "maven: verifying SHA-512")
                    (checksum/verify! tarball sha :sha512)
                    (log/info "maven: extracting to" root-dir)
                    (archive/extract! {:archive (.getCanonicalPath tarball)
                                       :dest root-dir
                                       ;; tarball wraps in `apache-maven-X.Y.Z/`
                                       :strip-components 1})
                    (.delete tarball)
                    (if-let [p (locate-mvn-bin root-dir)]
                      {:result :ok :path p}
                      {:result :failed
                       :explain (str "maven: extracted to " root-dir
                                     " but bin/mvn not present / not executable")}))))
              (catch Throwable t
                {:result :failed
                 :explain (str "maven: install threw: "
                               (.getClass t) " " (.getMessage t))}))))))))

(defn maven-installer
  ([] (maven-installer {}))
  ([config] (->MavenInstaller config)))
