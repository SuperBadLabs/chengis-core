(ns chengis.engine.workspace
  "Workspace directory allocation for builds.

   Two layouts are supported:

     1. Legacy per-build:  `<root>/<job-id>/<build-number>/`
        Created by `create-workspace`. Each build gets a fresh tree; the
        retention sweeper (`cleanup-old-workspaces`) trims the oldest
        N per job.

     2. Per-(job, branch):  `<root>/<job-id>/branches/<sanitized-branch>/`
        Created by `allocate!` when CHG-FEAT-003 multibranch is enabled
        for the job. Concurrent builds on `master` and `feature/x` no
        longer share a working tree, so a `git checkout` on one branch
        can't collide with a WIP checkout on another. The tree PERSISTS
        between builds on the same branch — the next build does
        `git fetch` + `git reset --hard` on the existing clone, which is
        materially faster than re-cloning.

   Security: branch names come from webhook payloads, which means they
   are attacker-controlled. `sanitize-branch-name` is the security
   boundary that ensures `..`, `/`, null bytes, and pathological lengths
   can never escape the workspace root. `validate-path` is the
   belt-and-suspenders fallback that double-checks the resolved path
   stays inside the canonical root."
  (:require [chengis.db.job-branches-store :as job-branches-store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.nio.file Files FileVisitResult LinkOption Path SimpleFileVisitor]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Path safety
;; ---------------------------------------------------------------------------

(defn- validate-path
  "Verify the resolved path is within workspace-root. Prevents path traversal."
  [workspace-root path]
  (let [root-canonical (.getCanonicalPath (io/file workspace-root))
        path-canonical (.getCanonicalPath (io/file path))]
    (when-not (.startsWith path-canonical root-canonical)
      (throw (ex-info "Path traversal detected"
                      {:workspace-root root-canonical :resolved-path path-canonical})))
    path-canonical))

;; ---------------------------------------------------------------------------
;; Branch-name sanitization (security-critical)
;; ---------------------------------------------------------------------------

(def ^:private branch-name-max-length
  "Cap sanitized branch directory segments at 64 chars. Git itself allows
   branch names up to ~255 chars on most filesystems, but very long
   names produce unwieldy paths and the cap keeps `(job-id)/<branch>`
   well clear of common filesystem limits (e.g. 255-byte path
   components, 4 096-byte full paths on Linux)."
  64)

(defn sanitize-branch-name
  "Turn a raw branch name into a filesystem-safe directory segment.

   Rules (in order):
     1. nil / blank             -> throw (caller mis-used the API)
     2. replace any char outside `[A-Za-z0-9._-]` with `_`
        (covers backslashes, slashes, spaces, unicode, control chars,
        null bytes, shell metas, URL-encoded sequences like `%2F` that
        didn't get decoded upstream)
     3. fold any run of `.` of length >= 2 to the same number of `_`
        (kills `..` traversal sequences ANYWHERE in the string, not
        just at the start; also kills the `...etc/passwd` shape).
     4. collapse leading `.` to `_` (single leading dot would still
        be a hidden-file marker on POSIX).
     5. strip trailing `.` (Windows reserves trailing dots; harmless
        on Linux but keeps cross-platform behaviour identical).
     6. cap at `branch-name-max-length` chars.
     7. if the result is empty after sanitization, throw — the caller
        passed a string that, after stripping unsafe characters, has no
        signal left.

   Idempotent: applying twice yields the same value as applying once
   (the safe-chars set is closed under the replacement rules).

   Examples:
     `feature/foo`         -> `feature_foo`
     `../../etc/passwd`    -> `___etc_passwd`       (`..` runs -> `__`,
                                                     then `_` for the
                                                     remaining leading
                                                     dot, slashes -> `_`)
     `release/v1.0`        -> `release_v1.0`
     `'; rm -rf /'`        -> `___rm_-rf__`"
  [branch-name]
  (when (or (nil? branch-name) (str/blank? branch-name))
    (throw (ex-info "Branch name is nil or blank"
                    {:branch-name branch-name})))
  (let [;; 2. — fold path separators, null bytes, control chars, and
        ;; any non-safe byte into `_`. The character class is
        ;; deliberately conservative: only ASCII letters, digits, dot,
        ;; hyphen, and underscore survive. Anything else (unicode,
        ;; shell metas, URL-encoded sequences, null bytes, control
        ;; chars) becomes `_`. This is the layer that defeats
        ;; null-byte truncation attacks.
        safe-chars (str/replace branch-name #"[^A-Za-z0-9._-]" "_")
        ;; 3. dot-runs of length >= 2 -> underscores. We do this BEFORE
        ;; the leading/trailing rules so a `..` anywhere in the string
        ;; — not just at the start — can't reconstitute a relative
        ;; path segment. `..foo` -> `__foo`; `foo..bar` -> `foo__bar`.
        no-dot-run (str/replace safe-chars #"\.{2,}"
                                #(apply str (repeat (count %) "_")))
        ;; 4. single leading dot -> underscore (POSIX hidden-file
        ;; marker; the dot-run rule already handled `..`+).
        no-leading (str/replace no-dot-run #"^\." "_")
        ;; 5. trailing dot — Windows reserves names ending in `.`
        no-trail   (str/replace no-leading #"\.+$" "")
        ;; 6. length cap
        capped     (if (> (count no-trail) branch-name-max-length)
                     (subs no-trail 0 branch-name-max-length)
                     no-trail)]
    (when (str/blank? capped)
      (throw (ex-info "Branch name sanitized to empty string"
                      {:original branch-name})))
    capped))

;; ---------------------------------------------------------------------------
;; Legacy per-(job, build-number) workspace (kept for non-multibranch jobs)
;; ---------------------------------------------------------------------------

(defn workspace-path
  "Compute the workspace directory path for a build."
  [workspace-root job-id build-number]
  (let [raw-path (str workspace-root "/" job-id "/" build-number)]
    (validate-path workspace-root raw-path)))

(defn create-workspace
  "Create the workspace directory for a build. Returns the absolute path."
  [workspace-root job-id build-number]
  (let [path (workspace-path workspace-root job-id build-number)
        dir (io/file path)]
    (when-not (.exists dir)
      (when-not (.mkdirs dir)
        (throw (ex-info "Failed to create workspace directory" {:path path})))
      (log/info "Created workspace:" (.getAbsolutePath dir)))
    (.getAbsolutePath dir)))

(defn cleanup-workspace
  "Delete a workspace directory and all its contents.
   Uses walkFileTree to avoid following symlinks."
  [workspace-path]
  (let [dir (io/file workspace-path)
        dir-path (.toPath dir)]
    (when (.exists dir)
      (Files/walkFileTree dir-path
                          (proxy [SimpleFileVisitor] []
                            (visitFile [file _attrs]
                              (Files/deleteIfExists file)
                              FileVisitResult/CONTINUE)
                            (postVisitDirectory [dir _exc]
                              (Files/deleteIfExists dir)
                              FileVisitResult/CONTINUE)))
      (log/info "Cleaned up workspace:" workspace-path))))

(defn cleanup-old-workspaces
  "Clean up workspaces for a job, keeping the last n builds."
  [workspace-root job-id keep-last-n]
  (let [job-dir (io/file workspace-root job-id)]
    (when (.exists job-dir)
      (let [^"[Ljava.io.File;" files (or (.listFiles ^java.io.File job-dir)
                                         (into-array java.io.File []))
            build-dirs (->> files
                            (filter #(.isDirectory ^java.io.File %))
                            ;; The `branches/` subdir lives alongside per-build
                            ;; numeric directories on multibranch jobs. Skip
                            ;; it here so the legacy retention sweeper never
                            ;; mistakes it for a build dir to expire.
                            (remove #(= "branches" (.getName ^java.io.File %)))
                            (sort-by #(try (Long/parseLong (.getName ^java.io.File %))
                                           (catch NumberFormatException _ 0)))
                            reverse)
            to-delete (drop keep-last-n build-dirs)]
        (doseq [^java.io.File d to-delete]
          (cleanup-workspace (.getAbsolutePath d))
          (log/info "Cleaned old workspace:" (.getAbsolutePath d)))))))

;; ---------------------------------------------------------------------------
;; Per-(job, branch) workspace (CHG-FEAT-003 PR4)
;; ---------------------------------------------------------------------------

(def ^:private branches-subdir
  "Subdirectory name under `<root>/<job-id>/` that holds per-branch
   workspace trees. Kept as a named const so the retention sweep can
   target it without hardcoding the literal in two places."
  "branches")

(defn branch-workspace-path
  "Canonical absolute path for the `(job-id, branch-name)` workspace.

   `branch-name` is sanitized via `sanitize-branch-name` before being
   joined to the path — callers should NOT pre-sanitize, so the
   sanitization rules live in exactly one place.

   The returned path is canonicalized (via `getCanonicalPath`) AND
   validated to live under `workspace-root`. A path that resolves
   outside the root (e.g. through a symlink-poisoning attack on the
   workspace tree) raises `ex-info` with `:path-traversal`.

   Idempotent: same `(workspace-root, job-id, branch-name)` always
   returns the same string."
  [workspace-root job-id branch-name]
  (let [safe-branch (sanitize-branch-name branch-name)
        raw-path    (str workspace-root "/" job-id "/" branches-subdir "/" safe-branch)]
    (validate-path workspace-root raw-path)))

(defn allocate!
  "Ensure the per-(job, branch) workspace directory exists; return its
   absolute path.

   Idempotent + thread-safe: uses `Files/createDirectories`, which is
   documented to be safe under concurrent invocation (the JDK turns
   `EEXIST` into a no-op rather than an exception).

   The returned path is the same one `branch-workspace-path` would
   compute — re-allocating from another build on the same branch
   re-uses the existing tree, which is the point: subsequent builds
   do `git fetch` against the cached clone rather than re-cloning.

   After creation, we re-resolve the path with `.toRealPath()` (which
   follows symlinks) and verify it still lives under the canonical
   workspace root. This catches symlink-escape attacks where a
   pre-existing symlink at the target location points outside the
   root."
  [workspace-root job-id branch-name]
  (let [path (branch-workspace-path workspace-root job-id branch-name)
        ^Path nio-path (.toPath (io/file path))]
    (Files/createDirectories nio-path (into-array java.nio.file.attribute.FileAttribute []))
    (let [real-path (str (.toRealPath nio-path (into-array LinkOption [])))
          root-real (str (.toRealPath (.toPath (io/file workspace-root))
                                      (into-array LinkOption [])))]
      (when-not (.startsWith real-path root-real)
        (throw (ex-info "Workspace allocate! resolved outside workspace root (symlink escape?)"
                        {:workspace-root root-real :resolved-real-path real-path}))))
    path))

(defn release!
  "Optional cleanup hook for the per-(job, branch) workspace. Deletes
   the directory recursively. Default builds DO NOT call this — the
   workspace persists between builds for `git fetch` re-use. This is
   the escape valve for `--no-keep-workspace` or post-build cleanup
   when disk pressure outweighs the cache benefit.

   Safe to call when the directory doesn't exist (no-op)."
  [workspace-root job-id branch-name]
  (let [path (branch-workspace-path workspace-root job-id branch-name)]
    (cleanup-workspace path)))

(defn- parse-archived-at
  "Best-effort parse of the `archived_at` text column to an Instant.
   The column stores `yyyy-MM-dd HH:mm:ss.SSS` UTC (see migration 099
   commentary). Returns nil on parse failure so the caller can skip
   the row defensively instead of crashing the whole sweep."
  [s]
  (when (and s (string? s) (not (str/blank? s)))
    (try
      (let [iso (str (str/replace s #" " "T") "Z")]
        (Instant/parse iso))
      (catch Exception _ nil))))

(defn retention-sweep!
  "Delete per-(job, branch) workspace directories whose `job_branches`
   row was archived more than `retention-days` ago.

   Iterates the workspace tree (no full DB scan): for every
   `<root>/<job-id>/branches/<sanitized-branch>/` directory on disk,
   look up the corresponding row in `job_branches`. If the row is
   missing OR its `archived_at` is older than the cutoff, delete the
   directory. Active branches (`archived_at IS NULL`) are always
   preserved.

   Returns `{:cleaned <int> :scanned <int>}` for retention metrics.

   Important caveats:
     * Sanitization is one-way (`feature/foo` -> `feature_foo`), so we
       can't always recover the original branch name from a directory
       name. We work around this by listing the job's branches from
       the DB and matching by sanitized name. A directory with no
       matching branch row (orphan) IS swept after the retention
       window — these are stale entries from a job/branch deleted
       outside our control."
  [ds workspace-root retention-days]
  (let [ws-root (io/file workspace-root)
        cutoff  (.minusSeconds (Instant/now) (* (long retention-days) 24 60 60))
        cleaned (atom 0)
        scanned (atom 0)]
    (when (.exists ws-root)
      (doseq [^File job-dir (.listFiles ws-root)
              :when (and (.isDirectory job-dir)
                         (.exists (io/file job-dir branches-subdir)))]
        (let [job-id   (.getName job-dir)
              br-root  (io/file job-dir branches-subdir)
              all-rows (try
                         (job-branches-store/list-all-branches ds job-id)
                         (catch Exception e
                           (log/warn "retention-sweep: list-all-branches failed for job"
                                     job-id (.getMessage e))
                           []))
              by-sanit (into {} (for [row all-rows
                                      :let [n (:branch-name row)]
                                      :when (and n (not (str/blank? n)))]
                                  [(sanitize-branch-name n)
                                   (parse-archived-at (:archived-at row))]))]
          (doseq [^File br-dir (or (.listFiles br-root) (into-array File []))
                  :when (.isDirectory br-dir)]
            (swap! scanned inc)
            (let [seg     (.getName br-dir)
                  arch-at (get by-sanit seg ::orphan)
                  sweep?  (cond
                            ;; orphan dir — no matching row at all
                            (= arch-at ::orphan) true
                            ;; still active (archived_at IS NULL) -> keep
                            (nil? arch-at) false
                            ;; archived -> check the cutoff
                            :else (.isBefore ^Instant arch-at cutoff))]
              (when sweep?
                (try
                  (cleanup-workspace (.getAbsolutePath br-dir))
                  (swap! cleaned inc)
                  (catch Exception e
                    (log/warn "retention-sweep: failed to delete"
                              (.getAbsolutePath br-dir) (.getMessage e))))))))))
    {:cleaned @cleaned :scanned @scanned}))
