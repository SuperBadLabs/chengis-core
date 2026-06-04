(ns chengis.tools
  "Tool installer framework — CC2-EX3a.

   Why this exists
   ===============
   Jenkinsfiles call `tool('jdk_17_latest')` and `tool('maven_3_9_latest')`.
   anvil v0.3 returned `\"\"` for both. Downstream
   `withEnv([\"JAVA_HOME=${env.JAVA_HOME}\"])` then ran with an empty
   JAVA_HOME, and the build either silently used whatever JDK was on
   the PATH or failed in unintuitive ways.

   This namespace is the framework underneath a real tool resolution
   pipeline. v0.2 ships the framework + the cache contract + the descriptor
   format + a directory-pinned reference installer. The concrete per-tool
   installers (JDK, Maven, Gradle, Node, …) and the mise delegation each
   land as follow-on PRs that plug into this protocol.

   The contract
   ============
   A *descriptor* is the operator-facing string an end user wrote in their
   Jenkinsfile:

       \"jdk_17_latest\"
       \"maven_3_9_latest\"
       \"node:20.10.0\"

   `parse-descriptor` returns
     {:kind :jdk | :maven | :node | … :version STRING :variant STRING?}

   An *Installer* satisfies this protocol:

       supports?      — descriptor map → bool
       cache-key      — descriptor map → string (used as the cache dir name)
       install        — descriptor map → {:result :ok :path STRING}
                                       | {:result :failed :explain STRING}
                                       | {:result :unsupported :explain STRING}
       locate         — descriptor map → STRING? (returns nil when missing)

   `resolve!` walks the registry top-to-bottom and:
     1. parses the descriptor
     2. asks each registered installer that `supports?` the descriptor
        to `locate` (which is where the installer consults its own
        on-disk cache under `(cache-path … (cache-key inst desc))`),
        then `install` if `locate` returned nil
     3. validates the returned path is a non-blank string — buggy
        installers that return `\"\"` are caught at this boundary and
        the registry falls through to the next installer
     4. returns the resolved path OR an unresolved sentinel that EX2's
        classifier consumes as `:tool-unresolved`

   The unresolved sentinel
   =======================
   The board's headline rule: a tool that couldn't be resolved must NOT
   silently return \"\". `resolve!` returns
     {:result :ok :path STRING}
     or
     {:result :unresolved :explain STRING :descriptor STRING}
   and `chengis.engine.result/record-unresolved-tool` records the
   descriptor for terminal classification.

   Refs: docs/v0.2-board.md CC2-EX3."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Descriptor parsing
;; ---------------------------------------------------------------------------

(def ^:private jenkins-style-pattern
  ;; `jdk_17_latest`, `maven_3_9_latest`, `gradle_8_5`, `node_20_10_0`
  #"^([a-zA-Z][a-zA-Z0-9]*)_(.+?)(?:_latest)?$")

(def ^:private modern-pattern
  ;; `jdk:17`, `maven:3.9.6`, `node:20.10.0`, `python:3.12`
  #"^([a-zA-Z][a-zA-Z0-9]*):(.+)$")

(defn parse-descriptor
  "Convert an operator-facing descriptor string into a map.

   Both Jenkins-style (`jdk_17_latest`) and modern (`jdk:17`) shapes
   are accepted. Underscores in Jenkins-style version segments become
   dots — `maven_3_9_6` becomes version `3.9.6`.

   Returns
     nil                                — when input is blank, nil, or
                                          not a string. Callers treat
                                          this as :unresolved.
     {:kind KW :version STR :raw STR}   — parsed Jenkins-style or modern
                                          shape. The Jenkins-style branch
                                          additionally sets :latest? bool.
     {:kind :unknown :unparsed? true …} — parser-survived map for shapes
                                          that didn't match either grammar.
                                          `resolve!` treats this as
                                          :unparseable, not silent success."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (cond
        (str/blank? s) nil

        (re-matches modern-pattern s)
        (let [[_ kind ver] (re-matches modern-pattern s)]
          {:kind (keyword (str/lower-case kind))
           :version ver
           :raw s})

        (re-matches jenkins-style-pattern s)
        (let [[_ kind ver-segment] (re-matches jenkins-style-pattern s)
              ver (str/replace ver-segment "_" ".")]
          {:kind (keyword (str/lower-case kind))
           :version ver
           :latest? (str/ends-with? s "_latest")
           :raw s})

        :else
        {:kind :unknown :version "" :raw s :unparsed? true}))))

