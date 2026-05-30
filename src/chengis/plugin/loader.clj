(ns chengis.plugin.loader
  "Plugin discovery and lifecycle management.
   Loads builtin plugins and external plugins from the plugins directory."
  (:require [chengis.db.plugin-policy-store :as plugin-policy-store]
            [chengis.plugin.manifest :as manifest]
            [chengis.plugin.registry :as registry]
            [chengis.plugin.sci :as plugin-sci]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Builtin plugin namespaces (always loaded)
;; ---------------------------------------------------------------------------

(def ^:private core-plugins
  "Core plugins that are always loaded (essential for basic operation)."
  ['chengis.plugin.builtin.shell
   'chengis.plugin.builtin.console-notifier
   'chengis.plugin.builtin.local-artifacts
   'chengis.plugin.builtin.git-scm
   'chengis.plugin.builtin.yaml-format
   'chengis.plugin.builtin.local-secrets])

(def ^:private optional-plugins
  "Optional plugins loaded only when their corresponding feature/config is active.
   Each entry is [namespace-symbol predicate-fn] where predicate-fn takes the config map."
  [['chengis.plugin.builtin.docker
    (fn [cfg] (or (get-in cfg [:docker :host])
                  (get-in cfg [:feature-flags :docker-layer-cache])))]
   ['chengis.plugin.builtin.slack-notifier
    (fn [cfg] (get-in cfg [:notifications :slack :default-webhook]))]
   ['chengis.plugin.builtin.email-notifier
    (fn [cfg] (get-in cfg [:notifications :email :host]))]
   ['chengis.plugin.builtin.github-status
    (fn [cfg] (or (get-in cfg [:scm :github :token])
                  (get-in cfg [:feature-flags :pr-status-checks])))]
   ['chengis.plugin.builtin.gitlab-status
    (fn [cfg] (or (get-in cfg [:scm :gitlab :token])
                  (get-in cfg [:feature-flags :pr-status-checks])))]
   ['chengis.plugin.builtin.vault-secrets
    (fn [cfg] (= "vault" (get-in cfg [:secrets :backend])))]])

;; ---------------------------------------------------------------------------
;; Plugin loading
;; ---------------------------------------------------------------------------

(defn- load-plugin-ns!
  "Require a plugin namespace and call its init! function."
  [ns-sym]
  (try
    (require ns-sym)
    (when-let [init-fn (resolve (symbol (str ns-sym) "init!"))]
      (init-fn)
      (log/debug "Loaded plugin:" ns-sym))
    true
    (catch Exception e
      (log/warn "Failed to load plugin" ns-sym ":" (.getMessage e))
      false)))

(defn- policy-trust-level
  "The authoritative trust ceiling for an external plugin, from policy.
   A policy with trust-level \"trusted\" (set by an admin; M2 will tie this to
   signing/provenance) permits the fast :trusted lane. Everything else —
   including the no-DB backward-compat path — caps at the hardened :sandboxed
   lane. The plugin's manifest can only lower this further (see manifest/
   effective-trust), never raise it."
  [ds plugin-name org-id]
  (let [policy (when ds (plugin-policy-store/get-plugin-policy ds plugin-name :org-id org-id))]
    (if (= "trusted" (:trust-level policy)) :trusted :sandboxed)))

(defn- load-external-plugins!
  "Load external plugins from the plugins directory through the SCI runtime.

   Each plugin is a .clj file evaluated by `chengis.plugin.sci/eval-plugin` —
   NOT `load-file`. The authoring contract is the SCI host API: a plugin calls
   `(chengis.plugin.host/register-* ...)` rather than defining a host namespace.
   Arbitrary in-process Clojure (the old `load-file` model) is no longer run.

   Trust routing:
   - When a datasource is provided, an explicit `allowed=true` policy is still
     required to load at all; trust-level then selects :trusted vs :sandboxed.
   - When no datasource is provided (backward compat), all plugins load but in
     the :sandboxed context — no DB means no trust, so nothing gets the fast lane.

   Capabilities and any self-imposed trust restriction come from an optional
   `<plugin>.edn` manifest (see `chengis.plugin.manifest`). The manifest can
   only narrow privilege, never widen it."
  [plugins-dir & {:keys [ds org-id secret-config]}]
  (let [^java.io.File dir (io/file plugins-dir)]
    (when (.isDirectory dir)
      (let [plugin-files (->> (or (.listFiles dir) (make-array java.io.File 0))
                              (filter (fn [^java.io.File f]
                                        (and (.isFile f)
                                             (.endsWith (.getName f) ".clj"))))
                              vec)]
        (when (seq plugin-files)
          (if ds
            (log/info "Loading" (count plugin-files) "external plugin(s) from" plugins-dir
                      "via SCI with trust policy enforcement")
            (log/warn "Loading" (count plugin-files) "external plugin(s) from" plugins-dir
                      "via SCI — no DB, so all run sandboxed.")))
        (doseq [^java.io.File f plugin-files]
          (let [plugin-name (str/replace (.getName f) #"\.clj$" "")]
            (if (and ds (not (plugin-policy-store/plugin-allowed? ds plugin-name :org-id org-id)))
              (log/warn "Blocked untrusted external plugin:" plugin-name
                        "— add to allowlist via Admin > Plugin Policies")
              (let [mf           (manifest/read-manifest f)
                    policy-trust (policy-trust-level ds plugin-name org-id)
                    trust        (manifest/effective-trust policy-trust (:requests-trust mf))
                    caps         (:capabilities mf)]
                (doseq [w (:warnings mf)]
                  (log/warn "Plugin manifest" (str plugin-name ":") w))
                (try
                  (plugin-sci/eval-plugin-file
                   (.getAbsolutePath f)
                   {:plugin-name plugin-name :trust trust :capabilities caps
                    :ds ds :org-id org-id :secret-config secret-config})
                  (log/info "Loaded external plugin:" (.getName f)
                            (str "[" (name trust)
                                 (when (seq caps) (str " caps=" (vec caps))) "]"))
                  (catch Exception e
                    (log/warn "Failed to load external plugin"
                              (.getName f) ":" (.getMessage e))))))))))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn load-plugins!
  "Load all plugins: core builtins first, then optional builtins (based on config),
   then external. Call this during system startup.
   When no system is provided (backward compat), loads all builtins."
  ([]
   (load-plugins! nil))
  ([system]
   (log/info "Loading plugins...")
   ;; Load core builtins (always needed)
   (doseq [ns-sym core-plugins]
     (load-plugin-ns! ns-sym))
   ;; Load optional builtins based on config
   (let [cfg (get system :config)]
     (if cfg
       ;; Config-aware lazy loading
       (doseq [[ns-sym pred-fn] optional-plugins]
         (if (pred-fn cfg)
           (load-plugin-ns! ns-sym)
           (log/debug "Skipping optional plugin:" ns-sym "(not configured)")))
       ;; No config available (backward compat) — load all optional plugins
       (doseq [[ns-sym _] optional-plugins]
         (load-plugin-ns! ns-sym))))
   ;; Load external plugins from configured directory (with trust policy enforcement)
   (when-let [plugins-dir (get-in system [:config :plugins :directory])]
     (load-external-plugins! plugins-dir
                             :ds (:db system) :org-id nil
                             :secret-config (:config system)))
   (let [summary (registry/registry-summary)]
     (log/info "Plugins loaded:"
               (:plugins summary) "plugins,"
               (count (:step-executors summary)) "step executors,"
               (count (:notifiers summary)) "notifiers,"
               (count (:pipeline-formats summary)) "formats")
     summary)))

(defn stop-plugins!
  "Stop all plugins. Call this during system shutdown."
  []
  (log/info "Stopping plugins...")
  (doseq [plugin (registry/list-plugins)]
    (when-let [stop-fn (:stop-fn plugin)]
      (try
        (stop-fn)
        (catch Exception e
          (log/warn "Error stopping plugin" (:name plugin) ":" (.getMessage e)))))))
