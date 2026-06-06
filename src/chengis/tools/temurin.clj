(ns chengis.tools.temurin
  "Eclipse Temurin JDK installer — first concrete installer in the
   CC2-EX3b family.

   Supports Jenkinsfile-style `jdk_17_latest` / `jdk_21_latest` plus
   modern `jdk:17` / `jdk:21.0.1+12` descriptors. Resolves through the
   Adoptium API and installs into the chengis tool cache.

   The contract that matters: `(tools/resolve! \"jdk_17_latest\")`
   returns `{:result :ok :path <abs>}` where `<abs>` is the
   extracted JDK root — `(io/file <abs> \"bin\" \"java\")` exists and
   is executable. Operators wire `JAVA_HOME=<abs>` and step bins
   from there.

   Network access goes through `chengis.tools.http` (single seam for
   `with-redefs` in tests). Checksums verified via
   `chengis.tools.checksum/verify!` against the digest the Adoptium
   payload carries inline. Platform detection via
   `chengis.tools.platform`.

   v0.3.3 scope: linux + macOS × x64 + aarch64. Windows JDKs are a
   known gap — descriptor matching still succeeds, but `install`
   returns `:result :unsupported` with an explain string naming the
   reason. Better to fail loud than to download a Windows .msi
   onto a linux runner.

   Refs: docs/v0.2-board.md CC2-EX3b — Concrete tool installers."
  (:require [chengis.tools :as tools]
            [chengis.tools.archive :as archive]
            [chengis.tools.checksum :as checksum]
            [chengis.tools.http :as http]
            [chengis.tools.platform :as platform]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Descriptor → Adoptium API parameters
;; ---------------------------------------------------------------------------

(defn- feature-version
  "Adoptium API takes a major-version integer. `version` is what
   `parse-descriptor` produced: '17', '21', '17.0.9+9'. Strip to the
   leading integer; reject blank / non-numeric leading char.

   Returns the int or nil. Caller treats nil as 'descriptor doesn't
   look like a JDK major'."
  [version]
  (when (string? version)
    (let [s (str/trim version)
          ;; Match leading digits; ignore everything after.
          m (re-find #"^(\d+)" s)]
      (when m
        (Integer/parseInt (second m))))))

(defn- os->adoptium [os]
  (case os
    :linux "linux"
    :mac   "mac"
    nil))

(defn- arch->adoptium [arch]
  (case arch
    :x64     "x64"
    :aarch64 "aarch64"
    nil))

;; ---------------------------------------------------------------------------
;; Adoptium API
;;
;; v3/assets/feature_releases/{feature_version}/ga endpoint:
;;
;;   https://api.adoptium.net/v3/assets/feature_releases/17/ga?
;;     architecture=x64&image_type=jdk&os=linux&vendor=eclipse&page=0&page_size=1
;;
;; Returns a JSON list; index 0 is the latest GA. Each element has
;;   .binaries[0].package.{link, checksum, name}
;; and .version_data.{semver, ...}.
;; ---------------------------------------------------------------------------

(def ^:private adoptium-base "https://api.adoptium.net/v3/assets/feature_releases")

(defn- adoptium-url
  [feature-major os-token arch-token]
  (str adoptium-base "/" feature-major "/ga"
       "?architecture=" arch-token
       "&image_type=jdk"
       "&os=" os-token
       "&vendor=eclipse"
       "&heap_size=normal"
       "&jvm_impl=hotspot"
       "&page=0&page_size=1"
       "&sort_method=DEFAULT&sort_order=DESC"))

(defn- pick-binary
  "From a parsed Adoptium response (vector of releases), pull out the
   first release's first binary's package payload. Returns
   {:link :checksum :semver :name} or nil if the payload doesn't
   match the expected shape (network glitched, API changed, etc.)."
  [payload]
  (when (sequential? payload)
    (when-let [release (first payload)]
      (let [binary (first (get release "binaries"))
            package (get binary "package")
            semver  (get-in release ["version_data" "semver"])]
        (when (and (map? package)
                   (string? (get package "link"))
                   (string? (get package "checksum")))
          {:link     (get package "link")
           :checksum (get package "checksum")
           :semver   (or semver "unknown")
           :name     (get package "name")})))))

;; ---------------------------------------------------------------------------
;; Installer
;; ---------------------------------------------------------------------------

(defn- cache-key-for [version os arch]
  (str "temurin/jdk-" version "-" (name os) "-" (name arch)))

(defn- archive-strip-components
  "Adoptium tarballs wrap their contents in a single
   `jdk-17.0.9+9/` directory. We strip 1 level so the JDK lays out
   directly under our cache subdir: `<cache>/bin/java`."
  []
  1)

(defn- locate-java-bin
  "Return the absolute path of `<root>/bin/java` if it exists and is
   executable, nil otherwise. The headline contract: a successful
   install means this file is real."
  [root]
  (let [f (io/file root "bin" "java")]
    (when (and (.exists f) (.canExecute f))
      (.getCanonicalPath (io/file root)))))

(defrecord TemurinInstaller [config]
  tools/Installer

  (installer-id [_] :temurin)

  (supports? [_ {:keys [kind version]}]
    (and (= :jdk kind)
         (some? (feature-version version))))

  (cache-key [this descriptor]
    (cache-key-for (:version descriptor)
                   (or (:os config) (platform/os))
                   (or (:arch config) (platform/arch))))

  (locate [this descriptor]
    (let [root (tools/cache-path (tools/default-cache-root)
                                 (tools/cache-key this descriptor))]
      (locate-java-bin root)))

  (install [this descriptor]
    (let [os   (or (:os config) (platform/os))
          arch (or (:arch config) (platform/arch))
          os-token (os->adoptium os)
          arch-token (arch->adoptium arch)
          feature (feature-version (:version descriptor))]
      (cond
        (not (and feature os-token arch-token))
        {:result :unsupported
         :explain (str "temurin: cannot target os=" os " arch=" arch
                       " for feature=" feature
                       " (only linux/mac × x64/aarch64 supported)")}

        :else
        (let [root-dir (tools/cache-path (tools/default-cache-root)
                                         (tools/cache-key this descriptor))
              root-file (io/file root-dir)]
          ;; If already extracted (someone else's race / prior run),
          ;; honor the cache and skip the download.
          (if-let [existing (locate-java-bin root-dir)]
            (do (log/info "temurin: cache hit at" existing)
                {:result :ok :path existing})
            (try
              (let [api-url (adoptium-url feature os-token arch-token)
                    _ (log/info "temurin: querying" api-url)
                    payload (http/fetch-json api-url)
                    pick (pick-binary payload)]
                (cond
                  (nil? pick)
                  {:result :failed
                   :explain (str "temurin: Adoptium API returned no usable"
                                 " release for feature=" feature
                                 " os=" os-token " arch=" arch-token)}

                  :else
                  (let [tarball-name (or (:name pick)
                                         (str "OpenJDK" feature
                                              "-jdk_" arch-token
                                              "_" os-token
                                              "_hotspot.tar.gz"))
                        ;; Keep the canonical .tar.gz extension so
                        ;; archive/extract! recognizes the kind.
                        ;; download-to-file already does an atomic
                        ;; rename via *.partial under the hood, so
                        ;; a half-downloaded tarball never lands at
                        ;; this name.
                        tarball (io/file root-file tarball-name)]
                    (log/info "temurin: downloading" (:link pick)
                              "(" (:semver pick) ")")
                    (.mkdirs root-file)
                    (http/download-to-file (:link pick) tarball)
                    (log/info "temurin: verifying SHA-256")
                    (checksum/verify! tarball (:checksum pick) :sha256)
                    (log/info "temurin: extracting to" root-dir)
                    (archive/extract! {:archive (.getCanonicalPath tarball)
                                       :dest root-dir
                                       :strip-components (archive-strip-components)})
                    ;; Cleanup the tarball — we don't need it after extract.
                    (.delete tarball)
                    (if-let [p (locate-java-bin root-dir)]
                      {:result :ok :path p}
                      {:result :failed
                       :explain (str "temurin: extracted to " root-dir
                                     " but bin/java not present / not executable")}))))
              (catch Throwable t
                {:result :failed
                 :explain (str "temurin: install threw: "
                               (.getClass t) " " (.getMessage t))}))))))))

(defn temurin-installer
  "Construct a TemurinInstaller. Optional config:
     :os    — override platform detection (for tests / cross-platform CI)
     :arch  — override platform detection
   No config = use platform/os + platform/arch."
  ([] (temurin-installer {}))
  ([config] (->TemurinInstaller config)))
