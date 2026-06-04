(ns chengis.engine.result
  "Honest build-result classification — CC2-EX2.

   Why this exists
   ===============
   anvil v0.3's wild-corpus matrix parses 15/15 real-world Jenkinsfiles but
   builds 0/15 to real artifacts. Yet the matrix reports 7/15 SUCCESS. The
   gap is that the executor classified an empty structural walk of the
   pipeline IR as `:success` whenever no step threw — even when:

     • `agent { docker }` was silently skipped because there was no
       Docker backend
     • `tool('jdk_17_latest')` returned `\"\"` and downstream `${JAVA_HOME}`
       was empty
     • `withCredentials([...])` bound every credential to `\"\"`
     • the entire stage body collapsed to a no-op

   `:success` is a contract — \"the build did the thing it said it would.\"
   A walk that ran zero shell commands and zero recorded effects did NOT
   do the thing. Reporting it as `:success` is a silent failure of the
   worst kind: green dashboards, broken artifacts.

   The honest classification
   =========================

       :success       — at least one shell step ran and the build's terminal
                        rule says \"all required steps exited 0\"
       :failure       — at least one required step exited non-zero, OR a
                        required input (cred, tool, agent) could not be
                        resolved at runtime
       :unstable      — completed but with test failures, deprecation
                        warnings or other operator-soft-failures recorded
       :aborted       — cancelled by signal or thread interrupt
       :neutral       — *new*. The build's IR walked, but nothing actually
                        executed — zero shell steps, zero recorded effects.
                        Equivalent to GitHub Actions' \"neutral\" status:
                        the run completed, but it did not assert anything
                        about the project state.
       :unsupported   — *new*. The build IR contains a construct this
                        executor cannot honor (e.g. `agent { kubernetes }`
                        on a single-host install, or a Jenkins plugin step
                        with no registered adapter). The build is neither
                        success nor failure — it's a config / capability
                        gap the operator must address.

   `:neutral` and `:unsupported` are the additions vs. anvil v0.3. Both
   are non-success terminal states; both surface a readable explain that
   names what was missing.

   Classifier rules
   ================
   `classify` takes an *observation map* (see `default-observation`) and
   returns `{:result KEYWORD :explain STRING :rule KEYWORD}`. The rule
   keyword names which decision fired — useful for debugging and for
   regression tests against the wild-corpus matrix.

   The rules are evaluated top-to-bottom and the first match wins. Order
   matters because they're stated negatively (\"if any of these fired,
   we are NOT a success\").

       :aborted-by-signal     → :aborted
       :unsupported-construct → :unsupported
       :credential-unresolved → :failure
       :tool-unresolved       → :failure
       :agent-unhonored       → :unsupported
       :step-nonzero-exit     → :failure
       :tests-failed          → :unstable
       :no-effects-recorded   → :neutral
       :default               → :success

   The rule set is intentionally small. The shape of the observation map
   is the API surface; products and backends contribute to it as builds
   progress and call `classify` once at completion.

   Compatibility note
   ==================
   This namespace does NOT yet wire into `chengis.engine.executor` (which
   still emits `:success`/`:failure`/`:aborted`). That migration is the
   subject of a follow-up PR that swaps the executor's terminal expression
   to `(classify observation)`. Keeping this PR scoped to the protocol
   makes the executor change reviewable in isolation and lets anvil's
   wild-corpus receipt land separately.

   Refs: `docs/v0.2-board.md` CC2-EX2."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Canonical result set
;; ---------------------------------------------------------------------------

(def all-results
  "Every result class this engine can emit. The order is the precedence
   order used by `worst-of` for rollups."
  [:aborted :unsupported :failure :unstable :neutral :success])

(def ^:private precedence
  ;; Lower number = worse for rollup purposes. :aborted dominates because
  ;; a cancelled child must not let the parent claim :success; :neutral
  ;; dominates :success in the same way (a stage that did nothing must
  ;; not be hidden behind a peer that did something trivial).
  (zipmap all-results (range)))

(defn worst-of
  "Roll a sequence of result keywords up to the worst — useful for
   stage/build-level summaries."
  [results]
  (or (->> results
           (filter precedence)
           (sort-by precedence)
           first)
      :neutral))

(defn success? [r] (= :success r))
(defn failure? [r] (= :failure r))
(defn aborted? [r] (= :aborted r))
(defn unstable? [r] (= :unstable r))
(defn neutral? [r] (= :neutral r))
(defn unsupported? [r] (= :unsupported r))

(defn terminal?
  "Returns true iff `r` is a build-terminal result class. All of the
   canonical results are terminal; non-terminal values are caller bugs."
  [r]
  (boolean (precedence r)))

(defn non-success-terminal?
  "True for every terminal state except :success. Useful for gating
   downstream actions (notifications, badges) that should fire on
   anything-other-than-green."
  [r]
  (and (terminal? r) (not= :success r)))

;; ---------------------------------------------------------------------------
;; Observation map
;; ---------------------------------------------------------------------------

(defn default-observation
  "Construct an empty observation map. Callers (the executor + backends)
   call the recorder helpers below as the build progresses, then pass
   the final map to `classify`."
  []
  {:cancelled? false
   :shell-steps-run 0
   :nonzero-exits []
   :unresolved-credentials []
   :unresolved-tools []
   :unhonored-agents []
   :unsupported-constructs []
   :recorded-effects []
   :test-summary nil})

;; Recorder helpers — pure, return a new observation.

(defn mark-cancelled [obs] (assoc obs :cancelled? true))

(defn record-shell-step
  ([obs] (update obs :shell-steps-run inc))
  ([obs {:keys [exit-code]}]
   (cond-> (update obs :shell-steps-run inc)
     (and (some? exit-code) (not (zero? exit-code)))
     (update :nonzero-exits conj exit-code))))

(defn record-unresolved-credential [obs cred-id]
  (update obs :unresolved-credentials (fnil conj []) cred-id))

(defn record-unresolved-tool [obs tool-id]
  (update obs :unresolved-tools (fnil conj []) tool-id))

(defn record-unhonored-agent [obs agent-shape]
  (update obs :unhonored-agents (fnil conj []) agent-shape))

(defn record-unsupported-construct [obs construct]
  (update obs :unsupported-constructs (fnil conj []) construct))

(defn record-effect
  "Record a non-shell effect that should count as work done — archived
   artifact, parsed test report, persisted problem, sent notification."
  [obs effect-keyword]
  (update obs :recorded-effects (fnil conj []) effect-keyword))

(defn record-test-summary
  "summary: {:tests N :failures N :errors N :skipped N}. When :failures
   or :errors > 0 the build classifies as :unstable absent a worse signal."
  [obs summary]
  (assoc obs :test-summary summary))

;; ---------------------------------------------------------------------------
;; Classifier
;; ---------------------------------------------------------------------------

(defn- list-or-summary
  "Pretty-print a list of items for the `explain` string. Caps at 3 to
   keep the explanation a single readable line."
  [items]
  (let [items (vec items)
        n (count items)]
    (if (<= n 3)
      (str/join ", " (map str items))
      (str (str/join ", " (map str (subvec items 0 3)))
           " (+" (- n 3) " more)"))))

(defn classify
  "Run the rules against `obs` and return
     {:result KEYWORD
      :explain STRING       — human-readable, names what fired
      :rule KEYWORD}        — names which rule decided

   Always succeeds. Inputs outside the contract collapse to the
   :default branch."
  [{:keys [cancelled? shell-steps-run nonzero-exits
           unresolved-credentials unresolved-tools
           unhonored-agents unsupported-constructs
           recorded-effects test-summary]
    :as obs}]
  (cond
    cancelled?
    {:result :aborted
     :rule :aborted-by-signal
     :explain "build cancelled by signal or interrupt"}

    (seq unsupported-constructs)
    {:result :unsupported
     :rule :unsupported-construct
     :explain (str "IR contains construct(s) this executor cannot honor: "
                   (list-or-summary unsupported-constructs))}

    (seq unresolved-credentials)
    {:result :failure
     :rule :credential-unresolved
     :explain (str "required credential(s) could not be resolved: "
                   (list-or-summary unresolved-credentials))}

    (seq unresolved-tools)
    {:result :failure
     :rule :tool-unresolved
     :explain (str "required tool(s) could not be resolved: "
                   (list-or-summary unresolved-tools))}

    (seq unhonored-agents)
    {:result :unsupported
     :rule :agent-unhonored
     :explain (str "agent shape(s) this executor cannot honor: "
                   (list-or-summary unhonored-agents))}

    (seq nonzero-exits)
    {:result :failure
     :rule :step-nonzero-exit
     :explain (str (count nonzero-exits)
                   " shell step(s) exited non-zero — last exit: "
                   (peek nonzero-exits))}

    (and test-summary
         (or (pos? (or (:failures test-summary) 0))
             (pos? (or (:errors test-summary) 0))))
    {:result :unstable
     :rule :tests-failed
     :explain (str "tests: " (:failures test-summary 0) " failure(s), "
                   (:errors test-summary 0) " error(s)")}

    (and (zero? (or shell-steps-run 0))
         (empty? recorded-effects))
    {:result :neutral
     :rule :no-effects-recorded
     :explain (str "no shell steps ran and no effects were recorded — "
                   "build's IR walked but did nothing")}

    :else
    {:result :success
     :rule :default
     :explain (str shell-steps-run " shell step(s) ran, "
                   (count recorded-effects) " effect(s) recorded")}))

(defn explain
  "Convenience: run `classify` and return only the explain string. Useful
   for log lines."
  [obs]
  (:explain (classify obs)))
