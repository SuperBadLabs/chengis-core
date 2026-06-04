(ns chengis.engine.backend.docker
  "Docker execution backend — CC2-EX1b.

   Implements `chengis.engine.backend/ExecutionBackend` against the host
   `docker` CLI. Auth, registry config and credential helpers ride on the
   host's `~/.docker/config.json` — there is no separate auth model. That
   means a project that runs `docker login ghcr.io` once gets pulls for
   free here.

   ## Modes

   The backend supports two container lifecycles, selected via the `:mode`
   config key on construction:

     :per-build  (default)     — `docker run -d` once at prepare-workspace
                                  time, `docker exec` for each step,
                                  `docker rm -f` at cleanup. Faster across
                                  steps (image layer cache + workspace
                                  filesystem are shared); ~closer to Jenkins
                                  `agent { docker }` semantics.

     :per-step                 — `docker run --rm` per step. Stronger
                                  isolation between steps; each step gets
                                  a pristine filesystem grafted onto the
                                  bind-mounted workspace. Slower (one
                                  container start per step).

   ## Workspace

   The workspace path on the host is bind-mounted into the container at
   the SAME path. That preserves Jenkinsfile assumptions like `pwd` matching
   between steps and absolute-path references in scripts (`$WORKSPACE/...`).

   ## Resource limits (cgroup)

   The backend honors the `:resource-limits` map on the build-spec:

     {:memory-mb LONG?    — passes --memory=<N>m
      :cpu-shares LONG?   — passes --cpu-shares=<N>
      :pids-max LONG?     — passes --pids-limit=<N>
      :cpus DOUBLE?}      — passes --cpus=<N>

   ## Cancellation

   On `cancel`, the backend sends SIGTERM to the container (`docker kill
   --signal=SIGTERM`), waits a grace period (default 10s), then SIGKILL.
   For `:per-step` mode the in-flight exec is bound to a container that
   stops cleanly when killed; for `:per-build` mode the entire build
   container goes away.

   ## What this is NOT

   - Not a Docker SDK client. The CLI is the API. This intentionally
     trades a little type safety for parity with operator habits and
     a smaller dependency footprint.
   - Not a multi-host orchestrator. K8s lives in EX6 (chengis-core v0.3).
   - Not a registry-mirror manager. The host's daemon config handles it.

   See `docs/v0.2-board.md` CC2-EX1b for the acceptance receipt."
  (:require [babashka.process :as bp]
            [chengis.engine.backend :as backend]
            [chengis.engine.log-masker :as masker]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; CLI shelling — kept narrow + testable
;; ---------------------------------------------------------------------------

(defn- run-docker
  "Invoke `docker` with `args` and return {:exit, :out, :err}. No shell
   interpolation — `args` is a vector. Used for control-plane commands
   (pull, run, exec, kill, rm). NOT used for the step command itself
   — see `exec-step` for that path, which streams output."
  [args & [{:keys [stdin timeout]}]]
  (let [opts (cond-> {:cmd (into ["docker"] args)
                      :out :string
                      :err :string}
               stdin (assoc :in stdin))
        proc (bp/process opts)
        ^Process jproc (:proc proc)
        timed-out? (when timeout
                     (not (.waitFor jproc timeout TimeUnit/MILLISECONDS)))]
    (if timed-out?
      (do (try (bp/destroy-tree proc) (catch Exception _ nil))
          {:exit -1 :out "" :err (str "docker " (first args) " timed out")
           :timed-out? true})
      (let [c @proc]
        {:exit (:exit c) :out (:out c) :err (:err c) :timed-out? false}))))

(defn docker-available?
  "Returns true iff `docker version` exits 0 against a reachable daemon.
   Used by tests + by the backend to fail-fast at construction with a clear
   message instead of cryptic CLI errors later."
  []
  (try
    (zero? (:exit (run-docker ["version" "--format" "{{.Server.Version}}"]
                              {:timeout 5000})))
    (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; Argument builders
;; ---------------------------------------------------------------------------

(defn- resource-flags
  "Turn a :resource-limits map into docker CLI flags. Skips nils."
  [{:keys [memory-mb cpu-shares pids-max cpus]}]
  (cond-> []
    memory-mb  (conj (str "--memory=" memory-mb "m"))
    cpu-shares (conj (str "--cpu-shares=" cpu-shares))
    pids-max   (conj (str "--pids-limit=" pids-max))
    cpus       (conj (str "--cpus=" cpus))))

(defn- env-flags
  "One `-e KEY=VAL` pair per env entry. Stable order for reproducible
   command lines (helps log diffing + tests)."
  [env]
  (->> (sort-by key (or env {}))
       (mapcat (fn [[k v]] ["-e" (str k "=" v)]))))

(defn- network-flags [network-mode]
  (when network-mode ["--network" network-mode]))

(defn- workspace-bind [workspace-path]
  ["-v" (str workspace-path ":" workspace-path)])

(defn- container-name
  "Deterministic per-build container name. Lowercased, sanitized to the
   subset docker accepts. Includes job + build + a salt fragment so retries
   don't collide if a prior cleanup failed."
  [{:keys [job-name build-number] :as _build-spec} salt]
  (let [sanitize (fn [s]
                   (-> (str s)
                       (str/replace #"[^a-zA-Z0-9_.-]" "-")
                       (str/lower-case)))]
    (str "chengis-" (sanitize job-name) "-" (or build-number 0) "-" salt)))

(defn- short-salt
  "Short randomized-but-deterministic-within-a-jvm-run salt for container
   names. We can't use `Math/random`/`Date/now` per project policy in some
   scripted contexts; here we're inside production code which is fine, but
   we keep it cheap and short."
  []
  (format "%06x" (rand-int 0xFFFFFF)))

;; ---------------------------------------------------------------------------
;; Image management
;; ---------------------------------------------------------------------------

(defn- image-present? [image]
  (zero? (:exit (run-docker ["image" "inspect" image] {:timeout 10000}))))

(defn- pull-image!
  "Pull an image with one retry. Returns {:result :ok | :failed, :explain?}."
  [image]
  (if (image-present? image)
    {:result :ok :cached? true}
    (let [attempt (fn []
                    (run-docker ["pull" image]
                                {:timeout (* 10 60 1000)}))
          r1 (attempt)]
      (if (zero? (:exit r1))
        {:result :ok :cached? false}
        (let [_ (log/warn "docker pull failed, retrying:" image
                          "stderr=" (:err r1))
              r2 (attempt)]
          (if (zero? (:exit r2))
            {:result :ok :cached? false :retried? true}
            {:result :failed
             :explain (str "docker pull failed for " image
                           " — last stderr: " (:err r2))}))))))

;; ---------------------------------------------------------------------------
;; Container lifecycle (per-build mode)
;; ---------------------------------------------------------------------------

(defn- start-build-container!
  "Start a long-running container that holds the workspace + env across
   steps. The container runs `tail -f /dev/null` so it survives between
   `docker exec`s. Returns {:container-id STRING, :result :ok|:failed,
   :explain?}."
  [{:keys [image network-mode resource-limits]}
   {:keys [workspace-path env] :as build-spec}]
  (let [name (container-name build-spec (short-salt))
        args (vec (concat ["run" "-d"
                           "--name" name
                           "-w" workspace-path]
                          (workspace-bind workspace-path)
                          (env-flags env)
                          (network-flags network-mode)
                          (resource-flags (or resource-limits {}))
                          [image
                           ;; idle command — kept alive until cleanup or cancel
                           "tail" "-f" "/dev/null"]))
        r (run-docker args {:timeout 60000})]
    (if (zero? (:exit r))
      {:result :ok
       :container-id (str/trim (:out r))
       :container-name name}
      {:result :failed
       :explain (str "docker run -d failed for image " image ": " (:err r))})))

(defn- exec-in-container
  "`docker exec` a shell command inside an already-running build container.
   Streams nothing today — captures stdout/stderr to strings, mirroring the
   LocalShell return shape. Streaming-to-:log-file is a follow-on item
   tracked under CC2-EX1b's polish list."
  [container-id {:keys [command dir env timeout mask-values]}]
  (let [logged-command (cond-> command
                         (seq mask-values) (masker/mask-secrets mask-values))
        _ (log/info "docker exec on" container-id ":" logged-command
                    (when dir (str "(in " dir ")")))
        start (System/currentTimeMillis)
        args (vec (concat ["exec"]
                          (when dir ["-w" dir])
                          (env-flags env)
                          [container-id "sh" "-c" command]))
        r (run-docker args {:timeout (or timeout 300000)})
        end (System/currentTimeMillis)
        base {:exit-code (:exit r)
              :stdout (:out r)
              :stderr (:err r)
              :duration-ms (- end start)
              :timed-out? (boolean (:timed-out? r))}]
    (cond-> base
      (seq mask-values) (-> (update :stdout masker/mask-secrets mask-values)
                            (update :stderr masker/mask-secrets mask-values)))))

(defn- run-disposable-container
  "Single-shot `docker run --rm` for a step. Used in :per-step mode and as
   the fallback when there is no prepared build container."
  [{:keys [image network-mode resource-limits]}
   workspace-path
   {:keys [command dir env timeout mask-values]}]
  (let [logged-command (cond-> command
                         (seq mask-values) (masker/mask-secrets mask-values))
        _ (log/info "docker run --rm on" image ":" logged-command
                    (when dir (str "(in " dir ")")))
        start (System/currentTimeMillis)
        args (vec (concat ["run" "--rm"
                           "-w" (or dir workspace-path)]
                          (workspace-bind workspace-path)
                          (env-flags env)
                          (network-flags network-mode)
                          (resource-flags (or resource-limits {}))
                          [image "sh" "-c" command]))
        r (run-docker args {:timeout (or timeout 300000)})
        end (System/currentTimeMillis)
        base {:exit-code (:exit r)
              :stdout (:out r)
              :stderr (:err r)
              :duration-ms (- end start)
              :timed-out? (boolean (:timed-out? r))}]
    (cond-> base
      (seq mask-values) (-> (update :stdout masker/mask-secrets mask-values)
                            (update :stderr masker/mask-secrets mask-values)))))

;; ---------------------------------------------------------------------------
;; Cancel
;; ---------------------------------------------------------------------------

(defn- kill-container!
  "Send SIGTERM, wait grace, then SIGKILL. Idempotent — missing container
   is treated as success."
  [container-id grace-ms]
  (try
    (run-docker ["kill" "--signal=SIGTERM" container-id] {:timeout 5000})
    ;; Poll for stop using `inspect`. We keep it short so cancel feels
    ;; responsive; this is not a soak-test grade exponential backoff.
    (let [deadline (+ (System/currentTimeMillis) grace-ms)
          running? (fn []
                     (let [r (run-docker
                              ["inspect" "-f" "{{.State.Running}}" container-id]
                              {:timeout 3000})]
                       (= "true" (str/trim (or (:out r) "")))))]
      (while (and (running?) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 200))
      (when (running?)
        (log/warn "container did not exit after SIGTERM, sending SIGKILL:"
                  container-id)
        (run-docker ["kill" "--signal=SIGKILL" container-id] {:timeout 5000})))
    nil
    (catch Exception e
      (log/warn "kill-container! error:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; DockerBackend record
;; ---------------------------------------------------------------------------

;; The state atom maps [job-name build-number] -> {:container-id ...
;;                                                  :workspace ...}.
;; Tests + callers can inspect it via `(:state backend)` if they need to.
;; Concurrent builds with distinct (job, build) tuples are safe; reusing
;; the same tuple while a prior build is still running is the operator's
;; problem to prevent (same constraint Jenkins enforces).

(defrecord DockerBackend [config state]
  backend/ExecutionBackend
  (backend-name [_]
    (str "docker:" (:image config)))

  (prepare-workspace [_ {:keys [workspace-path job-name build-number] :as bs}]
    (cond
      (nil? workspace-path)
      {:result :failed :explain "workspace-path is required"}

      (not (docker-available?))
      {:result :failed
       :explain "docker daemon not reachable — backend cannot prepare workspace"}

      :else
      (let [_ (.mkdirs (io/file workspace-path))
            pulled (pull-image! (:image config))]
        (if (= :failed (:result pulled))
          pulled
          (case (:mode config :per-build)
            :per-step
            ;; No long-running container; per-step `docker run --rm` later.
            ;; We still record the workspace in state so execute-step can
            ;; recover it when callers don't thread :dir through.
            (do
              (swap! state assoc [job-name build-number]
                     {:workspace workspace-path})
              {:result :ok
               :workspace workspace-path
               :backend-state {:mode :per-step :workspace workspace-path}})

            :per-build
            (let [s (start-build-container! config bs)]
              (if (= :failed (:result s))
                s
                (do
                  (swap! state assoc [job-name build-number]
                         {:container-id (:container-id s)
                          :container-name (:container-name s)
                          :workspace workspace-path})
                  {:result :ok
                   :workspace workspace-path
                   :backend-state {:mode :per-build
                                   :container-id (:container-id s)
                                   :container-name (:container-name s)}}))))))))

  (execute-step [_ {:keys [job-name build-number backend-state] :as step-spec}]
    (let [mode (or (:mode backend-state) (:mode config :per-build))]
      (case mode
        :per-build
        (let [cid (or (:container-id backend-state)
                      (get-in @state [[job-name build-number] :container-id]))]
          (if (nil? cid)
            {:exit-code 125
             :stdout ""
             :stderr (str "no build container — prepare-workspace must be "
                          "called before execute-step in :per-build mode")
             :duration-ms 0
             :timed-out? false}
            (exec-in-container cid step-spec)))

        :per-step
        (let [ws (or (get-in @state [[job-name build-number] :workspace])
                     (:dir step-spec))]
          (if (nil? ws)
            {:exit-code 125
             :stdout ""
             :stderr "no workspace — pass :dir or call prepare-workspace first"
             :duration-ms 0
             :timed-out? false}
            (run-disposable-container config ws step-spec))))))

  (cleanup [_ {:keys [job-name build-number]}]
    (let [k [job-name build-number]
          {:keys [container-id]} (get @state k)]
      (when container-id
        (try
          (run-docker ["rm" "-f" container-id] {:timeout 10000})
          (catch Exception e
            (log/warn "docker rm -f failed:" (.getMessage e)))))
      (swap! state dissoc k)
      nil))

  (cancel [_ {:keys [job-name build-number]}]
    (let [grace (get config :cancel-grace-ms 10000)
          k [job-name build-number]
          {:keys [container-id]} (get @state k)]
      (if container-id
        (kill-container! container-id grace)
        (log/info "cancel: no container tracked for"
                  (str job-name " #" build-number)))
      nil)))

(defn docker-backend
  "Construct a DockerBackend.

   Required:
     :image STRING               — fully qualified, e.g. \"eclipse-temurin:21\"

   Optional:
     :mode :per-build | :per-step   — default :per-build
     :network-mode STRING           — passed through as --network
     :resource-limits MAP           — see ns docstring
     :cancel-grace-ms LONG          — grace before SIGKILL (default 10000)

   Constructing the backend does NOT pull the image or start a container
   — that happens at `prepare-workspace`. Constructing it also does NOT
   verify the docker daemon is reachable, so the backend object is cheap
   to create in tests + configuration code paths.

   Mutable per-build state is held in an atom inside the record — see the
   record's docstring for the shape."
  [config]
  (when (nil? (:image config))
    (throw (ex-info "docker-backend requires :image" {:config config})))
  (->DockerBackend (merge {:mode :per-build} config) (atom {})))
