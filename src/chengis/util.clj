(ns chengis.util
  "Shared utility functions used across multiple modules."
  (:require [clojure.edn :as edn])
  (:import [java.util UUID]
           [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

(defn generate-id
  "Generate a random UUID string."
  []
  (str (UUID/randomUUID)))

(defn sha256-hex
  "Return the lowercase hex SHA-256 digest of a string.
   Used to hash high-entropy API tokens: a fast hash is cryptographically
   sufficient for random secrets, unlike bcrypt which only matters for
   low-entropy human passwords. Returns nil for nil input."
  [^String s]
  (when s
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes s StandardCharsets/UTF_8))
          sb (StringBuilder. (* 2 (alength digest)))]
      (doseq [b digest]
        (let [v (bit-and b 0xff)]
          (when (< v 16) (.append sb \0))
          (.append sb (Integer/toHexString v))))
      (.toString sb))))

(defn serialize-edn
  "Serialize a Clojure data structure to an EDN string. Returns nil for nil input."
  [data]
  (when data (pr-str data)))

(defn deserialize-edn
  "Deserialize an EDN string to a Clojure data structure. Returns nil for nil input."
  [s]
  (when s (edn/read-string s)))

(defn ensure-keyword
  "Coerce a status value to a keyword. Handles string, keyword, and nil inputs."
  [s]
  (cond
    (keyword? s) s
    (string? s)  (keyword s)
    :else        s))

(defn format-size
  "Format a byte count as a human-readable size string."
  [bytes]
  (cond
    (nil? bytes) "—"
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.1f MB" (/ bytes (* 1024.0 1024.0)))
    :else (format "%.2f GB" (/ bytes (* 1024.0 1024.0 1024.0)))))

(defn resolve-token
  "Resolve API token from config, with env fallback semantics.

   Rules:
   - :token key absent => fallback to env
   - :token key present and nil => fallback to env
   - :token key present and non-nil (including blank string) => use config value"
  [config env-var get-env-fn]
  (let [cfg (or config {})]
    (if (contains? cfg :token)
      (if (nil? (:token cfg))
        (get-env-fn env-var)
        (:token cfg))
      (get-env-fn env-var))))
