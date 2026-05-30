(ns chengis.engine.executor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.secret-store :as secret-store]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.dsl.core :as dsl]
            [chengis.dsl.yaml :as yaml-parser]
            [chengis.engine.approval :as approval]
            [chengis.engine.artifacts :as artifacts]
            [chengis.engine.cache :as cache]
            [chengis.engine.executor :as executor]
            [chengis.engine.git :as git]
            [chengis.engine.license-scanner :as license]
            [chengis.engine.notify :as notify]
            [chengis.engine.policy :as policy]
            [chengis.engine.process :as process]
            [chengis.engine.provenance :as provenance]
            [chengis.engine.sbom :as sbom]
            [chengis.engine.signing :as signing]
            [chengis.engine.stage-cache :as stage-cache]
            [chengis.engine.vulnerability-scanner :as vuln]
            [chengis.engine.workspace :as workspace]
            [chengis.metrics :as metrics]
            [chengis.plugin.loader :as plugin-loader]))

(use-fixtures :once (fn [f] (plugin-loader/load-plugins!) (f)))

(def test-system
  {:config {:workspace {:root "/tmp/chengis-test-workspaces"}}})

(defn cleanup-test-workspaces []
  (workspace/cleanup-workspace "/tmp/chengis-test-workspaces"))

(deftest run-build-simple
  (testing "simple pipeline with echo commands succeeds"
    (let [pipeline (dsl/defpipeline test-simple
                     (dsl/stage "Hello"
                                (dsl/step "Greet" (dsl/sh "echo 'hello world'"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 1 (count (:stage-results result))))
      (is (= :success (-> result :stage-results first :stage-status)))
      (is (= "hello world\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

(deftest run-build-multi-stage
  (testing "multi-stage pipeline runs stages sequentially"
    (let [pipeline (dsl/defpipeline test-multi
                     (dsl/stage "One"
                                (dsl/step "S1" (dsl/sh "echo 'stage one'")))
                     (dsl/stage "Two"
                                (dsl/step "S2" (dsl/sh "echo 'stage two'"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 2 (count (:stage-results result))))
      (is (every? #(= :success (:stage-status %)) (:stage-results result)))
      (cleanup-test-workspaces))))

(deftest run-build-failure-stops-pipeline
  (testing "stage failure stops the pipeline"
    (let [pipeline (dsl/defpipeline test-fail
                     (dsl/stage "Pass"
                                (dsl/step "OK" (dsl/sh "echo ok")))
                     (dsl/stage "Fail"
                                (dsl/step "Bad" (dsl/sh "exit 1")))
                     (dsl/stage "Never"
                                (dsl/step "Skip" (dsl/sh "echo never"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :failure (:build-status result)))
      ;; Only 2 stages ran (third was skipped due to pipeline stop)
      (is (= 2 (count (:stage-results result))))
      (is (= :success (-> result :stage-results first :stage-status)))
      (is (= :failure (-> result :stage-results second :stage-status)))
      (cleanup-test-workspaces))))

(deftest run-build-parallel-steps
  (testing "parallel steps run concurrently"
    (let [pipeline (dsl/defpipeline test-parallel
                     (dsl/stage "Parallel"
                                (dsl/parallel
                                 (dsl/step "A" (dsl/sh "echo a && sleep 0.2"))
                                 (dsl/step "B" (dsl/sh "echo b && sleep 0.2"))
                                 (dsl/step "C" (dsl/sh "echo c && sleep 0.2")))))
          start (System/currentTimeMillis)
          result (executor/run-build test-system pipeline {})
          duration (- (System/currentTimeMillis) start)]
      (is (= :success (:build-status result)))
      (is (= 3 (count (-> result :stage-results first :step-results))))
      ;; All three steps should complete; if truly parallel,
      ;; total time should be closer to 200ms than 600ms
      (is (< duration 2000) "Parallel steps should not take 3x sequential time")
      (cleanup-test-workspaces))))

(deftest run-build-with-env
  (testing "environment variables are passed to steps"
    (let [pipeline (dsl/defpipeline test-env
                     (dsl/stage "Env"
                                (dsl/step "Check" (dsl/sh "echo $MY_VAR"
                                                          :env {"MY_VAR" "chengis"}))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= "chengis\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

(deftest run-build-with-condition
  (testing "conditional step skips when condition not met"
    (let [pipeline (dsl/defpipeline test-cond
                     (dsl/stage "Deploy"
                                (dsl/when-branch "release"
                                                 (dsl/step "Deploy" (dsl/sh "echo deploying")))))
          result (executor/run-build test-system pipeline
                                     {:parameters {:branch "develop"}})]
      ;; Step should be skipped because branch != "release"
      (is (= :skipped (-> result :stage-results first :step-results first :step-status)))
      (cleanup-test-workspaces)))

  (testing "conditional step runs when condition met"
    (let [pipeline (dsl/defpipeline test-cond-pass
                     (dsl/stage "Deploy"
                                (dsl/when-branch "main"
                                                 (dsl/step "Deploy" (dsl/sh "echo deploying")))))
          result (executor/run-build test-system pipeline
                                     {:parameters {:branch "main"}})]
      (is (= :success (-> result :stage-results first :step-results first :step-status)))
      (is (= "deploying\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

;; ---------------------------------------------------------------------------
;; Phase 3c: Executor condition evaluation, cancellation, event-fn tests
;; ---------------------------------------------------------------------------

(deftest evaluate-condition-nil-returns-true-test
  (testing "nil condition evaluates to true (unconditional)"
    (is (true? (#'executor/evaluate-condition nil {}))
        "nil condition should return true")))

(deftest evaluate-condition-always-type-test
  (testing ":always condition type returns true"
    (is (true? (#'executor/evaluate-condition {:type :always} {}))
        ":always should return true")))

(deftest evaluate-condition-unknown-type-test
  (testing "unknown condition type defaults to true"
    (is (true? (#'executor/evaluate-condition {:type :unknown-xyz} {}))
        "Unknown condition type should default to true")))

(deftest evaluate-condition-branch-default-main-test
  (testing "branch condition defaults to 'main' when no :branch param"
    (is (true? (#'executor/evaluate-condition
                {:type :branch :value "main"} {}))
        "Should match default 'main' when no branch param provided")
    (is (false? (#'executor/evaluate-condition
                 {:type :branch :value "release"} {}))
        "Should not match 'release' against default 'main'")))

(deftest evaluate-condition-param-type-test
  (testing ":param condition matches parameter value"
    (is (true? (#'executor/evaluate-condition
                {:type :param :param "env" :value "production"}
                {:parameters {:env "production"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :param :param "env" :value "production"}
                 {:parameters {:env "staging"}})))))

(deftest cancelled-build-aborts-step-test
  (testing "cancelled build returns :aborted step status"
    (let [cancelled (atom true)
          build-ctx {:build-id "cancel-test"
                     :cancelled? cancelled
                     :current-stage "Deploy"}
          step-def {:step-name "should-abort" :command "echo never"}
          result (executor/run-step build-ctx step-def)]
      (is (= :aborted (:step-status result)))
      (is (= -2 (:exit-code result)))
      ;; Reason fragment: the abort path must surface the exact
      ;; "Build cancelled" stderr sentinel. This distinguishes the
      ;; pre-flight cancellation branch from a step :failure (which
      ;; carries the underlying command's stderr) and from the
      ;; :cancelled? result branch reached from process/execute-command.
      (is (re-find #"Build cancelled" (:stderr result))
          ":stderr must be the 'Build cancelled' sentinel"))))

(deftest event-fn-receives-events-test
  (testing "event-fn is called with step-started and step-completed events"
    (let [events (atom [])
          build-ctx {:build-id "evt-test"
                     :current-stage "Build"
                     :event-fn (fn [evt] (swap! events conj evt))
                     :workspace "/tmp"
                     :env {}}
          step-def {:step-name "echo-hi" :command "echo hi" :type :shell}]
      (executor/run-step build-ctx step-def)
      ;; Should have at least step-started and step-completed
      (let [types (set (map :event-type @events))]
        (is (contains? types :step-started)
            "Should emit :step-started event")
        (is (contains? types :step-completed)
            "Should emit :step-completed event")
        ;; Each event should have build-id and timestamp
        (doseq [evt @events]
          (is (= "evt-test" (:build-id evt)))
          (is (some? (:timestamp evt))))))))

(deftest step-condition-skip-emits-completed-test
  (testing "skipped step still emits step-completed event"
    (let [events (atom [])
          build-ctx {:build-id "skip-test"
                     :current-stage "Deploy"
                     :event-fn (fn [evt] (swap! events conj evt))
                     :parameters {:branch "develop"}}
          step-def {:step-name "deploy"
                    :command "echo deploy"
                    :condition {:type :branch :value "release"}}
          result (executor/run-step build-ctx step-def)]
      (is (= :skipped (:step-status result)))
      (is (some #(= :step-completed (:event-type %)) @events)
          "Skipped step should still emit step-completed"))))

;; ---------------------------------------------------------------------------
;; CHG-MUT-009: targeted mutation-killing tests
;; ---------------------------------------------------------------------------

;; ---- run-step: status cond, exact return values, duration metric ----

(deftest run-step-success-exact-result-test
  (testing "successful step returns exact status/exit/stdout/stderr/duration"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "OUT" :stderr "" :duration-ms 1234})]
      (let [build-ctx {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
            step-def {:step-name "ok" :command "x" :type :shell}
            r (executor/run-step build-ctx step-def)]
        (is (= :success (:step-status r)))
        (is (= 0 (:exit-code r)))
        (is (= "OUT" (:stdout r)))
        (is (= "" (:stderr r)))
        (is (= 1234 (:duration-ms r)))
        (is (= "ok" (:step-name r)))))))

(deftest run-step-failure-nonzero-exit-test
  (testing "non-zero exit code yields :failure with that exit code (kills const-zero-one/comp-eq-neq)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 7 :stdout "" :stderr "boom" :duration-ms 5})]
      (let [r (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
                                 {:step-name "bad" :command "x" :type :shell})]
        (is (= :failure (:step-status r)))
        (is (= 7 (:exit-code r)))
        (is (= "boom" (:stderr r)))))))

(deftest run-step-cancelled-result-aborts-test
  (testing ":cancelled? in process result yields :aborted (kills status cond first branch)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code -2 :cancelled? true :stdout "" :stderr "" :duration-ms 1})]
      (let [r (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
                                 {:step-name "c" :command "x" :type :shell})]
        (is (= :aborted (:step-status r)))))))

(deftest run-step-cancelled-build-exact-fields-test
  (testing "cancelled build returns empty stdout and 'Build cancelled' stderr (kills const-empty-str / const-nonempty-str-empty)"
    (let [r (executor/run-step {:build-id "b" :current-stage "S"
                                :cancelled? (atom true)}
                               {:step-name "x" :command "echo"})]
      (is (= :aborted (:step-status r)))
      (is (= -2 (:exit-code r)))
      (is (= "" (:stdout r)))
      (is (= "Build cancelled" (:stderr r))))))

(deftest run-step-skipped-exit-code-zero-test
  (testing "skipped step has exit-code 0 exactly (kills const-zero-one at line 92)"
    (let [r (executor/run-step {:build-id "b" :current-stage "S"
                                :parameters {:branch "develop"}}
                               {:step-name "d" :command "echo"
                                :condition {:type :branch :value "release"}})]
      (is (= :skipped (:step-status r)))
      (is (= 0 (:exit-code r))))))

(deftest run-step-records-duration-metric-test
  (testing "step duration metric is recorded with ms/1000 seconds (kills arith-div-mult line 133)"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 2000})
                    chengis.metrics/record-step-duration!
                    (fn [_reg step-name status secs]
                      (reset! captured {:step step-name :status status :secs secs}))]
        (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}
                            :metrics-registry :reg}
                           {:step-name "m" :command "x" :type :shell})
        (is (= 2.0 (:secs @captured)) "2000ms should be 2.0 seconds (division, not multiplication)")
        (is (= :success (:status @captured)))
        (is (= "m" (:step @captured)))))))

(deftest run-step-env-merge-order-test
  (testing "step env overrides build env (kills clj-merge-swap at line 107)"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command
                    (fn [opts] (reset! captured opts)
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp"
                            :env {"K" "build" "ONLY-BUILD" "1"}}
                           {:step-name "e" :command "x" :type :shell
                            :env {"K" "step" "ONLY-STEP" "2"}})
        (is (= "step" (get-in @captured [:env "K"])) "step env should win over build env")
        (is (= "1" (get-in @captured [:env "ONLY-BUILD"])))
        (is (= "2" (get-in @captured [:env "ONLY-STEP"])))))))

(deftest run-step-mask-values-assoc-test
  (testing "mask-values present -> assoc onto command opts (kills coll-assoc-dissoc/nil-when line 109-110)"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command
                    (fn [opts] (reset! captured opts)
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}
                            :mask-values #{"secret1"}}
                           {:step-name "e" :command "x" :type :shell})
        (is (= #{"secret1"} (:mask-values @captured)) "mask-values should be assoc'd")))))

(deftest run-step-no-mask-values-no-assoc-test
  (testing "empty mask-values -> not assoc'd (other side of the seq branch)"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command
                    (fn [opts] (reset! captured opts)
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (executor/run-step {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}
                            :mask-values #{}}
                           {:step-name "e" :command "x" :type :shell})
        (is (not (contains? @captured :mask-values)))))))

;; ---- run-steps-sequential: stop on failure/abort, cancellation ----

(deftest run-steps-sequential-stops-on-failure-test
  (testing "sequential steps stop after first failure (kills coll-first-last / reduced branch)"
    (with-redefs [process/execute-command
                  (fn [{:keys [command]}]
                    (if (= command "FAIL")
                      {:exit-code 1 :stdout "" :stderr "e" :duration-ms 1}
                      {:exit-code 0 :stdout command :stderr "" :duration-ms 1}))]
      (let [steps [{:step-name "a" :command "A" :type :shell}
                   {:step-name "b" :command "FAIL" :type :shell}
                   {:step-name "c" :command "C" :type :shell}]
            results (#'executor/run-steps-sequential
                     {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}} steps)]
        (is (= 2 (count results)) "should stop after the failing step")
        (is (= "a" (-> results first :step-name)))
        (is (= :failure (-> results second :step-status)))
        ;; Reason fragment: the failing step result must carry the
        ;; underlying command's stderr ("e") — kills mutants that drop
        ;; or rewrite stderr on the abort-on-failure path and confirms
        ;; the failure-classification differs from the cancellation
        ;; sentinel ("Build cancelled").
        (is (= "e" (-> results second :stderr))
            "failing step must preserve underlying command stderr")
        (is (= 1 (-> results second :exit-code))
            "failing step must preserve underlying command exit-code")))))

(deftest run-steps-sequential-cancelled-stops-test
  (testing "sequential run halts immediately when build is cancelled"
    (let [results (#'executor/run-steps-sequential
                   {:build-id "b" :current-stage "S" :cancelled? (atom true)}
                   [{:step-name "a" :command "A"}])]
      (is (= [] results)))))

(deftest run-steps-sequential-all-succeed-order-test
  (testing "all steps run in order when none fail (kills coll-first-last on results conj)"
    (with-redefs [process/execute-command
                  (fn [{:keys [command]}] {:exit-code 0 :stdout command :stderr "" :duration-ms 1})]
      (let [results (#'executor/run-steps-sequential
                     {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
                     [{:step-name "first" :command "1" :type :shell}
                      {:step-name "last" :command "2" :type :shell}])]
        (is (= ["first" "last"] (mapv :step-name results)))
        (is (every? #(= :success (:step-status %)) results))))))

;; ---- run-steps-parallel ----

(deftest run-steps-parallel-runs-all-test
  (testing "parallel steps all run and results preserve INPUT order/count
            (kills coll-first-last: run-steps-parallel docstring guarantees
            results[i] corresponds to input-steps[i] regardless of which
            thread completes first; assert vector equality, not set)"
    (with-redefs [process/execute-command
                  ;; Stagger completion so completion-order != input-order
                  ;; on the bounded pool: p0 (largest sleep) finishes last,
                  ;; p9 (smallest sleep) finishes first. A buggy reorder
                  ;; (e.g. returning completion order, or first/last
                  ;; swapped) would surface as a vector mismatch here.
                  (fn [{:keys [command]}]
                    (let [n (Long/parseLong command)]
                      (Thread/sleep (- 30 (* 3 n)))
                      {:exit-code 0 :stdout command :stderr "" :duration-ms 1}))]
      (let [steps   (mapv (fn [i] {:step-name (str "p" i)
                                   :command (str i)
                                   :type :shell})
                          (range 10))
            results (#'executor/run-steps-parallel
                     {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}
                      :max-parallel-steps 2}
                     steps)]
        (is (= 10 (count results)))
        ;; Exact vector match — INPUT order, NOT set/permutation. This is
        ;; the key assertion that kills the `coll-first-last` mutant on
        ;; the result-aggregation in run-steps-parallel: if the source
        ;; reorders results (e.g. returns them in completion order, or
        ;; rotates first<->last), this equality fails.
        (is (= ["p0" "p1" "p2" "p3" "p4" "p5" "p6" "p7" "p8" "p9"]
               (mapv :step-name results)))
        ;; Spot-check first/last explicitly — directly targets the
        ;; coll-first-last mutant class which swaps (first x) <-> (last x).
        (is (= "p0" (:step-name (first results))))
        (is (= "p9" (:step-name (last results))))))))

;; ---- Bounded pool: thread-count regression spy ----
;;
;; Pre-fix, `run-steps-parallel` allocated one `core.async/thread` per step.
;; Fanning out 50 steps spawned 50 threads (most blocked on a Semaphore).
;; Post-fix, all parallel steps share a single fixed-size pool — the live
;; thread count is bounded by the pool size (default 8), no matter how
;; many steps fan out.
;;
;; This spy uses `with-redefs` to capture `(Thread/currentThread).getName`
;; for every dispatched step into a concurrent set (ConcurrentHashMap-backed,
;; safe under concurrent insertion). If the bounded pool is in place, we
;; see at most ~8 distinct thread names. Pre-fix, we would see ~50.
(deftest run-steps-parallel-thread-count-bounded-test
  (testing "50 parallel steps reuse the bounded pool (no thread ballooning)"
    (let [thread-names (java.util.concurrent.ConcurrentHashMap/newKeySet)
          step-count (atom 0)
          steps (mapv (fn [i] {:step-name (str "p" i)
                               :command (str "echo " i)
                               :type :shell})
                      (range 50))]
      (with-redefs [process/execute-command
                    (fn [{:keys [command]}]
                      (.add thread-names (.getName (Thread/currentThread)))
                      (swap! step-count inc)
                      ;; Hold the thread briefly so concurrent steps actually
                      ;; overlap on different threads — without this, a fast
                      ;; serial-looking execution could artificially shrink
                      ;; the observed thread set even with the old code.
                      (Thread/sleep 5)
                      {:exit-code 0 :stdout command :stderr "" :duration-ms 5})]
        (let [results (#'executor/run-steps-parallel
                       {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
                       steps)]
          ;; All 50 steps actually executed (not silently dropped).
          (is (= 50 (count results)))
          (is (= 50 @step-count))
          (is (every? #(= :success (:step-status %)) results))
          ;; Thread-count cap: default pool is 8, allow a small fudge for
          ;; any executor internals or test-runner threads that might sneak
          ;; in. Pre-fix would yield ~50 distinct names.
          (is (<= (count thread-names) 10)
              (str "Expected ≤10 distinct threads (bounded pool), got "
                   (count thread-names) ": " thread-names))
          ;; All observed thread names should belong to our pool.
          (is (every? #(re-find #"^chengis-parallel-step-" %) thread-names)
              (str "Unexpected thread name: " thread-names)))))))

;; Codex P1 follow-up regression: init-pools! must honor the configured
;; [:thread-pools :max-parallel-steps] value. Pre-fix, the defonce'd pool
;; was sized only from CHENGIS_MAX_PARALLEL_STEPS env at namespace load,
;; so the config.edn knob (and its CHENGIS_MAX_PARALLEL_STEPS_* env-var
;; mappings via chengis.config) had no effect.
;;
;; Test contract: after init-pools! with N=2, 30 parallel steps must
;; reuse ≤ 4 distinct threads. With the pre-fix shape (init-pools! a
;; no-op), the default-sized pool of 8 would yield more.
(deftest init-pools-honors-config-max-parallel-steps-test
  (testing "executor/init-pools! resizes the parallel-step pool to the
            configured [:thread-pools :max-parallel-steps] value"
    (let [thread-names (java.util.concurrent.ConcurrentHashMap/newKeySet)
          steps (mapv (fn [i] {:step-name (str "cfg" i)
                               :command (str "echo " i)
                               :type :shell})
                      (range 30))]
      (try
        ;; Configure the pool to 2 — must shrink from the default 8.
        (executor/init-pools! {:thread-pools {:max-parallel-steps 2}
                               :parallel-stages {:max-concurrent 4}})
        (with-redefs [process/execute-command
                      (fn [{:keys [command]}]
                        (.add thread-names (.getName (Thread/currentThread)))
                        ;; Hold the thread so concurrent work actually overlaps.
                        (Thread/sleep 10)
                        {:exit-code 0 :stdout command :stderr "" :duration-ms 10})]
          (let [results (#'executor/run-steps-parallel
                         {:build-id "b" :current-stage "S" :workspace "/tmp" :env {}}
                         steps)]
            (is (= 30 (count results)))
            (is (every? #(= :success (:step-status %)) results))
            ;; Pool size 2 + fudge for any test-runner stragglers / executor
            ;; internals. Pre-fix (init-pools! no-op) would give up to 8.
            (is (<= (count thread-names) 4)
                (str "init-pools! must downsize the pool to 2; observed "
                     (count thread-names) " distinct threads: "
                     thread-names))))
        (finally
          ;; Restore defaults so subsequent tests aren't pinned to N=2.
          (executor/init-pools! {}))))))

;; ---- containerize-steps ----

(deftest containerize-steps-nil-config-passthrough-test
  (testing "no container config returns steps unchanged (kills nil-if-if-not line 178)"
    (let [steps [{:step-name "a" :type :shell :command "x"}]]
      (is (= steps (#'executor/containerize-steps steps nil))))))

(deftest containerize-steps-wraps-shell-test
  (testing "shell steps wrapped to :docker with image (kills nil-if/comp-eq-neq line 181, merge line 182)"
    (let [out (#'executor/containerize-steps
               [{:step-name "a" :command "x"}]
               {:image "alpine"})]
      (is (= :docker (:type (first out))))
      (is (= "alpine" (:image (first out))))
      (is (= "x" (:command (first out))) "command preserved through merge"))))

(deftest containerize-steps-leaves-docker-untouched-test
  (testing "existing :docker step is not re-wrapped (other side of = :shell branch)"
    (let [step {:step-name "a" :type :docker :image "orig" :command "x"}
          out (#'executor/containerize-steps [step] {:image "alpine"})]
      (is (= "orig" (:image (first out))) "existing docker image must not be overwritten"))))

(deftest containerize-steps-optional-keys-test
  (testing "each optional container key is conditionally merged (kills nil-when lines 185-195)"
    (let [cfg {:image "img" :volumes ["v"] :workdir "/w" :network "net"
               :pull-policy "always" :docker-args ["--rm"] :cache-volumes ["c"]}
          out (first (#'executor/containerize-steps [{:step-name "a" :command "x"}] cfg))]
      (is (= ["v"] (:volumes out)))
      (is (= "/w" (:workdir out)))
      (is (= "net" (:network out)))
      (is (= "always" (:pull-policy out)))
      (is (= ["--rm"] (:docker-args out)))
      (is (= ["c"] (:cache-volumes out))))
    (testing "absent optional keys are not added"
      (let [out (first (#'executor/containerize-steps
                        [{:step-name "a" :command "x"}] {:image "img"}))]
        (is (not (contains? out :volumes)))
        (is (not (contains? out :workdir)))
        (is (not (contains? out :network)))
        (is (not (contains? out :pull-policy)))
        (is (not (contains? out :docker-args)))
        (is (not (contains? out :cache-volumes)))))))

;; ---- run-stage: status aggregation, aborted/skipped/empty result vectors ----

(deftest run-stage-cancelled-empty-step-results-test
  (testing "cancelled stage returns :aborted with [] step-results (kills const-empty-vec-nil line 217)"
    (let [r (executor/run-stage {:build-id "b" :cancelled? (atom true)}
                                {:stage-name "S" :steps [{:step-name "x" :command "y"}]})]
      (is (= :aborted (:stage-status r)))
      (is (= [] (:step-results r)))
      (is (= "S" (:stage-name r))))))

(deftest run-stage-skipped-empty-step-results-test
  (testing "stage with unmet condition is :skipped with [] step-results (kills const-empty-vec-nil line 226)"
    (let [r (executor/run-stage {:build-id "b" :parameters {:branch "dev"}}
                                {:stage-name "S"
                                 :condition {:type :branch :value "main"}
                                 :steps [{:step-name "x" :command "y"}]})]
      (is (= :skipped (:stage-status r)))
      (is (= [] (:step-results r))))))

(deftest run-stage-success-status-test
  (testing "all steps succeed -> stage :success (kills cond final branch)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [r (executor/run-stage {:build-id "b" :workspace "/tmp" :env {}}
                                  {:stage-name "S" :parallel? false
                                   :steps [{:step-name "a" :command "x" :type :shell}]})]
        (is (= :success (:stage-status r)))))))

(deftest run-stage-failure-status-test
  (testing "a failing step -> stage :failure"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 1 :stdout "" :stderr "e" :duration-ms 1})]
      (let [r (executor/run-stage {:build-id "b" :workspace "/tmp" :env {}}
                                  {:stage-name "S" :parallel? false
                                   :steps [{:step-name "a" :command "x" :type :shell}]})]
        (is (= :failure (:stage-status r)))))))

(deftest run-stage-aborted-status-test
  (testing "an aborted step -> stage :aborted (precedence over failure, kills cond ordering)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code -2 :cancelled? true :stdout "" :stderr "" :duration-ms 1})]
      (let [r (executor/run-stage {:build-id "b" :workspace "/tmp" :env {}}
                                  {:stage-name "S" :parallel? false
                                   :steps [{:step-name "a" :command "x" :type :shell}]})]
        (is (= :aborted (:stage-status r)))))))

(deftest run-stage-all-skipped-status-test
  (testing "every step skipped -> stage :skipped (kills every? + cond all-skipped branch)"
    (let [r (executor/run-stage {:build-id "b" :parameters {:branch "dev"}}
                                {:stage-name "S" :parallel? false
                                 :steps [{:step-name "a" :command "x"
                                          :condition {:type :branch :value "main"}}
                                         {:step-name "b" :command "y"
                                          :condition {:type :branch :value "main"}}]})]
      (is (= :skipped (:stage-status r))))))

(deftest run-stage-records-duration-metric-test
  (testing "stage duration recorded as positive seconds via nanoTime diff /1e9 (kills arith line 249)"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})
                    chengis.metrics/record-stage-duration!
                    (fn [_reg stage-name status secs]
                      (reset! captured {:stage stage-name :status status :secs secs}))]
        (executor/run-stage {:build-id "b" :workspace "/tmp" :env {} :metrics-registry :reg}
                            {:stage-name "S" :parallel? false
                             :steps [{:step-name "a" :command "x" :type :shell}]})
        (is (= "S" (:stage @captured)))
        (is (= :success (:status @captured)))
        (is (>= (:secs @captured) 0.0) "duration must be non-negative (subtraction order)")
        (is (< (:secs @captured) 60.0))))))

(deftest run-stage-parallel-mode-test
  (testing "parallel? true uses parallel runner (kills if-not branch line 235);
            step-results preserve INPUT order even with staggered completion
            (kills coll-first-last on the stage-level result vector)"
    (with-redefs [process/execute-command
                  ;; "a" sleeps longer than "b": b completes first.
                  ;; If results were returned in completion order, first
                  ;; would be "b". Source contract is input order.
                  (fn [{:keys [command]}]
                    (when (= command "1") (Thread/sleep 25))
                    {:exit-code 0 :stdout command :stderr "" :duration-ms 1})]
      (let [r (executor/run-stage {:build-id "b" :workspace "/tmp" :env {}}
                                  {:stage-name "S" :parallel? true
                                   :steps [{:step-name "a" :command "1" :type :shell}
                                           {:step-name "b" :command "2" :type :shell}]})]
        (is (= :success (:stage-status r)))
        (is (= 2 (count (:step-results r))))
        ;; Pin INPUT order on the stage-level result vector.
        (is (= ["a" "b"] (mapv :step-name (:step-results r))))
        (is (= "a" (:step-name (first (:step-results r)))))
        (is (= "b" (:step-name (last (:step-results r)))))))))

;; ---- run-post-actions ----

(deftest run-post-actions-nil-when-empty-test
  (testing "no post-actions -> nil (kills nil-when line 278)"
    (is (nil? (#'executor/run-post-actions {:build-id "b"} :success nil)))
    (is (nil? (#'executor/run-post-actions {:build-id "b"} :success {})))))

(deftest run-post-actions-always-runs-test
  (testing ":always group runs regardless of status, stage name 'post:always' (kills const-nonempty-str line 283)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [results (#'executor/run-post-actions
                     {:build-id "b" :workspace "/tmp" :env {}}
                     :success
                     {:always [{:step-name "a" :command "x" :type :shell}]})]
        (is (= 1 (count results)))
        (is (= "post:always" (-> results first :stage-name)))))))

(deftest run-post-actions-on-success-only-on-success-test
  (testing ":on-success runs only for :success build (kills logical-and-or/comp-eq-neq line 286)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [success-results (#'executor/run-post-actions
                             {:build-id "b" :workspace "/tmp" :env {}}
                             :success
                             {:on-success [{:step-name "s" :command "x" :type :shell}]})
            failure-results (#'executor/run-post-actions
                             {:build-id "b" :workspace "/tmp" :env {}}
                             :failure
                             {:on-success [{:step-name "s" :command "x" :type :shell}]})]
        (is (= ["post:on-success"] (mapv :stage-name success-results)))
        (is (= [] failure-results) "on-success must NOT run on failed build")))))

(deftest run-post-actions-on-failure-for-failure-and-aborted-test
  (testing ":on-failure runs for :failure and :aborted but not :success (kills set membership / and line 290)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [pa {:on-failure [{:step-name "f" :command "x" :type :shell}]}
            ctx {:build-id "b" :workspace "/tmp" :env {}}
            fail (#'executor/run-post-actions ctx :failure pa)
            abort (#'executor/run-post-actions ctx :aborted pa)
            succ (#'executor/run-post-actions ctx :success pa)]
        (is (= ["post:on-failure"] (mapv :stage-name fail)))
        (is (= ["post:on-failure"] (mapv :stage-name abort)))
        (is (= [] succ) "on-failure must NOT run on success")))))

(deftest run-post-action-group-empty-steps-nil-test
  (testing "empty steps -> nil (kills nil-when line 265)"
    (is (nil? (#'executor/run-post-action-group {:build-id "b"} "post:always" [])))
    (is (nil? (#'executor/run-post-action-group {:build-id "b"} "post:always" nil)))))

(deftest run-post-action-group-false-not-parallel-test
  (testing "post-action group runs steps (parallel? false) and returns stage result (kills logical-false-true line 267)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [r (#'executor/run-post-action-group
               {:build-id "b" :workspace "/tmp" :env {}}
               "post:always"
               [{:step-name "a" :command "x" :type :shell}])]
        (is (= "post:always" (:stage-name r)))
        (is (= :success (:stage-status r)))))))

;; ---- execute-stage-with-checks ----

(deftest execute-stage-cached-skip-test
  (testing "cached result short-circuits execution (kills nil-if line 306)"
    (let [cached {:stage-name "S" :stage-status :success :step-results [] :cached? true}]
      (with-redefs [stage-cache/should-skip-stage?
                    (fn [& _] {:skip? true :cached-result cached})]
        (let [r (#'executor/execute-stage-with-checks {} {:build-id "b"} {:stage-name "S"})]
          (is (= cached r)))))))

(deftest execute-stage-policy-denied-test
  (testing "policy denial aborts with reason and [] step-results (kills if-not/const-empty-vec line 323)"
    (with-redefs [stage-cache/should-skip-stage? (fn [& _] {:skip? false})
                  policy/check-stage-policies!
                  (fn [& _] {:proceed false :reason "blocked-by-policy"})]
      (let [r (#'executor/execute-stage-with-checks {} {:build-id "b"} {:stage-name "S"})]
        (is (= :aborted (:stage-status r)))
        (is (= "blocked-by-policy" (:reason r)))
        (is (= [] (:step-results r)))))))

(deftest execute-stage-approval-denied-test
  (testing "approval denial aborts with reason (kills if-not approval branch)"
    (with-redefs [stage-cache/should-skip-stage? (fn [& _] {:skip? false})
                  policy/check-stage-policies! (fn [& _] {:proceed true})
                  approval/check-stage-approval!
                  (fn [& _] {:proceed false :reason "approval-timeout"})]
      (let [r (#'executor/execute-stage-with-checks {} {:build-id "b"} {:stage-name "S"})]
        (is (= :aborted (:stage-status r)))
        (is (= "approval-timeout" (:reason r)))))))

(deftest execute-stage-runs-and-saves-cache-test
  (testing "success path restores+saves cache and stage-result (kills nil-when/and/comp-eq-neq lines 344-363)"
    (let [restore-called (atom false)
          save-called (atom false)
          stage-save-called (atom false)]
      (with-redefs [stage-cache/should-skip-stage?
                    (fn [& _] {:skip? false :fingerprint "fp123"})
                    policy/check-stage-policies! (fn [& _] {:proceed true})
                    approval/check-stage-approval! (fn [& _] {:proceed true})
                    cache/restore-cache! (fn [& _] (reset! restore-called true))
                    cache/save-cache! (fn [& _] (reset! save-called true))
                    stage-cache/save-stage-result! (fn [& _] (reset! stage-save-called true))
                    process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [r (#'executor/execute-stage-with-checks
                 {:db :db}
                 {:build-id "b" :workspace "/tmp" :env {"GIT_COMMIT" "abc"} :job-id "j"}
                 {:stage-name "S" :parallel? false :cache [{:key "k" :paths ["p"]}]
                  :steps [{:step-name "a" :command "x" :type :shell}]})]
          (is (= :success (:stage-status r)))
          (is @restore-called "cache restore should run when :cache present")
          (is @save-called "cache save should run on success with :cache")
          (is @stage-save-called "stage-result save should run on success with fingerprint"))))))

(deftest execute-stage-no-cache-no-save-test
  (testing "failed stage does not save cache (other side of (and = :success ...) line 354)"
    (let [save-called (atom false)]
      (with-redefs [stage-cache/should-skip-stage?
                    (fn [& _] {:skip? false :fingerprint "fp"})
                    policy/check-stage-policies! (fn [& _] {:proceed true})
                    approval/check-stage-approval! (fn [& _] {:proceed true})
                    cache/restore-cache! (fn [& _] nil)
                    cache/save-cache! (fn [& _] (reset! save-called true))
                    stage-cache/save-stage-result! (fn [& _] nil)
                    process/execute-command
                    (fn [_] {:exit-code 1 :stdout "" :stderr "e" :duration-ms 1})]
        (#'executor/execute-stage-with-checks
         {:db :db}
         {:build-id "b" :workspace "/tmp" :env {"GIT_COMMIT" "abc"} :job-id "j"}
         {:stage-name "S" :parallel? false :cache [{:key "k"}]
          :steps [{:step-name "a" :command "x" :type :shell}]})
        (is (false? @save-called) "cache must not be saved when stage fails")))))

;; ---- run-stages-sequential / run-stages-dag (via run-build paths covered below) ----

(deftest run-stages-sequential-stops-on-failure-test
  (testing "sequential stages stop after a failing stage (kills set/if-not line 392)"
    (with-redefs [stage-cache/should-skip-stage? (fn [& _] {:skip? false})
                  policy/check-stage-policies! (fn [& _] {:proceed true})
                  approval/check-stage-approval! (fn [& _] {:proceed true})
                  process/execute-command
                  (fn [{:keys [command]}]
                    (if (= command "FAIL")
                      {:exit-code 1 :stdout "" :stderr "e" :duration-ms 1}
                      {:exit-code 0 :stdout command :stderr "" :duration-ms 1}))]
      (let [results (#'executor/run-stages-sequential
                     {} {:build-id "b" :workspace "/tmp" :env {}}
                     [{:stage-name "ok" :parallel? false
                       :steps [{:step-name "a" :command "A" :type :shell}]}
                      {:stage-name "bad" :parallel? false
                       :steps [{:step-name "b" :command "FAIL" :type :shell}]}
                      {:stage-name "never" :parallel? false
                       :steps [{:step-name "c" :command "C" :type :shell}]}])]
        (is (= ["ok" "bad"] (mapv :stage-name results)) "third stage should not run")
        (is (= :failure (-> results second :stage-status)))))))

(deftest run-stages-dag-blocked-on-failed-dep-test
  (testing "DAG: dependent of a failed stage is skipped with 'Dependency failed' reason (kills const-nonempty-str lines 444/447, filter line 433)"
    (with-redefs [stage-cache/should-skip-stage? (fn [& _] {:skip? false})
                  policy/check-stage-policies! (fn [& _] {:proceed true})
                  approval/check-stage-approval! (fn [& _] {:proceed true})
                  process/execute-command
                  (fn [{:keys [command]}]
                    (if (= command "FAIL")
                      {:exit-code 1 :stdout "" :stderr "e" :duration-ms 1}
                      {:exit-code 0 :stdout command :stderr "" :duration-ms 1}))]
      (let [stages [{:stage-name "A" :parallel? false
                     :steps [{:step-name "a" :command "FAIL" :type :shell}]}
                    {:stage-name "B" :depends-on ["A"] :parallel? false
                     :steps [{:step-name "b" :command "B" :type :shell}]}]
            results (#'executor/run-stages-dag
                     {:config {:parallel-stages {:max-concurrent 2}}}
                     {:build-id "b" :workspace "/tmp" :env {}}
                     stages)
            by-name (into {} (map (juxt :stage-name identity) results))]
        (is (= :failure (get-in by-name ["A" :stage-status])))
        (is (= :aborted (get-in by-name ["B" :stage-status])))
        (is (= "Dependency failed" (get-in by-name ["B" :reason])))))))

(deftest run-stages-dag-all-succeed-test
  (testing "DAG: independent + dependent stages all succeed (kills comp-lt-gt/arith line 481, when-not line 425)"
    (with-redefs [stage-cache/should-skip-stage? (fn [& _] {:skip? false})
                  policy/check-stage-policies! (fn [& _] {:proceed true})
                  approval/check-stage-approval! (fn [& _] {:proceed true})
                  process/execute-command
                  (fn [{:keys [command]}] {:exit-code 0 :stdout command :stderr "" :duration-ms 1})]
      (let [stages [{:stage-name "A" :parallel? false
                     :steps [{:step-name "a" :command "A" :type :shell}]}
                    {:stage-name "B" :depends-on ["A"] :parallel? false
                     :steps [{:step-name "b" :command "B" :type :shell}]}]
            results (#'executor/run-stages-dag
                     {:config {:parallel-stages {:max-concurrent 4}}}
                     {:build-id "b" :workspace "/tmp" :env {}}
                     stages)]
        (is (= 2 (count results)))
        (is (every? #(= :success (:stage-status %)) results))
        (is (= #{"A" "B"} (set (map :stage-name results))))))))

;; ---- run-build: env construction, git, PaC detection, post/artifacts/supply-chain ----

(deftest run-build-build-env-exact-keys-test
  (testing "build-env contains exact BUILD_ID/BUILD_NUMBER/JOB_NAME/WORKSPACE and PARAM_ vars (kills const-nonempty-str lines 620-627, str-remove-arg)"
    (let [captured-env (atom nil)]
      (with-redefs [process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-env-keys
                         (dsl/stage "S"
                                    (dsl/step "a" (dsl/sh "echo x"))))
              result (executor/run-build test-system pipeline
                                         {:job-id "myjob" :build-number 42
                                          :parameters {:my-flag "on"}})
              env @captured-env]
          (is (= :success (:build-status result)))
          (is (= "myjob" (get env "JOB_NAME")))
          (is (= "42" (get env "BUILD_NUMBER")))
          (is (string? (get env "BUILD_ID")))
          (is (seq (get env "BUILD_ID")))
          (is (contains? env "WORKSPACE"))
          (is (= "on" (get env "PARAM_MY_FLAG")) "param key uppercased with - -> _ and PARAM_ prefix"))
        (cleanup-test-workspaces)))))

(deftest run-build-default-build-number-one-test
  (testing "missing build-number defaults to 1 (kills const-one-zero line 502)"
    (let [captured-env (atom nil)]
      (with-redefs [process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-default-bn
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline {}))
        (is (= "1" (get @captured-env "BUILD_NUMBER")))
        (cleanup-test-workspaces)))))

(deftest run-build-git-failure-fails-build-test
  (testing "git checkout failure -> build :failure with [] stage-results and pipeline-source 'server' (kills const-nonempty-str line 548, const-empty-vec line 543, do-remove line 537)"
    (with-redefs [git/checkout-source!
                  (fn [& _] {:success? false :error "clone failed" :git-info nil})]
      (let [pipeline (dsl/defpipeline test-git-fail
                       {:source {:type :git :url "https://example.com/r.git"}}
                       (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))
            result (executor/run-build test-system pipeline {})]
        (is (= :failure (:build-status result)))
        (is (= [] (:stage-results result)))
        (is (= "server" (:pipeline-source result)))
        (is (nil? (:git-info result))))
      (cleanup-test-workspaces))))

(deftest run-build-git-success-injects-git-env-test
  (testing "successful git checkout injects GIT_* env and :branch param (kills const-nonempty-str lines 602-606, nil-when line 601)"
    (let [captured-env (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [& _] {:success? true
                               :git-info {:branch "feature-x" :commit "deadbeef"
                                          :commit-short "dead" :author "me"
                                          :message "msg"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-git-ok
                         {:source {:type :git :url "https://example.com/r.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))
              result (executor/run-build test-system pipeline {})
              env @captured-env]
          (is (= :success (:build-status result)))
          (is (= "feature-x" (get env "GIT_BRANCH")))
          (is (= "deadbeef" (get env "GIT_COMMIT")))
          (is (= "dead" (get env "GIT_COMMIT_SHORT")))
          (is (= "me" (get env "GIT_AUTHOR")))
          (is (= "msg" (get env "GIT_MESSAGE")))
          (is (= "feature-x" (get-in result [:git-info :branch]))))
        (cleanup-test-workspaces)))))

(deftest run-build-pr-build-injects-pr-env-vars-test
  (testing "CHG-FEAT-002 PR2 — PR builds get PR_NUMBER / PR_BASE_REF /
            PR_HEAD_REF / PR_AUTHOR / PR_URL env vars surfaced to steps so
            shell pipelines can branch on them (e.g. `if [ -n \"$PR_NUMBER\" ]`)."
    (let [captured-env (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [& _] {:success? true
                               :git-info {:branch "feature/x" :commit "abc"
                                          :commit-short "abc" :author "a" :message "m"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-pr-env
                         {:source {:type :git :url "https://example.com/r.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline
                              {:parameters {:branch      "feature/x"
                                            :commit      "abc"
                                            :pr-number   42
                                            :pr-base-ref "main"
                                            :pr-head-ref "feature/x"
                                            :pr-author   "alice"
                                            :pr-url      "https://github.com/acme/widget/pull/42"}})
          (let [env @captured-env]
            (is (= "42"           (get env "PR_NUMBER"))
                "PR_NUMBER is stringified — env vars are always strings")
            (is (= "main"         (get env "PR_BASE_REF")))
            (is (= "feature/x"    (get env "PR_HEAD_REF")))
            (is (= "alice"        (get env "PR_AUTHOR")))
            (is (= "https://github.com/acme/widget/pull/42" (get env "PR_URL")))))
        (cleanup-test-workspaces)))))

(deftest run-build-non-pr-build-omits-pr-env-vars-test
  (testing "non-PR build (push/manual, no :pr-number) → no PR_* env vars at all"
    (let [captured-env (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [& _] {:success? true
                               :git-info {:branch "main" :commit "c"
                                          :commit-short "c" :author "a" :message "m"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-no-pr
                         {:source {:type :git :url "https://example.com/r.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline
                              {:parameters {:branch "main" :commit "c"}})   ;; no :pr-number
          (let [env @captured-env]
            (is (not (contains? env "PR_NUMBER")))
            (is (not (contains? env "PR_BASE_REF")))
            (is (not (contains? env "PR_HEAD_REF")))
            (is (not (contains? env "PR_AUTHOR")))
            (is (not (contains? env "PR_URL"))
                "PR_URL must not be set on push triggers either")))
        (cleanup-test-workspaces)))))

(deftest run-build-pr-build-partial-fields-only-injects-present-vars-test
  (testing "PR build with deleted fork or incomplete payload → only the populated
            PR_* vars are set; nil-valued ones are omitted entirely (don't surface
            literal \"nil\" strings to shells)."
    (let [captured-env (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [& _] {:success? true
                               :git-info {:branch "x" :commit "y"
                                          :commit-short "y" :author "a" :message "m"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [opts] (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-pr-partial
                         {:source {:type :git :url "https://example.com/r.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline
                              {:parameters {:pr-number 7}})   ;; everything else nil/missing
          (let [env @captured-env]
            (is (= "7" (get env "PR_NUMBER")) "PR_NUMBER is the trigger — must be set")
            (is (not (contains? env "PR_BASE_REF")) "nil :pr-base-ref must not set PR_BASE_REF")
            (is (not (contains? env "PR_HEAD_REF")))
            (is (not (contains? env "PR_AUTHOR")))
            (is (not (contains? env "PR_URL")))))
        (cleanup-test-workspaces)))))

(deftest run-build-pr-build-skips-branch-override-on-clone-test
  (testing "CHG-FEAT-002 / Codex PR #24 P1 r8 — for PR builds (:pr-number in params),
            the :branch override must NOT be applied to source-config before clone.
            The PR head branch typically does not exist in the base repo, so
            `git clone -b feature/x base-url` would fail outright. The reactive
            fork-fetch retry in checkout-source! handles the actual SHA pinning."
    (let [captured-source (atom nil)
          captured-opts   (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [source _ws _commit opts]
                      (reset! captured-source source)
                      (reset! captured-opts opts)
                      {:success? true
                       :git-info {:branch "detached" :commit "abc"
                                  :commit-short "abc" :author "a" :message "m"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-pr
                         {:source {:type :git :url "https://example.com/base.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline
                              {:parameters {:branch           "feature/x"   ;; PR head ref — doesn't exist in base
                                            :commit           "abc"
                                            :pr-number        42
                                            :pr-head-repo-url "https://example.com/fork.git"
                                            :pr-author        "alice"}})
          (is (nil? (:branch @captured-source))
              "PR build must NOT carry :branch into source-config (would break `git clone -b`)")
          (is (= "https://example.com/fork.git" (:head-repo-url @captured-opts)))
          (is (true? (:require-commit-checkout? @captured-opts))
              "PR build must request strict commit checkout")
          (cleanup-test-workspaces))))))

(deftest run-build-non-pr-still-applies-branch-override-test
  (testing "Non-PR triggers (push/manual) still apply :branch to clone (legacy)"
    (let [captured-source (atom nil)]
      (with-redefs [git/checkout-source!
                    (fn [source _ws _commit _opts]
                      (reset! captured-source source)
                      {:success? true
                       :git-info {:branch "main" :commit "x" :commit-short "x"
                                  :author "a" :message "m"}})
                    chengisfile/chengisfile-exists? (fn [_] false)
                    yaml-parser/detect-yaml-file (fn [_] nil)
                    process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [pipeline (dsl/defpipeline test-push
                         {:source {:type :git :url "https://example.com/r.git"}}
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build test-system pipeline
                              {:parameters {:branch "main" :commit "x"}})   ;; no :pr-number → push trigger
          (is (= "main" (:branch @captured-source))
              "non-PR build must still apply branch override (push semantic)")
          (cleanup-test-workspaces))))))

(deftest run-build-chengisfile-pac-source-test
  (testing "Chengisfile detected -> pipeline-source 'chengisfile' (kills const-nonempty-str line 569, and lines 552/568)"
    (with-redefs [git/checkout-source!
                  (fn [& _] {:success? true :git-info {:branch "main" :commit "c"
                                                       :commit-short "c" :author "a" :message "m"}})
                  chengisfile/chengisfile-exists? (fn [_] true)
                  chengisfile/chengisfile-path (fn [_] "/tmp/Chengisfile")
                  chengisfile/parse-chengisfile
                  (fn [_] {:pipeline {:stages [{:stage-name "CF" :parallel? false
                                                :steps [{:step-name "a" :command "echo x" :type :shell}]}]}})
                  process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [pipeline (dsl/defpipeline test-cf
                       {:source {:type :git :url "https://example.com/r.git"}}
                       (dsl/stage "ServerStage" (dsl/step "s" (dsl/sh "echo server"))))
            result (executor/run-build test-system pipeline {})]
        (is (= "chengisfile" (:pipeline-source result)))
        (is (= ["CF"] (mapv :stage-name (:stage-results result))) "should use Chengisfile stages"))
      (cleanup-test-workspaces))))

(deftest run-build-yaml-pac-source-test
  (testing "YAML detected (no Chengisfile) -> pipeline-source 'yaml' (kills const-nonempty-str line 572, and lines 559/560/571)"
    (with-redefs [git/checkout-source!
                  (fn [& _] {:success? true :git-info {:branch "main" :commit "c"
                                                       :commit-short "c" :author "a" :message "m"}})
                  chengisfile/chengisfile-exists? (fn [_] false)
                  yaml-parser/detect-yaml-file (fn [_] "/tmp/wf.yml")
                  yaml-parser/parse-yaml-workflow
                  (fn [_] {:pipeline {:stages [{:stage-name "YML" :parallel? false
                                                :steps [{:step-name "a" :command "echo x" :type :shell}]}]}})
                  process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [pipeline (dsl/defpipeline test-yaml
                       {:source {:type :git :url "https://example.com/r.git"}}
                       (dsl/stage "ServerStage" (dsl/step "s" (dsl/sh "echo server"))))
            result (executor/run-build test-system pipeline {})]
        (is (= "yaml" (:pipeline-source result)))
        (is (= ["YML"] (mapv :stage-name (:stage-results result)))))
      (cleanup-test-workspaces))))

(deftest run-build-no-git-server-source-test
  (testing "no git source -> pipeline-source 'server' (kills const-nonempty-str line 574)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [pipeline (dsl/defpipeline test-server-src
                       (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))
            result (executor/run-build test-system pipeline {})]
        (is (= "server" (:pipeline-source result))))
      (cleanup-test-workspaces))))

(deftest run-build-aborted-status-test
  (testing "aborted stage -> build :aborted (kills cond ordering lines 679-682)"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code -2 :cancelled? true :stdout "" :stderr "" :duration-ms 1})]
      (let [pipeline (dsl/defpipeline test-abort-build
                       (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))
            result (executor/run-build test-system pipeline {})]
        (is (= :aborted (:build-status result))))
      (cleanup-test-workspaces))))

(deftest run-build-post-actions-run-test
  (testing "post-actions execute and are appended to stage-results (kills run-post-actions integration)"
    (with-redefs [process/execute-command
                  (fn [{:keys [command]}] {:exit-code 0 :stdout command :stderr "" :duration-ms 1})]
      (let [pipeline (dsl/defpipeline test-post
                       (dsl/stage "Main" (dsl/step "m" (dsl/sh "echo main")))
                       (dsl/post (dsl/always (dsl/step "cleanup" (dsl/sh "echo clean")))))
            result (executor/run-build test-system pipeline {})
            names (mapv :stage-name (:stage-results result))]
        (is (= :success (:build-status result)))
        (is (some #{"post:always"} names) "post:always stage should be present"))
      (cleanup-test-workspaces))))

(deftest run-build-supply-chain-flags-test
  (testing "supply-chain checks only run when their feature flags enabled + :db present (kills nil-when lines 722-732)"
    (let [calls (atom #{})]
      (with-redefs [process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})
                    secret-store/get-secrets-for-build (fn [& _] {})
                    notify/dispatch-notifications! (fn [& _] nil)
                    chengis.engine.provenance/generate-provenance!
                    (fn [& _] (swap! calls conj :provenance))
                    chengis.engine.sbom/generate-sbom!
                    (fn [& _] (swap! calls conj :sbom))
                    chengis.engine.vulnerability-scanner/scan-build!
                    (fn [& _] (swap! calls conj :vuln))
                    chengis.engine.license-scanner/scan-licenses!
                    (fn [& _] (swap! calls conj :license))
                    chengis.engine.signing/sign-artifacts!
                    (fn [& _] (swap! calls conj :signing))]
        (let [system {:config {:workspace {:root "/tmp/chengis-test-workspaces"}
                               :feature-flags {:slsa-provenance true
                                               :sbom-generation true
                                               :container-scanning false
                                               :license-scanning false
                                               :artifact-signing true}}
                      :db :fake-db}
              pipeline (dsl/defpipeline test-supply
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build system pipeline {})
          (is (contains? @calls :provenance) "provenance runs when flag on + db present")
          (is (contains? @calls :sbom) "sbom runs when flag on")
          (is (contains? @calls :signing) "signing runs when flag on")
          (is (not (contains? @calls :vuln)) "vuln scan must NOT run when flag off")
          (is (not (contains? @calls :license)) "license scan must NOT run when flag off")))
      (cleanup-test-workspaces))))

(deftest run-build-no-db-skips-supply-chain-test
  (testing "no :db -> supply-chain block skipped entirely (kills nil-when line 722)"
    (let [calls (atom #{})]
      (with-redefs [process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})
                    chengis.engine.provenance/generate-provenance!
                    (fn [& _] (swap! calls conj :provenance))]
        (let [system {:config {:workspace {:root "/tmp/chengis-test-workspaces"}
                               :feature-flags {:slsa-provenance true}}}
              pipeline (dsl/defpipeline test-no-db
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x"))))]
          (executor/run-build system pipeline {})
          (is (empty? @calls) "supply-chain must not run without :db")))
      (cleanup-test-workspaces))))

(deftest run-build-artifacts-collected-test
  (testing "artifact patterns trigger collection and result :artifacts (kills nil-when line 690, str path line 693)"
    (let [collect-args (atom nil)]
      (with-redefs [process/execute-command
                    (fn [_] {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})
                    artifacts/collect-artifacts!
                    (fn [ws dir patterns]
                      (reset! collect-args {:ws ws :dir dir :patterns patterns})
                      [{:filename "out.txt" :path "/a/out.txt" :size-bytes 10
                        :content-type "text/plain" :sha256-hash "h"}])]
        (let [pipeline (dsl/defpipeline test-artifacts
                         (dsl/stage "S" (dsl/step "a" (dsl/sh "echo x")))
                         (dsl/artifacts "**/*.txt"))
              result (executor/run-build test-system pipeline {:job-id "aj" :build-number 3})]
          (is (= ["**/*.txt"] (:patterns @collect-args)))
          (is (= "artifacts/aj/3" (:dir @collect-args)) "artifact dir built with default root + / + job + / + build-number")
          (is (= 1 (count (:artifacts result))))))
      (cleanup-test-workspaces))))

;; ---- evaluate-condition extra branches (param/branch) ----

(deftest evaluate-condition-branch-matches-param-test
  (testing "branch condition matches provided :branch param (kills branch case body)"
    (is (true? (#'executor/evaluate-condition
                {:type :branch :value "release"}
                {:parameters {:branch "release"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :branch :value "release"}
                 {:parameters {:branch "main"}})))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-001 PR3 — :when expression evaluator (+ :env, + step-level)
;; ---------------------------------------------------------------------------

(deftest evaluate-condition-env-type-test
  (testing ":env condition matches env var by name + value"
    (is (true? (#'executor/evaluate-condition
                {:type :env :var "DEPLOY" :value "true"}
                {:env {"DEPLOY" "true"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :env :var "DEPLOY" :value "true"}
                 {:env {"DEPLOY" "false"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :env :var "MISSING" :value "true"}
                 {:env {}}))
        "absent env var → does not match")))

(deftest evaluate-condition-and-type-test
  (testing ":and short-circuit: all conditions must hold"
    (is (true? (#'executor/evaluate-condition
                {:type :and :conditions [{:type :branch :value "main"}
                                         {:type :env :var "X" :value "y"}]}
                {:parameters {:branch "main"} :env {"X" "y"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :and :conditions [{:type :branch :value "main"}
                                          {:type :env :var "X" :value "y"}]}
                 {:parameters {:branch "dev"} :env {"X" "y"}}))
        ":and is false when first condition fails")
    (is (true? (#'executor/evaluate-condition
                {:type :and :conditions []}
                {}))
        "empty :and is vacuously true (matches every?'s identity)")))

(deftest evaluate-condition-or-type-test
  (testing ":or holds when at least one inner condition holds"
    (is (true? (#'executor/evaluate-condition
                {:type :or :conditions [{:type :branch :value "main"}
                                        {:type :branch :value "release"}]}
                {:parameters {:branch "release"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :or :conditions [{:type :branch :value "main"}
                                         {:type :branch :value "release"}]}
                 {:parameters {:branch "dev"}})))
    (is (false? (#'executor/evaluate-condition
                 {:type :or :conditions []}
                 {}))
        "empty :or is false (matches some's identity)")))

(deftest evaluate-condition-not-type-test
  (testing ":not inverts the inner condition"
    (is (false? (#'executor/evaluate-condition
                 {:type :not :condition {:type :branch :value "main"}}
                 {:parameters {:branch "main"}})))
    (is (true? (#'executor/evaluate-condition
                {:type :not :condition {:type :branch :value "main"}}
                {:parameters {:branch "dev"}})))))

(deftest evaluate-condition-nested-expr-test
  (testing "nested [:and ... [:or ...]] evaluates correctly end-to-end"
    ;; Realistic clause: deploy only on main, AND (env DEPLOY=true OR param force=true)
    (let [cond {:type :and
                :conditions [{:type :branch :value "main"}
                             {:type :or
                              :conditions [{:type :env :var "DEPLOY" :value "true"}
                                           {:type :param :param "force" :value "true"}]}]}]
      (is (true? (#'executor/evaluate-condition cond
                                                {:parameters {:branch "main"} :env {"DEPLOY" "true"}})))
      (is (true? (#'executor/evaluate-condition cond
                                                {:parameters {:branch "main" :force "true"} :env {}})))
      (is (false? (#'executor/evaluate-condition cond
                                                 {:parameters {:branch "dev" :force "true"} :env {"DEPLOY" "true"}}))
          "branch != main → :and is false regardless of inner :or")
      (is (false? (#'executor/evaluate-condition cond
                                                 {:parameters {:branch "main"} :env {}}))
          "neither inner condition holds → :or false → :and false"))))

;; run-step + step-level :when tests live further down (after mk-build-ctx).

;; ---------------------------------------------------------------------------
;; CHG-FEAT-001 PR1 — `:retry` clause
;; ---------------------------------------------------------------------------
;; The retry contract:
;;   (:retry step-def) = nil           → exactly 1 attempt; no :attempts key on result
;;   {:max N}                          → up to N total attempts (Jenkins-style)
;;   {:max N :delay MS}                → sleep MS between attempts
;;   Only :failure triggers another attempt; :success or :aborted ends the loop.
;; These tests stub process/execute-command so we don't actually fork shells —
;; the loop logic is what we're pinning, not the exec backend.

(defn- mk-build-ctx
  "Minimal build-ctx for direct run-step calls. The plugin registry lookup
   inside run-step returns nil for unknown :type, which means it falls
   through to process/execute-command — exactly what our with-redefs stubs."
  []
  {:workspace "/tmp/chengis-feat-retry"
   :env {}
   :cancelled? (atom false)
   :event-chan nil
   :metrics-registry nil
   :mask-values []
   :current-stage "S"})

(deftest run-step-without-retry-runs-exactly-once-test
  (testing "no :retry key → one attempt; result has no :attempts key (byte-compatible with pre-retry behavior)"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 1 :stdout "" :stderr "boom" :duration-ms 1})]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Fail" :type :shell :command "exit 1"})]
          (is (= 1 @call-count) "must invoke execute-command exactly once")
          (is (= :failure (:step-status result)))
          (is (not (contains? result :attempts))
              "no :retry → no :attempts on result (would surprise existing callers)"))))))

(deftest run-step-retry-succeeds-on-first-attempt-test
  (testing ":retry {:max 3} when step succeeds immediately → 1 call, :attempts 1"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 0 :stdout "ok" :stderr "" :duration-ms 1})]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Win" :type :shell :command "true"
                                         :retry {:max 3 :delay 0}})]
          (is (= :success (:step-status result)))
          (is (= 1 @call-count) "success on first attempt must not retry")
          (is (= 1 (:attempts result))))))))

(deftest run-step-retry-eventually-succeeds-test
  (testing ":retry {:max 3} when step fails twice then succeeds → 3 calls, :attempts 3"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      (if (< @call-count 3)
                        {:exit-code 1 :stdout "" :stderr "transient" :duration-ms 1}
                        {:exit-code 0 :stdout "finally" :stderr "" :duration-ms 1}))]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Flaky" :type :shell :command "./flaky.sh"
                                         :retry {:max 3 :delay 0}})]
          (is (= :success (:step-status result)) "final status is the last attempt's")
          (is (= 3 @call-count))
          (is (= 3 (:attempts result)))
          (is (= "finally" (:stdout result)) "stdout is from the winning attempt"))))))

(deftest run-step-retry-exhausts-and-fails-test
  (testing ":retry {:max 3} when all attempts fail → 3 calls, :failure, :attempts 3"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 1 :stdout "" :stderr (str "fail-" @call-count)
                       :duration-ms 1})]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Doomed" :type :shell :command "./doomed.sh"
                                         :retry {:max 3 :delay 0}})]
          (is (= :failure (:step-status result)))
          (is (= 3 @call-count) "must exhaust all attempts, not bail early")
          (is (= 3 (:attempts result)))
          (is (= "fail-3" (:stderr result)) "stderr is from the final attempt"))))))

(deftest run-step-retry-max-1-equivalent-to-no-retry-test
  (testing ":retry {:max 1} → exactly one attempt, no retry on failure (matches no-:retry behavior except for the :attempts key)"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 1 :stdout "" :stderr "x" :duration-ms 1})]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Once" :type :shell :command "exit 1"
                                         :retry {:max 1}})]
          (is (= 1 @call-count))
          (is (= :failure (:step-status result)))
          (is (= 1 (:attempts result))))))))

(deftest run-step-retry-honors-delay-test
  (testing ":retry {:delay MS} sleeps between attempts; total wall time ≥ (max-1) * delay"
    (let [call-count (atom 0)
          ;; 80 ms — large enough to be unambiguous, small enough not to slow the suite.
          delay-ms 80]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 1 :stdout "" :stderr "x" :duration-ms 1})]
        (let [start (System/currentTimeMillis)
              _ (executor/run-step (mk-build-ctx)
                                   {:step-name "Sleepy" :type :shell :command "exit 1"
                                    :retry {:max 3 :delay delay-ms}})
              elapsed (- (System/currentTimeMillis) start)]
          (is (= 3 @call-count))
          ;; 3 attempts → 2 sleeps. Allow generous slack for slow CI.
          (is (>= elapsed (* 2 delay-ms))
              (str "elapsed " elapsed "ms expected ≥ " (* 2 delay-ms) " ms")))))))

(deftest run-step-retry-skips-on-cancellation-test
  (testing "cancellation during retry loop → bail with :aborted, no further attempts"
    (let [call-count (atom 0)
          cancel-flag (atom false)
          build-ctx (assoc (mk-build-ctx) :cancelled? cancel-flag)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      ;; After the first failure, flip the cancellation flag
                      ;; so the retry loop's pre-attempt check fires.
                      (when (= 1 @call-count) (reset! cancel-flag true))
                      {:exit-code 1 :stdout "" :stderr "x" :duration-ms 1})]
        (let [result (executor/run-step build-ctx
                                        {:step-name "Cancelled" :type :shell :command "exit 1"
                                         :retry {:max 5 :delay 0}})]
          (is (= 1 @call-count) "post-cancel attempts must not fire")
          (is (= :aborted (:step-status result))
              "cancellation during retry surfaces as :aborted, not :failure"))))))

(deftest run-step-retry-duration-includes-prior-attempts-and-sleeps-test
  ;; Codex PR #17 P2: previously :duration-ms was copied from only the LAST
  ;; attempt, dropping prior-attempt time + retry sleeps. Metrics + events
  ;; under-reported. Fix: run-step-with-retry overrides :duration-ms with
  ;; cumulative wall time when :retry is set.
  (testing ":duration-ms covers all attempts + all inter-attempt sleeps"
    (let [call-count (atom 0)
          per-attempt-ms 50
          delay-ms 60]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      ;; Simulate a 50ms exec per attempt
                      (Thread/sleep per-attempt-ms)
                      (if (< @call-count 3)
                        {:exit-code 1 :stdout "" :stderr "x"
                         :duration-ms per-attempt-ms}
                        {:exit-code 0 :stdout "ok" :stderr ""
                         :duration-ms per-attempt-ms}))]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "TimedRetry" :type :shell :command "true"
                                         :retry {:max 3 :delay delay-ms}})]
          (is (= :success (:step-status result)))
          (is (= 3 (:attempts result)))
          ;; 3 attempts × 50 ms + 2 sleeps × 60 ms = ~270 ms minimum.
          ;; Last-attempt-only behavior would give ~50 ms (wrong).
          (is (>= (:duration-ms result) 250)
              (str ":duration-ms must aggregate; got " (:duration-ms result)
                   " (expected ≥ 250 — 3 attempts + 2 sleeps)"))
          ;; Loose upper bound — generous slack for CI scheduling jitter.
          (is (< (:duration-ms result) 5000)))))))

(deftest run-step-no-retry-preserves-raw-duration-ms-test
  (testing "no :retry key → :duration-ms passes through from execute-step-once verbatim"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 0 :stdout "ok" :stderr "" :duration-ms 42})]
      (let [result (executor/run-step (mk-build-ctx)
                                      {:step-name "Plain" :type :shell :command "true"})]
        (is (= :success (:step-status result)))
        (is (= 42 (:duration-ms result))
            "no-retry path must NOT touch :duration-ms — preserves byte-compat")
        (is (not (contains? result :attempts))
            "no-retry path must NOT add :attempts key")))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-001 PR2 — `:continue-on-fail` clause
;; ---------------------------------------------------------------------------
;; Contract:
;;   step-def with :continue-on-fail true that FAILS → step-result gets
;;     :step-status :failure + :continue-on-fail? true; run-steps-sequential
;;     does NOT bail; stage status calculation excludes this from its
;;     failure roll-up. :aborted ALWAYS bails (cancellation > continue-on-fail).
;;   step-def without :continue-on-fail behaves exactly as before — no
;;     :continue-on-fail? key on result, sequential bails on failure as usual.

(deftest run-step-continue-on-fail-marks-failure-but-preserves-status-test
  (testing ":continue-on-fail true on a failing step: status stays :failure, :continue-on-fail? marker added"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 1 :stdout "" :stderr "fail" :duration-ms 1})]
      (let [result (executor/run-step (mk-build-ctx)
                                      {:step-name "OptIn" :type :shell :command "exit 1"
                                       :continue-on-fail true})]
        (is (= :failure (:step-status result))
            "failure status preserved — UI sees the failure happened")
        (is (true? (:continue-on-fail? result))
            "marker present so stage-status calc + sequential loop can ignore it")))))

(deftest run-step-continue-on-fail-on-success-does-not-add-marker-test
  (testing "no :continue-on-fail? marker on a successful step (even with the opt-in)"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 0 :stdout "ok" :stderr "" :duration-ms 1})]
      (let [result (executor/run-step (mk-build-ctx)
                                      {:step-name "OptIn" :type :shell :command "true"
                                       :continue-on-fail true})]
        (is (= :success (:step-status result)))
        (is (not (contains? result :continue-on-fail?))
            "marker only surfaces when it actually suppressed a failure")))))

(deftest run-step-no-continue-on-fail-key-no-marker-test
  (testing "step without :continue-on-fail key on a failing step: no marker, status :failure (byte-compat)"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 1 :stdout "" :stderr "fail" :duration-ms 1})]
      (let [result (executor/run-step (mk-build-ctx)
                                      {:step-name "Normal" :type :shell :command "exit 1"})]
        (is (= :failure (:step-status result)))
        (is (not (contains? result :continue-on-fail?))
            "default behavior preserved exactly — no marker key")))))

(deftest run-build-continue-on-fail-step-does-not-stop-stage-test
  ;; End-to-end through the real DSL + run-build path. Step 1 fails with
  ;; opt-in; step 2 must still run.
  (testing "failing :continue-on-fail step does NOT stop a sequential stage"
    (let [pipeline {:stages [{:stage-name "S"
                              :parallel? false
                              :steps [{:step-name "A" :type :shell
                                       :command "exit 1"
                                       :continue-on-fail true}
                                      {:step-name "B" :type :shell
                                       :command "echo b"}]}]}
          result (executor/run-build test-system pipeline {})
          [a b] (-> result :stage-results first :step-results)]
      (is (= 2 (count (-> result :stage-results first :step-results)))
          "both steps must run; default behavior would have bailed after A")
      (is (= :failure (:step-status a)))
      (is (true? (:continue-on-fail? a)))
      (is (= :success (:step-status b)))
      (cleanup-test-workspaces))))

(deftest run-build-continue-on-fail-step-keeps-stage-success-test
  (testing "stage status is :success when the only failure was :continue-on-fail"
    (let [pipeline {:stages [{:stage-name "S"
                              :parallel? false
                              :steps [{:step-name "A" :type :shell
                                       :command "exit 1"
                                       :continue-on-fail true}
                                      {:step-name "B" :type :shell
                                       :command "echo b"}]}]}
          result (executor/run-build test-system pipeline {})
          stage (-> result :stage-results first)]
      (is (= :success (:stage-status stage))
          "stage rolls up to :success — failure was explicitly opted in to be ignored")
      (is (= :success (:build-status result)))
      (cleanup-test-workspaces))))

(deftest run-build-blocking-failure-after-continue-on-fail-still-fails-stage-test
  (testing "a non-continue-on-fail failure AFTER a continue-on-fail failure still fails the stage"
    (let [pipeline {:stages [{:stage-name "S"
                              :parallel? false
                              :steps [{:step-name "A" :type :shell
                                       :command "exit 1"
                                       :continue-on-fail true}
                                      {:step-name "B" :type :shell
                                       :command "exit 2"}
                                      {:step-name "C" :type :shell
                                       :command "echo c"}]}]}
          result (executor/run-build test-system pipeline {})
          [a b c] (-> result :stage-results first :step-results)]
      (is (= 2 (count (-> result :stage-results first :step-results)))
          "stage bails AFTER B's hard failure — C does not run")
      (is (= :failure (:step-status a)))
      (is (true? (:continue-on-fail? a)))
      (is (= :failure (:step-status b)))
      (is (not (contains? b :continue-on-fail?))
          "B is a real failure — no marker")
      (is (nil? c))
      (is (= :failure (-> result :stage-results first :stage-status))
          "stage status :failure because B was a blocking failure")
      (cleanup-test-workspaces))))

(deftest run-build-all-failures-continue-on-fail-stage-success-test
  (testing "stage with ALL steps failing under :continue-on-fail is still :success"
    ;; Edge case — if every step in the stage opts in, the stage rolls up clean.
    ;; Same logic applies for a 1-step stage with continue-on-fail.
    (let [pipeline {:stages [{:stage-name "S"
                              :parallel? false
                              :steps [{:step-name "A" :type :shell
                                       :command "exit 1"
                                       :continue-on-fail true}
                                      {:step-name "B" :type :shell
                                       :command "exit 2"
                                       :continue-on-fail true}]}]}
          result (executor/run-build test-system pipeline {})
          stage (-> result :stage-results first)]
      (is (= 2 (count (:step-results stage))) "all steps ran")
      (is (= :success (:stage-status stage)))
      (is (= :success (:build-status result)))
      (cleanup-test-workspaces))))

(deftest run-step-retry-and-continue-on-fail-compose-test
  ;; Self-review of PR2: pin the composition. Retry runs first (exhausts all
  ;; attempts), then if the final result is :failure AND :continue-on-fail
  ;; is opted in, the marker is added and the stage doesn't bail. Important
  ;; to lock this in because both features mutate the result map and a future
  ;; refactor could accidentally drop one when both are set.
  (testing ":retry exhausts → :continue-on-fail marker added → sequential stage continues"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 1 :stdout "" :stderr "x" :duration-ms 1})]
        (let [result (executor/run-step (mk-build-ctx)
                                        {:step-name "Combo" :type :shell :command "exit 1"
                                         :retry {:max 3 :delay 0}
                                         :continue-on-fail true})]
          (is (= 3 @call-count) "retry ran all 3 attempts")
          (is (= :failure (:step-status result)))
          (is (= 3 (:attempts result)) ":attempts surfaced from retry")
          (is (true? (:continue-on-fail? result))
              ":continue-on-fail? marker added on top of retry exhaustion"))))))

