(ns chengis.plugin.sci
  "SCI-based runtime for Chengis plugins — ONE runtime, TWO trust contexts.

   This is the trusted/first-party fast lane AND the simple-and-secure
   third-party lane, distinguished only by which context a plugin's source is
   evaluated in (the routing decision belongs to `plugin-policy-store`):

     :trusted    first-party / signed-by-us.
                 Java interop allowed, broad host API, native speed.

     :sandboxed  third-party / marketplace / unsigned.
                 NO Java interop, NO eval/load/require/slurp/spit, a curated
                 clojure.core subset, and host functions gated by a declared
                 capability set. Wrapped in a wall-clock timeout — at load AND
                 at callback-invocation time.

   SECURITY MODEL — read this before trusting it:
   The sandbox is secure BY CURATED SURFACE + signing/review, NOT by memory
   isolation. SCI evaluates in-process and shares the JVM heap. That is an
   acceptable, simple, secure design *only while the third-party tier stays a
   gated allowlist* (signed + reviewed, enforced by `plugin-policy-store`).
   Speed is deliberately NOT a priority for the sandboxed lane — we spend the
   slack on validation, copying, and limits. If Chengis ever needs to run
   anonymous, unreviewed, hostile code, that is the moment to add a hard
   memory-isolated runtime (WASM) — not before.

   Sandbox guarantees (and their limits):
   - Registrations are STAGED during evaluation and committed only if eval
     succeeds within the timeout — a plugin that registers then throws/hangs
     leaves the registry untouched.
   - A sandboxed plugin may NOT overwrite an existing registry key (it cannot
     hijack a builtin like :shell or another plugin's handler).
   - Sandboxed callbacks are wrapped in a wall-clock timeout at INVOCATION time,
     not just at load, so an allowed plugin can't hang the build worker.
   - Eval + callbacks run on a dedicated daemon thread pool, isolated from the
     app's shared future pool. LIMIT: the JVM cannot force-kill a thread, so a
     deliberately non-interruptible native loop leaks one daemon thread per
     occurrence (bounded blast radius, never blocks shutdown). SCI-interpreted
     loops DO observe interruption and stop.

   AUTHORING MODEL:
   Plugins do NOT implement host protocols directly (defrecord-against-a-host-
   protocol is fragile across the SCI boundary). Instead they call host
   registration functions with plain SCI functions, and this namespace wraps
   those functions in the real `chengis.plugin.protocol` reifications before
   handing them to `chengis.plugin.registry`. Example plugin source:

     (require '[chengis.plugin.host :as h])
     (h/register-notifier! :my-slack
       (fn [build-result config]
         (h/http-post (:webhook-url config) {:body (h/json-encode build-result)})
         {:status :sent :details \"posted\"}))"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [sci.core :as sci]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Callable Executors ThreadFactory TimeUnit TimeoutException
            ExecutionException]))

;; ---------------------------------------------------------------------------
;; Dedicated execution pool for plugin eval + sandboxed callbacks
;; ---------------------------------------------------------------------------
;; A dedicated, BOUNDED daemon pool (NOT clojure.core/future's shared
;; soloExecutor) so a runaway plugin can't pollute or exhaust the app-wide
;; future pool, and its threads never block JVM shutdown. The JVM can't
;; force-kill a thread, so a non-interruptible native loop leaks one daemon
;; thread here — but the FIXED pool size caps total threads: once that many are
;; stuck, further sandbox calls queue and time out (fail closed) rather than
;; spawning unbounded threads and exhausting the host.

(def ^:private plugin-pool-size
  (max 4 (* 2 (.. Runtime getRuntime availableProcessors))))

(def ^:private ^java.util.concurrent.ExecutorService plugin-executor
  (delay
    (Executors/newFixedThreadPool
     plugin-pool-size
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. ^Runnable r)
           (.setName "chengis-plugin-sandbox")
           (.setDaemon true)))))))