;; ---------------------------------------------------------------------------
;; Cache root
;;
;; The env and system-property lookups are deliberately routed through
;; defn indirections so tests can `with-redefs` them. Reading
;; `System/getenv` directly inline makes the env-override branch
;; non-deterministic in CI and dev shells.
;; ---------------------------------------------------------------------------

(defn getenv*
  "Indirection over `System/getenv`. Test seam — production behavior is
   unchanged."
  [name]
  (System/getenv name))

(defn getprop*
  "Indirection over `System/getProperty`. Test seam — production behavior
   is unchanged."
  [name]
  (System/getProperty name))

(defn default-cache-root
  "Returns the on-disk cache root for tool installations. Operators can
   override via `CHENGIS_TOOL_CACHE` env, otherwise `~/.chengis/tools/`."
  []
  (or (getenv* "CHENGIS_TOOL_CACHE")
      (str (getprop* "user.home") "/.chengis/tools")))

(defn- canonical-prefix?
  "True iff `child` resolves to a path under (or equal to) `parent` —
   i.e. no `..` escape. Compares canonical paths to defeat symlink and
   relative-segment trickery."
  [^String parent ^String child]
  (let [p (.getCanonicalPath (io/file parent))
        c (.getCanonicalPath (io/file child))]
    (or (= p c)
        (str/starts-with? c (str p java.io.File/separatorChar)))))

(defn cache-path
  "Return the canonical on-disk path for a descriptor under `root` (or
   `default-cache-root` if not given).

   Descriptors originate in Jenkinsfiles, which are
   attacker-controllable. `cache-path` enforces that the resolved path
   stays within `root` — a `cache-key` containing `..` segments would
   otherwise let an attacker write into the operator's filesystem
   outside the tool cache. On violation, throws ex-info with the
   offending key. This is the same posture as
   `chengis.engine.workspace/validate-path`."
  ([cache-key] (cache-path (default-cache-root) cache-key))
  ([root cache-key]
   (when (or (str/blank? root) (str/blank? cache-key))
     (throw (ex-info "cache-path requires non-blank root and cache-key"
                     {:root root :cache-key cache-key})))
   (let [combined (str root java.io.File/separatorChar cache-key)]
     (when-not (canonical-prefix? root combined)
       (throw (ex-info "cache-key would escape the cache root"
                       {:root root :cache-key cache-key
                        :resolved combined})))
     combined)))

;; ---------------------------------------------------------------------------
;; Installer protocol + registry
;; ---------------------------------------------------------------------------

