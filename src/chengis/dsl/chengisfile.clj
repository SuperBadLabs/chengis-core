(ns chengis.dsl.chengisfile
  "Parse Chengisfile (EDN) from a repository into internal pipeline format.)

   The Chengisfile is a pure-data EDN file that lives at the root of a
   source repository — the Pipeline as Code equivalent of a Jenkinsfile.

   Format:
     {:description \"My pipeline\"
      :import [{:name \"shared-utils\"   :version \"1.2\"}
               {:name \"deploy-helpers\" :version \"@sha:abc1234\"}]
      :stages [{:name \"Build\"
                :steps [{:name \"Compile\" :run \"mvn compile\"}]}
               {:name \"Test\"
                :parallel true
                :steps [{:name \"Unit\" :run \"mvn test\"}
                        {:name \"Lint\" :run \"mvn lint\"}]}
               {:name \"Deploy\"
                :when {:branch \"main\"}
                :steps [{:name \"Ship\" :run \"./deploy.sh\"
                         :env {\"ENV\" \"prod\"}}]}]}

   Security: Uses clojure.edn/read-string (no code execution).
   Tagged literals are disabled."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Path helpers
;; ---------------------------------------------------------------------------

(defn chengisfile-path
  "Return the expected Chengisfile path within a workspace directory."
  [workspace-dir]
  (str workspace-dir "/Chengisfile"))

(defn chengisfile-exists?
  "Check whether a Chengisfile exists in the given workspace."
  [workspace-dir]
  (.exists (io/file (chengisfile-path workspace-dir))))

;; ---------------------------------------------------------------------------
;; Conversion: Chengisfile EDN → internal pipeline format
;; ---------------------------------------------------------------------------

(declare convert-condition)

(defn- convert-expr-condition
  "Convert a vector-form :when expression to an internal condition.
     [:and conds...]  → {:type :and :conditions [...]}
     [:or  conds...]  → {:type :or  :conditions [...]}
     [:not cond]      → {:type :not :condition  {...}}
   Recursive — leaf maps inside the expression pass through convert-condition,
   so nesting works:
     [:and {:branch \"main\"} [:or {:env \"X\" :value \"a\"} {:env \"X\" :value \"b\"}]]
   Returns nil on unknown operator so the validator can flag the typo."
  [v]
  (let [[op & args] v]
    (case op
      :and {:type :and :conditions (mapv convert-condition args)}
      :or  {:type :or  :conditions (mapv convert-condition args)}
      :not {:type :not :condition  (convert-condition (first args))}
      nil)))

(defn convert-condition
  "Convert a Chengisfile :when clause to an internal condition map.
   Leaf maps:
     {:branch \"main\"}              → {:type :branch :value \"main\"}
     {:param \"x\" :value \"y\"}     → {:type :param :param \"x\" :value \"y\"}
     {:env \"X\" :value \"v\"}       → {:type :env :var \"X\" :value \"v\"}
   Expression vectors (recursive composition):
     [:and conds...]  [:or conds...]  [:not cond]
   nil → nil"
  [when-clause]
  (cond
    (nil? when-clause) nil

    (vector? when-clause)
    (convert-expr-condition when-clause)

    (map? when-clause)
    (cond
      (contains? when-clause :branch)
      {:type :branch :value (:branch when-clause)}

      (contains? when-clause :param)
      {:type :param :param (:param when-clause) :value (:value when-clause)}

      (contains? when-clause :env)
      {:type :env :var (:env when-clause) :value (:value when-clause)}

      :else nil)

    :else nil))

(defn convert-step
  "Convert a Chengisfile step map to an internal step map.
   {:name \"Compile\" :run \"mvn compile\" :env {\"K\" \"V\"} :timeout 30000}
   → {:step-name \"Compile\" :type :shell :command \"mvn compile\" :env {\"K\" \"V\"} :timeout 30000}

   If the step has an :image key, it becomes a :docker type step.
   If the step has a :retry map, it is passed through verbatim — the
   executor reads :retry to drive the retry loop. Validated upstream.
   If the step has :continue-on-fail true, the executor will record the
   failure but not bail the stage. Validated upstream.
   If the step has a :when clause, the executor evaluates it and skips
   the step (:step-status :skipped) when the condition is false."
  [edn-step]
  (let [is-docker? (some? (:image edn-step))]
    (cond-> {:step-name (:name edn-step)
             :type      (if is-docker? :docker :shell)
             :command   (:run edn-step)}
      is-docker?                       (assoc :image (:image edn-step))
      (:env edn-step)                  (assoc :env (:env edn-step))
      (:timeout edn-step)              (assoc :timeout (:timeout edn-step))
      (:dir edn-step)                  (assoc :dir (:dir edn-step))
      (:volumes edn-step)              (assoc :volumes (:volumes edn-step))
      (:workdir edn-step)              (assoc :workdir (:workdir edn-step))
      (:network edn-step)              (assoc :network (:network edn-step))
      (:retry edn-step)                (assoc :retry (:retry edn-step))
      (contains? edn-step
                 :continue-on-fail)    (assoc :continue-on-fail
                                              (:continue-on-fail edn-step))
      ;; Step-level :when — convert to executor's :condition shape.
      ;; run-step already calls evaluate-condition on (:condition step-def),
      ;; so simply populating it here is enough to wire up step-level :when.
      (:when edn-step)                 (assoc :condition
                                              (convert-condition (:when edn-step))))))

(defn convert-stage
  "Convert a Chengisfile stage map to an internal stage map.
   {:name \"Build\" :parallel true :when {:branch \"main\"} :steps [...]}
   → {:stage-name \"Build\" :parallel? true :condition {...} :steps [...]}

   If the stage has a :container key, it is passed through for Docker wrapping."
  [edn-stage]
  (cond-> {:stage-name (:name edn-stage)
           :parallel?  (boolean (:parallel edn-stage))
           :steps      (mapv convert-step (:steps edn-stage))}
    (:when edn-stage)      (assoc :condition (convert-condition (:when edn-stage)))
    (:container edn-stage) (assoc :container (:container edn-stage))
    (:approval edn-stage)  (assoc :approval (:approval edn-stage))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- step-retry-errors
  "Return a vector of human-readable error strings for an invalid :retry
   clause on a step. Empty vector when :retry is absent or valid.

   Shared between stage-step and post-action-step validation so the same
   contract is enforced everywhere convert-step forwards :retry. Codex PR #17
   second-pass review caught the post-action gap — extracting this helper
   prevents the divergence."
  [step step-label]
  (if-not (:retry step)
    []
    (let [r (:retry step)]
      (cond
        (not (map? r))
        [(str step-label ": :retry must be a map")]

        (not (pos-int? (:max r)))
        [(str step-label ": :retry must have :max (positive integer); got " (pr-str (:max r)))]

        (and (contains? r :delay)
             (not (and (integer? (:delay r)) (not (neg? (:delay r))))))
        [(str step-label ": :retry :delay must be a non-negative integer (ms); got " (pr-str (:delay r)))]

        :else []))))

(defn- step-continue-on-fail-errors
  "Return a vector of error strings for a malformed :continue-on-fail clause.
   Contract: when present, must be literal true or false. Truthy-but-not-true
   values (1, \"yes\", :true) are rejected so the EDN-strict philosophy holds
   and a typo doesn't silently flip behavior.

   Same shared-helper pattern as step-retry-errors — called from both
   stage-step and post-action-step validation."
  [step step-label]
  (if-not (contains? step :continue-on-fail)
    []
    (let [v (:continue-on-fail step)]
      (if (boolean? v)
        []
        [(str step-label ": :continue-on-fail must be true or false; got " (pr-str v))]))))

(declare when-clause-errors)

(defn- when-leaf-errors
  "Validate a single leaf :when map. Must have one of :branch, :param, :env.
   :param requires both :param and a NON-NIL :value.
   :env requires both :env and a NON-NIL :value.

   The non-nil :value requirement matters: evaluate-condition does
   `(= (:value c) (get-in ctx [...]))` — if :value is nil AND the env var /
   parameter is absent, get-in returns nil, `(= nil nil)` is true, and the
   gated step runs when the variable is MISSING. That's the opposite of
   the user's intent, so we reject :value nil at parse time. (Codex PR #21 P2.)"
  [m label]
  (cond
    (not (map? m))
    [(str label ": :when leaf must be a map; got " (pr-str m))]

    (contains? m :branch)
    (if (string? (:branch m))
      []
      [(str label ": :when :branch must be a string; got " (pr-str (:branch m)))])

    (contains? m :param)
    (cond
      (not (string? (:param m)))
      [(str label ": :when :param must be a string; got " (pr-str (:param m)))]
      (not (contains? m :value))
      [(str label ": :when {:param ...} must also have :value")]
      (nil? (:value m))
      [(str label ": :when {:param ...} :value must not be nil")]
      :else [])

    (contains? m :env)
    (cond
      (not (string? (:env m)))
      [(str label ": :when :env must be a string; got " (pr-str (:env m)))]
      (not (contains? m :value))
      [(str label ": :when {:env ...} must also have :value")]
      (nil? (:value m))
      [(str label ": :when {:env ...} :value must not be nil")]
      :else [])

    :else
    [(str label ": :when leaf must contain one of :branch, :param, :env; got "
          (pr-str (vec (keys m))))]))

(defn- when-operand-errors
  "Validate a single operand INSIDE a :when expression vector.
   Operands must be a real condition — a leaf map or another expression vector.
   nil is rejected here (Codex PR #21 P2 round 2) because evaluate-condition
   would treat a nil child as true, causing `[:or nil ...]` / `[:not nil]`
   to silently invert or run gated steps unexpectedly. Top-level nil :when
   stays valid (means \"no condition\") via the contains?-guarded caller."
  [v label]
  (if (nil? v)
    [(str label ": nil operand not allowed inside :when expression")]
    (when-clause-errors v label)))

(defn- when-expr-errors
  "Validate a vector-form :when expression.
   [:and conds...] / [:or conds...] need at least one inner condition.
   [:not cond] needs exactly one. Any other operator is rejected.
   Operands are validated via when-operand-errors, which rejects nil."
  [v label]
  (cond
    (not (vector? v))
    [(str label ": :when expression must be a vector; got " (pr-str v))]

    (zero? (count v))
    [(str label ": :when expression vector must not be empty")]

    :else
    (let [[op & args] v]
      (case op
        (:and :or)
        (cond
          (zero? (count args))
          [(str label ": :when [" op "] must have at least one inner condition")]
          :else
          (vec (mapcat #(when-operand-errors % (str label " inside [" op "]")) args)))

        :not
        (cond
          (not= 1 (count args))
          [(str label ": :when [:not ...] takes exactly one inner condition; got "
                (count args))]
          :else
          (when-operand-errors (first args) (str label " inside [:not]")))

        ;; Unknown operator
        [(str label ": :when expression operator must be :and / :or / :not; got "
              (pr-str op))]))))

(defn- when-clause-errors
  "Top-level :when validator. Dispatches to leaf or expression form."
  [w label]
  (cond
    (nil? w) []
    (vector? w) (when-expr-errors w label)
    (map? w)    (when-leaf-errors w label)
    :else       [(str label ": :when must be a map or vector; got " (pr-str w))]))

(defn- step-when-errors
  "Return validation errors for a step's :when clause. Shared between
   stage-step and post-action-step validation via convert-step's pass-through."
  [step step-label]
  (if-not (contains? step :when)
    []
    (when-clause-errors (:when step) (str step-label " :when"))))

;; ---------------------------------------------------------------------------
;; :import validation (CHG-FEAT-005 PR2)
;; ---------------------------------------------------------------------------
;;
;; Top-level :import declares shared-library dependencies the resolver
;; (chengis.engine.library-resolver) hydrates before the pipeline runs:
;;
;;   :import [{:name "shared-utils"   :version "1.2"}
;;            {:name "deploy-helpers" :version "@sha:abc1234"}]
;;
;; Version-spec semantics MUST match
;; chengis.db.library-store/resolve-version's contract verbatim — PR1 baked
;; in the floating/exact/sha split and the resolver here only re-uses it.
;; That contract:
;;
;;   "latest"            — pointer to most-recently-published version
;;   "@sha:<hex>"        — exact SHA pin (the only case where a label-free
;;                         lookup happens)
;;   2+-dot label        — EXACT version match, no expansion
;;   <2-dot label        — floating-prefix scan; spec "v1" matches v1.x.y
;;
;; Validation at parse time covers SHAPE — name is a non-blank string,
;; version is a non-blank string in the supported shape. Anything that
;; resolves at runtime (does the library exist? does the SHA match?) is
;; the resolver's job and surfaces there.

(def ^:private sha-hex-re
  "Allowed character set for @sha:<hex>. We accept 4-40 hex chars so a
   developer can pin to a short SHA the way git itself does, but reject
   anything outside [0-9a-fA-F] (a webhook payload smuggling a path
   separator or shell metachar can't reach the git layer through here)."
  #"[0-9a-fA-F]{4,40}")

(def ^:private label-version-re
  "Allowed character set for a non-sha version spec. Git ref names are
   the union of letters, digits, '.', '-', '_', '/', and '+', so all
   are allowed here. The deliberate REJECTIONS are LIKE-style wildcards
   (% / *) and shell metacharacters (space, ;, |, $, etc.). The
   resolver's underlying SQL uses substr-equality, not LIKE, so the %
   guard is defence-in-depth — but enforcing the contract here is
   cheaper than relying on it and produces a much clearer user-facing
   error. Empty / blank caught separately above."
  ;; Char-class members (each appears literally): A-Z a-z 0-9 . _ + / -
  ;; The trailing `-` is at the end of the class so it's a literal dash.
  #"[A-Za-z0-9][A-Za-z0-9._+/_-]*")

(defn- import-version-spec-errors
  "Return a vector of error strings for one :version field. Empty when
   the spec is well-formed. The shape rules:

     - must be a non-blank string
     - \"latest\" passes
     - \"@sha:<hex>\" with 4-40 hex chars passes
     - any other string must match label-version-re

   We deliberately do NOT classify floating vs exact here — the dot
   count is a runtime detail of the resolver, not a Chengisfile concern.
   The version field's job is to be a syntactically-valid spec."
  [version label]
  (cond
    (not (string? version))
    [(str label ": :version must be a string; got " (pr-str version))]

    (str/blank? version)
    [(str label ": :version must not be blank")]

    (= "latest" version)
    []

    (str/starts-with? version "@sha:")
    (let [sha (subs version (count "@sha:"))]
      (if (re-matches sha-hex-re sha)
        []
        [(str label ": :version @sha: pin must be 4-40 hex chars; got "
              (pr-str sha))]))

    (re-matches label-version-re version)
    []

    :else
    [(str label ": :version spec contains disallowed characters; got "
          (pr-str version))]))

(defn- import-name-errors
  "Return a vector of error strings for one :name field. Library names
   are owner/path style (\"org/shared\", \"deploy-helpers\") — we allow
   the same character class as the version spec MINUS the leading-
   character constraint, plus an optional one '/' separator. Keep this
   permissive enough to match library-store's UNIQUE(org_id, name) —
   the registry side decides what name shapes it stores, this side
   just keeps shell-metas and path-traversal sequences out."
  [name label]
  (cond
    (not (string? name))
    [(str label ": :name must be a string; got " (pr-str name))]

    (str/blank? name)
    [(str label ": :name must not be blank")]

    ;; reject path-traversal early so the cache-dir layout
    ;; (<cache-dir>/<name>/<sha>) can't be tricked into writing outside
    ;; its root even before the resolver normalizes the path.
    (str/includes? name "..")
    [(str label ": :name must not contain '..' (path-traversal guard)")]

    (not (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" name))
    [(str label ": :name contains disallowed characters; got " (pr-str name))]

    :else
    []))

(defn- import-entry-errors
  "Validate one :import vector element."
  [entry label]
  (cond
    (not (map? entry))
    [(str label ": must be a map; got " (pr-str entry))]

    :else
    (let [base-errors (cond-> []
                        (not (contains? entry :name))
                        (conj (str label ": missing :name"))

                        (not (contains? entry :version))
                        (conj (str label ": missing :version")))]
      (cond-> base-errors
        (contains? entry :name)
        (into (import-name-errors (:name entry) label))

        (contains? entry :version)
        (into (import-version-spec-errors (:version entry) label))))))

(defn- imports-errors
  "Validate a top-level :import declaration (when present). Empty
   :import is rejected — a no-op import declaration adds noise without
   intent; users who don't want imports should omit the key entirely."
  [imports]
  (cond
    (nil? imports) []

    (not (vector? imports))
    [(str ":import must be a vector; got " (pr-str imports))]

    (empty? imports)
    [":import must not be empty — omit the key entirely if no imports"]

    :else
    (let [;; Per-entry shape errors. Clojure doesn't ship a single-pass
          ;; mapcat-indexed in core, so compose map-indexed + apply concat.
          per-entry (vec (apply concat
                                (map-indexed
                                 (fn [idx entry]
                                   (import-entry-errors
                                    entry (str ":import[" idx "]")))
                                 imports)))
          ;; Surface duplicate names early so a user reading the error
          ;; message doesn't have to mentally diff two near-identical
          ;; rows. Operates on valid name strings only.
          dup-names (->> imports
                         (keep #(when (map? %) (:name %)))
                         (filter string?)
                         frequencies
                         (filter (fn [[_ n]] (> n 1)))
                         (mapv first))
          dup-errors (mapv #(str ":import has duplicate :name "
                                 (pr-str %))
                           dup-names)]
      (into per-entry dup-errors))))

(defn convert-import
  "Convert one :import map entry to its internal pipeline representation.
   For now (PR2) the internal shape is the EDN as-given — kept as a
   distinct fn so PR3's lockfile wiring can hook in here without
   reaching back into the validator."
  [entry]
  {:name    (:name entry)
   :version (:version entry)})

(defn validate-chengisfile
  "Validate a parsed Chengisfile EDN map.
   Returns {:valid? bool :errors [\"error messages\"]}."
  [data]
  (let [errors (atom [])]
    ;; Top-level checks
    (when-not (map? data)
      (swap! errors conj "Chengisfile must be a map"))

    (when (map? data)
      ;; Validate :import (optional). Done before :stages so a malformed
      ;; import surfaces alongside structural errors in the same pass.
      (when (contains? data :import)
        (doseq [e (imports-errors (:import data))]
          (swap! errors conj e)))

      ;; :stages is required
      (when-not (:stages data)
        (swap! errors conj "Missing required key :stages"))

      (when (:stages data)
        (when-not (vector? (:stages data))
          (swap! errors conj ":stages must be a vector"))

        (when (and (vector? (:stages data)) (empty? (:stages data)))
          (swap! errors conj ":stages must not be empty"))

        (when (and (vector? (:stages data)) (seq (:stages data)))
          ;; Validate each stage
          (doseq [[idx stage] (map-indexed vector (:stages data))]
            (let [prefix (str "Stage " (inc idx))]
              (when-not (map? stage)
                (swap! errors conj (str prefix ": must be a map")))

              (when (map? stage)
                (when (str/blank? (:name stage))
                  (swap! errors conj (str prefix ": missing :name")))

                (when-not (:steps stage)
                  (swap! errors conj (str prefix " (" (or (:name stage) "?") "): missing :steps")))

                (when (:steps stage)
                  (when-not (vector? (:steps stage))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :steps must be a vector")))

                  (when (and (vector? (:steps stage)) (empty? (:steps stage)))
                    (swap! errors conj (str prefix " (" (or (:name stage) "?") "): :steps must not be empty")))

                  (when (and (vector? (:steps stage)) (seq (:steps stage)))
                    (doseq [[sidx step] (map-indexed vector (:steps stage))]
                      (let [step-prefix (str prefix " (" (or (:name stage) "?") "), Step " (inc sidx))]
                        (when-not (map? step)
                          (swap! errors conj (str step-prefix ": must be a map")))

                        (when (map? step)
                          (when (str/blank? (:name step))
                            (swap! errors conj (str step-prefix ": missing :name")))

                          (when (str/blank? (:run step))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): missing :run")))

                          (when (and (:env step) (not (map? (:env step))))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): :env must be a map")))

                          (when (and (:timeout step) (not (pos-int? (:timeout step))))
                            (swap! errors conj (str step-prefix " (" (or (:name step) "?") "): :timeout must be a positive integer")))

                          ;; :retry + :continue-on-fail + :when validation —
                          ;; shared with post-action steps via dedicated helpers.
                          ;; Same contract enforced wherever convert-step forwards
                          ;; these keys.
                          (let [step-label (str step-prefix " (" (or (:name step) "?") ")")]
                            (doseq [e (step-retry-errors step step-label)]
                              (swap! errors conj e))
                            (doseq [e (step-continue-on-fail-errors step step-label)]
                              (swap! errors conj e))
                            (doseq [e (step-when-errors step step-label)]
                              (swap! errors conj e)))))))

                ;; Validate :when clause via the shared helper. Stage-level
                ;; uses the same contract as step-level (map leaf or expression
                ;; vector with :and / :or / :not).
                  (doseq [e (when-clause-errors
                             (:when stage)
                             (str prefix " (" (or (:name stage) "?") ")"))]
                    (swap! errors conj e))))))))

      ;; Validate :post section if present
      (when-let [post (:post data)]
        (when-not (map? post)
          (swap! errors conj ":post must be a map"))
        (when (map? post)
          (doseq [group-key [:always :on-success :on-failure]]
            (when-let [steps (get post group-key)]
              (when-not (vector? steps)
                (swap! errors conj (str ":post " (name group-key) " must be a vector")))
              (when (vector? steps)
                (doseq [[idx step] (map-indexed vector steps)]
                  (let [prefix (str ":post " (name group-key) " step " (inc idx))]
                    (when-not (map? step)
                      (swap! errors conj (str prefix ": must be a map")))
                    (when (map? step)
                      (when (str/blank? (:name step))
                        (swap! errors conj (str prefix ": missing :name")))
                      (when (str/blank? (:run step))
                        (swap! errors conj (str prefix ": missing :run")))
                      ;; convert-step forwards :retry + :continue-on-fail + :when
                      ;; for post-action steps too — same contract enforced via
                      ;; the shared helpers (Codex PR #17 P1 set the pattern).
                      (let [step-label (str prefix " (" (or (:name step) "?") ")")]
                        (doseq [e (step-retry-errors step step-label)]
                          (swap! errors conj e))
                        (doseq [e (step-continue-on-fail-errors step step-label)]
                          (swap! errors conj e))
                        (doseq [e (step-when-errors step step-label)]
                          (swap! errors conj e))))))))))))

    {:valid? (empty? @errors)
     :errors @errors}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn parse-chengisfile
  "Parse a Chengisfile from disk. Returns:
   {:pipeline {:description ... :stages [...]}} on success
   {:error \"message\"} on failure

   Uses clojure.edn/read-string for safe, no-code-execution parsing.
   File size limited to 1MB for safety."
  [file-path]
  (try
    (let [f (io/file file-path)]
      (when-not (.exists f)
        (throw (ex-info "File not found" {:path file-path})))

      ;; Size check
      (when (> (.length f) (* 1024 1024))
        (throw (ex-info "Chengisfile exceeds 1MB size limit" {:size (.length f)})))

      (let [content (slurp f)
            ;; Safe EDN parsing — no tagged literals, no code execution
            data (edn/read-string {:readers {}} content)
            ;; Validate
            {:keys [valid? errors]} (validate-chengisfile data)]
        (if-not valid?
          {:error (str "Validation failed: " (str/join "; " errors))}
          ;; Convert to internal format
          (let [stages (mapv convert-stage (:stages data))
                imports (when-let [imp (:import data)]
                          (mapv convert-import imp))
                post-actions (when-let [post (:post data)]
                               (let [convert-post-steps (fn [steps]
                                                          (when (seq steps)
                                                            (mapv convert-step steps)))]
                                 (cond-> {}
                                   (:always post)     (assoc :always (convert-post-steps (:always post)))
                                   (:on-success post) (assoc :on-success (convert-post-steps (:on-success post)))
                                   (:on-failure post) (assoc :on-failure (convert-post-steps (:on-failure post))))))
                artifact-patterns (when-let [arts (:artifacts data)]
                                    (vec arts))
                notify-configs (when-let [notifs (:notify data)]
                                 (vec notifs))
                matrix-config (:matrix data)
                pipeline (cond-> {:stages stages}
                           (:description data)    (assoc :description (:description data))
                           (:container data)      (assoc :container (:container data))
                           (seq imports)           (assoc :imports imports)
                           (seq post-actions)      (assoc :post-actions post-actions)
                           (seq artifact-patterns) (assoc :artifacts artifact-patterns)
                           (seq notify-configs)    (assoc :notify notify-configs)
                           matrix-config           (assoc :matrix matrix-config))]
            (log/info "Chengisfile parsed successfully:"
                      (count stages) "stages")
            {:pipeline (cond-> pipeline
                         (:extends data) (assoc :extends (:extends data)))}))))
    (catch Exception e
      {:error (str "Failed to parse Chengisfile: " (.getMessage e))})))