(deftest run-step-cancellation-overrides-continue-on-fail-test
  ;; Self-review of PR2: cancellation takes precedence over :continue-on-fail.
  ;; If the build is cancelled BEFORE the step runs, status is :aborted (not
  ;; :failure), so the :continue-on-fail? marker is never set, and
  ;; run-steps-sequential's :aborted branch bails the stage regardless.
  (testing "build cancelled before step → :aborted, no :continue-on-fail? marker, stage bails"
    (let [cancel-flag (atom true)
          build-ctx (assoc (mk-build-ctx) :cancelled? cancel-flag)
          result (executor/run-step build-ctx
                                    {:step-name "Cancelled" :type :shell :command "exit 1"
                                     :continue-on-fail true})]
      (is (= :aborted (:step-status result)))
      (is (not (contains? result :continue-on-fail?))
          "cancellation > continue-on-fail — never surfaces the marker on :aborted"))))

(deftest run-build-continue-on-fail-in-parallel-stage-test
  (testing "parallel stage: continue-on-fail failure doesn't block sibling steps OR stage status"
    (let [pipeline {:stages [{:stage-name "P"
                              :parallel? true
                              :steps [{:step-name "Pass" :type :shell
                                       :command "echo ok"}
                                      {:step-name "OptIn" :type :shell
                                       :command "exit 1"
                                       :continue-on-fail true}]}]}
          result (executor/run-build test-system pipeline {})
          stage (-> result :stage-results first)
          steps (group-by :step-name (:step-results stage))]
      (is (= :success (-> steps (get "Pass") first :step-status)))
      (is (= :failure (-> steps (get "OptIn") first :step-status)))
      (is (true? (-> steps (get "OptIn") first :continue-on-fail?)))
      (is (= :success (:stage-status stage))
          "parallel stage status ignores continue-on-fail failures, same as sequential")
      (cleanup-test-workspaces))))

