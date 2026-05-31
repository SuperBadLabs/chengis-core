(ns chengis.plugin.loader
  "Plugin discovery and lifecycle management.
   Loads builtin plugins and external plugins from the plugins directory."
  (:require [chengis.db.plugin-policy-store :as plugin-policy-store]
            [chengis.plugin.manifest :as manifest]
            [chengis.plugin.registry :as registry]
            [chengis.plugin.sci :as plugin-sci]
            [chengis.plugin.signing :as signing]
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
  [plugins-dir & {:keys [ds org-id secret-config signing-keys require-signed?]}]
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
          ;; Per-plugin try wraps EVERYTHING (incl. the source/manifest reads), so
          ;; one unreadable/removed file is logged and skipped without aborting the
          ;; rest of the directory.
          (try
            (let [plugin-name (str/replace (.getName f) #"\.clj$" "")
                  policy      (when ds (plugin-policy-store/get-plugin-policy ds plugin-name :org-id org-id))]
              (cond
                ;; --- Policy gates first: decided from the policy row alone, BEFORE
                ;;     any file I/O, so blocked plugins never get read/verified. ---

                ;; Quarantine — hard block, regardless of allowlist/trust (M2b).
                (and policy (= 1 (:quarantined policy)))
                (log/warn "Blocked quarantined external plugin:" plugin-name
                          "—" (or (:quarantine-reason policy) "quarantined"))

                ;; Allowlist — an explicit allowed=true policy is required (with a DB).
                (and ds (not (= 1 (:allowed policy))))
                (log/warn "Blocked untrusted external plugin:" plugin-name
                          "— add to allowlist via Admin > Plugin Policies")

                :else
                ;; Cleared policy — now read source + manifest ONCE and verify +
                ;; evaluate exactly these bytes (no re-read between check and use,
                ;; TOCTOU-safe). The signature covers BOTH source and manifest, so
                ;; tampering either invalidates it.
                (let [source    (slurp f)
                      edn-file  (manifest/manifest-file f)
                      edn-str   (when (.isFile edn-file) (slurp edn-file))
                      sig       (signing/read-signature f)
                      ;; verifying-key-id returns the key-id of the active key
                      ;; that verified (provenance: 'who signed'), or nil. A
                      ;; revoked key never matches, so a plugin signed only by a
                      ;; now-revoked key reads as unsigned here.
                      signed-by (when sig
                                  (signing/verifying-key-id (.getBytes source "UTF-8")
                                                            (when edn-str (.getBytes edn-str "UTF-8"))
                                                            sig signing-keys))
                      signed?   (boolean signed-by)]
                  (cond
                    ;; A present-but-invalid signature is a tamper signal — refuse it
                    ;; outright. Never fall back to honoring its (now unverified)
                    ;; manifest, which could otherwise add capabilities like :secrets.
                    (and sig (not signed?))
                    (log/warn "Blocked external plugin with an invalid signature:" plugin-name
                              "— present but does not verify (tampered or wrong key)")

                    ;; require-signed mode — unsigned plugins are blocked entirely (M2b).
                    (and require-signed? (not signed?))
                    (log/warn "Blocked unsigned external plugin:" plugin-name
                              "— signing is required ([:plugins :signing :require-signed])")

                    :else
                    (let [mf           (manifest/parse edn-str plugin-name)
                          policy-trust (if (= "trusted" (:trust-level policy)) :trusted :sandboxed)
                          ;; Signing gates :trusted — a trusted policy without a valid
                          ;; signature is forced to :sandboxed (M2a). Policy alone can
                          ;; never grant the fast lane to unattested bytes.
                          gated-trust  (if (and (= policy-trust :trusted) (not signed?))
                                         (do (log/warn "Plugin" plugin-name
                                                       "has a trusted policy but no valid signature —"
                                                       "forcing :sandboxed (M2a)")
                                             :sandboxed)
                                         policy-trust)
                          trust        (manifest/effective-trust gated-trust (:requests-trust mf))
                          caps         (:capabilities mf)]
                      (doseq [w (:warnings mf)]
                        (log/warn "Plugin manifest" (str plugin-name ":") w))
                      (plugin-sci/eval-plugin
                       {:plugin-name plugin-name :source source :trust trust :capabilities caps
                        :ds ds :org-id org-id :secret-config secret-config})
                      ;; Capability-grant audit (M2c): record + log the effective grant.
                      (registry/record-grant! {:plugin plugin-name :trust trust
                                               :capabilities (vec caps) :signed? signed?
                                               :signed-by signed-by
                                               :org-id org-id})
                      (log/info "PLUGIN GRANT" plugin-name
                                {:trust trust :capabilities (vec caps)
                                 :signed? signed? :signed-by signed-by})
                      (log/info "Loaded external plugin:" (.getName f)
                                (str "[" (name trust)
                                     (when (seq caps) (str " caps=" (vec caps))) "]")))))))
            (catch Exception e
              (log/warn "Failed to load external plugin"
                        (.getName f) ":" (.getMessage e)))))))))

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
                             :secret-config (:config system)
                             :signing-keys (get-in system [:config :plugins :signing :public-keys])
                             :require-signed? (get-in system [:config :plugins :signing :require-signed])))
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
