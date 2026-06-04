(ns chengis.engine.steps
  "Plugin-step framework — CC2-EX5.

   Why this exists
   ===============
   The chengis-core engine knows how to execute *shell steps* — `sh
   'echo hi'` in Jenkins-speak. It doesn't natively know how to execute
   a *recorder* like `archiveArtifacts`, a *parser* like `junit`, or a
   *delivery* step like `s3.upload`. anvil v0.3 sometimes had ad-hoc
   adapters; more often it silently walked past unrecognized steps and
   reported SUCCESS.

   This namespace gives products (anvil, Chengis) a single registry to
   attach step adapters to, and gives the executor a single call to
   look up and invoke them. When no adapter is registered, the call
   returns a *recordable* unsupported-step result the EX2 classifier
   recognizes as `:unsupported-construct` — not a silent walk-past.

   The contract
   ============
   A step adapter is a record / map satisfying the `Step` protocol:

     id          — :artifacts/archive, :tests/junit, :problems/record, …
     describe    — short human description for log lines and the
                   `chengis-core ls-steps`-style introspection
     execute     — runs the step. Receives a build-ctx (engine-provided
                   workspace, build-spec, observation accumulator) and
                   the step's `config` map. Returns a step-result map.

   The step-result shape is intentionally a superset of the shell-step
   shape so the executor's terminal expression doesn't need to branch:

     {:exit-code LONG          — 0 on success, non-zero on failure
      :stdout STRING           — optional, for logs
      :stderr STRING           — optional
      :duration-ms LONG
      :effects [KEYWORD ...]   — what was recorded (:artifact-archived,
                                  :tests-recorded, :problems-recorded, …)
                                  These flow into the EX2 observation map
                                  and prevent vacuous-SUCCESS classification
      :test-summary MAP?       — when present, flows into EX2 test-summary
      :explain STRING?         — required when exit-code is non-zero
      :unsupported? BOOL?}     — set by the fallback adapter

   The registry
   ============
   A single process-wide atom holds id->adapter. Products register at
   boot. Lookups are O(1); the registry is intentionally not pluggable
   (one process, one registry, sleep at night).

   Refs: docs/v0.2-board.md CC2-EX5."
  (:require [chengis.engine.result :as result]
            [chengis.engine.test-parser :as test-parser]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Protocol + registry
;; ---------------------------------------------------------------------------

(defprotocol Step
  "An adapter for one named step type. Implementations are kept
   side-effect-free at construction; the work happens in `execute`."
  (step-id [this]
    "Keyword identifier. Must be globally unique within the registry.")
  (describe [this]
    "Short string used in logs + the steps-registry introspection.")
  (execute [this build-ctx config]
    "Run the step. See ns docstring for the build-ctx + step-result
     shapes. Implementations MUST set :exit-code and SHOULD set
     :effects so the build's terminal classification stays honest."))

(defonce ^:private registry (atom {}))

(defn register!
  "Register `step` under `(step-id step)`. Overwrites silently — the
   last writer wins, which is the right default for hot-reload during
   development. In production a single boot-time register! per product
   is the convention."
  [step]
  (let [id (step-id step)]
    (when-not (keyword? id)
      (throw (ex-info "step-id must be a keyword" {:step step})))
    (swap! registry assoc id step)
    id))

(defn unregister! [id]
  (swap! registry dissoc id)
  nil)

(defn registered
  "Returns a sorted map of id -> short description."
  []
  (into (sorted-map)
        (map (fn [[id step]] [id (describe step)]))
        @registry))

(defn lookup
  "Return the registered adapter for id, or nil."
  [id]
  (get @registry id))

(defn clear-registry!
  "Test-helper. Production callers should not need this."
  []
  (reset! registry {})
  nil)

;; ---------------------------------------------------------------------------
;; Honest unsupported-step fallback
;; ---------------------------------------------------------------------------

(defn unsupported-step-result
  "Construct the step-result the executor should return when a step id
   has no registered adapter. This result is shaped to flow into the
   EX2 observation map as `record-unsupported-construct`, so the build's
   terminal classification becomes `:unsupported` — not a vacuous
   `:success`."
  [step-id]
  {:exit-code 125
   :stdout ""
   :stderr (str "no adapter registered for step type " step-id)
   :duration-ms 0
   :explain (str "step " step-id " not supported by this executor")
   :unsupported? true
   :effects []
   :step-id step-id})

(defn dispatch
  "Resolve `id` against the registry and invoke `execute`. When unknown,
   return the unsupported-step result above — never silently succeed."
  [id build-ctx config]
  (if-let [adapter (lookup id)]
    (execute adapter build-ctx config)
    (unsupported-step-result id)))

;; Bridge into EX2 observation maps: callers that already maintain an
;; observation atom can pass the step-result through this to keep it
;; current — convenience over correctness (callers can do this by hand).
(defn record-into-observation
  "Given an in-flight observation map and a step-result, return the
   observation updated to reflect the step's exit-code, effects, and
   any test-summary the step computed."
  [obs {:keys [exit-code unsupported? step-id effects test-summary]}]
  (cond-> obs
    true                  (result/record-shell-step {:exit-code exit-code})
    unsupported?          (result/record-unsupported-construct
                           (str "step." (name step-id)))
    (seq effects)         (as-> o (reduce result/record-effect o effects))
    test-summary          (result/record-test-summary test-summary)))

;; ---------------------------------------------------------------------------
;; Built-in primitives — small, no-network set chosen for v0.2.
;;   Notifications (slack/email/github-*) + deliveries (scp/s3/gcs/http)
;;   ship in a follow-on PR; they each add a dependency + a credentials
;;   contract that EX4 settles. Keeping them out of EX5 keeps this PR
;;   reviewable and the test budget honest.
;; ---------------------------------------------------------------------------

;; -- :artifacts/archive ----------------------------------------------------

(defn- copy-file! [^java.io.File src ^java.io.File dst]
  (.mkdirs (.getParentFile dst))
  (with-open [in (io/input-stream src)
              out (io/output-stream dst)]
    (io/copy in out)))

(defn- glob-match?
  "Tiny glob matcher: handles `*`, `**`, and plain segments. Good enough
   for `target/*.jar`, `**/surefire-reports/TEST-*.xml`, `dist/**`. Not
   a full shell glob.

   Conversion order matters:
     1. escape literal `.`
     2. `**/` → `(?:.*/)?` — match any-path-then-slash OR nothing at
        all, so `**/x` matches both top-level `x` and `a/b/x`
     3. `**`  → `.*`
     4. `*`   → `[^/]*`"
  [pattern ^String path]
  (let [re (-> pattern
               (str/replace #"\." "\\\\.")
               (str/replace #"\*\*/" "::DSLASH::")
               (str/replace #"\*\*" "::DOUBLESTAR::")
               (str/replace #"\*" "[^/]*")
               (str/replace #"::DSLASH::" "(?:.*/)?")
               (str/replace #"::DOUBLESTAR::" ".*"))]
    (boolean (re-matches (re-pattern re) path))))

(defn- list-workspace-files [^String workspace]
  (let [base (io/file workspace)]
    (when (.exists base)
      (->> (file-seq base)
           (filter #(.isFile ^java.io.File %))
           (map (fn [^java.io.File f]
                  [f (.substring (.getAbsolutePath f)
                                 (inc (.length (.getAbsolutePath base))))]))))))

(defrecord ArtifactsArchiveStep []
  Step
  (step-id [_] :artifacts/archive)
  (describe [_] "Copy files matching glob patterns from the workspace into the build's artifact directory")
  (execute [_ {:keys [workspace artifact-dir]} {:keys [patterns]
                                                :or {patterns ["**"]}}]
    (cond
      (nil? workspace)
      {:exit-code 2 :stdout "" :stderr "no workspace in build-ctx"
       :duration-ms 0 :explain ":artifacts/archive needs :workspace" :effects []}

      (nil? artifact-dir)
      {:exit-code 2 :stdout "" :stderr "no artifact-dir in build-ctx"
       :duration-ms 0 :explain ":artifacts/archive needs :artifact-dir" :effects []}

      :else
      (let [start (System/currentTimeMillis)
            files (list-workspace-files workspace)
            matched (->> files
                         (filter (fn [[_ rel]]
                                   (some #(glob-match? % rel) patterns))))]
        (doseq [[^java.io.File src rel] matched]
          (let [dst (io/file artifact-dir rel)]
            (copy-file! src dst)))
        {:exit-code (if (seq matched) 0 1)
         :stdout (str "archived " (count matched) " file(s)")
         :stderr (when-not (seq matched)
                   "no files matched any pattern")
         :duration-ms (- (System/currentTimeMillis) start)
         :effects (if (seq matched) [:artifact-archived] [])
         :explain (when-not (seq matched)
                    (str "no files matched patterns: "
                         (str/join ", " patterns)))
         :archived-count (count matched)}))))

;; -- :tests/junit ---------------------------------------------------------

(defrecord JunitStep []
  Step
  (step-id [_] :tests/junit)
  (describe [_] "Scan workspace for JUnit / surefire XML reports and summarize")
  (execute [_ {:keys [workspace]} {:keys [paths] :or {paths ["**/surefire-reports/*.xml"
                                                            "**/test-results/*.xml"]}}]
    (let [start (System/currentTimeMillis)
          files (list-workspace-files workspace)
          xmls (->> files
                    (filter (fn [[_ rel]] (some #(glob-match? % rel) paths)))
                    (map first))
          per-case (mapcat (fn [^java.io.File f]
                             (test-parser/parse-junit-xml (slurp f)))
                           xmls)
          tests (count per-case)
          fails (count (filter #(= "fail" (:status %)) per-case))
          errors (count (filter #(= "error" (:status %)) per-case))
          skipped (count (filter #(= "skip" (:status %)) per-case))
          summary {:tests tests :failures fails :errors errors :skipped skipped}]
      {:exit-code (if (zero? tests) 1 0)
       :stdout (str "junit: " tests " test(s) across "
                    (count xmls) " file(s) — "
                    fails " failure(s), " errors " error(s)")
       :stderr (when (zero? tests) "no test XML found")
       :duration-ms (- (System/currentTimeMillis) start)
       :effects (if (pos? tests) [:tests-recorded] [])
       :test-summary summary
       :explain (when (zero? tests) "junit step found no test reports")})))

;; -- :problems/record -----------------------------------------------------

(defrecord ProblemsRecordStep []
  Step
  (step-id [_] :problems/record)
  (describe [_] "Persist a list of problem records to the build's artifact dir")
  (execute [_ {:keys [artifact-dir]} {:keys [problems]
                                      :or {problems []}}]
    (cond
      (nil? artifact-dir)
      {:exit-code 2 :stdout "" :stderr "no artifact-dir in build-ctx"
       :duration-ms 0 :explain ":problems/record needs :artifact-dir"
       :effects []}

      :else
      (let [start (System/currentTimeMillis)
            out (io/file artifact-dir "problems.edn")
            _ (.mkdirs (.getParentFile out))
            _ (spit out (pr-str (vec problems)))]
        {:exit-code 0
         :stdout (str "recorded " (count problems) " problem(s)")
         :stderr ""
         :duration-ms (- (System/currentTimeMillis) start)
         :effects (if (seq problems) [:problems-recorded] [])
         :problems-recorded-count (count problems)}))))

;; ---------------------------------------------------------------------------
;; Default registration
;; ---------------------------------------------------------------------------

(defn register-defaults!
  "Register the built-in primitives. Idempotent. Products call this at
   boot; tests call `clear-registry!` then `register-defaults!` to start
   from a known baseline."
  []
  (register! (->ArtifactsArchiveStep))
  (register! (->JunitStep))
  (register! (->ProblemsRecordStep))
  nil)