(deftest run-step-retry-cancellation-during-sleep-bails-fast-test
  ;; Codex PR #17 P2: previously Thread/sleep ran the full :delay before the
  ;; next iteration's cancellation check fired — a 60s delay meant 60s of
  ;; unresponsive cancel. Fix: sleep-cancellable polls every 100ms and bails
  ;; on cancel. This test pins the contract.
  (testing "cancel flipped DURING the inter-attempt sleep → step aborts within ~poll-interval, not full :delay"
    (let [call-count (atom 0)
          cancel-flag (atom false)
          build-ctx (assoc (mk-build-ctx) :cancelled? cancel-flag)
          ;; 5000 ms delay would be a 5-second hang under the buggy code;
          ;; with sleep-cancellable bailing at ≤100 ms poll, this test
          ;; completes in ~250 ms even with generous CI slack.
          big-delay-ms 5000]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      ;; Flip cancel ~150 ms after the first attempt's failure
                      ;; — well inside the sleep window — on a background thread
                      ;; so this exec call returns immediately.
                      (when (= 1 @call-count)
                        (future
                          (Thread/sleep 150)
                          (reset! cancel-flag true)))
                      {:exit-code 1 :stdout "" :stderr "x" :duration-ms 1})]
        (let [start (System/currentTimeMillis)
              result (executor/run-step build-ctx
                                        {:step-name "CancelInSleep" :type :shell :command "exit 1"
                                         :retry {:max 5 :delay big-delay-ms}})
              elapsed (- (System/currentTimeMillis) start)]
          (is (= 1 @call-count) "no further attempts after cancel during sleep")
          (is (= :aborted (:step-status result)))
          ;; Allow up to 2s of slack for slow CI but assert we're nowhere near
          ;; the 5000ms full sleep — the whole point of the fix.
          (is (< elapsed 2000)
              (str "elapsed " elapsed "ms — must bail well before "
                   big-delay-ms "ms (Codex PR #17 P2)")))))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-001 PR3 — step-level :when end-to-end (uses mk-build-ctx)