(defn- with-timeout
  "Run `thunk` on the plugin pool, aborting if it exceeds `ms`.
   Throws ex-info {:type :plugin/timeout} on overrun; unwraps task exceptions."
  [ms thunk]
  (let [fut (.submit @plugin-executor ^Callable (fn [] (thunk)))]
    (try
      (.get fut ms TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel fut true)
        (throw (ex-info "Plugin evaluation timed out"
                        {:type :plugin/timeout :timeout-ms ms})))
      (catch ExecutionException e
        (throw (or (.getCause e) e))))))

;; ---------------------------------------------------------------------------
;; Protocol wrappers — turn plain plugin fns into real host-protocol records
;; ---------------------------------------------------------------------------
;; A plugin author supplies a bare function; we reify the host protocol around
;; it. For sandboxed plugins the function is additionally guarded by a callback
;; timeout (see `guard-callback`) so a hung handler can't block the build worker.

(defn- guard-callback
  "For :sandboxed trust, wrap a 2-arg handler fn so each invocation is bounded
   by `call-timeout-ms`. Trusted (first-party) handlers run directly."
  [trust call-timeout-ms f]
  (if (= trust :sandboxed)
    (fn [a b] (with-timeout call-timeout-ms #(f a b)))
    f))

(defn- wrap-notifier [f]
  (reify proto/Notifier
    (send-notification [_ build-result config] (f build-result config))))

(defn- wrap-step-executor [f]
  (reify proto/StepExecutor
    (execute-step [_ build-ctx step-def] (f build-ctx step-def))))

(defn- wrap-status-reporter [f]
  (reify proto/ScmStatusReporter
    (report-status [_ build-info config] (f build-info config))))

;; ---------------------------------------------------------------------------
;; Capability-gated host functions
;; ---------------------------------------------------------------------------
;; The host API surface a plugin sees. Registration fns are always present but
;; only STAGE intents (committed post-eval). Side-effecting capabilities
;; (network, secrets) are present ONLY when the plugin's manifest declared them.

(def known-capabilities
  "Canonical set of declarable plugin capabilities — the single source of truth
   used by both this runtime and `chengis.plugin.manifest` validation. A plugin
   manifest lists a subset of these; the sandboxed host binds only the granted
   ones. Each capability maps to a curated host function (see `host-namespace`)."
  #{:http        ;; outbound HTTP (notifiers, status reporters)
    :secrets     ;; read secrets via the configured SecretBackend
    :log})       ;; structured logging

(defn- host-namespace
  "Build the `chengis.plugin.host` namespace map exposed to a plugin.
   `staging` is an atom collecting registration intents (committed post-eval).
   `granted` is the set of capabilities the policy/manifest allowed.
   `secret-config` is the real server config map — its `[:secrets :backend]`
   selects the backend and the backends themselves read their settings from it
   (e.g. Vault reads `[:secrets :vault]`, local reads `[:secrets :master-key]`)."
  [{:keys [plugin-name granted ds org-id staging secret-config]}]
  (let [grant?      (set granted)
        tag         (str "[plugin:" plugin-name "] ")
        backend-key (get-in secret-config [:secrets :backend] "local")
        stage!      (fn [kind key f] (swap! staging conj {:kind kind :key key :f f}) nil)
        always  {;; --- registration: STAGE only; commit happens post-eval ---
                 'register-notifier!
                 (fn [type f] (stage! :notifier type f))
                 'register-step-executor!
                 (fn [type f] (stage! :step-executor type f))
                 'register-status-reporter!
                 (fn [type f] (stage! :status-reporter type f))
                 ;; --- pure data helpers (safe, always available) ---
                 'json-encode (fn [x] (json/write-str x))
                 'json-decode (fn [s] (json/read-str s :key-fn keyword))}
        gated   (cond-> {}
                  (grant? :log)
                  (assoc 'log (fn [& args]
                                (log/info (str tag (str/join " " (map str args))))))

                  (grant? :http)
                  (assoc 'http-post
                         (fn [url opts]
                           @(http/post url (merge {:timeout 10000} opts)))
                         'http-get
                         (fn [url opts]
                           @(http/get url (merge {:timeout 10000} opts))))

                  (and (grant? :secrets) ds)
                  (assoc 'fetch-secret
                         (fn [secret-name]
                           (when-let [backend (registry/get-secret-backend backend-key)]
                             ;; backends read their settings from the real config;
                             ;; org-id scopes the lookup.
                             (proto/fetch-secret backend secret-name "global"
                                                 (cond-> (or secret-config {})
                                                   org-id (assoc :org-id org-id)))))))]
    (merge always gated)))

;; ---------------------------------------------------------------------------
;; Registration commit — collision-checked, trust-aware, post-eval
;; ---------------------------------------------------------------------------

(defn- registered?
  "Is a handler already registered for this kind+key?"
  [kind key]
  (case kind
    :notifier        (some? (registry/get-notifier key))
    :step-executor   (some? (registry/get-step-executor key))
    :status-reporter (some? (registry/get-status-reporter key))
    false))

(defn- commit-registrations!
  "Commit staged registration intents to the global registry. Called ONLY after
   a plugin evaluates successfully within its timeout (so a failed/partial load
   never mutates the registry). A :sandboxed plugin may not overwrite an existing
   key — that would let it hijack a builtin (:shell) or another plugin's handler.
   Sandboxed handlers are wrapped in a callback timeout."
  [staged {:keys [plugin-name trust call-timeout-ms]}]
  (doseq [{:keys [kind key f]} staged]
    (if (and (= trust :sandboxed) (registered? kind key))
      (log/warn "Refusing sandboxed plugin" plugin-name
                "attempt to override existing" (name kind) key)
      (let [g (guard-callback trust call-timeout-ms f)]
        (case kind
          :notifier        (registry/register-notifier! key (wrap-notifier g))
          :step-executor   (registry/register-step-executor! key (wrap-step-executor g))
          :status-reporter (registry/register-status-reporter! key (wrap-status-reporter g)))
        (log/debug "Registered" (name kind) key "from plugin" plugin-name)))))

;; ---------------------------------------------------------------------------
;; Context construction
;; ---------------------------------------------------------------------------

(def ^:private sandbox-deny
  "clojure.core symbols denied in the sandboxed context. Interop is already
   off (no :classes), so this closes the remaining doors:
   - code loading / eval (eval, load*, read-string round-tripped to code)
   - var surgery (intern, alter-var-root, with-redefs)
   - filesystem (slurp, spit, file-seq)
   - thread spawning that would escape the wall-clock timeout (future, agent…)
   NOTE: `require`/`use`/`refer` are intentionally ALLOWED — SCI's require
   only resolves context-registered namespaces (e.g. chengis.plugin.host),
   never the JVM classpath, so it cannot be used to break out."
  '[eval load-file load-string load-reader load
    intern alter-var-root with-redefs with-redefs-fn
    slurp spit file-seq read read-string read+string
    future future-call agent send send-off pmap pcalls pvalues
    add-tap remove-tap tap>])

