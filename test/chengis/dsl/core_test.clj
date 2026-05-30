(ns chengis.dsl.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]))

(use-fixtures :each (fn [f] (dsl/clear-registry!) (f)))

(deftest sh-test
  (testing "basic shell command"
    (is (= {:type :shell :command "echo hi"}
           (dsl/sh "echo hi"))))

  (testing "shell command with options"
    (let [result (dsl/sh "make" :env {"CC" "gcc"} :dir "/src" :timeout 5000)]
      (is (= "make" (:command result)))
      (is (= {"CC" "gcc"} (:env result)))
      (is (= "/src" (:dir result)))
      (is (= 5000 (:timeout result))))))

(deftest step-test
  (testing "step combines name with action"
    (let [s (dsl/step "Build" (dsl/sh "make"))]
      (is (= "Build" (:step-name s)))
      (is (= :shell (:type s)))
      (is (= "make" (:command s))))))

(deftest stage-test
  (testing "sequential stage"
    (let [s (dsl/stage "Build"
                       (dsl/step "Compile" (dsl/sh "make compile"))
                       (dsl/step "Link" (dsl/sh "make link")))]
      (is (= "Build" (:stage-name s)))
      (is (false? (:parallel? s)))
      (is (= 2 (count (:steps s))))))

  (testing "parallel stage"
    (let [s (dsl/stage "Test"
                       (dsl/parallel
                        (dsl/step "Unit" (dsl/sh "make test"))
                        (dsl/step "Lint" (dsl/sh "make lint"))))]
      (is (= "Test" (:stage-name s)))
      (is (true? (:parallel? s)))
      (is (= 2 (count (:steps s)))))))

(deftest when-branch-test
  (testing "adds branch condition to steps"
    (let [steps (dsl/when-branch "main"
                                 (dsl/step "Deploy" (dsl/sh "./deploy.sh")))]
      (is (= 1 (count steps)))
      (is (= {:type :branch :value "main"}
             (:condition (first steps)))))))

(deftest defpipeline-test
  (testing "defines and registers a pipeline"
    (let [p (dsl/defpipeline test-pipeline
              {:description "A test pipeline"}
              (dsl/stage "Build"
                         (dsl/step "Compile" (dsl/sh "echo compile")))
              (dsl/stage "Test"
                         (dsl/step "Run" (dsl/sh "echo test"))))]
      (is (= "test-pipeline" (:pipeline-name p)))
      (is (= "A test pipeline" (:description p)))
      (is (= 2 (count (:stages p))))
      ;; Registered in the registry
      (is (= p (dsl/get-pipeline "test-pipeline")))
      (is (= ["test-pipeline"] (vec (dsl/list-pipelines))))))

  (testing "pipeline without options map"
    (let [p (dsl/defpipeline simple
              (dsl/stage "Only"
                         (dsl/step "Do" (dsl/sh "echo do"))))]
      (is (= "simple" (:pipeline-name p)))
      (is (nil? (:description p)))
      (is (= 1 (count (:stages p)))))))

;; ---------------------------------------------------------------------------
;; Phase 3.1 mutation remediation: dsl.core — collection ordering, condition
;; paths, and or-fallback coverage to kill surviving mutations.
;; ---------------------------------------------------------------------------

;; --- when-param: zero tests existed before ---

(deftest when-param-test
  (testing "adds param condition to steps"
    (let [steps (dsl/when-param "env" "production"
                                (dsl/step "Deploy" (dsl/sh "./deploy.sh")))]
      (is (= 1 (count steps)))
      (is (= {:type :param :param "env" :value "production"}
             (:condition (first steps))))
      (is (= "Deploy" (:step-name (first steps))))))

  (testing "when-param with multiple steps adds condition to each"
    (let [steps (dsl/when-param "env" "staging"
                                (dsl/step "A" (dsl/sh "echo a"))
                                (dsl/step "B" (dsl/sh "echo b")))]
      (is (= 2 (count steps)))
      ;; First step has the condition — kills first→last mutation
      (is (= "A" (:step-name (first steps))))
      (is (= "B" (:step-name (second steps))))
      (doseq [s steps]
        (is (= {:type :param :param "env" :value "staging"}
               (:condition s)))))))