;; ---------------------------------------------------------------------------

(deftest run-step-when-clause-skips-step-test
  ;; End-to-end: step-level :when populated into :condition by convert-step,
  ;; evaluated by run-step, and the failing condition yields :skipped without
  ;; the underlying command being invoked.
  (testing "step with :when {:branch 'main'} is :skipped when branch is 'dev'"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
        (let [build-ctx (assoc (mk-build-ctx) :parameters {:branch "dev"})
              result (executor/run-step build-ctx
                                        {:step-name "MainOnly" :type :shell
                                         :command "echo deploy"
                                         :condition {:type :branch :value "main"}})]
          (is (= :skipped (:step-status result)))
          (is (zero? @call-count) "command must not run when condition fails"))))))

(deftest run-step-when-clause-runs-step-when-condition-holds-test
  (testing "step with :when {:branch 'main'} runs when branch is 'main'"
    (let [call-count (atom 0)]
      (with-redefs [process/execute-command
                    (fn [_]
                      (swap! call-count inc)
                      {:exit-code 0 :stdout "ok" :stderr "" :duration-ms 1})]
        (let [build-ctx (assoc (mk-build-ctx) :parameters {:branch "main"})
              result (executor/run-step build-ctx
                                        {:step-name "MainOnly" :type :shell :command "echo"
                                         :condition {:type :branch :value "main"}})]
          (is (= :success (:step-status result)))
          (is (= 1 @call-count)))))))

