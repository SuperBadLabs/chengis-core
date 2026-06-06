(ns chengis.tools.gradle
  "Gradle installer — third concrete installer in the CC2-EX3b family.

   Supports Jenkinsfile-style `gradle_8_5` and modern `gradle:8.5`
   descriptors plus `gradle_latest` / `gradle:latest` via Gradle's
   official current-versions JSON endpoint.

   The contract: `(tools/resolve! \"gradle_8_5\")` returns
   `{:result :ok :path <abs>}` where `(io/file <abs> \"bin\" \"gradle\")`
   exists and is executable.

   Distribution shape:
     - gradle.org publishes binary distribution zips at
       https://services.gradle.org/distributions/gradle-<ver>-bin.zip
       with a sibling `.sha256` text file containing the digest.
     - For `:latest`, GET https://services.gradle.org/versions/current
       which returns JSON `{\"version\":\"8.5\", \"downloadUrl\":\"…\",
                            \"checksumUrl\":\"…\"}`.

   Gradle distributions are architecture-agnostic (pure-JVM) so the
   cache-key only encodes the version. We still record os so
   cross-OS test runs don't collide on shared cache mounts.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [chengis.tools :as tools]
            [chengis.tools.archive :as archive]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private distributions-base
  "https://services.gradle.org/distributions")

(def ^:private current-versions-url
  "https://services.gradle.org/versions/current")

(defn- valid-version?
  "Gradle version must look like X.Y or X.Y.Z."
  [v]
  (and (string? v)
       (boolean (re-matches #"^\d+\.\d+(?:\.\d+)?$" v))))

(defn- tarball-url [version]
  (str distributions-base "/gradle-" version "-bin.zip"))

(defn- checksum-url [version]
  (str (tarball-url version) ".sha256"))

(defn- cache-key-for [version os]
  (str "gradle/" version "-" (name os)))

(defn- locate-gradle-bin
  [root]
  (let [f (io/file root "bin" "gradle")]
    (when (and (.exists f) (.canExecute f))
      (.getCanonicalPath (io/file root)))))

(defn- resolve-version
  "If descriptor is `:latest`, hit the current-versions endpoint and
   return the live version string. Otherwise the descriptor's own
   version. Returns nil on resolution failure."
  [descriptor]
  (let [v (:version descriptor)]
    (cond
      (and (string? v) (= "latest" (str/lower-case v)))
      (try
        (let [payload (http/fetch-json current-versions-url)
              live (get payload "version")]
          (when (valid-version? live) live))
        (catch Throwable _ nil))

      (:latest? descriptor)
      (try
        (let [payload (http/fetch-json current-versions-url)
              live (get payload "version")]
          (when (valid-version? live) live))
        (catch Throwable _ nil))

      (valid-version? v) v

      :else nil)))

(defn- parse-sha256
  "Gradle's `.sha256` file is just the hex digest with optional trailing
   newline. Take the first whitespace-separated token."
  [raw]
  (let [token (first (str/split (str/trim (or raw "")) #"\s+"))]
    (when (and (string? token)
               (re-matches #"[0-9a-fA-F]{64}" (str token)))
      (.toLowerCase ^String token))))

(defrecord GradleInstaller [config]
  tools/Installer

  (installer-id [_] :gradle)

  (supports? [_ {:keys [kind version] :as descriptor}]
    (and (= :gradle kind)
         (or (valid-version? version)
             (:latest? descriptor)
             (and (string? version)
                  (= "latest" (str/lower-case (or version "")))))))

  (cache-key [this descriptor]
    ;; Cache key uses the resolved (concrete) version when we can — for
    ;; `:latest` we encode "latest" as a placeholder which gets
    ;; rewritten on install. This means `locate` for `:latest` won't
    ;; cache-hit a prior `8.5` install, but that's honest: the
    ;; operator asked for `latest`, and a separate-day "latest" might
    ;; be a different version. install resolves concretely and falls
    ;; through to the per-version path.
    (cache-key-for (or (:version descriptor) "latest")
                   (or (:os config) (platform/os))))

  (locate [this descriptor]
    (let [root (tools/cache-path (tools/default-cache-root)
                                 (tools/cache-key this descriptor))]
      (locate-gradle-bin root)))

  (install [this descriptor]
    (let [os (or (:os config) (platform/os))]
      (cond
        (not (#{:linux :mac} os))
        {:result :unsupported
         :explain (str "gradle: os " os " not supported")}

        :else
        (try
          (if-let [concrete (resolve-version descriptor)]
            ;; Re-key against the concrete version so per-version caches
            ;; share a tree. A `latest` descriptor that resolves to 8.5
            ;; lands at `gradle/8.5-linux/...`.
            (let [concrete-desc (assoc descriptor :version concrete)
                  cache-key (cache-key-for concrete os)
                  root-dir (tools/cache-path (tools/default-cache-root)
                                             cache-key)
                  root-file (io/file root-dir)]
              (if-let [existing (locate-gradle-bin root-dir)]
                (do (log/info "gradle: cache hit at" existing)
                    {:result :ok :path existing})
                (let [sha-url (checksum-url concrete)
                      tar-url (tarball-url concrete)
                      _ (log/info "gradle: fetching SHA-256 from" sha-url)
                      sha-raw (String. ^bytes (http/fetch-bytes sha-url) "UTF-8")
                      sha (parse-sha256 sha-raw)]
                  (cond
                    (nil? sha)
                    {:result :failed
                     :explain (str "gradle: could not parse SHA-256 from " sha-url)}

                    :else
                    (let [zip-name (str "gradle-" concrete "-bin.zip")
                          zipfile (io/file root-file zip-name)]
                      (log/info "gradle: downloading" tar-url)
                      (.mkdirs root-file)
                      (http/download-to-file tar-url zipfile)
                      (log/info "gradle: verifying SHA-256")
                      (checksum/verify! zipfile sha :sha256)
                      (log/info "gradle: extracting to" root-dir)
                      ;; Zip wraps in `gradle-<ver>/`. Extract then
                      ;; promote the contents one level — archive/extract!
                      ;; doesn't support :strip-components for zip
                      ;; (would need a walk+move pass), so we do it
                      ;; explicitly by extracting into a scratch
                      ;; subdir then moving up.
                      (let [scratch (io/file root-file "_scratch")]
                        (.mkdirs scratch)
                        (archive/extract! {:archive (.getCanonicalPath zipfile)
                                           :dest (.getCanonicalPath scratch)})
                        (let [inner (first (filter #(.isDirectory ^java.io.File %)
                                                   (.listFiles scratch)))]
                          (when inner
                            (doseq [child (.listFiles ^java.io.File inner)]
                              (.renameTo ^java.io.File child
                                         (io/file root-file (.getName ^java.io.File child)))))
                          (.delete (io/file root-file "_scratch" (or (.getName ^java.io.File inner)
                                                                     "_")))
                          (.delete scratch)))
                      (.delete zipfile)
                      (if-let [p (locate-gradle-bin root-dir)]
                        {:result :ok :path p}
                        {:result :failed
                         :explain (str "gradle: extracted to " root-dir
                                       " but bin/gradle not present / not executable")}))))))
            {:result :failed
             :explain (str "gradle: could not resolve version from "
                           (pr-str descriptor))})
          (catch Throwable t
            {:result :failed
             :explain (str "gradle: install threw: "
                           (.getClass t) " " (.getMessage t))}))))))

(defn gradle-installer
  ([] (gradle-installer {}))
  ([config] (->GradleInstaller config)))