;; --- stage ordering: use 3+ stages to kill first/last mutations ---

(deftest pipeline-stage-ordering-test
  (testing "stages are preserved in order (first stage first, last stage last)"
    (let [p (dsl/defpipeline ordered-pipeline
              (dsl/stage "One"   (dsl/step "S1" (dsl/sh "echo 1")))
              (dsl/stage "Two"   (dsl/step "S2" (dsl/sh "echo 2")))
              (dsl/stage "Three" (dsl/step "S3" (dsl/sh "echo 3"))))]
      (is (= 3 (count (:stages p))))
      ;; Must be "One" first and "Three" last — kills first↔last mutations
      (is (= "One"   (:stage-name (first (:stages p)))))
      (is (= "Three" (:stage-name (last (:stages p)))))
      (is (= "Two"   (:stage-name (second (:stages p))))))))

;; --- stage with options map: approval, container, depends-on, cache ---

(deftest stage-with-approval-opts-test
  (testing "stage accepts options map with :approval"
    (let [s (dsl/stage "Deploy"
                       {:approval {:required true :timeout-minutes 60}}
                       (dsl/step "Go" (dsl/sh "deploy.sh")))]
      (is (= "Deploy" (:stage-name s)))
      (is (= {:required true :timeout-minutes 60} (:approval s)))
      (is (= 1 (count (:steps s))))))

  (testing "stage with :depends-on option"
    (let [s (dsl/stage "Integration"
                       {:depends-on ["Build" "Test"]}
                       (dsl/step "Verify" (dsl/sh "echo ok")))]
      (is (= ["Build" "Test"] (:depends-on s)))))

  (testing "stage with :cache option"
    (let [s (dsl/stage "Build"
                       {:cache [{:key "mvn-deps" :paths ["~/.m2"]}]}
                       (dsl/step "Compile" (dsl/sh "mvn compile")))]
      (is (= [{:key "mvn-deps" :paths ["~/.m2"]}] (:cache s)))))

  (testing "map with :step-name is NOT treated as opts — it becomes a step"
    ;; A map that has :step-name should NOT be consumed as the stage opts map
    ;; This kills the and→or mutation on the opts detection condition
    (let [step (dsl/step "ActualStep" (dsl/sh "echo step"))
          s (dsl/stage "MyStage" step)]
      (is (= 1 (count (:steps s))))
      (is (= "ActualStep" (:step-name (first (:steps s)))))
      (is (nil? (:approval s))
          "step map with :step-name must not be consumed as stage opts")))

  (testing "map with :parallel? is NOT treated as opts"
    ;; A parallel group has :parallel? true — must not be consumed as opts
    (let [par (dsl/parallel
               (dsl/step "A" (dsl/sh "echo a"))
               (dsl/step "B" (dsl/sh "echo b")))
          s (dsl/stage "ParStage" par)]
      (is (true? (:parallel? s)))
      (is (= 2 (count (:steps s)))))))

;; --- post-action helpers ---

(deftest post-action-fns-test
  (testing "always returns :always key"
    (let [r (dsl/always (dsl/step "Notify" (dsl/sh "echo notify")))]
      (is (contains? r :always))
      (is (= 1 (count (:always r))))
      (is (= "Notify" (:step-name (first (:always r)))))))

  (testing "on-success returns :on-success key"
    (let [r (dsl/on-success (dsl/step "Deploy" (dsl/sh "deploy.sh")))]
      (is (contains? r :on-success))
      (is (= "Deploy" (:step-name (first (:on-success r)))))))

  (testing "on-failure returns :on-failure key"
    (let [r (dsl/on-failure (dsl/step "Alert" (dsl/sh "alert.sh")))]
      (is (contains? r :on-failure))
      (is (= "Alert" (:step-name (first (:on-failure r)))))))

  (testing "post merges all groups"
    (let [r (dsl/post
             (dsl/always (dsl/step "A" (dsl/sh "echo a")))
             (dsl/on-success (dsl/step "S" (dsl/sh "echo s")))
             (dsl/on-failure (dsl/step "F" (dsl/sh "echo f"))))]
      (is (contains? (:post-actions r) :always))
      (is (contains? (:post-actions r) :on-success))
      (is (contains? (:post-actions r) :on-failure)))))

