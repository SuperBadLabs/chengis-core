(ns chengis.tools.platform
  "Platform detection — OS + architecture — used by tool installers
   that fetch upstream binaries.

   Routed through `tools/getprop*` indirections (same seam as the
   cache-root lookup) so tests can pin the platform to a known value
   without monkeypatching `System/getProperty`.

   v0.3 covers the platforms wild-corpus and dogfood actually run on:
   linux/macos × x64/aarch64. Windows is a known gap; an installer
   that needs to target Windows will short-circuit with an honest
   `:result :unsupported` rather than guessing a download URL.

   Refs: docs/v0.2-board.md CC2-EX3b — Concrete tool installers."
  (:require [chengis.tools :as tools]
            [clojure.string :as str]))

(defn os
  "Returns :linux, :mac, :windows, or :unknown.

   Reads `os.name` via `tools/getprop*` so tests can override. Uses
   the conventional case-insensitive substring match against the
   common values JVMs produce on each platform."
  []
  (let [n (str/lower-case (or (tools/getprop* "os.name") ""))]
    (cond
      (str/blank? n)                       :unknown
      (str/starts-with? n "linux")         :linux
      (str/starts-with? n "mac")           :mac
      (str/includes?    n "darwin")        :mac
      (str/starts-with? n "windows")       :windows
      :else                                :unknown)))

(defn arch
  "Returns :x64, :aarch64, or :unknown.

   Maps the standard `os.arch` values: amd64, x86_64 → :x64;
   aarch64, arm64 → :aarch64. Anything else is :unknown so installers
   can fail-loud."
  []
  (let [a (str/lower-case (or (tools/getprop* "os.arch") ""))]
    (case a
      ("amd64" "x86_64")                   :x64
      ("aarch64" "arm64")                  :aarch64
      :unknown)))

(defn supported?
  "True iff the current host is one the installer set can target."
  []
  (boolean
   (and (#{:linux :mac} (os))
        (#{:x64 :aarch64} (arch)))))