(defn- build-context
  "Construct an SCI context for the given trust level.
     :sandboxed — no :classes (interop off), curated core (deny-list), host ns
                  with only granted capabilities.
     :trusted   — interop on (broad :classes), full host ns."
  [{:keys [plugin-name trust granted ds org-id staging secret-config]
    :or   {trust :sandboxed granted #{}}}]
  (let [host (host-namespace {:plugin-name plugin-name
                              :granted (if (= trust :trusted) known-capabilities granted)
                              :ds ds :org-id org-id :staging staging
                              :secret-config secret-config})]
    (case trust
      :trusted
      (sci/init
       {:namespaces {'chengis.plugin.host host}
        ;; first-party code is allowed interop with these host classes.
        ;; (Expand this allowlist as real first-party plugins need it — the
        ;; trusted lane is a curated allowlist too, just a generous one.)
        :classes {'System   java.lang.System
                  'Math     java.lang.Math
                  'String   java.lang.String
                  'Long     java.lang.Long
                  'Integer  java.lang.Integer
                  'Double   java.lang.Double
                  'Boolean  java.lang.Boolean
                  'Object   java.lang.Object
                  'Throwable java.lang.Throwable}})

      ;; default: sandboxed
      (sci/init
       {:namespaces {'chengis.plugin.host host}
        :deny sandbox-deny
        ;; no :classes key => Java interop is unavailable in the sandbox
        }))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn eval-plugin
  "Evaluate a plugin's Clojure `source` string in an SCI context.

   opts:
     :plugin-name     string, for logging/attribution        (required)
     :source          plugin Clojure source string           (required)
     :trust           :trusted | :sandboxed   (default :sandboxed)
     :capabilities    set of declared capabilities (sandboxed only;
                      :trusted gets all of `known-capabilities`)
     :timeout-ms      wall-clock cap for sandboxed eval (default 5000;
                      :trusted runs inline, untimed)
     :call-timeout-ms wall-clock cap per sandboxed callback invocation
                      (default 60000; ignored for :trusted)
     :ds :org-id      passed to capability-gated host fns (e.g. :secrets)
     :secret-config   the real server config map; its [:secrets :backend] selects
                      the backend and backends read their settings from it

   Registrations made by the plugin are STAGED and committed to
   `chengis.plugin.registry` only if evaluation succeeds within the timeout.
   Returns the value of the last form evaluated."
  [{:keys [plugin-name source trust capabilities timeout-ms call-timeout-ms ds org-id secret-config]
    :or   {trust :sandboxed capabilities #{} timeout-ms 5000 call-timeout-ms 60000}}]
  (when (str/blank? plugin-name)
    (throw (ex-info "eval-plugin requires :plugin-name" {})))
  (let [bad (seq (remove known-capabilities capabilities))]
    (when bad
      (log/warn "Plugin" plugin-name "declared unknown capabilities (ignored):" (vec bad))))
  (let [staging (atom [])
        ctx     (build-context {:plugin-name plugin-name :trust trust
                                :granted (set capabilities) :ds ds :org-id org-id
                                :staging staging :secret-config secret-config})
        run     (fn [] (sci/eval-string* ctx source))]
    (log/info "Evaluating plugin" plugin-name "in" (name trust) "context"
              (when (= trust :sandboxed)
                (str "(caps " (vec capabilities) ", eval " timeout-ms "ms, call " call-timeout-ms "ms)")))
    (let [result
          (try
            (if (= trust :trusted)
              (run)
              (with-timeout timeout-ms run))
            (catch clojure.lang.ExceptionInfo e
              (log/warn "Plugin" plugin-name "evaluation failed (no registrations committed):"
                        (.getMessage e))
              (throw e))
            (catch Exception e
              (log/warn "Plugin" plugin-name "evaluation failed (no registrations committed):"
                        (.getMessage e))
              (throw (ex-info (str "Plugin evaluation failed: " (.getMessage e))
                              {:type :plugin/eval-error :plugin-name plugin-name} e))))]
      ;; Eval succeeded within the timeout — now commit the staged registrations.
      (commit-registrations! @staging {:plugin-name plugin-name :trust trust
                                       :call-timeout-ms call-timeout-ms})
      result)))

(defn eval-plugin-file
  "Convenience: read a .clj file and eval its contents via `eval-plugin`.
   `opts` is merged into the `eval-plugin` arg map (so pass :trust,
   :capabilities, :ds, :org-id, :secret-config here)."
  [path opts]
  (let [source (slurp path)
        name*  (-> (str path) (str/replace #".*/" "") (str/replace #"\.clj$" ""))]
    (eval-plugin (merge {:plugin-name name* :source source} opts))))