;; --- artifacts, notify, matrix helpers ---

(deftest artifacts-fn-test
  (testing "artifacts returns :artifacts key with patterns vector"
    (let [r (dsl/artifacts "target/*.jar" "reports/**/*.xml")]
      (is (= {:artifacts ["target/*.jar" "reports/**/*.xml"]} r))))

  (testing "artifacts with single pattern"
    (let [r (dsl/artifacts "build/output.tar.gz")]
      (is (= ["build/output.tar.gz"] (:artifacts r))))))

(deftest notify-fn-test
  (testing "notify returns :notify key with type and config"
    (let [r (dsl/notify :slack {:webhook-url "https://hooks.slack.com/..." :channel "#builds"})]
      (is (= [{:type :slack :webhook-url "https://hooks.slack.com/..." :channel "#builds"}]
             (:notify r))))))

(deftest matrix-fn-test
  (testing "matrix returns :matrix key with config"
    (let [r (dsl/matrix {:os ["linux" "macos"] :jdk ["11" "17"]})]
      (is (= {:os ["linux" "macos"] :jdk ["11" "17"]} (:matrix r)))))

  (testing "matrix with :exclude option"
    (let [r (dsl/matrix {:os ["linux" "macos"] :jdk ["11" "17"]}
                        :exclude [{:os "macos" :jdk "11"}])]
      (is (= [{:os "macos" :jdk "11"}]
             (get-in r [:matrix :exclude]))))))

;; --- build-pipeline: post-actions, artifacts, notify filtering ---

(deftest build-pipeline-post-actions-test
  (testing "post-action maps are NOT included in :stages"
    (let [p (dsl/defpipeline with-post
              (dsl/stage "Build" (dsl/step "C" (dsl/sh "make")))
              (dsl/post (dsl/always (dsl/step "Cleanup" (dsl/sh "clean.sh")))))]
      ;; Only real stages in :stages — post-action map filtered out
      (is (= 1 (count (:stages p))))
      (is (= "Build" (:stage-name (first (:stages p)))))
      (is (some? (:post-actions p)))
      (is (= 1 (count (get-in p [:post-actions :always]))))))

  (testing "artifacts map is NOT included in :stages"
    (let [p (dsl/defpipeline with-artifacts
              (dsl/stage "Build" (dsl/step "C" (dsl/sh "make")))
              (dsl/artifacts "target/*.jar" "dist/*.zip"))]
      (is (= 1 (count (:stages p))))
      (is (= ["target/*.jar" "dist/*.zip"] (:artifacts p)))))

  (testing "notify map is NOT included in :stages"
    (let [p (dsl/defpipeline with-notify
              (dsl/stage "Build" (dsl/step "C" (dsl/sh "make")))
              (dsl/notify :slack {:channel "#ci"}))]
      (is (= 1 (count (:stages p))))
      (is (= 1 (count (:notify p))))))

  (testing "matrix map is NOT included in :stages"
    (let [p (dsl/defpipeline with-matrix
              (dsl/stage "Test" (dsl/step "R" (dsl/sh "run-tests.sh")))
              (dsl/matrix {:jdk ["11" "17"]}))]
      (is (= 1 (count (:stages p))))
      (is (= {:jdk ["11" "17"]} (:matrix p))))))

;; --- when-branch with multiple steps: ordering test ---

(deftest when-branch-multiple-steps-ordering-test
  (testing "when-branch with 3 steps preserves order — kills first↔last mutation"
    (let [steps (dsl/when-branch "release"
                                 (dsl/step "Tag"    (dsl/sh "git tag"))
                                 (dsl/step "Build"  (dsl/sh "make build"))
                                 (dsl/step "Deploy" (dsl/sh "./deploy.sh")))]
      (is (= 3 (count steps)))
      (is (= "Tag"    (:step-name (first steps))))
      (is (= "Deploy" (:step-name (last steps))))
      (is (= "Build"  (:step-name (second steps))))
      ;; All have branch condition
      (doseq [s steps]
        (is (= {:type :branch :value "release"} (:condition s)))))))
