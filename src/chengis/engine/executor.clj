(ns chengis.engine.executor
  "Pipeline execution engine. Runs stages sequentially or in parallel (DAG mode),
   steps within a stage either sequentially or in parallel, handles failures
   and abort signals."
  (:require [clojure.string :as str]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.dsl.yaml :as yaml-parser]
            [chengis.engine.approval :as approval]
            [chengis.engine.cache :as cache]
            [chengis.engine.dag :as dag]
            [chengis.engine.log-context :as log-ctx]
            [chengis.engine.policy :as policy]
            [chengis.engine.stage-cache :as stage-cache]
            [chengis.engine.matrix :as matrix]
            [chengis.engine.artifacts :as artifacts]
            [chengis.engine.notify :as notify]
            [chengis.engine.provenance :as provenance]
            [chengis.engine.sbom :as sbom]
            [chengis.engine.vulnerability-scanner :as vuln-scanner]
            [chengis.engine.license-scanner :as license-scanner]
            [chengis.engine.signing :as signing]
            [chengis.engine.git :as git]
            [chengis.engine.process :as process]
            [chengis.engine.workspace :as workspace]
            [chengis.feature-flags :as ff]
            [chengis.metrics :as metrics]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as plugin-reg]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util.concurrent Callable Executors ExecutorService Future ThreadFactory]))

(defn- now []
  (str (Instant/now)))

(defn- emit
  "Emit a build event if event-fn is present in the build context."
  [build-ctx event-type data]
  (when-let [f (:event-fn build-ctx)]
    (f {:build-id (:build-id build-ctx)
        :event-type event-type
        :timestamp (now)
        :data data})))

(defn- cancelled?
  "Check if the build has been cancelled."
  [build-ctx]
  (when-let [flag (:cancelled? build-ctx)]
    @flag))

