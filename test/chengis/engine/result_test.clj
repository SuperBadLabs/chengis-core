(ns chengis.engine.result-test
  "Acceptance tests for the honest result classifier (CC2-EX2).

   The receipts target the exact regression the wild-corpus matrix
   exposed: anvil v0.3 reporting :success when nothing actually ran. Each
   test fixes the specific shape that produced a vacuous SUCCESS."
  (:require [chengis.engine.result :as r]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Canonical set + predicates
;; ---------------------------------------------------------------------------

(deftest canonical-result-set
  (testing "the full enum is enumerable and includes the two new classes"
    (is (some #{:neutral} r/all-results))
    (is (some #{:unsupported} r/all-results))
    (is (= 6 (count r/all-results)))))

(deftest predicates
  (is (r/success? :success))
  (is (r/failure? :failure))
  (is (r/aborted? :aborted))
  (is (r/unstable? :unstable))
  (is (r/neutral? :neutral))
  (is (r/unsupported? :unsupported))
  (is (not (r/success? :neutral)))
  (is (r/terminal? :neutral))
  (is (r/terminal? :unsupported))
  (is (not (r/terminal? :running)))
  (is (r/non-success-terminal? :neutral))
  (is (r/non-success-terminal? :unsupported))
  (is (not (r/non-success-terminal? :success))))

(deftest worst-of-precedence
  (testing "aborted dominates everything"
    (is (= :aborted (r/worst-of [:success :aborted :failure]))))
  (testing "neutral dominates success"
    (is (= :neutral (r/worst-of [:success :neutral]))))
  (testing "unsupported dominates failure"
    (is (= :unsupported (r/worst-of [:failure :unsupported]))))
  (testing "all-success rolls up to success"
    (is (= :success (r/worst-of [:success :success :success]))))
  (testing "empty rolls up to neutral, not success"
    (is (= :neutral (r/worst-of []))))
  (testing "unknowns are ignored, do not crash"
    (is (= :success (r/worst-of [:success :pancakes])))))

;; ---------------------------------------------------------------------------
;; Classifier — the wild-corpus regressions, one rule at a time
;; ---------------------------------------------------------------------------

(deftest cancelled-build-classifies-aborted
  (let [obs (-> (r/default-observation)
                (r/record-shell-step {:exit-code 0})
                (r/mark-cancelled))
        {:keys [result rule]} (r/classify obs)]
    (is (= :aborted result))
    (is (= :aborted-by-signal rule))))

(deftest k8s-agent-on-single-host-classifies-unsupported
  (testing "wild-corpus regression: agent { kubernetes } on a single-host install"
    (let [obs (-> (r/default-observation)
                  (r/record-unsupported-construct "agent.kubernetes"))
          {:keys [result rule explain]} (r/classify obs)]
      (is (= :unsupported result))
      (is (= :unsupported-construct rule))
      (is (re-find #"agent.kubernetes" explain)))))

(deftest unresolved-credential-classifies-failure
  (testing "wild-corpus regression: withCredentials([id:'X']) where X is missing"
    (let [obs (-> (r/default-observation)
                  (r/record-shell-step {:exit-code 0})
                  (r/record-unresolved-credential "apache-snapshot"))
          {:keys [result rule explain]} (r/classify obs)]
      (is (= :failure result))
      (is (= :credential-unresolved rule))
      (is (re-find #"apache-snapshot" explain)))))

(deftest unresolved-tool-classifies-failure
  (testing "wild-corpus regression: tool('jdk_17_latest') returning empty"
    (let [obs (-> (r/default-observation)
                  (r/record-unresolved-tool "jdk_17_latest"))
          {:keys [result rule explain]} (r/classify obs)]
      (is (= :failure result))
      (is (= :tool-unresolved rule))
      (is (re-find #"jdk_17_latest" explain)))))

(deftest docker-agent-without-backend-classifies-unsupported
  (testing "wild-corpus regression: agent { docker { image 'X' } } with no Docker backend"
    (let [obs (-> (r/default-observation)
                  (r/record-unhonored-agent "agent.docker:eclipse-temurin:21"))
          {:keys [result rule]} (r/classify obs)]
      (is (= :unsupported result))
      (is (= :agent-unhonored rule)))))

(deftest nonzero-exit-classifies-failure
  (let [obs (-> (r/default-observation)
                (r/record-shell-step {:exit-code 0})
                (r/record-shell-step {:exit-code 2}))
        {:keys [result rule explain]} (r/classify obs)]
    (is (= :failure result))
    (is (= :step-nonzero-exit rule))
    (is (re-find #"non-zero" explain))))

(deftest test-failures-classify-unstable
  (let [obs (-> (r/default-observation)
                (r/record-shell-step {:exit-code 0})
                (r/record-effect :artifact-archived)
                (r/record-test-summary {:tests 50 :failures 3 :errors 0 :skipped 2}))
        {:keys [result rule]} (r/classify obs)]
    (is (= :unstable result))
    (is (= :tests-failed rule))))

(deftest empty-walk-classifies-neutral-not-success
  (testing "the headline regression: an empty walk must NOT classify as :success"
    (let [obs (r/default-observation)
          {:keys [result rule explain]} (r/classify obs)]
      (is (= :neutral result)
          "an IR walk that ran zero steps is not a successful build")
      (is (= :no-effects-recorded rule))
      (is (re-find #"did nothing" explain)))))

(deftest pipeline-with-only-recorded-effects-classifies-success
  (testing "a build that recorded an artifact but ran no shell step is still success"
    ;; Real shape: a Jenkinsfile that uses a recorder-style step
    ;; (archiveArtifacts) on prebuilt content. No shell, but a real effect.
    (let [obs (-> (r/default-observation)
                  (r/record-effect :artifact-archived))
          {:keys [result rule]} (r/classify obs)]
      (is (= :success result))
      (is (= :default rule)))))

(deftest real-success-path
  (let [obs (-> (r/default-observation)
                (r/record-shell-step {:exit-code 0})
                (r/record-shell-step {:exit-code 0})
                (r/record-effect :artifact-archived)
                (r/record-effect :tests-recorded))
        {:keys [result rule explain]} (r/classify obs)]
    (is (= :success result))
    (is (= :default rule))
    (is (re-find #"2 shell step" explain))
    (is (re-find #"2 effect" explain))))

(deftest rule-precedence-aborted-beats-failures
  (testing "if a build had failures THEN was cancelled, :aborted wins"
    (let [obs (-> (r/default-observation)
                  (r/record-shell-step {:exit-code 1})
                  (r/mark-cancelled))]
      (is (= :aborted (:result (r/classify obs)))))))

(deftest rule-precedence-unsupported-beats-failure
  (testing "an :unsupported construct dominates downstream step failures
            — because the failures may be caused by the unsupported shape"
    (let [obs (-> (r/default-observation)
                  (r/record-shell-step {:exit-code 0})
                  (r/record-unsupported-construct "agent.kubernetes")
                  (r/record-shell-step {:exit-code 1}))]
      (is (= :unsupported (:result (r/classify obs)))))))

(deftest explain-convenience-returns-just-the-string
  (is (string? (r/explain (r/default-observation))))
  (is (re-find #"did nothing" (r/explain (r/default-observation)))))

;; ---------------------------------------------------------------------------
;; Wild-corpus shape receipts — the headline outcome
;; ---------------------------------------------------------------------------

(deftest wild-corpus-7-of-15-vacuous-successes-reclassify
  (testing "the 7 wild-corpus entries that previously reported false :success
            now classify as :neutral or :unsupported, depending on the
            silent-skip path that produced the SUCCESS"
    ;; This isn't a transcript of the actual builds — it's the SHAPE of
    ;; each silent-skip path, exercised against the classifier. anvil's
    ;; v0.4 wild-corpus integration will report the real numbers; this
    ;; test guards the rule set against regression.
    (let [shapes
          [;; Pure structural walk — every stage steps body was skipped
           {:obs (r/default-observation)
            :expected :neutral
            :why "empty walk"}
           ;; agent { docker } silently skipped on no-docker host
           {:obs (-> (r/default-observation)
                     (r/record-unhonored-agent "agent.docker"))
            :expected :unsupported
            :why "docker agent skipped"}
           ;; agent { kubernetes } skipped
           {:obs (-> (r/default-observation)
                     (r/record-unsupported-construct "agent.kubernetes"))
            :expected :unsupported
            :why "kubernetes agent skipped"}
           ;; tool() returned empty, withCredentials bound ""
           {:obs (-> (r/default-observation)
                     (r/record-shell-step {:exit-code 0})
                     (r/record-unresolved-tool "jdk_17_latest"))
            :expected :failure
            :why "tool unresolved"}
           {:obs (-> (r/default-observation)
                     (r/record-shell-step {:exit-code 0})
                     (r/record-unresolved-credential "gpg-key"))
            :expected :failure
            :why "cred unresolved"}
           ;; A stage body that consists solely of an unsupported step
           {:obs (-> (r/default-observation)
                     (r/record-unsupported-construct "step.recordIssues"))
            :expected :unsupported
            :why "unsupported step"}
           ;; A pipeline that only sets env and conditionals — no work
           {:obs (r/default-observation)
            :expected :neutral
            :why "env-only pipeline"}]]
      (doseq [{:keys [obs expected why]} shapes]
        (is (= expected (:result (r/classify obs)))
            (str "shape: " why " — expected " expected))))))
