(ns chengis.tools.checksum
  "Streaming checksum verification for downloaded tool archives.

   Adoptium's API payload carries SHA-256 inline; Apache mirrors
   publish SHA-512 alongside; nodejs.org bundles SHA-256 in a
   SHASUMS file. So we need both SHA-256 and SHA-512, both computed
   streaming so a 200MB JDK doesn't load into memory just to verify.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [clojure.java.io :as io])
  (:import [java.io InputStream]
           [java.security MessageDigest]
           [javax.xml.bind DatatypeConverter]))

(defn- hex
  "Lowercase hex of a byte array. Matches the canonical form Adoptium /
   Apache / nodejs.org publish so string-equality works without
   case normalization at the call site."
  ^String [^bytes b]
  (let [s (DatatypeConverter/printHexBinary b)]
    (.toLowerCase s)))

(defn- digest!
  "Stream `file` through `MessageDigest/get-instance algo` and return
   the hex digest. 64KB read buffer — enough to keep the JIT-warm
   hash path saturated without blowing the small-object heap."
  [algo file]
  (with-open [^InputStream in (io/input-stream (io/file file))]
    (let [md (MessageDigest/getInstance algo)
          buf (byte-array 65536)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur))))
      (hex (.digest md)))))

(defn sha256
  "Compute the SHA-256 hex digest of `file`. Lowercase."
  [file]
  (digest! "SHA-256" file))

(defn sha512
  "Compute the SHA-512 hex digest of `file`. Lowercase."
  [file]
  (digest! "SHA-512" file))

(defn verify!
  "Verify `file`'s digest matches `expected` (hex, case-insensitive).
   `algo` is one of :sha256 :sha512.

   Returns the file's canonical path on match.
   Throws ex-info with :type :checksum/mismatch on miss, naming the
   expected vs got digest and the algo. Installers catch this and
   convert to `:result :failed :explain ...`.

   Throws ex-info :type :checksum/missing-expected if `expected` is
   blank — refuse to silently accept an arbitrary file just because
   the operator forgot to populate the expected digest."
  [file expected algo]
  (when (or (nil? expected)
            (not (string? expected))
            (= "" (clojure.string/trim expected)))
    (throw (ex-info "verify! requires a non-blank expected digest"
                    {:type :checksum/missing-expected
                     :file (str file) :algo algo})))
  (let [got (case algo
              :sha256 (sha256 file)
              :sha512 (sha512 file)
              (throw (ex-info (str "unknown checksum algo: " algo)
                              {:type :checksum/unknown-algo
                               :algo algo})))]
    (if (= (.toLowerCase ^String expected) got)
      (.getCanonicalPath (io/file file))
      (throw (ex-info (str algo " mismatch for " file)
                      {:type :checksum/mismatch
                       :file (str file)
                       :algo algo
                       :expected (.toLowerCase ^String expected)
                       :got got})))))
