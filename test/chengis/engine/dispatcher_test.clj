(ns chengis.engine.dispatcher-test
  "Tests for the StepDispatcher protocol + reference orchestrator.
   Mirrors the spike #1 coverage but at the production namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dispatcher :as d]))

(defn- recording-dispatcher
  "A dispatcher that supports a fixed set of :type values and records every
   step it dispatches into an atom. Useful for unit tests."
  [supported-types calls-atom]
  (reify d/StepDispatcher
    (supports? [_ step] (contains? supported-types (:type step)))
    (dispatch  [_ step ctx]
      (swap! calls-atom conj (:type step))
      {:status :ok :output (str (:type step)) :ctx ctx})
    (describe  [_] "recording")))

(deftest run-pipeline-happy-path-test
  (testing "every step gets dispatched in source order across stages"
    (let [calls (atom [])
          d (recording-dispatcher #{:a :b :c} calls)
          pipeline {:stages [{:name "S1" :steps [{:type :a} {:type :b}]}
                             {:name "S2" :steps [{:type :c}]}]}
          result (d/run-pipeline pipeline d {})]
      (is (= :ok (:status result)))
      (is (= 2 (count (:stages result))))
      (is (= [:a :b :c] @calls)))))

(deftest unknown-step-type-fails-cleanly-test
  (testing "an unsupported step type fails the stage with a diagnostic"
    (let [calls (atom [])
          d (recording-dispatcher #{:a} calls)
          pipeline {:stages [{:name "S1" :steps [{:type :a} {:type :wat}]}]}
          result (d/run-pipeline pipeline d {})]
      (is (= :failed (:status result)))
      (let [step-results (-> result :stages first :step-results)]
        (is (= 2 (count step-results)))
        (is (= :unknown-step-type (-> step-results second :error)))))))

(deftest first-failure-aborts-subsequent-stages-test
  (testing "failure in stage 1 skips stage 2"
    (let [d (reify d/StepDispatcher
              (supports? [_ s] (= :run (:type s)))
              (dispatch  [_ s ctx]
                (if (= "boom" (:cmd s))
                  {:status :failed :error :step-failed}
                  {:status :ok :ctx ctx}))
              (describe  [_] "fragile"))
          pipeline {:stages [{:name "S1" :steps [{:type :run :cmd "ok"}
                                                 {:type :run :cmd "boom"}
                                                 {:type :run :cmd "never"}]}
                             {:name "S2" :steps [{:type :run :cmd "also-never"}]}]}
          result (d/run-pipeline pipeline d {})]
      (is (= :failed (:status result)))
      (is (= 1 (count (:stages result))))
      (let [s1-steps (-> result :stages first :step-results)]
        (is (= 2 (count s1-steps)) "never-runs was not dispatched")))))

(deftest multi-dispatcher-routes-by-supports-test
  (testing "multi-dispatcher composes — single pipeline mixes step vocabularies"
    (let [calls-a (atom []) calls-b (atom [])
          da (recording-dispatcher #{:a-1 :a-2} calls-a)
          db (recording-dispatcher #{:b-1 :b-2} calls-b)
          multi (d/multi-dispatcher da db)
          pipeline {:stages [{:name "Mixed" :steps [{:type :a-1}
                                                    {:type :b-1}
                                                    {:type :a-2}
                                                    {:type :b-2}]}]}
          result (d/run-pipeline pipeline multi {})]
      (is (= :ok (:status result)))
      (is (= [:a-1 :a-2] @calls-a))
      (is (= [:b-1 :b-2] @calls-b))
      (is (.contains ^String (d/describe multi) "recording,recording")))))

(deftest multi-dispatcher-no-supports-test
  (testing "multi-dispatcher fails cleanly when nothing supports a step"
    (let [d-empty (d/multi-dispatcher)
          pipeline {:stages [{:name "X" :steps [{:type :anything}]}]}
          result (d/run-pipeline pipeline d-empty {})]
      (is (= :failed (:status result))))))
