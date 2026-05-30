(ns chengis.engine.artifacts
  "Build artifact collection and storage.
   Copies files matching glob patterns from the workspace to persistent storage."
  (:require [clojure.java.io :as io]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.nio.file FileSystems Files Path Paths]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]
           [java.io FileInputStream]))

(defn- normalize-glob-pattern
  "Lift the pattern normalization out of the walker so it's reusable
   when we compile multiple matchers up-front. See compile-matcher.
     - Patterns with path separators (e.g., 'target/foo/*.jar') are used as-is
     - Simple filename patterns (e.g., '*.jar') get prepended with **/
       so they match at any depth, using {*.jar,**/*.jar} to also match
       root-level files (Java's **/ requires at least one segment)."
  [^String pattern]
  (cond
    (.startsWith pattern "**/") pattern
    (.startsWith pattern "/")   pattern
    (.contains pattern "/")     pattern
    :else                       (str "{" pattern ",**/" pattern "}")))

(defn- compile-matcher
  "Build a Java PathMatcher for one glob pattern. Doing this once per
   pattern (rather than once per file) is what makes the multi-pattern
   walk cheap."
  ^java.nio.file.PathMatcher [^String pattern]
  (.getPathMatcher (FileSystems/getDefault)
                   (str "glob:" (normalize-glob-pattern pattern))))

(defn- collect-matching-files
  "Walk `dir` ONCE and return every file matching ANY of `patterns`,
   deduplicated and order-preserving by traversal order.

   The old code (glob-match called per pattern in a for-comprehension)
   walked the entire tree once per pattern — O(P × N) on pattern count
   × file count — and also collected a file once per matching pattern,
   so a file matching two patterns ended up copied twice into the
   artifact dir, with the second copy overwriting the first (extra
   I/O, duplicate hash computation, and a duplicate entry in the
   result vector). One walk + a HashSet for dedupe fixes both."
  [^String dir patterns]
  (let [base-path (Paths/get dir (into-array String []))
        matchers (mapv compile-matcher patterns)
        seen     (java.util.HashSet.)
        result   (java.util.ArrayList.)]
    (when (Files/exists base-path (into-array java.nio.file.LinkOption []))
      (Files/walkFileTree base-path
                          (proxy [java.nio.file.SimpleFileVisitor] []
                            (visitFile [^Path path ^BasicFileAttributes _attrs]
                              (let [relative (.relativize base-path path)]
              ;; `some` short-circuits on the first matching pattern so
              ;; common patterns at the head of the list pay the lowest
              ;; per-file cost.
                                (when (some (fn [^java.nio.file.PathMatcher m] (.matches m relative))
                                            matchers)
                                  (when (.add seen path)
                                    (.add result path))))
                              java.nio.file.FileVisitResult/CONTINUE)
                            (visitFileFailed [_path _exc]
                              java.nio.file.FileVisitResult/CONTINUE))))
    (vec result)))

(defn- content-type-for
  "Guess MIME type from filename extension."
  [filename]
  (cond
    (re-find #"\.jar$" filename)  "application/java-archive"
    (re-find #"\.zip$" filename)  "application/zip"
    (re-find #"\.tar\.gz$" filename) "application/gzip"
    (re-find #"\.html?$" filename) "text/html"
    (re-find #"\.json$" filename) "application/json"
    (re-find #"\.xml$" filename)  "application/xml"
    (re-find #"\.txt$" filename)  "text/plain"
    (re-find #"\.log$" filename)  "text/plain"
    (re-find #"\.css$" filename)  "text/css"
    (re-find #"\.js$" filename)   "application/javascript"
    (re-find #"\.pdf$" filename)  "application/pdf"
    (re-find #"\.png$" filename)  "image/png"
    (re-find #"\.jpg$" filename)  "image/jpeg"
    (re-find #"\.svg$" filename)  "image/svg+xml"
    :else "application/octet-stream"))

(def ^:private pre-compressed-content-types
  #{"application/java-archive"
    "application/zip"
    "application/gzip"
    "image/png"
    "image/jpeg"})

(defn pre-compressed?
  "True when the payload is already in a compressed wire format —
   gzipping it again wastes CPU and frequently grows the byte count.
   Used by artifact upload + serve to send raw bytes with
   Content-Encoding: identity for these MIME types."
  [^String content-type]
  (boolean (contains? pre-compressed-content-types content-type)))

;; Use shared util/format-size for human-readable byte formatting

(defn compute-sha256
  "Compute SHA-256 hash of a file. Returns lowercase hex string, or nil on error."
  [^java.io.File file]
  (try
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (with-open [fis (FileInputStream. file)]
        (loop []
          (let [n (.read fis buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur)))))
      (format "%064x" (BigInteger. 1 (.digest digest))))
    (catch Exception e
      (log/warn "Failed to compute SHA-256 for" (.getName file) ":" (.getMessage e))
      nil)))

(defn collect-artifacts!
  "Collect artifacts matching glob patterns from workspace to artifact directory.
   Returns a vector of {:filename :path :size-bytes :content-type} maps.

   Performs ONE filesystem walk regardless of how many patterns are
   supplied (see collect-matching-files). Each matching file is copied
   exactly once even when it matches multiple patterns — the old
   per-pattern loop would copy + hash duplicates."
  [workspace-dir artifact-dir patterns]
  (when (seq patterns)
    (let [dest (io/file artifact-dir)
          source-paths (collect-matching-files workspace-dir patterns)
          ws-base (Paths/get workspace-dir (into-array String []))]
      (.mkdirs dest)
      (log/info "Collecting artifacts to:" artifact-dir)
      (into []
            (for [^Path source-path source-paths]
              (let [source-file (.toFile source-path)
                    filename (.toString (.relativize ws-base source-path))
                ;; Flatten nested paths to avoid directory conflicts in artifact dir
                    flat-name (.replace filename "/" "_")
                    dest-file (io/file dest flat-name)]
            ;; Copy file to artifact directory
                (io/make-parents dest-file)
                (io/copy source-file dest-file)
                (let [size (.length dest-file)]
                  (log/info "  Artifact:" flat-name "(" (util/format-size size) ")")
                  {:filename flat-name
                   :original-path filename
                   :path (.getAbsolutePath dest-file)
                   :size-bytes size
                   :content-type (content-type-for flat-name)
                   :sha256-hash (compute-sha256 dest-file)})))))))
