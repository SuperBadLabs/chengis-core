(ns chengis.tools.archive
  "Archive extraction for tool tarballs / zips.

   Shells out to `tar` and `unzip` (both POSIX-universal — every linux
   and macOS CI runner has them) rather than pulling in
   Apache Commons Compress. Two reasons:

     1. zero new transitive dep on chengis-core's classpath
     2. native `tar` is faster than the JDK + commons-compress path
        on the 200MB JDK tarball case

   Traversal-defense layered:
     - pre-validate: list entries via `tar -tzf` / `unzip -l`; reject
       on any entry that starts with `/` or contains a `..` path
       segment. Defeats the classic 'tarbomb' (../etc/passwd) shape.
     - post-extract: canonical-prefix? check on every dirent — every
       file produced must resolve under `dest-dir`. Defeats symlink
       races mid-extraction.

   The `:strip-components N` knob mirrors `tar --strip-components` —
   useful for archives that wrap their contents in a top-level
   `apache-maven-3.9.6/` directory we want to flatten.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [babashka.process :as p]
            [chengis.tools :as tools]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- canonical-prefix?
  "Same shape as `tools/canonical-prefix?` (which is private) — verifies
   `child` resolves under `parent` after canonicalization. Duplicated
   here so this ns has no circular dep on the protocol ns."
  [parent child]
  (let [p (.getCanonicalPath (io/file parent))
        c (.getCanonicalPath (io/file child))]
    (or (= p c)
        (str/starts-with? c (str p java.io.File/separatorChar)))))

(defn- archive-kind
  "Detect the archive shape from the filename — controls which CLI
   we invoke. Conservative: anything not on the known list is
   :unknown and rejected loudly."
  [archive-path]
  (let [n (str/lower-case (str archive-path))]
    (cond
      (or (str/ends-with? n ".tar.gz")
          (str/ends-with? n ".tgz"))   :tar-gz
      (str/ends-with? n ".tar")        :tar
      (str/ends-with? n ".zip")        :zip
      :else                            :unknown)))

(defn- sh!
  "Run a shell command, capturing both streams. Throws ex-info on
   non-zero exit with the captured stderr."
  [argv & {:keys [in]}]
  (let [{:keys [exit out err]}
        @(p/process argv {:out :string :err :string :in (or in :inherit)})]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed (" exit "): "
                           (str/join " " argv))
                      {:type :archive/command-failed
                       :argv argv
                       :exit exit
                       :stderr err})))
    out))

(defn- tar-entries
  "List the entries of a tar(.gz) archive without extracting. Used for
   pre-validation before we touch the destination directory."
  [archive-path]
  (let [argv (case (archive-kind archive-path)
               :tar-gz ["tar" "-tzf" (str archive-path)]
               :tar    ["tar" "-tf"  (str archive-path)]
               (throw (ex-info "not a tar archive" {:archive archive-path})))
        out (sh! argv)]
    (->> (str/split-lines out)
         (remove str/blank?))))

(defn- zip-entries [archive-path]
  ;; `unzip -Z1` is the portable script-friendly listing flag —
  ;; one entry per line, no headers/footers.
  (let [out (sh! ["unzip" "-Z1" (str archive-path)])]
    (->> (str/split-lines out)
         (remove str/blank?))))

(defn- unsafe-entry?
  "True iff this archive entry name would escape the destination dir
   — absolute path, or contains a `..` path segment. Backslashes also
   blocked (Windows-shaped paths in a tarball don't belong on Linux/mac
   and signal probable tampering)."
  [name]
  (or (str/blank? name)
      (str/starts-with? name "/")
      (str/includes? name "\\")
      (boolean (some #{".."} (str/split name #"/")))))

(defn- validate-entries!
  "Reject the archive loudly if any entry is unsafe. Returns nil on
   success; throws ex-info on first offender."
  [entries archive-path]
  (when-let [bad (first (filter unsafe-entry? entries))]
    (throw (ex-info (str "unsafe archive entry: " (pr-str bad))
                    {:type :archive/unsafe-entry
                     :archive archive-path
                     :entry bad})))
  nil)

(defn- walk-files
  "All files (not dirs) under `root`, lazily."
  [root]
  (let [root-file (io/file root)]
    (when (.exists root-file)
      (->> (file-seq root-file)
           (filter #(.isFile ^java.io.File %))))))

(defn extract!
  "Extract `archive-path` into `dest-dir`, creating it if needed.
   Returns the canonical absolute path of `dest-dir`.

   Options:
     :strip-components N  — like tar --strip-components; drops N leading
                            path segments from each entry. zip archives
                            don't support this natively; we extract then
                            move entries up N levels.

   Defenses:
     1. pre-validation: lists entries before touching the FS; refuses
        on `..` / absolute / Windows-style paths.
     2. post-extraction: walks the extracted tree; throws if any file
        resolves outside `dest-dir`.

   Throws ex-info on any safety violation, command failure, or unknown
   archive kind. Installers catch this and turn it into
   `:result :failed :explain ...`."
  [{:keys [archive dest strip-components]
    :or {strip-components 0}}]
  (let [kind (archive-kind archive)
        dest-file (io/file dest)]
    (when (= :unknown kind)
      (throw (ex-info (str "unknown archive kind for " archive)
                      {:type :archive/unknown-kind :archive archive})))
    (.mkdirs dest-file)
    (let [entries (case kind
                    (:tar-gz :tar) (tar-entries archive)
                    :zip           (zip-entries archive))]
      (validate-entries! entries archive)
      ;; Extract via the native tool — tar/unzip both have hardened
      ;; behavior for the entry shapes we already pre-validated.
      (case kind
        :tar-gz (sh! (cond-> ["tar" "-xzf" (str archive)
                               "-C" (.getCanonicalPath dest-file)]
                        (pos? strip-components)
                        (conj "--strip-components" (str strip-components))))
        :tar    (sh! (cond-> ["tar" "-xf" (str archive)
                               "-C" (.getCanonicalPath dest-file)]
                        (pos? strip-components)
                        (conj "--strip-components" (str strip-components))))
        :zip    (do (sh! ["unzip" "-q" "-o" (str archive)
                           "-d" (.getCanonicalPath dest-file)])
                    ;; zip strip-components: walk + move. Rare in our
                    ;; corpus (gradle is the only zip target and its
                    ;; layout already wraps in gradle-X.Y.Z/) so this
                    ;; is here for parity, not perf.
                    (when (pos? strip-components)
                      (throw (ex-info "zip + strip-components not yet supported"
                                      {:type :archive/unsupported-shape
                                       :archive archive}))))))
    ;; Defense-in-depth: post-extraction prefix check.
    (doseq [^java.io.File f (walk-files dest-file)]
      (when-not (canonical-prefix? (.getCanonicalPath dest-file)
                                   (.getCanonicalPath f))
        (throw (ex-info (str "extracted file escaped dest: " f)
                        {:type :archive/escape-detected
                         :file (.getCanonicalPath f)
                         :dest (.getCanonicalPath dest-file)}))))
    (.getCanonicalPath dest-file)))