(defprotocol Installer
  "An installer knows how to resolve one or more tool descriptor shapes.
   Implementations should be cheap to construct; expensive work happens
   in `install`."
  (installer-id [this]
    "Short identifier for logs + telemetry, e.g. :mise :temurin :nodejs-org")

  (supports? [this descriptor]
    "Returns true iff this installer can handle the descriptor.")

  (cache-key [this descriptor]
    "Stable cache key for the descriptor — used as the subdirectory name
     under (cache-root). Must be the same string across runs for the
     same descriptor.")

  (locate [this descriptor]
    "Return a path string if a previously-installed copy is available
     (typically under (cache-path …)), nil otherwise.")

  (install [this descriptor]
    "Install (download + extract + verify) the tool. Returns
       {:result :ok :path STRING}
       {:result :failed :explain STRING}
       {:result :unsupported :explain STRING}"))

(defonce ^:private registry (atom []))

(defn register-installer!
  "Append `inst` to the registry. Insertion order = resolution
   priority — earlier-registered installers are tried first."
  [inst]
  (swap! registry conj inst)
  (installer-id inst))

(defn registered-installers []
  (mapv installer-id @registry))

(defn clear-registry! [] (reset! registry []))

;; ---------------------------------------------------------------------------
;; Reference installer: DirPinned
;;
;; A trivial installer that resolves descriptors from a fixed operator-
;; provided directory. Useful for:
;;   1. tests
;;   2. operators who maintain their own tool tree out of band
;;      (e.g. a Nix-managed /opt/tools, an Ansible-provisioned /usr/local)
;;
;; Real installers (mise delegate, temurin, nodejs.org direct) ship in
;; follow-on PRs. The DirPinned installer is the receipt that the
;; protocol shape is correct end-to-end without needing network in the
;; default test path.
;; ---------------------------------------------------------------------------

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defrecord DirPinnedInstaller [config]
  Installer
  (installer-id [_] :dir-pinned)

  (supports? [_ {:keys [kind version]}]
    ;; A pin must be a non-blank string. A misconfigured `""` or nil pin
    ;; must NOT be considered supported — otherwise `locate` would treat
    ;; `(io/file "")` as the JVM's cwd and return the empty string as a
    ;; resolved path, violating the headline contract.
    (and (keyword? kind)
         (non-blank-string? version)
         (non-blank-string?
          (get-in config [:pins [kind version]]))))

  (cache-key [_ {:keys [kind version]}]
    (str "dir-pinned/" (name kind) "/" version))

  (locate [this descriptor]
    (when (supports? this descriptor)
      (let [pin (get-in config [:pins [(:kind descriptor) (:version descriptor)]])]
        (when (and (non-blank-string? pin)
                   (.exists (io/file pin)))
          pin))))

  (install [this descriptor]
    (if-let [p (locate this descriptor)]
      {:result :ok :path p}
      {:result :failed
       :explain (str "dir-pinned: descriptor "
                     (pr-str descriptor)
                     " points to a path that does not exist")})))

(defn dir-pinned-installer
  "Construct a DirPinnedInstaller. `pins` is a map keyed by
   [kind version] (e.g. [:jdk \"17\"]) to an absolute filesystem path."
  [{:keys [pins] :or {pins {}}}]
  (->DirPinnedInstaller {:pins pins}))

;; ---------------------------------------------------------------------------
;; resolve!
;; ---------------------------------------------------------------------------

(defn- unresolved
  ([descriptor explain] (unresolved descriptor explain nil))
  ([descriptor explain rule]
   {:result :unresolved
    :explain explain
    :descriptor (:raw descriptor)
    :rule rule}))

(defn resolve!
  "Resolve `desc-str` to a concrete on-disk path.

   Returns
     {:result :ok :path STRING :installer KEYWORD :cached? BOOL}
   or
     {:result :unresolved :explain STRING :descriptor STRING :rule KEYWORD?}.

   Never returns an empty string. Never throws on missing installers — the
   honest fallback is :unresolved, which the EX2 classifier maps to
   :failure via `record-unresolved-tool`."
  [desc-str]
  (let [d (parse-descriptor desc-str)]
    (cond
      (nil? d)
      (unresolved {:raw desc-str} "descriptor is blank or not a string"
                  :blank-descriptor)

      (:unparsed? d)
      (unresolved d (str "could not parse descriptor: " desc-str)
                  :unparseable)

      :else
      (let [supporters (filter #(supports? % d) @registry)]
        (if (empty? supporters)
          (unresolved
           d
           (str "no registered installer supports descriptor "
                (:kind d) " " (:version d))
           :no-installer)
          (loop [[inst & more] supporters]
            (if (nil? inst)
              (unresolved d
                          (str "all installers failed for "
                               (:raw d))
                          :install-failed)
              (let [from-cache (locate inst d)]
                (cond
                  ;; Honest non-blank cache hit.
                  (non-blank-string? from-cache)
                  {:result :ok :path from-cache
                   :installer (installer-id inst)
                   :cached? true}

                  ;; A buggy `locate` returned a non-string or blank
                  ;; string. Headline rule: never silently succeed.
                  ;; Fall through to install. Log and continue.
                  (some? from-cache)
                  (do (log/warn "installer"
                                (installer-id inst)
                                "locate returned non-string or blank path —"
                                "treating as miss:" (pr-str from-cache))
                      (recur more))

                  :else
                  (let [r (install inst d)]
                    (cond
                      (and (= :ok (:result r))
                           (non-blank-string? (:path r)))
                      (assoc r :installer (installer-id inst)
                             :cached? false)

                      ;; A buggy installer returned :ok with a blank
                      ;; :path. The framework MUST NOT propagate that.
                      ;; Treat as install-failed and fall through.
                      (= :ok (:result r))
                      (do (log/warn "installer"
                                    (installer-id inst)
                                    ":ok but :path was blank — treating as failed:"
                                    (pr-str r))
                          (recur more))

                      ;; on :failed or :unsupported, try next installer
                      :else
                      (do (log/info "installer"
                                    (installer-id inst)
                                    "could not install"
                                    (:raw d) "—"
                                    (:explain r))
                          (recur more)))))))))))))

(defn resolved-path
  "Convenience: return the path if `resolve!` succeeded, nil otherwise.
   Callers that just need 'the path or nothing' use this; callers that
   need to react to :unresolved (the executor's terminal step does) use
   `resolve!` directly."
  [desc-str]
  (let [r (resolve! desc-str)]
    (when (= :ok (:result r)) (:path r))))
