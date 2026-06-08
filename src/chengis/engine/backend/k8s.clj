(ns chengis.engine.backend.k8s
  "Kubernetes execution backend — CC2-EX6 / anvil v0.6 T1.1.

   Implements `chengis.engine.backend/ExecutionBackend` against the host
   `kubectl` CLI. Auth, cluster selection and credentials ride on the
   caller's kubeconfig — `KUBECONFIG` env, falling back to
   `~/.kube/config`. No separate auth model.

   ## Design

   Mirrors `chengis.engine.backend.docker`'s shape (CLI shell-out, no
   SDK dependency) so consuming products (anvil) can swap backends
   without learning a new API. The cost is one fork per kubectl call;
   for CI workloads where each step takes seconds-to-minutes that's
   negligible.

   ## Mode

   First cut: `:per-step` only (analogous to docker-backend's :per-step).
   Each step gets a fresh pod with `restartPolicy: Never`. The pod's
   container runs `sh -c '<command>'`, terminates, the backend collects
   the exit code + logs, then `kubectl delete pod`. Workspace is a
   fresh `emptyDir` per step — no cross-step state today. (`:per-build`
   mode with PVC-backed workspace is a follow-up; gated on real-world
   need from the wild-corpus heavies.)

   ## Resource limits

   The backend honors `:resource-limits` on construction:

     {:memory-mb LONG?    — sets requests.memory + limits.memory to <N>Mi
      :cpus DOUBLE?       — sets requests.cpu + limits.cpu to <N> (cores)
      :cpu-shares LONG?}  — IGNORED (Docker-only concept; no k8s analog)

   Pids-max is also dropped — k8s lacks a per-pod equivalent (cgroup
   v2 has `pids.max` at node level but no pod-spec field as of 1.31).

   ## :env / :user / :host-user?

   - `:env` on the step-spec → injected as container `env: [{name,value}]`
     entries. Backend env (from build-spec) merges underneath.
   - `:user` (string) → `securityContext.runAsUser` if the string is a
     plain integer; otherwise dropped with a warning (k8s expects a
     numeric uid, not a username).
   - `:host-user?` → auto-detect host uid via `id -u` and set
     `securityContext.runAsUser` to that uid. Default true, so files
     written via a hostPath / PVC land readable from the host. Set
     `:host-user? false` for images that require root for setup.

   ## Kubeconfig

   Resolution order at construction time:

     1. `:kubeconfig-path` in config — explicit operator override
     2. `KUBECONFIG` env var
     3. `~/.kube/config`

   The resolved path is passed via `KUBECONFIG=...` env to every
   `kubectl` invocation so the backend can run multi-cluster without
   the calling process changing its global env.

   ## Namespace

   `:namespace` config selects the k8s namespace. Defaults to
   `default`. The backend does NOT create the namespace — operators
   are responsible for provisioning it. Pod names are deterministically
   derived from (job-name, build-number) so cancellation can target
   by name.

   ## Cancellation

   `cancel` issues `kubectl delete pod --grace-period=N <name>`. k8s
   sends SIGTERM, waits the grace period, then SIGKILL. The default
   grace is 10s — same as the docker backend's `:cancel-grace-ms`.

   ## What this is NOT

   - Not a kubernetes client-go SDK. The kubectl CLI is the API.
   - Not a multi-cluster orchestrator. Operators pick the cluster
     via kubeconfig.
   - Not a workspace-PVC manager. First-cut pods get emptyDir.

   See anvil v0.6 board T1 + AV6-2 for the locked decision context."
  (:require [babashka.process :as bp]
            [chengis.engine.backend :as backend]
            [chengis.engine.log-masker :as masker]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Kubeconfig resolution
;; ---------------------------------------------------------------------------

(defn resolve-kubeconfig-path
  "Pick the kubeconfig path to use for kubectl invocations.

   Order: explicit `:kubeconfig-path` in config → `KUBECONFIG` env →
   `~/.kube/config`. Returns the path string regardless of whether the
   file exists; callers using `kubectl` will surface a clear error if
   the file is missing.

   `getenv` is parameterized so tests can pin without touching the JVM
   environment."
  ([config] (resolve-kubeconfig-path config #(System/getenv %)))
  ([config getenv]
   (or (:kubeconfig-path config)
       (let [env-val (getenv "KUBECONFIG")]
         (when-not (str/blank? env-val) env-val))
       (str (System/getProperty "user.home") "/.kube/config"))))

;; ---------------------------------------------------------------------------
;; CLI shelling
;; ---------------------------------------------------------------------------

(defn- run-kubectl
  "Invoke `kubectl` with `args` (vector) and return {:exit, :out, :err,
   :timed-out?}. The kubeconfig path is threaded via the `KUBECONFIG`
   env var so the backend's choice doesn't leak into the calling
   process's environment."
  [kubeconfig args & [{:keys [stdin timeout]}]]
  (let [env (merge (into {} (System/getenv))
                   {"KUBECONFIG" kubeconfig})
        opts (cond-> {:cmd (into ["kubectl"] args)
                      :out :string
                      :err :string
                      :extra-env env}
               stdin (assoc :in stdin))
        proc (bp/process opts)
        ^Process jproc (:proc proc)
        timed-out? (when timeout
                     (not (.waitFor jproc timeout TimeUnit/MILLISECONDS)))]
    (if timed-out?
      (do (try (bp/destroy-tree proc) (catch Exception _ nil))
          {:exit -1 :out "" :err (str "kubectl " (first args) " timed out")
           :timed-out? true})
      (let [c @proc]
        {:exit (:exit c) :out (:out c) :err (:err c) :timed-out? false}))))

(defn kubectl-available?
  "Returns true iff `kubectl version --client` exits 0. Does NOT
   verify cluster reachability — that surfaces at prepare-workspace
   time with a clearer message than 'kubectl not found'."
  ([] (kubectl-available? (resolve-kubeconfig-path {})))
  ([kubeconfig]
   (try
     (zero? (:exit (run-kubectl kubeconfig
                                ["version" "--client" "-o" "json"]
                                {:timeout 5000})))
     (catch Exception _ false))))

(defn cluster-reachable?
  "Returns true iff `kubectl cluster-info` exits 0 against the configured
   kubeconfig. Used by prepare-workspace to fail fast with a clear error."
  [kubeconfig]
  (try
    (zero? (:exit (run-kubectl kubeconfig
                               ["cluster-info"]
                               {:timeout 10000})))
    (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; Host-user detection — mirrors docker.clj for parity
;; ---------------------------------------------------------------------------

(defn- detect-host-uid-gid
  "Run `id -u` / `id -g` and return `[uid gid]` strings, or nil on
   platforms where either fails. Cached for JVM lifetime."
  []
  (try
    (let [run (fn [cmd]
                (let [pb (ProcessBuilder. ^java.util.List cmd)
                      _ (.redirectErrorStream pb true)
                      proc (.start pb)
                      out (slurp (.getInputStream proc))]
                  (.waitFor proc)
                  (str/trim out)))
          u (run ["id" "-u"])
          g (run ["id" "-g"])]
      (when (and (re-matches #"\d+" u) (re-matches #"\d+" g))
        [u g]))
    (catch Throwable _ nil)))

(def ^:private host-uid-gid (delay (detect-host-uid-gid)))

(defn- security-context
  "Build a pod securityContext map honoring :user / :host-user?. Returns
   nil when no user override applies (pod runs as image-default user)."
  [{:keys [user host-user?] :or {host-user? true}}]
  (cond
    (and (string? user) (re-matches #"\d+" user))
    {:runAsUser (Long/parseLong user)}

    (string? user)
    (do (log/warn (str "chengis.backend.k8s: :user='" user "' is not a "
                       "numeric uid; k8s securityContext.runAsUser ignored. "
                       "Use a numeric uid (e.g. '1000') or set "
                       ":host-user? true for auto-detect."))
        nil)

    host-user?
    (when-let [[u _g] @host-uid-gid]
      {:runAsUser (Long/parseLong u)})

    :else
    nil))

;; ---------------------------------------------------------------------------
;; Pod spec assembly
;; ---------------------------------------------------------------------------

(defn- resource-spec
  "Convert :resource-limits → k8s container resources map. Returns nil
   when no usable limit is set (so we don't emit empty `resources: {}`
   blocks)."
  [{:keys [memory-mb cpus]}]
  (let [m (cond-> {}
            memory-mb (assoc :memory (str memory-mb "Mi"))
            cpus      (assoc :cpu (str cpus)))]
    (when (seq m)
      ;; Same value for requests + limits — the docker backend
      ;; analog passes --memory= as a hard limit. Matching here.
      {:requests m :limits m})))

(defn- env-list
  "Convert env map → list of {:name :value} entries. Stable-ordered
   for reproducible pod specs (helps log diff + tests)."
  [env]
  (mapv (fn [[k v]] {:name (str k) :value (str v)})
        (sort-by key (or env {}))))

(defn pod-name
  "Deterministic per-build/step pod name. k8s names: lowercased,
   [a-z0-9-], ≤253 chars (we target ≤63 to be safe for service-DNS
   restrictions in case operators reuse the name). Includes a salt
   so step retries within a build don't collide."
  ([build-spec salt] (pod-name build-spec salt "step"))
  ([{:keys [job-name build-number]} salt suffix]
   (let [sanitize (fn [s]
                    (-> (str s)
                        (str/replace #"[^a-zA-Z0-9-]" "-")
                        (str/lower-case)
                        (str/replace #"-+" "-")
                        (str/replace #"^-|-$" "")))
         base (str "chengis-" (sanitize job-name)
                   "-" (or build-number 0)
                   "-" suffix
                   "-" salt)
         ;; k8s pod name max len = 63 chars (service DNS subdomain).
         trimmed (if (> (count base) 63)
                   (subs base 0 63)
                   base)]
     ;; If the trim left a trailing hyphen, drop it.
     (str/replace trimmed #"-$" ""))))

(defn- short-salt
  "Short randomized salt for pod names. Same shape as docker's
   short-salt — 6 hex chars."
  []
  (format "%06x" (rand-int 0xFFFFFF)))

(def ^:private workspace-mount-path
  "Path inside the pod where the workspace emptyDir is mounted. Matches
   the conventional Jenkins /home/jenkins/agent layout — pipelines
   that hardcode that path get it free. Operators with different
   conventions can override via :pod-template-yaml (not yet wired)."
  "/home/jenkins/agent")

(defn build-pod-spec
  "Build a k8s Pod manifest (Clojure map; encoded to JSON downstream)
   for a single step. Pure function — exposed for tests.

   Inputs:
     config     — the K8sBackend's config map (image, namespace,
                  resource-limits, user, host-user?, …)
     step-spec  — the per-step spec (command, env, …)
     pod        — the chosen pod name (see `pod-name`)
     env        — merged env map (build-spec env + step-spec env)

   The pod runs ONE container that executes `sh -c <command>` and
   terminates. `restartPolicy: Never` ensures k8s doesn't retry a
   failed step on us; we want the exit code straight through."
  [{:keys [image namespace image-pull-policy] :as config
    :or {namespace "default" image-pull-policy "IfNotPresent"}}
   {:keys [command]}
   pod
   env]
  (let [res (resource-spec (:resource-limits config))
        sec (security-context config)
        container (cond-> {:name "step"
                           :image image
                           :imagePullPolicy image-pull-policy
                           :command ["sh" "-c" command]
                           :workingDir workspace-mount-path
                           :env (env-list env)
                           :volumeMounts [{:name "workspace"
                                           :mountPath workspace-mount-path}]}
                    res (assoc :resources res))
        spec (cond-> {:restartPolicy "Never"
                      :containers [container]
                      :volumes [{:name "workspace"
                                 :emptyDir {}}]}
               sec (assoc :securityContext sec))]
    {:apiVersion "v1"
     :kind "Pod"
     :metadata {:name pod
                :namespace namespace
                :labels {:app "chengis-step"
                         :chengis.io/job-name (or (some-> (:job-name config) str) "")
                         :chengis.io/build-number (or (some-> (:build-number config) str) "")}}
     :spec spec}))

;; ---------------------------------------------------------------------------
;; Pod lifecycle
;; ---------------------------------------------------------------------------

(defn- apply-pod!
  "Send a Pod manifest to the cluster via `kubectl apply -f -`. Returns
   {:result :ok|:failed, :explain?}."
  [kubeconfig pod-manifest]
  (let [body (json/write-str pod-manifest)
        r (run-kubectl kubeconfig
                       ["apply" "-f" "-"]
                       {:stdin body :timeout 30000})]
    (if (zero? (:exit r))
      {:result :ok}
      {:result :failed
       :explain (str "kubectl apply failed: " (:err r))})))

(defn- wait-for-pod-phase
  "Poll `kubectl get pod -o jsonpath='{.status.phase}'` until phase is
   Succeeded or Failed, or until timeout elapses. Returns the final
   phase string (or :timed-out)."
  [kubeconfig namespace pod-name- timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (or timeout-ms 300000))]
    (loop []
      (let [r (run-kubectl kubeconfig
                           ["get" "pod" pod-name-
                            "-n" namespace
                            "-o" "jsonpath={.status.phase}"]
                           {:timeout 5000})
            phase (str/trim (or (:out r) ""))]
        (cond
          (and (zero? (:exit r))
               (contains? #{"Succeeded" "Failed"} phase))
          phase

          (>= (System/currentTimeMillis) deadline)
          :timed-out

          :else
          (do (Thread/sleep 500)
              (recur)))))))

(defn- pod-exit-code
  "Read the terminated exit code from the pod's container status. Returns
   the integer exit code, or nil if unparseable."
  [kubeconfig namespace pod-name-]
  (let [r (run-kubectl kubeconfig
                       ["get" "pod" pod-name-
                        "-n" namespace
                        "-o" "jsonpath={.status.containerStatuses[0].state.terminated.exitCode}"]
                       {:timeout 5000})
        s (str/trim (or (:out r) ""))]
    (when (re-matches #"-?\d+" s)
      (Long/parseLong s))))

(defn- pod-logs
  "Fetch container logs via `kubectl logs`. Returns {:out STRING}.
   Errors are swallowed and surfaced as an empty out — the caller's
   :stderr already carries the exit-code explain."
  [kubeconfig namespace pod-name-]
  (let [r (run-kubectl kubeconfig
                       ["logs" pod-name- "-n" namespace
                        "--tail=-1"]
                       {:timeout 30000})]
    (if (zero? (:exit r))
      {:out (or (:out r) "")}
      {:out ""})))

(defn- delete-pod!
  "Best-effort `kubectl delete pod`. Idempotent — missing pod is fine."
  [kubeconfig namespace pod-name- grace-ms]
  (try
    (run-kubectl kubeconfig
                 ["delete" "pod" pod-name-
                  "-n" namespace
                  (str "--grace-period=" (max 0 (long (/ (or grace-ms 10000) 1000))))
                  "--ignore-not-found=true"]
                 {:timeout 15000})
    nil
    (catch Exception e
      (log/warn "kubectl delete pod failed:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Disposable-pod step execution (per-step mode)
;; ---------------------------------------------------------------------------

(defn- run-disposable-pod
  "Single-shot pod for a step. Apply manifest, wait for terminal phase,
   pull logs + exit code, delete pod. Returns the execute-step result
   shape."
  [config kubeconfig {:keys [job-name build-number timeout mask-values]
                      :as step-spec}
   build-env]
  (let [namespace (or (:namespace config) "default")
        salt (short-salt)
        name (pod-name {:job-name job-name :build-number build-number} salt)
        merged-env (merge build-env (:env step-spec {}))
        config-with-job (assoc config :job-name job-name :build-number build-number)
        manifest (build-pod-spec config-with-job step-spec name merged-env)
        logged-command (cond-> (:command step-spec)
                         (seq mask-values) (masker/mask-secrets mask-values))
        _ (log/info "kubectl apply pod" name "ns=" namespace
                    "image=" (:image config) ":" logged-command)
        start (System/currentTimeMillis)
        applied (apply-pod! kubeconfig manifest)]
    (if (= :failed (:result applied))
      {:exit-code 125
       :stdout ""
       :stderr (:explain applied)
       :duration-ms (- (System/currentTimeMillis) start)
       :timed-out? false}
      (let [phase (wait-for-pod-phase kubeconfig namespace name (or timeout 300000))
            timed-out? (= phase :timed-out)
            logs (pod-logs kubeconfig namespace name)
            exit-code (or (pod-exit-code kubeconfig namespace name)
                          (case phase
                            "Succeeded" 0
                            "Failed" 1
                            (if timed-out? 124 -1)))
            _ (delete-pod! kubeconfig namespace name
                           (get config :cancel-grace-ms 10000))
            end (System/currentTimeMillis)
            base {:exit-code exit-code
                  :stdout (:out logs)
                  :stderr ""
                  :duration-ms (- end start)
                  :timed-out? (boolean timed-out?)}]
        (cond-> base
          (seq mask-values) (-> (update :stdout masker/mask-secrets mask-values)
                                (update :stderr masker/mask-secrets mask-values)))))))

;; ---------------------------------------------------------------------------
;; K8sBackend record
;; ---------------------------------------------------------------------------

(defrecord K8sBackend [config state kubeconfig]
  backend/ExecutionBackend
  (backend-name [_]
    (str "kubernetes:" (or (:namespace config) "default") "/" (:image config)))

  (prepare-workspace [_ {:keys [workspace-path job-name build-number env] :as _bs}]
    (cond
      (nil? workspace-path)
      {:result :failed :explain "workspace-path is required"}

      (not (kubectl-available? kubeconfig))
      {:result :failed
       :explain (str "kubectl CLI not available or kubeconfig unreadable "
                     "(KUBECONFIG=" kubeconfig "); backend cannot prepare workspace")}

      (not (cluster-reachable? kubeconfig))
      {:result :failed
       :explain (str "kubernetes cluster not reachable via kubeconfig "
                     kubeconfig "; check `kubectl cluster-info`")}

      :else
      (do
        ;; Per-step mode: no long-running pod to create. Record the
        ;; workspace + build env in state so execute-step can recover
        ;; them if callers don't thread them through.
        (.mkdirs (io/file workspace-path))
        (swap! state assoc [job-name build-number]
               {:workspace workspace-path
                :env (or env {})})
        {:result :ok
         :workspace workspace-path
         :backend-state {:mode :per-step
                         :workspace workspace-path
                         :env (or env {})}})))

  (execute-step [_ {:keys [job-name build-number backend-state] :as step-spec}]
    (let [tracked (get @state [job-name build-number])
          build-env (or (:env backend-state)
                        (:env tracked)
                        {})
          ws (or (:workspace backend-state)
                 (:workspace tracked)
                 (:dir step-spec))]
      (if (nil? ws)
        {:exit-code 125
         :stdout ""
         :stderr "no workspace — pass :dir or call prepare-workspace first"
         :duration-ms 0
         :timed-out? false}
        (run-disposable-pod config kubeconfig step-spec build-env))))

  (cleanup [_ {:keys [job-name build-number]}]
    (swap! state dissoc [job-name build-number])
    nil)

  (cancel [_ {:keys [job-name build-number]}]
    ;; Per-step pods are short-lived and named with a per-step salt, so
    ;; there's no single tracked name to delete. Best-effort: delete
    ;; ALL pods labeled with this job+build. The label selector matches
    ;; what build-pod-spec stamps on metadata.labels.
    (let [namespace (or (:namespace config) "default")
          selector (str "chengis.io/job-name=" (str job-name)
                        ",chengis.io/build-number=" (str (or build-number 0)))
          grace (get config :cancel-grace-ms 10000)
          grace-s (max 0 (long (/ grace 1000)))]
      (try
        (run-kubectl kubeconfig
                     ["delete" "pod"
                      "-n" namespace
                      "-l" selector
                      (str "--grace-period=" grace-s)
                      "--ignore-not-found=true"]
                     {:timeout 15000})
        (catch Exception e
          (log/warn "kubectl delete pod (cancel) failed:" (.getMessage e))))
      nil)))

(defn k8s-backend
  "Construct a K8sBackend.

   Required:
     :image STRING               — fully qualified container image, e.g.
                                    \"eclipse-temurin:21\"

   Optional:
     :mode :per-step             — currently the only supported mode
     :namespace STRING           — k8s namespace (default \"default\")
     :kubeconfig-path STRING     — explicit kubeconfig path. Falls back
                                    to KUBECONFIG env, then ~/.kube/config.
     :image-pull-policy STRING   — default \"IfNotPresent\"
     :resource-limits MAP        — {:memory-mb LONG? :cpus DOUBLE?}
     :cancel-grace-ms LONG       — grace before SIGKILL (default 10000)
     :host-user? BOOL            — default true. Pod's securityContext
                                    .runAsUser ← `id -u` on the controller.
                                    Set false for images that need root.
     :user STRING                — explicit numeric uid for runAsUser.
                                    Overrides :host-user? when present.

   Constructing the backend does NOT contact the cluster — that happens
   at `prepare-workspace`. Kubeconfig path resolution does happen at
   construction so it's stable per backend instance."
  [config]
  (when (nil? (:image config))
    (throw (ex-info "k8s-backend requires :image" {:config config})))
  (let [kubeconfig (resolve-kubeconfig-path config)]
    (->K8sBackend (merge {:mode :per-step :namespace "default"} config)
                  (atom {})
                  kubeconfig)))
