(ns chengis.engine.backend
  "Execution-backend protocol — the layer beneath `chengis.engine.dispatcher`
   that decides WHERE a step's shell command runs.

   Why this exists: anvil v0.3 always ran step shell commands in-process
   via `chengis.engine.process/execute-command`. Real-world Jenkinsfiles
   ship `agent { docker }`, `agent { kubernetes }`, `agent { dockerfile }`
   — none of which can be honored by a single-host shell. anvil silently
   skipped the body and reported SUCCESS for the walk. The wild-corpus
   matrix (15 real-world OSS Jenkinsfiles) parses 15/15 but builds 0/15
   to real artifacts.

   The backend protocol is the pluggable layer that fixes this:

     LocalShell   (this ns)                 — in-process shell, current behavior
     Docker       (chengis-core v0.2 EX1b)  — container per build or per step
     Kubernetes   (chengis-core v0.3 EX6)   — pod-template, container exec
     SSH-remote   (future)                  — exec on a remote host over SSH

   The `StepDispatcher` protocol (which a product like anvil registers
   to teach core about its step types) sits ABOVE this. When an anvil
   `:jenkins/sh` handler is dispatched, the handler asks the backend to
   run the command rather than calling
   `chengis.engine.process/execute-command` directly. Same flow as
   before for LocalShell; a Docker container exec for DockerBackend; a
   `kubectl exec` for KubernetesBackend.

   Naming note: this is distinct from `chengis.agent.*` in this repo,
   which is the distributed-agent daemon (a separate process that
   receives build dispatches from a master controller over HTTP). The
   two layers compose: a distributed agent worker can itself hold an
   ExecutionBackend; a single-host install uses the backend directly
   without an agent daemon.

   See `docs/v0.2-board.md` for the full Brasstacks Phase 1 plan."
  (:require [chengis.engine.process :as process]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Spec shapes (data contracts shared across all backends)
;; ---------------------------------------------------------------------------

;; build-spec — passed to `prepare-workspace` and `cleanup`:
;;   {:job-name STRING                   — e.g. "apache-zookeeper"
;;    :build-number LONG                 — e.g. 12
;;    :workspace-path STRING             — absolute path the backend should
;;                                          treat as the canonical workspace
;;    :env {STRING STRING}               — initial env (engine-provided
;;                                          BUILD_NUMBER / JOB_NAME / ...)
;;    :resource-limits                   — optional cgroup-style limits
;;      {:memory-mb LONG?                  — memory cap
;;       :cpu-shares LONG?                 — CPU shares
;;       :pids-max LONG?}                  — process count cap
;;    :backend-config ANY?}              — backend-specific opts (image name,
;;                                          pod-template yaml, etc.)
;;
;; step-spec — passed to `execute-step`:
;;   {:command STRING                    — shell command to run
;;    :dir STRING?                       — cwd within the workspace
;;    :env {STRING STRING}?              — env overrides for this step
;;    :timeout LONG?                     — ms (default 300000)
;;    :mask-values [STRING ...]?         — secret strings to redact in logs
;;    :log-file FILE?                    — destination for streaming output
;;                                          (when nil, output is buffered)
;;    :stdin STRING?}                    — optional stdin
;;
;; execute-step return:
;;   {:exit-code LONG
;;    :stdout STRING                     — empty when :log-file streamed
;;    :stderr STRING                     — empty when :log-file streamed
;;    :duration-ms LONG
;;    :timed-out? BOOL}

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol ExecutionBackend
  "Pluggable execution backend. Implementations decide WHERE a step's
   shell command runs (local process, Docker container, K8s pod, …).

   Lifecycle for a typical build:

     1. prepare-workspace — backend allocates/mounts the workspace
     2. (steps run one or more times)  execute-step — one shell step
     3. cleanup — backend tears down build-scoped resources

   Cancellation (signal from another thread) goes through `cancel`. The
   backend must make cancel safe to call multiple times and from any
   thread."

  (backend-name [this]
    "Short identifier for logs + telemetry. Examples: \"local-shell\",
     \"docker:eclipse-temurin:21\", \"kubernetes:default\".")

  (prepare-workspace [this build-spec]
    "Ensure the workspace and any backend-specific build-scoped resources
     exist before any steps run. Returns
       {:workspace STRING           — absolute path the backend will use
                                       as cwd by default
        :backend-state ANY?         — opaque state the backend wants to
                                       thread through subsequent calls
        :result KEYWORD             — :ok | :failed | :unsupported
        :explain STRING?}           — required when :result is not :ok
     The caller stores :backend-state and threads it back via
     :backend-state in subsequent step-specs.")

  (execute-step [this step-spec]
    "Run one shell step. Returns the execution result map (see header).
     The backend is responsible for streaming output to :log-file when
     supplied and for masking :mask-values from any logged form of the
     command.")

  (cleanup [this build-spec]
    "Tear down build-scoped resources. For LocalShell: typically a no-op
     or a workspace prune. For Docker: stop + rm the container. For K8s:
     delete the Pod. Idempotent — safe to call twice. Return nil.")

  (cancel [this build-spec]
    "Interrupt an in-flight build belonging to (job-name, build-number).
     Safe to call from any thread, including the build's own thread.
     Return nil. Backend should aim to deliver SIGINT then SIGKILL after
     a grace period."))

;; ---------------------------------------------------------------------------
;; LocalShell reference backend
;;
;; The behaviour anvil v0.3 has today: every step runs in the same JVM,
;; cwd is the workspace dir, env merges over inherited env. This is the
;; identity-implementation of the protocol — installing it should
;; preserve every existing behavior bit-for-bit. EX1b's Docker backend
;; is the first one that materially diverges.
;; ---------------------------------------------------------------------------

(defrecord LocalShellBackend [config]
  ExecutionBackend
  (backend-name [_] "local-shell")

  (prepare-workspace [_ {:keys [workspace-path]}]
    (if (nil? workspace-path)
      {:result :failed :explain "workspace-path is required"}
      (let [f (io/file workspace-path)]
        (.mkdirs f)
        {:workspace workspace-path
         :backend-state nil
         :result :ok})))

  (execute-step [_ {:keys [command dir env timeout mask-values]}]
    (process/execute-command (cond-> {:command command}
                               dir          (assoc :dir dir)
                               env          (assoc :env env)
                               timeout      (assoc :timeout timeout)
                               mask-values  (assoc :mask-values mask-values))))

  (cleanup [_ _]
    ;; LocalShell holds no per-build state. Workspace persistence is
    ;; the engine's job, not the backend's, for parity with v0.3.
    nil)

  (cancel [_ {:keys [job-name build-number]}]
    ;; The local shell backend cannot interrupt an in-flight subprocess
    ;; through this method — cancellation in v0.3 anvil flows through
    ;; the runner's thread-interrupt mechanism, which kills the worker
    ;; thread and lets the subprocess complete its current command
    ;; before the next step is skipped. EX1b's Docker backend gets a
    ;; real cancel (SIGINT → SIGKILL on the container).
    (log/info (str "anvil.backend.local-shell: cancel requested for "
                   job-name " #" build-number
                   " — local backend has no in-process subprocess kill; "
                   "rely on runner-thread interrupt"))
    nil))

(defn local-shell-backend
  "Construct a LocalShellBackend. Optional `config` map carries
   backend-specific tuning (none today; reserved for future use)."
  ([] (local-shell-backend {}))
  ([config] (->LocalShellBackend config)))

;; ---------------------------------------------------------------------------
;; Default backend convention
;;
;; Products that haven't yet plumbed pluggable backends through can call
;; `(default-backend)` and behave as v0.3 anvil does today. The Docker
;; / K8s backends ship under their own namespaces with distinct
;; constructors so the choice is always explicit.
;; ---------------------------------------------------------------------------

(defn default-backend
  "Returns the in-process LocalShellBackend. Equivalent to today's
   v0.3 anvil execution: shell commands run in this JVM's process tree,
   workspace is a directory on the controller's filesystem."
  []
  (local-shell-backend))
