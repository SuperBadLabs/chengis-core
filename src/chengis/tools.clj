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
     2. checks the on-disk cache under `(cache-root)/{cache-key}`
     3. asks each registered installer that `supports?` the descriptor
        to `locate`, then `install` if missing
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

   Returns nil for unrecognized shapes; callers should treat nil as
   :unresolved.

   Both Jenkins-style (`jdk_17_latest`) and modern (`jdk:17`) shapes
   are accepted. Underscores in Jenkins-style version segments become
   dots — `maven_3_9_6` becomes version `3.9.6`."
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
;; ---------------------------------------------------------------------------

(defn default-cache-root
  "Returns the on-disk cache root for tool installations. Operators can
   override via `CHENGIS_TOOL_CACHE` env, otherwise `~/.chengis/tools/`."
  []
  (or (System/getenv "CHENGIS_TOOL_CACHE")
      (str (System/getProperty "user.home") "/.chengis/tools")))

(defn cache-path
  "Return the canonical on-disk path for a descriptor under `root` (or
   `default-cache-root` if not given)."
  ([cache-key] (cache-path (default-cache-root) cache-key))
  ([root cache-key]
   (str root java.io.File/separatorChar cache-key)))

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

(defrecord DirPinnedInstaller [config]
  Installer
  (installer-id [_] :dir-pinned)

  (supports? [_ {:keys [kind version]}]
    (and (keyword? kind)
         (not (str/blank? version))
         (boolean (get-in config [:pins [kind version]]))))

  (cache-key [_ {:keys [kind version]}]
    (str "dir-pinned/" (name kind) "/" version))

  (locate [this descriptor]
    (when (supports? this descriptor)
      (let [pin (get-in config [:pins [(:kind descriptor) (:version descriptor)]])]
        (when (.exists (io/file pin))
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
                (if from-cache
                  {:result :ok :path from-cache
                   :installer (installer-id inst)
                   :cached? true}
                  (let [r (install inst d)]
                    (case (:result r)
                      :ok
                      (assoc r :installer (installer-id inst)
                             :cached? false)

                      ;; on :failed or :unsupported, try next installer
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