(deftest run-step-when-clause-env-condition-test
  (testing ":when {:env ...} skips when env var doesn't match"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [build-ctx (assoc (mk-build-ctx) :env {"DEPLOY" "false"})
            result (executor/run-step build-ctx
                                      {:step-name "OptIn" :type :shell :command "echo"
                                       :condition {:type :env :var "DEPLOY" :value "true"}})]
        (is (= :skipped (:step-status result)))))))

(deftest run-step-when-expression-skips-on-and-failure-test
  (testing ":when [:and {:branch 'main'} {:env 'X' :value 'y'}] skips when env mismatch"
    (with-redefs [process/execute-command
                  (fn [_]
                    {:exit-code 0 :stdout "" :stderr "" :duration-ms 1})]
      (let [build-ctx (assoc (mk-build-ctx)
                             :parameters {:branch "main"}
                             :env {"X" "nope"})
            result (executor/run-step build-ctx
                                      {:step-name "OptIn" :type :shell :command "echo"
                                       :condition {:type :and
                                                   :conditions [{:type :branch :value "main"}
                                                                {:type :env :var "X" :value "y"}]}})]
        (is (= :skipped (:step-status result)))))))

;; ---------------------------------------------------------------------------
;; T4-ORC: Executor semantic oracle — skip/fail/run staged scenario
;; ---------------------------------------------------------------------------
;;
;; One scenario, end-to-end through `run-build`, that encodes the executor's
;; multi-stage failure-propagation contract:
;;
;;   Stage A : [a1 ok]            → :success
;;   Stage B : [b1 ok, b2 fail]   → :failure (b1 ran, b2 failed, stage bails)
;;   Stage C : [c1 ok]            → never executes; NOT present in results
;;
;; The semantic this oracle pins down:
;;
;;   1. `run-stages-sequential` `reduced`s on stage failure, so downstream
;;      stages are DROPPED from `:stage-results` entirely (not recorded as
;;      :skipped — that's the per-step :condition semantic, distinct from
;;      cross-stage failure propagation).
;;   2. Within a failing stage, `run-steps-sequential` runs steps in declared
;;      order until first failure, then `reduced`s — so b1 IS recorded
;;      (success) and b2 IS recorded (failure), but no later steps in B would
;;      appear if they existed.
;;   3. Roll-up: any-failed? → :build-status :failure (no :aborted, no :success).
;;   4. Stage status roll-up: a stage with one failing step (not opt-in
;;      :continue-on-fail) gets :stage-status :failure regardless of earlier
;;      successes in the same stage.
;;
;; Determinism: `process/execute-command` is redef'd to a pure function keyed
;; on the command string — "ok"-prefixed commands succeed, "fail" exits 1.
;; No real subprocesses, no sleeps, no clock dependence beyond `Instant/now`
;; which we don't assert on.
(deftest executor-skip-fail-run-staged-scenario-oracle-test
  (testing "Stage A success, Stage B fails on second step, Stage C dropped from results"
    (with-redefs [process/execute-command
                  (fn [{:keys [command]}]
                    (cond
                      (= command "ok-a1") {:exit-code 0 :stdout "a1-out" :stderr "" :duration-ms 1}
                      (= command "ok-b1") {:exit-code 0 :stdout "b1-out" :stderr "" :duration-ms 1}
                      (= command "fail-b2") {:exit-code 1 :stdout "" :stderr "b2-stderr" :duration-ms 1}
                      (= command "ok-c1") {:exit-code 0 :stdout "c1-out" :stderr "" :duration-ms 1}
                      :else {:exit-code 0 :stdout "" :stderr "" :duration-ms 1}))]
      (let [pipeline {:pipeline-name "oracle-skip-fail-run"
                      :stages [{:stage-name "A"
                                :steps [{:step-name "a1" :type :shell :command "ok-a1"}]}
                               {:stage-name "B"
                                :steps [{:step-name "b1" :type :shell :command "ok-b1"}
                                        {:step-name "b2" :type :shell :command "fail-b2"}]}
                               {:stage-name "C"
                                :steps [{:step-name "c1" :type :shell :command "ok-c1"}]}]}
            result (executor/run-build test-system pipeline {})
            stages (:stage-results result)
            [stage-a stage-b] stages
            [a1] (:step-results stage-a)
            [b1 b2] (:step-results stage-b)]

        ;; --- Build-level oracle ---
        (is (= :failure (:build-status result))
            "any stage failure → build :failure (not :success, not :aborted)")
        (is (= "oracle-skip-fail-run" (:job-id result)))
        (is (= 1 (:build-number result)))

        ;; --- Cross-stage failure propagation ---
        (is (= 2 (count stages))
            "Stage C must NOT appear in results — pipeline stops on B's failure")
        (is (= ["A" "B"] (mapv :stage-name stages))
            "Stage ordering preserved; C dropped entirely (no :skipped entry)")
        (is (= [:success :failure] (mapv :stage-status stages))
            "Stage A succeeds; Stage B rolls up to :failure (one failing step, no continue-on-fail)")

        ;; --- Stage A: single-step success ---
        (is (= "A" (:stage-name stage-a)))
        (is (= 1 (count (:step-results stage-a))))
        (is (= "a1" (:step-name a1)))
        (is (= :success (:step-status a1)))
        (is (= 0 (:exit-code a1)))
        (is (= "a1-out" (:stdout a1)))

        ;; --- Stage B: step ordering + partial execution before failure ---
        (is (= "B" (:stage-name stage-b)))
        (is (= 2 (count (:step-results stage-b)))
            "Both b1 (success) and b2 (failure) are recorded; in-stage failure stops further B steps but does NOT drop preceding success")
        (is (= ["b1" "b2"] (mapv :step-name (:step-results stage-b)))
            "Within-stage step order preserved in results")
        (is (= [:success :failure] (mapv :step-status (:step-results stage-b))))
        (is (= 0 (:exit-code b1)))
        (is (= "b1-out" (:stdout b1)))
        (is (= 1 (:exit-code b2))
            "b2 exit-code propagated verbatim from process result")
        (is (= "b2-stderr" (:stderr b2))
            "b2 stderr propagated verbatim from process result")

        ;; --- Negative assertions: things that MUST NOT happen ---
        (is (not-any? #(= "C" (:stage-name %)) stages)
            "Stage C must never appear — proves the pipeline did not run downstream of failure")
        (is (not (contains? (set (mapv :stage-status stages)) :skipped))
            "No stage is marked :skipped — cross-stage failure-propagation uses 'dropped from results', distinct from per-step :condition skip")
        (is (not (contains? (set (mapv :stage-status stages)) :aborted))
            ":aborted is for cancellation, not normal failure propagation")

        (cleanup-test-workspaces)))))
