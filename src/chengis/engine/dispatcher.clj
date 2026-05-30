(ns chengis.engine.dispatcher
  "StepDispatcher protocol — the API contract between chengis-core and any
   product built on top of it (Chengis, anvil, future GitLab-CI / CircleCI
   compat products).

   Productized from spike #1 (`chengis.spike.dispatcher`). The spike's
   tests proved that a `MultiDispatcher` cleanly composes — anvil's
   runtime can register one dispatcher for Chengis-native step types and
   another for Jenkins-style steps, and a single pipeline can mix step
   vocabularies. THIS is the technical mechanism behind the Trojan-horse
   strategy's stage-by-stage Chengisfile-native graduation.

   This namespace is intentionally minimal. The full pipeline executor
   (`chengis.engine.executor`) does scope-wrapping, retries, caching,
   etc. on top of dispatching individual steps. `run-pipeline` here is a
   thin reference orchestrator suitable for tests and lightweight runs;
   real product execution goes through the executor."
  (:require [clojure.string :as str]))

(defprotocol StepDispatcher
  "A product registers one of these to tell chengis-core how to execute
   its step types. Core's orchestrator never inspects step payloads
   directly — it only asks `supports?` and calls `dispatch`."
  (supports? [this step]
    "Predicate: can this dispatcher execute this step? Receives the step
     map; should be fast / pure / safe to call many times.")
  (dispatch [this step ctx]
    "Execute the step in the given context. Return:

       {:status :ok | :failed
        :output  STRING                  ; human-readable summary
        :ctx     {...}                   ; possibly-modified context
        :error   KEYWORD                 ; optional, on :failed
        :stdout  STRING                  ; optional, returnStdout-style steps
        :details ANY}                    ; optional, free-form")
  (describe [this]
    "Short human-readable name for logs and traces (e.g. \"anvil-jenkins\")."))

;; ---------------------------------------------------------------------------
;; Reference orchestrator
;;
;; Walks a pipeline IR (Chengisfile shape: {:stages [{:name :steps [...]}]})
;; and calls dispatch on each step. Stops a stage on first failure; skips
;; subsequent stages. This is intentionally simpler than chengis.engine.executor
;; — the executor adds scope-wrapping, retries, caching, observability.
;; ---------------------------------------------------------------------------

(defn- run-step [step ctx dispatcher]
  (if (supports? dispatcher step)
    (dispatch dispatcher step ctx)
    {:status :failed
     :error :unknown-step-type
     :step step
     :dispatcher (describe dispatcher)}))

(defn- run-stage [stage ctx dispatcher]
  (let [{:keys [final-ctx step-results failed?]}
        (reduce
         (fn [{:keys [final-ctx step-results failed?]} step]
           (if failed?
             {:final-ctx final-ctx :step-results step-results :failed? true}
             (let [r (run-step step final-ctx dispatcher)]
               {:final-ctx (or (:ctx r) final-ctx)
                :step-results (conj step-results r)
                :failed? (= :failed (:status r))})))
         {:final-ctx ctx :step-results [] :failed? false}
         (:steps stage))]
    {:name (:name stage)
     :status (if failed? :failed :ok)
     :step-results step-results
     :final-ctx final-ctx}))

(defn run-pipeline
  "Reference orchestrator. Walks stages → steps and dispatches each.
   On the first failed step, the rest of that stage is skipped AND
   subsequent stages are skipped."
  [pipeline-ir dispatcher initial-ctx]
  (let [{:keys [stage-results final-ctx aborted?]}
        (reduce
         (fn [{:keys [stage-results final-ctx aborted?]} stage]
           (if aborted?
             {:stage-results stage-results :final-ctx final-ctx :aborted? true}
             (let [sr (run-stage stage final-ctx dispatcher)]
               {:stage-results (conj stage-results sr)
                :final-ctx (:final-ctx sr)
                :aborted? (= :failed (:status sr))})))
         {:stage-results [] :final-ctx initial-ctx :aborted? false}
         (:stages pipeline-ir))]
    {:status (if aborted? :failed :ok)
     :stages stage-results
     :final-ctx final-ctx}))

;; ---------------------------------------------------------------------------
;; MultiDispatcher — compose product-specific dispatchers.
;;
;; The Trojan-horse mechanism. anvil's runtime registers
;; (multi-dispatcher chengis-dispatcher anvil-jenkins-dispatcher) so a
;; single pipeline can mix step vocabularies — supporting stage-by-stage
;; Chengisfile-native graduation within one anvil install.
;; ---------------------------------------------------------------------------

(defrecord MultiDispatcher [dispatchers]
  StepDispatcher
  (supports? [_ step] (boolean (some #(supports? % step) dispatchers)))
  (dispatch  [_ step ctx]
    (if-let [d (first (filter #(supports? % step) dispatchers))]
      (dispatch d step ctx)
      {:status :failed :error :no-dispatcher-supports :step step}))
  (describe  [_]
    (str "Multi[" (str/join "," (map describe dispatchers)) "]")))

(defn multi-dispatcher
  "Compose multiple dispatchers behind a single StepDispatcher. The first
   dispatcher whose `supports?` returns true gets the step."
  [& dispatchers]
  (->MultiDispatcher (vec dispatchers)))