(defn- evaluate-condition
  "Check whether a step/stage condition is met given the build context.
   Leaf types:
     :branch  → matches build-ctx [:parameters :branch] (defaults to \"main\")
     :param   → matches a named build parameter
     :env     → matches an env var visible to the build (build-ctx :env)
     :always  → always true
   Composite types (recursive):
     :and     → all :conditions true
     :or      → any :condition true
     :not     → inverts :condition
   nil → true (unconditional).
   Unknown type → true (preserves prior fallback so an unrecognised condition
   doesn't silently skip steps; the parser catches typos upstream)."
  [condition build-ctx]
  (if (nil? condition)
    true
    (case (:type condition)
      :branch (= (:value condition)
                 (get-in build-ctx [:parameters :branch] "main"))
      :param  (= (:value condition)
                 (get-in build-ctx [:parameters (keyword (:param condition))]))
      :env    (= (:value condition)
                 (get-in build-ctx [:env (:var condition)]))
      :always true
      :and    (every? #(evaluate-condition % build-ctx) (:conditions condition))
      :or     (boolean (some #(evaluate-condition % build-ctx) (:conditions condition)))
      :not    (not (evaluate-condition (:condition condition) build-ctx))
      true)))

(defn- execute-step-once
  "Run a step's underlying command exactly once. No retry, no skip, no
   condition check, no events — those live in run-step. Returns the same
   shape as process/execute-command (with :exit-code, :stdout, :stderr,
   :duration-ms, optionally :cancelled?).

   Extracted so run-step can wrap it in a retry loop without duplicating
   the plugin-dispatch + shell-fallback branch."
  [build-ctx step-def]
  (let [step-type (or (:type step-def) :shell)
        executor (plugin-reg/get-step-executor step-type)]
    (if executor
      (proto/execute-step executor build-ctx step-def)
      ;; Fallback to direct shell execution for backward compat
      (process/execute-command
       (cond-> {:command (:command step-def)
                :dir (:workspace build-ctx)
                :env (merge (:env build-ctx) (:env step-def))
                :timeout (:timeout step-def)}
         (seq (:mask-values build-ctx))
         (assoc :mask-values (:mask-values build-ctx)))))))

(def ^:private retry-cancellation-poll-ms
  "How often the retry inter-attempt sleep wakes to check cancellation.
   Small enough that long :delay values (minutes) still abort within ~100 ms
   of a cancel; large enough that the polling itself is invisible (≤1% wake
   overhead for any realistic :delay)."
  100)

(defn- sleep-cancellable
  "Sleep up to total-ms, but bail early if (cancelled? build-ctx) becomes true.
   Returns true if cut short by cancellation, false on natural completion.
   Polls every retry-cancellation-poll-ms; sleeps the remaining window if it's
   smaller than the poll interval.

   Computes `remaining` exactly once per iteration and clamps via `(pos? slice)`
   so a context switch between the deadline-check and the sleep call can't yield
   a negative argument to Thread/sleep (which would throw)."
  [build-ctx total-ms]
  (let [deadline (+ (System/currentTimeMillis) total-ms)]
    (loop []
      (if (cancelled? build-ctx)
        true
        (let [remaining (- deadline (System/currentTimeMillis))
              slice (min retry-cancellation-poll-ms remaining)]
          (if (pos? slice)
            (do (Thread/sleep slice) (recur))
            false))))))

(defn- run-step-with-retry
  "Drive execute-step-once in a retry loop when (:retry step-def) is set.
   Contract:
     :retry {:max N}           — up to N total attempts (matches Jenkins `retry(N)`).
                                 N=1 means no retry; useful for documentation.
     :retry {:max N :delay MS} — sleep MS between attempts. Default 0.

   Only :failure triggers a retry. :aborted (cancellation) or :success ends the loop
   immediately. The final result is the *last* attempt's raw result; we also
   tack on :attempts so callers can surface 'succeeded after 2 retries'.

   Cancellation is honored AT TWO POINTS: (a) at the top of the loop before
   the next attempt fires, and (b) inside the inter-attempt sleep, which polls
   every 100 ms and returns early on cancel. Long :delay values therefore do
   NOT block a build cancel for more than ~100 ms.

   When :retry is set, :duration-ms on the returned result is REWRITTEN to
   total wall time across all attempts and inter-attempt sleeps — otherwise
   the metric would under-report retried steps (the last attempt alone
   ignores prior-attempt time + sleep cost). For non-retry steps, the raw
   :duration-ms from execute-step-once is preserved verbatim.

   No :retry key → behave exactly like a single execute-step-once call (zero
   behavior change for existing pipelines)."
  [build-ctx step-def]
  (if-not (:retry step-def)
    ;; Fast path: zero overhead, byte-identical to the pre-retry call.
    (execute-step-once build-ctx step-def)
    (let [retry-cfg (:retry step-def)
          max-attempts (or (:max retry-cfg) 1)
          delay-ms (or (:delay retry-cfg) 0)
          start-ns (System/nanoTime)
          total-elapsed-ms #(quot (- (System/nanoTime) start-ns) 1000000)]
      (loop [attempt 1]
        ;; Cancellation between retries: bail without another attempt.
        (if (and (> attempt 1) (cancelled? build-ctx))
          {:exit-code -2 :stdout "" :stderr "Build cancelled" :cancelled? true
           :attempts (dec attempt)
           :duration-ms (total-elapsed-ms)}
          (let [result (execute-step-once build-ctx step-def)
                done?  (or (:cancelled? result)
                           (zero? (:exit-code result))
                           (>= attempt max-attempts))]
            (if done?
              ;; Override per-attempt :duration-ms with cumulative wall time
              ;; — matters for metrics + step-completed events. Codex PR #17 P2.
              (assoc result :attempts attempt :duration-ms (total-elapsed-ms))
              (do
                (log/warn "Step failed on attempt" attempt "of" max-attempts
                          "— retrying after" delay-ms "ms")
                (when (pos? delay-ms)
                  (sleep-cancellable build-ctx delay-ms))
                (recur (inc attempt))))))))))

(defn run-step
  "Execute a single step. Returns a step result map.

   Honors (:retry step-def) — a {:max N :delay MS} map drives a retry loop
   around the underlying command. Without :retry, semantics are identical to
   prior behavior (one attempt). Retry events are NOT emitted per-attempt;
   the step-result carries :attempts so the UI can render 'N attempts' on
   one step-completed event."
  [build-ctx step-def]
  (let [step-name (:step-name step-def)
        stage-name (:current-stage build-ctx)
        started-at (now)]
    (log-ctx/with-step-context step-name
    ;; Check cancellation before running
      (if (cancelled? build-ctx)
        (do
          (log/info "Step aborted (build cancelled):" step-name)
          (emit build-ctx :step-completed
                {:stage-name stage-name :step-name step-name :step-status :aborted})
          {:step-name step-name
           :step-status :aborted
           :exit-code -2
           :stdout ""
           :stderr "Build cancelled"
           :started-at started-at
           :completed-at (now)})
        (do
          (log/info "Running step:" step-name)
          (emit build-ctx :step-started {:stage-name stage-name :step-name step-name})
          (if-not (evaluate-condition (:condition step-def) build-ctx)
            (let [result {:step-name step-name
                          :step-status :skipped
                          :exit-code 0
                          :started-at started-at
                          :completed-at (now)}]
              (log/info "Skipping step (condition not met):" step-name)
              (emit build-ctx :step-completed
                    (merge {:stage-name stage-name} result))
              result)
            (let [result (run-step-with-retry build-ctx step-def)
                  status (cond
                           (:cancelled? result) :aborted
                           (zero? (:exit-code result)) :success
                           :else :failure)]
              (when (= status :failure)
                (log/error "Step failed:" step-name "exit code:" (:exit-code result)
                           (when (> (:attempts result 1) 1)
                             (str "(after " (:attempts result) " attempts)")))
                (when (seq (:stderr result))
                  (log/error "stderr:" (:stderr result))))
              (let [;; A failure on a step that explicitly opted in to
                  ;; :continue-on-fail is marked so run-steps-sequential
                  ;; doesn't bail and run-stage's status calc ignores it.
                  ;; Strict (= true v) — truthy-but-not-true is rejected at
                  ;; parse time, but defending here too costs nothing.
                    continue-on-fail? (and (= :failure status)
                                           (true? (:continue-on-fail step-def)))
                    step-result (cond-> {:step-name step-name
                                         :step-status status
                                         :exit-code (:exit-code result)
                                         :stdout (:stdout result)
                                         :stderr (:stderr result)
                                         :duration-ms (:duration-ms result)
                                         :started-at started-at
                                         :completed-at (now)}
                                ;; Only surface :attempts when retry was configured —
                                ;; keeps existing step-results byte-identical for
                                ;; pipelines that don't opt in.
                                  (:retry step-def) (assoc :attempts (:attempts result 1))
                                ;; Surface continue-on-fail marker only when it
                                ;; ACTUALLY suppressed a failure. UI consumers can
                                ;; render a distinct "❌ ignored" badge.
                                  continue-on-fail? (assoc :continue-on-fail? true))]
                (emit build-ctx :step-completed
                      (merge {:stage-name stage-name} step-result))
              ;; Record step duration metric (try/catch to never break builds)
                (when-let [ms (:duration-ms result)]
                  (try
                    (metrics/record-step-duration!
                     (:metrics-registry build-ctx) step-name status (/ (double ms) 1000.0))
                    (catch Exception e
                      (log/debug "Failed to record step metric:" (.getMessage e)))))
                step-result))))))))

(defn- run-steps-sequential
  "Run steps one by one. Stops on first :aborted (cancellation) or :failure
   UNLESS the failed step opted into :continue-on-fail — in which case the
   stage continues to the next step. :aborted always bails (cancellation
   takes precedence over continue-on-fail)."
  [build-ctx steps]
  (reduce (fn [results step-def]
            (if (cancelled? build-ctx)
              (reduced results)
              (let [result (run-step build-ctx step-def)
                    updated (conj results result)
                    status (:step-status result)]
                (cond
                  (= :aborted status)           (reduced updated)
                  ;; Failure with explicit opt-in: keep going.
                  (and (= :failure status)
                       (:continue-on-fail? result)) updated
                  (= :failure status)            (reduced updated)
                  :else                          updated))))
          []
          steps))

(def ^:private ^:const default-max-parallel-steps
  "Default maximum number of steps that can run concurrently within a stage.
   Prevents thread pool exhaustion from large parallel step counts (e.g. matrix builds)."
  8)

(def ^:private ^:const default-max-parallel-stages
  "Default maximum number of stages that can run concurrently in DAG mode.
   Matches the default in `run-stages-dag`'s read of
   [:parallel-stages :max-concurrent]."
  4)

(defn- ^ThreadFactory named-daemon-thread-factory
  "Construct a ThreadFactory that names threads `<prefix>-<n>` and marks them
   daemon, so a stuck pool can never keep the JVM alive past application exit."
  [prefix]
  (let [counter (java.util.concurrent.atomic.AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r)
          (.setName (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- pool-size-from-env
  "Read a positive Integer pool size from `env-var`, falling back to
   `default-n` when the var is unset, malformed, or not positive. We
   refuse to throw at namespace-load time: an operator typo
   (`CHENGIS_MAX_PARALLEL_STEPS=abc` or `=0`) would otherwise crash the
   whole app on startup instead of degrading to the safe default."
  [env-var default-n]
  (let [raw (System/getenv env-var)]
    (or (when raw
          (try
            (let [n (Integer/parseInt raw)]
              (when (pos? n) n))
            (catch NumberFormatException _
              (log/warn env-var "is not an integer:" raw
                        "— falling back to default" default-n)
              nil)))
        default-n)))

;; Bounded executor for parallel-step execution within a stage.
;;
;; Pre-fix: `core.async/thread` allocated from the unbounded cached pool, with
;; a Semaphore (default 8) gating actual concurrency. With 100 parallel steps,
;; 100 threads existed — 92 blocked on the semaphore.
;;
;; Post-fix: a fixed-size pool with an internal unbounded queue caps the live
;; thread count at the configured size. Excess work waits in the queue instead
;; of spawning blocked threads. User-visible semantics (N concurrent at a time)
;; are unchanged.
;;
;; Pool size precedence at namespace load: CHENGIS_MAX_PARALLEL_STEPS env var
;; > default-max-parallel-steps. At server startup, `init-pools!` replaces
;; this with a config-derived pool (so [:thread-pools :max-parallel-steps]
;; from config.edn takes effect — see Codex P1 follow-up below).
;;
;; Per-build `:max-parallel-steps` overrides embedded in build-ctx are NO
;; LONGER honored — the pool is JVM-wide. This is the intended trade-off:
;; the alternative (per-build Semaphore on top of the pool) reintroduces the
;; blocked-thread problem we're fixing.
(defonce ^:private ^ExecutorService parallel-step-executor
  (Executors/newFixedThreadPool
   (int (pool-size-from-env "CHENGIS_MAX_PARALLEL_STEPS"
                            default-max-parallel-steps))
   (named-daemon-thread-factory "chengis-parallel-step")))

;; Bounded executor for parallel stage execution in DAG mode. Mirrors the
;; step pool — same rationale, same trade-off. Sized from
;; CHENGIS_MAX_PARALLEL_STAGES (default 4, matching the
;; [:parallel-stages :max-concurrent] config default). Resized from config
;; by `init-pools!` at startup.
(defonce ^:private ^ExecutorService parallel-stage-executor
  (Executors/newFixedThreadPool
   (int (pool-size-from-env "CHENGIS_MAX_PARALLEL_STAGES"
                            default-max-parallel-stages))
   (named-daemon-thread-factory "chengis-parallel-stage")))

(defn init-pools!
  "Re-initialize the parallel-step and parallel-stage executors with sizes
   derived from the merged config map. Called once at server startup AFTER
   `chengis.config/load-config` has merged env overrides into the config.
   Mirrors `chengis.engine.events/init-event-bus!` pattern — replaces the
   namespace-load defonce'd pools via alter-var-root.

   Codex P1 follow-up: without this hook, the configured
   `[:thread-pools :max-parallel-steps]` and `[:parallel-stages :max-concurrent]`
   values from config.edn (and their env-var mappings like
   `CHENGIS_PARALLEL_STAGES_MAX`) were silently ignored — the pools were
   sized only from `CHENGIS_MAX_PARALLEL_STEPS` / `CHENGIS_MAX_PARALLEL_STAGES`
   raw env vars read at namespace load (before config exists). Now the pool
   sizes honor the full defaults > file > env merge that chengis.config
   computes.

   Safe to call exactly once at startup before any parallel pipelines run.
   Shuts down the original (defonce'd) pools after swapping so we don't
   leak the startup-default pool's threads."
  [config]
  (let [steps-n  (int (or (get-in config [:thread-pools :max-parallel-steps])
                          default-max-parallel-steps))
        stages-n (int (or (get-in config [:parallel-stages :max-concurrent])
                          default-max-parallel-stages))
        new-step-pool  (Executors/newFixedThreadPool
                        steps-n
                        (named-daemon-thread-factory "chengis-parallel-step"))
        new-stage-pool (Executors/newFixedThreadPool
                        stages-n
                        (named-daemon-thread-factory "chengis-parallel-stage"))
        old-step-pool  parallel-step-executor
        old-stage-pool parallel-stage-executor]
    (alter-var-root #'parallel-step-executor  (constantly new-step-pool))
    (alter-var-root #'parallel-stage-executor (constantly new-stage-pool))
    ;; Shut down the original startup-default pools (no in-flight work
    ;; expected this early, but .shutdown allows queued tasks to complete
    ;; cleanly either way).
    (.shutdown ^ExecutorService old-step-pool)
    (.shutdown ^ExecutorService old-stage-pool)
    (log/info "Parallel executor pools initialized from config:"
              "max-parallel-steps=" steps-n
              "max-parallel-stages=" stages-n)))

(defn- run-steps-parallel
  "Run steps concurrently on the bounded `parallel-step-executor`. Waits for
   all to complete. Results are returned in input order.

   Concurrency cap: fixed at JVM startup from CHENGIS_MAX_PARALLEL_STEPS
   (default 8). Per-build `:max-parallel-steps` in build-ctx is NOT honored
   here anymore — see the `parallel-step-executor` docstring."
  [build-ctx steps]
  (let [^java.util.List tasks
        (mapv (fn [step-def]
                ^Callable (fn [] (run-step build-ctx step-def)))
              steps)
        futures (mapv (fn [^Callable task]
                        (.submit parallel-step-executor task))
                      tasks)]
    ;; `.get` blocks the orchestrator thread until each future completes.
    ;; Order is preserved because we walk `futures` in input order.
    (mapv (fn [^Future f] (.get f)) futures)))

(defn- containerize-steps
  "If a stage has a :container config, wrap all :shell steps to run as :docker.
   Steps that already have :type :docker are left unchanged."
  [steps container-config]
  (if-not container-config
    steps
    (mapv (fn [step-def]
            (if (= :shell (or (:type step-def) :shell))
              (merge step-def
                     {:type :docker
                      :image (:image container-config)}
                     (when (:volumes container-config)
                       {:volumes (:volumes container-config)})
                     (when (:workdir container-config)
                       {:workdir (:workdir container-config)})
                     (when (:network container-config)
                       {:network (:network container-config)})
                     (when (:pull-policy container-config)
                       {:pull-policy (:pull-policy container-config)})
                     (when (:docker-args container-config)
                       {:docker-args (:docker-args container-config)})
                     (when (:cache-volumes container-config)
                       {:cache-volumes (:cache-volumes container-config)}))
              step-def))
          steps)))

(defn run-stage
  "Execute a pipeline stage. Returns a stage result map.
   If the stage has a :container config, shell steps are wrapped to run in Docker."
  [build-ctx stage-def]
  (let [stage-name (:stage-name stage-def)
        started-at (now)
        start-ns (System/nanoTime)
        ;; Add current stage name to context so steps can reference it
        build-ctx (assoc build-ctx :current-stage stage-name)]
    (log-ctx/with-stage-context stage-name
    ;; Check cancellation before running stage
      (if (cancelled? build-ctx)
        (do
          (log/info "Stage aborted (build cancelled):" stage-name)
          (emit build-ctx :stage-completed {:stage-name stage-name :stage-status :aborted})
          {:stage-name stage-name
           :stage-status :aborted
           :step-results []
           :started-at started-at
           :completed-at (now)})
        (do
          (log/info "=== Stage:" stage-name "===")
          (emit build-ctx :stage-started {:stage-name stage-name})
          (if-not (evaluate-condition (:condition stage-def) build-ctx)
            (let [result {:stage-name stage-name
                          :stage-status :skipped
                          :step-results []
                          :started-at started-at
                          :completed-at (now)}]
              (log/info "Skipping stage (condition not met):" stage-name)
              (emit build-ctx :stage-completed {:stage-name stage-name :stage-status :skipped})
              result)
            (let [;; Apply container wrapping if stage has :container config
                  effective-steps (containerize-steps (:steps stage-def)
                                                      (:container stage-def))
                  step-results (if (:parallel? stage-def)
                                 (run-steps-parallel build-ctx effective-steps)
                                 (run-steps-sequential build-ctx effective-steps))
                ;; A :failure marked :continue-on-fail? was explicitly opted
                ;; in to "this can fail, keep going" — exclude it from the
                ;; stage's failure roll-up so the stage status reflects the
                ;; un-suppressed work only. Aborted always wins.
                  blocking-failure? (fn [r]
                                      (and (= :failure (:step-status r))
                                           (not (:continue-on-fail? r))))
                  any-failed?  (some blocking-failure? step-results)
                  any-aborted? (some #(= :aborted (:step-status %)) step-results)
                  all-skipped? (every? #(= :skipped (:step-status %)) step-results)
                  stage-status (cond
                                 any-aborted? :aborted
                                 any-failed?  :failure
                                 all-skipped? :skipped
                                 :else        :success)]
              (log/info "Stage" stage-name "completed with status:" stage-status)
              (emit build-ctx :stage-completed {:stage-name stage-name :stage-status stage-status})
            ;; Record stage duration metric (try/catch to never break builds)
              (let [duration-s (/ (double (- (System/nanoTime) start-ns)) 1e9)]
                (try
                  (metrics/record-stage-duration!
                   (:metrics-registry build-ctx) stage-name stage-status duration-s)
                  (catch Exception e
                    (log/debug "Failed to record stage metric:" (.getMessage e)))))
              {:stage-name stage-name
               :stage-status stage-status
               :step-results step-results
               :started-at started-at
               :completed-at (now)})))))))

(defn- run-post-action-group
  "Run a group of post-action steps as an implicit stage.
   Post-action failures are logged but do NOT affect build status."
  [build-ctx stage-name steps]
  (when (seq steps)
    (let [stage-def {:stage-name stage-name
                     :parallel? false
                     :steps steps}]
      (log/info "--- Post-action:" stage-name "---")
      (run-stage build-ctx stage-def))))

(defn- run-post-actions
  "Execute post-build action groups based on build status.
   Runs :always regardless, :on-success for successful builds,
   :on-failure for failed/aborted builds.
   Returns a vector of stage results for all executed post-action groups."
  [build-ctx build-status post-actions]
  (when (seq post-actions)
    (log/info "--- Running post-build actions ---")
    (let [results (atom [])]
      ;; Always runs regardless of build status
      (when-let [always-steps (:always post-actions)]
        (when-let [result (run-post-action-group build-ctx "post:always" always-steps)]
          (swap! results conj result)))
      ;; On success
      (when (and (= :success build-status) (:on-success post-actions))
        (when-let [result (run-post-action-group build-ctx "post:on-success" (:on-success post-actions))]
          (swap! results conj result)))
      ;; On failure (failure or aborted)
      (when (and (#{:failure :aborted} build-status) (:on-failure post-actions))
        (when-let [result (run-post-action-group build-ctx "post:on-failure" (:on-failure post-actions))]
          (swap! results conj result)))
      @results)))

;; ---------------------------------------------------------------------------
;; Stage execution with policy + approval checks
;; ---------------------------------------------------------------------------

(defn- execute-stage-with-checks
  "Run a single stage through the cache → policy → approval → execution pipeline.
   Includes build result caching (skip if cached) and artifact cache restore/save.
   Returns a stage result map. Used by both sequential and DAG execution."
  [system build-ctx stage-def]
  ;; 0. Build result cache check (skip stage if inputs match a previous success)
  (let [cache-check (stage-cache/should-skip-stage? system build-ctx stage-def)]
    (if (:skip? cache-check)
      (do
        (log/info "Stage" (:stage-name stage-def) "skipped (cached result)")
        (emit build-ctx :stage-cached {:stage-name (:stage-name stage-def)})
        (:cached-result cache-check))
      ;; 1. Policy check (can deny outright or override approval requirements)
      (let [policy-result (policy/check-stage-policies! system build-ctx stage-def)]
        (if-not (:proceed policy-result)
          ;; Policy denied — abort
          (do
            (log/warn "Stage" (:stage-name stage-def)
                      "blocked by policy:" (:reason policy-result))
            (emit build-ctx :stage-policy-denied
                  {:stage-name (:stage-name stage-def)
                   :reason (:reason policy-result)})
            {:stage-name (:stage-name stage-def)
             :stage-status :aborted
             :step-results []
             :reason (:reason policy-result)})
          ;; 2. Apply approval overrides from policy, then check approval
          (let [effective-stage (if-let [overrides (:approval-overrides policy-result)]
                                  (policy/apply-approval-overrides stage-def overrides)
                                  stage-def)
                approval-result (approval/check-stage-approval!
                                 system build-ctx effective-stage)]
            (if-not (:proceed approval-result)
              ;; Approval denied/timed-out — abort
              (do
                (log/warn "Stage" (:stage-name stage-def)
                          "approval denied:" (:reason approval-result))
                (emit build-ctx :stage-skipped
                      {:stage-name (:stage-name stage-def)
                       :reason (:reason approval-result)})
                {:stage-name (:stage-name stage-def)
                 :stage-status :aborted
                 :step-results []
                 :reason (:reason approval-result)})
              ;; 3. Cache restore (before stage execution)
              (let [_ (when-let [cache-decls (:cache stage-def)]
                        (try
                          (cache/restore-cache!
                           (:workspace build-ctx) (:config system) (:db system)
                           (:job-id build-ctx) cache-decls)
                          (catch Exception e
                            (log/debug "Cache restore failed:" (.getMessage e)))))
                    ;; 4. Run stage
                    result (run-stage build-ctx stage-def)]
                ;; 5. Cache save (after successful stage)
                (when (and (= :success (:stage-status result))
                           (seq (:cache stage-def)))
                  (try
                    (cache/save-cache!
                     (:workspace build-ctx) (:config system) (:db system)
                     (:job-id build-ctx) (:cache stage-def))
                    (catch Exception e
                      (log/debug "Cache save failed:" (.getMessage e)))))
                ;; 6. Save stage result to cache for future builds
                (when (and (= :success (:stage-status result))
                           (:fingerprint cache-check))
                  (try
                    (stage-cache/save-stage-result!
                     (:db system)
                     {:job-id (:job-id build-ctx)
                      :fingerprint (:fingerprint cache-check)
                      :stage-name (:stage-name stage-def)
                      :stage-result result
                      :git-commit (get-in build-ctx [:env "GIT_COMMIT"])
                      :org-id (:org-id build-ctx)})
                    (catch Exception e
                      (log/debug "Stage cache save failed:" (.getMessage e)))))
                result))))))))

;; ---------------------------------------------------------------------------
;; Sequential stage execution (original behavior)
;; ---------------------------------------------------------------------------

(defn- run-stages-sequential
  "Run stages in sequential order. Stops on first failure or cancellation.
   This is the original execution mode, used when no :depends-on is present."
  [system build-ctx stages]
  (reduce (fn [results stage-def]
            (if (cancelled? build-ctx)
              (reduced results)
              (let [result (execute-stage-with-checks system build-ctx stage-def)
                    updated (conj results result)]
                (if (#{:failure :aborted} (:stage-status result))
                  (do
                    (log/error "Pipeline stopped: stage"
                               (:stage-name stage-def)
                               (name (:stage-status result)))
                    (reduced updated))
                  updated))))
          []
          stages))

;; ---------------------------------------------------------------------------
;; DAG-based parallel stage execution
;; ---------------------------------------------------------------------------

(defn- run-stages-dag
  "Run stages in parallel according to their :depends-on DAG.
   Stages with no dependencies (or empty deps) start immediately.
   Stages wait until all their dependencies have completed successfully.
   On failure/abort, downstream dependents are skipped.

   Uses the bounded `parallel-stage-executor` to cap concurrent stage
   execution. The pool is sized from config by `init-pools!` at startup
   ([:parallel-stages :max-concurrent], default 4). The
   `max-concurrent` value read here is purely diagnostic — it should
   match the pool size when init-pools! has run."
  [system build-ctx stages]
  (let [dag-map (dag/build-dag stages)
        max-concurrent (get-in system [:config :parallel-stages :max-concurrent] 4)
        ;; Track results and state
        completed (atom #{})       ;; set of completed stage names
        failed (atom #{})          ;; set of failed/aborted stage names
        results (atom [])          ;; ordered results vector
        stage-map (into {} (map (fn [s] [(:stage-name s) s]) stages))]
    (log/info "DAG execution mode: max-concurrent=" max-concurrent
              "stages=" (count stages))
    (loop []
      (when-not (cancelled? build-ctx)
        (let [ready (dag/ready-stages dag-map @completed)
              ;; Remove stages that are already done or currently being processed
              all-done (into @completed @failed)
              remaining (remove #(contains? all-done %) (keys dag-map))
              runnable (remove #(contains? all-done %) ready)]
          (if (and (empty? runnable) (seq remaining))
            ;; Stages remain but none are runnable — check if they have failed deps
            (let [blocked (filter (fn [stage-name]
                                    (let [deps (get dag-map stage-name)]
                                      (some #(contains? @failed %) deps)))
                                  remaining)]
              (if (seq blocked)
                ;; Mark blocked stages as skipped due to failed dependencies
                (do
                  (doseq [stage-name blocked]
                    (let [result {:stage-name stage-name
                                  :stage-status :aborted
                                  :step-results []
                                  :reason "Dependency failed"}]
                      (log/warn "Stage" stage-name "skipped: dependency failed")
                      (emit build-ctx :stage-skipped
                            {:stage-name stage-name :reason "Dependency failed"})
                      (swap! results conj result)
                      (swap! failed conj stage-name)))
                  (recur))
                ;; Stages still waiting for deps — wait and retry
                (do
                  (Thread/sleep 50)
                  (recur))))
            (when (seq runnable)
              ;; Submit ready stages to the bounded executor. The pool's
              ;; fixed thread count is the concurrency cap; excess submits
              ;; queue inside the executor instead of spawning blocked
              ;; threads. Backpressure on this loop comes from `.get` below,
              ;; which blocks until every dispatched wave finishes.
              (let [futures (mapv
                             (fn [stage-name]
                               (let [stage-def (get stage-map stage-name)
                                     ^Callable task
                                     (fn []
                                       (let [result (execute-stage-with-checks
                                                     system build-ctx stage-def)]
                                         {:stage-name stage-name
                                          :result result}))]
                                 (.submit parallel-stage-executor task)))
                             runnable)
                    ;; Wait for all dispatched stages to complete.
                    stage-results (mapv (fn [^Future f] (.get f)) futures)]
                ;; Process results
                (doseq [{:keys [stage-name result]} stage-results]
                  (swap! results conj result)
                  (if (#{:failure :aborted} (:stage-status result))
                    (swap! failed conj stage-name)
                    (swap! completed conj stage-name)))
                ;; Continue if there are more stages to run
                (when (< (+ (count @completed) (count @failed)) (count dag-map))
                  (recur))))))))
    @results))

;; ---------------------------------------------------------------------------
;; Build entry-point phases
;; ---------------------------------------------------------------------------
;; The phases below were extracted from run-build to keep the orchestrator
;; readable. Each is invoked once per build, in order: git checkout →
;; pipeline-source detection → (build body) → supply-chain checks.

(defn- do-git-checkout!
  "Clone the pipeline's git source, if any. Returns
     {:git-result RAW :git-info {…} :success? bool}
   where :git-info is the parsed branch/commit/author/message metadata.
   When no git source is configured, returns
     {:git-result nil :git-info nil :success? true}
   (treated as a clean no-checkout, not a failure).

   Emits :git-started before, :git-completed/:git-failed after."
  [source ws params early-ctx]
  (if-not (and source (= :git (:type source)))
    {:git-result nil :git-info nil :success? true}
    (do
      (emit early-ctx :git-started {:url (git/sanitize-url (:url source))})
      (let [commit-override (get-in params [:parameters :commit])
            branch-override (get-in params [:parameters :branch])
            pr-head-url     (get-in params [:parameters :pr-head-repo-url])
            pr-head-url-ssh (get-in params [:parameters :pr-head-repo-url-ssh])
            pr-build?       (some? (get-in params [:parameters :pr-number]))
            ;; CHG-FEAT-002 — for PR/MR builds, do NOT apply the branch
            ;; override to the clone: the PR head branch (e.g. "feature/x")
            ;; typically does NOT exist in the base repository (forks live in
            ;; a separate namespace), so `git clone -b feature/x base-url`
            ;; would fail outright before the reactive fork fetch retry could
            ;; run (Codex PR #24 P1 r8). For non-PR triggers (push/manual/
            ;; cron) the branch override still applies — push always names a
            ;; branch that exists in the cloned repo.
            effective-source (cond-> source
                               (and branch-override (not pr-build?))
                               (assoc :branch branch-override))
            ;; When the webhook carried a PR head repo URL (forked PR/MR),
            ;; fetch the head SHA from that fork remote before checkout so we
            ;; don't silently fall back to base-branch HEAD. For ANY PR/MR
            ;; build (:pr-number present), require the commit-override
            ;; checkout to succeed even when head-repo-url is nil (e.g.
            ;; deleted fork) — a "successful build on wrong revision" is
            ;; worse than a clear failure (Codex PR #24 P2 round 3).
            result (git/checkout-source! effective-source ws commit-override
                                         {:head-repo-url            pr-head-url
                                          :head-repo-url-ssh        pr-head-url-ssh
                                          :require-commit-checkout? pr-build?})]
        (if (:success? result)
          (do
            (log/info "Git checkout complete:" (get-in result [:git-info :commit-short]))
            (emit early-ctx :git-completed {:git-info (:git-info result)})
            {:git-result result :git-info (:git-info result) :success? true})
          (do
            (log/error "Git checkout failed:" (:error result))
            (emit early-ctx :git-failed {:error (:error result)})
            {:git-result result :git-info nil :success? false}))))))

(defn- detect-pipeline-source
  "After git checkout, look for an in-workspace pipeline definition:
     Chengisfile (EDN) → YAML workflow → fall back to the server-supplied
     pipeline.
   Returns {:effective-pipeline P :pipeline-source TAG} where TAG is
   \"chengisfile\", \"yaml\", or \"server\". The merge preserves the
   server pipeline's identity while overlaying detected stages/description/
   container/post-actions/artifacts/notify when present.

   When git-success? is false, skips detection and returns the server
   pipeline as-is."
  [pipeline ws git-success? early-ctx]
  (let [cf-result (when (and git-success? (chengisfile/chengisfile-exists? ws))
                    (log/info "Chengisfile detected in workspace")
                    (emit early-ctx :chengisfile-detected {:workspace ws})
                    (chengisfile/parse-chengisfile (chengisfile/chengisfile-path ws)))
        yaml-result (when (and git-success?
                               (not (and cf-result (:pipeline cf-result))))
                      (when-let [yaml-path (yaml-parser/detect-yaml-file ws)]
                        (log/info "YAML workflow detected:" yaml-path)
                        (emit early-ctx :yaml-detected {:path yaml-path})
                        (yaml-parser/parse-yaml-workflow yaml-path)))
        [pac-result pac-source]
        (cond
          (and cf-result (:pipeline cf-result))     [cf-result "chengisfile"]
          (and yaml-result (:pipeline yaml-result)) [yaml-result "yaml"]
          :else                                     [nil "server"])
        effective-pipeline
        (if pac-result
          (let [pac-pipeline (:pipeline pac-result)]
            (log/info "Using" pac-source "pipeline:"
                      (count (:stages pac-pipeline)) "stages")
            (cond-> (assoc pipeline :stages (:stages pac-pipeline))
              (:description pac-pipeline)  (assoc :description (:description pac-pipeline))
              (:container pac-pipeline)    (assoc :container (:container pac-pipeline))
              (:post-actions pac-pipeline) (assoc :post-actions (:post-actions pac-pipeline))
              (:artifacts pac-pipeline)    (assoc :artifacts (:artifacts pac-pipeline))
              (:notify pac-pipeline)       (assoc :notify (:notify pac-pipeline))))
          (do
            (when (and cf-result (:error cf-result))
              (log/warn "Chengisfile parse error:" (:error cf-result))
              (emit early-ctx :chengisfile-error {:error (:error cf-result)}))
            (when (and yaml-result (:error yaml-result))
              (log/warn "YAML parse error:" (:error yaml-result))
              (emit early-ctx :yaml-error {:error (:error yaml-result)}))
            pipeline))]
    {:effective-pipeline effective-pipeline :pipeline-source pac-source}))

(defn- run-supply-chain-checks!
  "Best-effort supply-chain checks gated by feature flags. Each is wrapped in
   try/catch — failures are logged but never escalate to the build result.
   Skipped entirely when no :db is configured (smoke/test mode)."
  [system build-result]
  (when (:db system)
    (try
      (when (ff/enabled? (:config system) :slsa-provenance)
        (provenance/generate-provenance! system build-result))
      (when (ff/enabled? (:config system) :sbom-generation)
        (sbom/generate-sbom! system build-result))
      (when (ff/enabled? (:config system) :container-scanning)
        (vuln-scanner/scan-build! system build-result))
      (when (ff/enabled? (:config system) :license-scanning)
        (license-scanner/scan-licenses! system build-result))
      (when (ff/enabled? (:config system) :artifact-signing)
        (signing/sign-artifacts! system build-result))
      (catch Exception e
        (log/warn "Supply chain checks failed:" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Build entry point
;; ---------------------------------------------------------------------------

(defn run-build
  "Execute a complete build for a pipeline definition.

   Arguments:
     system      - system map containing :config and optionally :db
     pipeline    - pipeline definition map (from DSL)
     params      - build parameters map (supports optional :event-fn for live updates
                   and :cancelled? atom for cancellation)

   Returns a build result map with status, stage results, and timing info."
  [system pipeline params]
  (let [build-id (util/generate-id)
        job-id (or (:job-id params) (:pipeline-name pipeline))
        build-number (or (:build-number params) 1)
        org-id (:org-id params)
        workspace-root (get-in system [:config :workspace :root] "workspaces")
        ;; CHG-FEAT-003 PR4: per-(job, branch) workspace isolation.
        ;; The build_runner passes `:branch` + `:workspace-isolation?`
        ;; through params for multibranch builds. When both are set —
        ;; AND the global per-branch toggle is on — we allocate a
        ;; branch-keyed tree so concurrent builds on different
        ;; branches don't share a working directory. Otherwise fall
        ;; back to the legacy `<root>/<job>/<build-number>/` layout
        ;; so non-multibranch jobs are byte-identical to pre-PR4
        ;; behaviour.
        branch (:branch params)
        per-branch-enabled? (get-in system [:config :workspace :per-branch] true)
        ws (if (and (:workspace-isolation? params)
                    per-branch-enabled?
                    branch
                    (not (str/blank? branch)))
             (workspace/allocate! workspace-root job-id branch)
             (workspace/create-workspace workspace-root job-id build-number))
        started-at (now)
        ;; Minimal build-ctx for emitting early events
        early-ctx {:build-id build-id
                   :event-fn (:event-fn params)
                   :cancelled? (:cancelled? params)}]
    (log-ctx/with-build-context build-id job-id (or org-id "default-org")
      (log/info "========================================")
      (log/info "Starting build" build-id "for" (:pipeline-name pipeline))
      (log/info "Workspace:" ws)
      (log/info "========================================")
    ;; --- Git checkout phase (before stages) ---
      (let [{:keys [git-result git-info success?]}
            (do-git-checkout! (:source pipeline) ws params early-ctx)]
      ;; Fail fast if git checkout failed
        (if (and git-result (not success?))
          (do
            (emit early-ctx :build-completed {:build-status :failure})
            {:build-id build-id
             :job-id job-id
             :build-number build-number
             :build-status :failure
             :stage-results []
             :workspace ws
             :started-at started-at
             :completed-at (now)
             :git-info nil
             :pipeline-source "server"})
        ;; --- Pipeline-as-Code detection (multi-format) ---
          (let [{:keys [effective-pipeline pipeline-source]}
                (detect-pipeline-source pipeline ws (boolean (and git-result success?)) early-ctx)
              ;; --- Normal build execution ---
                git-env (when git-info
                          {"GIT_BRANCH"       (:branch git-info)
                           "GIT_COMMIT"       (:commit git-info)
                           "GIT_COMMIT_SHORT" (:commit-short git-info)
                           "GIT_AUTHOR"       (:author git-info)
                           "GIT_MESSAGE"      (:message git-info)})
              ;; --- PR/MR env vars (CHG-FEAT-002 PR2) ---
              ;; Surface webhook-derived PR fields as PR_* env vars so
              ;; pipeline steps can branch on them (e.g. `if [ -n "$PR_NUMBER" ]`).
              ;; Each var only appears when the value is non-nil — keeps the
              ;; env clean for non-PR builds and for PRs with deleted forks
              ;; (e.g. :pr-head-repo-url nil).
                pr-env (when-let [pr-num (get-in params [:parameters :pr-number])]
                         (cond-> {"PR_NUMBER" (str pr-num)}
                           (get-in params [:parameters :pr-base-ref])
                           (assoc "PR_BASE_REF" (str (get-in params [:parameters :pr-base-ref])))
                           (get-in params [:parameters :pr-head-ref])
                           (assoc "PR_HEAD_REF" (str (get-in params [:parameters :pr-head-ref])))
                           (get-in params [:parameters :pr-author])
                           (assoc "PR_AUTHOR" (str (get-in params [:parameters :pr-author])))
                           (get-in params [:parameters :pr-url])
                           (assoc "PR_URL" (str (get-in params [:parameters :pr-url])))))
              ;; --- Secrets injection ---
                secrets-map (when (:db system)
                              (try
                                (secret-store/get-secrets-for-build
                                 (:db system) (:config system) job-id :org-id org-id)
                                (catch Exception e
                                  (log/warn "Failed to load secrets:" (.getMessage e))
                                  {})))
                secret-values (when (seq secrets-map) (set (vals secrets-map)))
              ;; --- Parameter env vars ---
                param-env (when-let [p (:parameters params)]
                            (reduce-kv (fn [m k v]
                                         (assoc m
                                                (str "PARAM_" (str/upper-case
                                                               (str/replace (name k) "-" "_")))
                                                (str v)))
                                       {} p))
                build-env (merge {"BUILD_ID" build-id
                                  "BUILD_NUMBER" (str build-number)
                                  "JOB_NAME" job-id
                                  "WORKSPACE" ws}
                                 git-env
                                 pr-env
                                 secrets-map
                                 param-env
                                 (:env params))
                build-ctx {:build-id build-id
                           :job-id job-id
                           :build-number build-number
                           :workspace ws
                           :parameters (merge (or (:parameters params) {})
                                              (when git-info
                                                {:branch (:branch git-info)}))
                           :env build-env
                           :event-fn (:event-fn params)
                           :cancelled? (:cancelled? params)
                           :mask-values secret-values
                           :docker-config (get-in system [:config :docker])
                           :max-parallel-steps (or (get-in system [:config :thread-pools :max-parallel-steps])
                                                   (get-in system [:config :parallel-steps :max-concurrent])
                                                   default-max-parallel-steps)
                           :metrics-registry (:metrics system)
                           :db (:db system)
                           :org-id org-id}
              ;; Propagate pipeline-level :container to stages that don't have their own
                pipeline-container (:container effective-pipeline)
                pre-matrix-stages (if pipeline-container
                                    (mapv (fn [s]
                                            (if (:container s)
                                              s
                                              (assoc s :container pipeline-container)))
                                          (:stages effective-pipeline))
                                    (:stages effective-pipeline))
              ;; Matrix expansion: if the pipeline has a :matrix config,
              ;; expand each stage into N copies (one per combination)
                matrix-config (:matrix effective-pipeline)
                max-combos (get-in system [:config :matrix :max-combinations]
                                   matrix/default-max-combinations)
                effective-stages (if matrix-config
                                   (matrix/expand-stages pre-matrix-stages matrix-config
                                                         :max max-combos)
                                   pre-matrix-stages)]
            (emit build-ctx :build-started {:job-id job-id :build-number build-number})
            (let [;; Choose execution mode: DAG (parallel) or sequential
                  use-dag? (and (dag/has-dag? effective-stages)
                                (ff/enabled? (:config system) :parallel-stage-execution))
                  stage-results
                  (if use-dag?
                    (do (log/info "Using DAG execution mode")
                        (run-stages-dag system build-ctx effective-stages))
                    (run-stages-sequential system build-ctx effective-stages))
                  any-failed? (some #(= :failure (:stage-status %)) stage-results)
                  any-aborted? (some #(= :aborted (:stage-status %)) stage-results)
                  build-status (cond
                                 any-aborted? :aborted
                                 any-failed?  :failure
                                 :else        :success)
                ;; --- Post-build actions ---
                  post-actions (:post-actions effective-pipeline)
                  post-results (run-post-actions build-ctx build-status post-actions)
                  all-stage-results (into stage-results post-results)
                ;; --- Artifact collection ---
                  artifact-patterns (:artifacts effective-pipeline)
                  collected-artifacts
                  (when (seq artifact-patterns)
                    (try
                      (let [artifact-root (get-in system [:config :artifacts :root] "artifacts")
                            artifact-dir (str artifact-root "/" job-id "/" build-number)]
                        (artifacts/collect-artifacts! ws artifact-dir artifact-patterns))
                      (catch Exception e
                        (log/warn "Artifact collection failed:" (.getMessage e))
                        nil)))
                ;; Persist artifact metadata to DB
                  _ (when (and (seq collected-artifacts) (:db system))
                      (doseq [art collected-artifacts]
                        (artifact-store/save-artifact! (:db system)
                                                       {:build-id build-id
                                                        :filename (:filename art)
                                                        :path (:path art)
                                                        :size-bytes (:size-bytes art)
                                                        :content-type (:content-type art)
                                                        :sha256-hash (:sha256-hash art)})))
                  completed-at (now)
                ;; Build result map (constructed before notifications so they can use it)
                  build-result (cond-> {:build-id build-id
                                        :job-id job-id
                                        :build-number build-number
                                        :build-status build-status
                                        :stage-results all-stage-results
                                        :workspace ws
                                        :started-at started-at
                                        :completed-at completed-at
                                        :pipeline-source pipeline-source
                                        :artifacts collected-artifacts}
                                 git-info (assoc :git-info git-info))
                ;; --- Supply chain checks (after artifacts, before notifications) ---
                  _ (run-supply-chain-checks! system build-result)
                ;; --- Notifications ---
                  _ (try
                      (notify/dispatch-notifications!
                       (:db system) build-result
                       (:notify effective-pipeline)
                       (:config system))
                      (catch Exception e
                        (log/warn "Notification dispatch failed:" (.getMessage e))))]
              (log/info "========================================")
              (log/info "Build" build-id "completed with status:" build-status)
              (log/info "========================================")
              (emit build-ctx :build-completed {:build-status build-status})
              build-result)))))))
