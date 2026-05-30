(ns chengis.dsl.chengisfile-test
  (:require [clojure.test :refer :all]
            [chengis.dsl.chengisfile :as cf]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- write-temp-chengisfile
  "Write content to a temp Chengisfile and return the file path."
  [content]
  (let [dir (io/file (str "/tmp/chengis-test-cf-" (System/nanoTime)))
        f   (io/file dir "Chengisfile")]
    (.mkdirs dir)
    (spit f content)
    {:dir  (.getAbsolutePath dir)
     :file (.getAbsolutePath f)}))

;; ---------------------------------------------------------------------------
;; convert-condition
;; ---------------------------------------------------------------------------

(deftest convert-condition-test
  (testing "nil input returns nil"
    (is (nil? (cf/convert-condition nil))))

  (testing "branch condition"
    (is (= {:type :branch :value "main"}
           (cf/convert-condition {:branch "main"}))))

  (testing "param condition"
    (is (= {:type :param :param "deploy" :value "true"}
           (cf/convert-condition {:param "deploy" :value "true"}))))

  (testing "unknown keys return nil"
    (is (nil? (cf/convert-condition {:foo "bar"}))))

  (testing "env condition"
    (is (= {:type :env :var "DEPLOY" :value "true"}
           (cf/convert-condition {:env "DEPLOY" :value "true"}))))

  (testing "expression [:and ...]"
    (is (= {:type :and
            :conditions [{:type :branch :value "main"}
                         {:type :env :var "X" :value "y"}]}
           (cf/convert-condition [:and {:branch "main"} {:env "X" :value "y"}]))))

  (testing "expression [:or ...]"
    (is (= {:type :or
            :conditions [{:type :branch :value "main"}
                         {:type :branch :value "release"}]}
           (cf/convert-condition [:or {:branch "main"} {:branch "release"}]))))

  (testing "expression [:not ...]"
    (is (= {:type :not :condition {:type :branch :value "main"}}
           (cf/convert-condition [:not {:branch "main"}]))))

  (testing "nested expression [:and {} [:or {} {}]]"
    ;; Compositional sanity — the kind of clause a real CI/CD user writes.
    (let [result (cf/convert-condition
                  [:and
                   {:branch "main"}
                   [:or {:env "DEPLOY" :value "true"}
                    {:param "force" :value "true"}]])]
      (is (= :and (:type result)))
      (is (= {:type :branch :value "main"} (first (:conditions result))))
      (is (= :or (:type (second (:conditions result)))))
      (is (= 2 (count (:conditions (second (:conditions result))))))))

  (testing "expression with unknown operator returns nil (validator catches it)"
    (is (nil? (cf/convert-condition [:xor {:branch "main"} {:branch "dev"}])))))

;; ---------------------------------------------------------------------------
;; convert-step
;; ---------------------------------------------------------------------------

(deftest convert-step-test
  (testing "minimal step"
    (is (= {:step-name "Compile"
            :type      :shell
            :command   "mvn compile"}
           (cf/convert-step {:name "Compile" :run "mvn compile"}))))

  (testing "step with env"
    (let [result (cf/convert-step {:name "Deploy" :run "./deploy.sh"
                                   :env {"ENV" "prod" "REGION" "us-east-1"}})]
      (is (= "Deploy" (:step-name result)))
      (is (= :shell (:type result)))
      (is (= "./deploy.sh" (:command result)))
      (is (= {"ENV" "prod" "REGION" "us-east-1"} (:env result)))))

  (testing "step with timeout"
    (let [result (cf/convert-step {:name "Slow" :run "sleep 100" :timeout 120000})]
      (is (= 120000 (:timeout result)))))

  (testing "step with dir"
    (let [result (cf/convert-step {:name "Sub" :run "make" :dir "subproject"})]
      (is (= "subproject" (:dir result)))))

  (testing "step with all options"
    (let [result (cf/convert-step {:name "Full" :run "cmd"
                                   :env {"A" "1"} :timeout 5000 :dir "/app"})]
      (is (= {:step-name "Full" :type :shell :command "cmd"
              :env {"A" "1"} :timeout 5000 :dir "/app"}
             result))))

  (testing "step with :retry passes the retry map through verbatim"
    ;; The DSL layer is intentionally a thin pass-through for :retry — the
    ;; executor owns retry semantics. Pin the shape here so a future refactor
    ;; that tries to normalize {:max N} → {:retries (- N 1)} or similar gets
    ;; caught in the parser, not in three executor tests at once.
    (let [result (cf/convert-step {:name "Flaky" :run "./flaky.sh"
                                   :retry {:max 3 :delay 500}})]
      (is (= "Flaky" (:step-name result)))
      (is (= {:max 3 :delay 500} (:retry result)))))

  (testing "step with :retry without :delay passes through (executor defaults delay to 0)"
    (let [result (cf/convert-step {:name "Bare" :run "./bare.sh" :retry {:max 2}})]
      (is (= {:max 2} (:retry result)))))

  (testing "step with :continue-on-fail true passes through"
    (let [result (cf/convert-step {:name "MayFail" :run "./flaky.sh"
                                   :continue-on-fail true})]
      (is (true? (:continue-on-fail result)))))

  (testing "step with :continue-on-fail false passes through (explicit no)"
    ;; false has to round-trip too — useful for users overriding a template
    ;; default that sets true.
    (let [result (cf/convert-step {:name "Strict" :run "./strict.sh"
                                   :continue-on-fail false})]
      (is (false? (:continue-on-fail result)))))

  (testing "step without :continue-on-fail key has no :continue-on-fail in result"
    (let [result (cf/convert-step {:name "Default" :run "./d.sh"})]
      (is (not (contains? result :continue-on-fail)))))

  (testing "step with :when {:branch ...} populates :condition (step-level :when, PR3)"
    ;; Previously step-level :when was silently dropped by convert-step —
    ;; only stage-level :when worked. PR3 closes that gap. Without the
    ;; populated :condition the executor's evaluate-condition would see nil
    ;; (always-run), so this is the key assertion.
    (let [result (cf/convert-step {:name "DeployStep" :run "./deploy.sh"
                                   :when {:branch "main"}})]
      (is (= {:type :branch :value "main"} (:condition result)))))

  (testing "step with :when {:env ...} populates :condition"
    (let [result (cf/convert-step {:name "OptIn" :run "./run.sh"
                                   :when {:env "DEPLOY" :value "true"}})]
      (is (= {:type :env :var "DEPLOY" :value "true"} (:condition result)))))

  (testing "step with :when expression populates :condition recursively"
    (let [result (cf/convert-step {:name "Multi" :run "./run.sh"
                                   :when [:and {:branch "main"}
                                          {:env "X" :value "y"}]})]
      (is (= :and (-> result :condition :type)))
      (is (= 2 (-> result :condition :conditions count))))))

;; ---------------------------------------------------------------------------
;; convert-stage
;; ---------------------------------------------------------------------------

(deftest convert-stage-test
  (testing "sequential stage"
    (let [result (cf/convert-stage {:name "Build"
                                    :steps [{:name "A" :run "echo a"}
                                            {:name "B" :run "echo b"}]})]
      (is (= "Build" (:stage-name result)))
      (is (false? (:parallel? result)))
      (is (= 2 (count (:steps result))))
      (is (nil? (:condition result)))))

  (testing "parallel stage"
    (let [result (cf/convert-stage {:name "Test"
                                    :parallel true
                                    :steps [{:name "Unit" :run "test"}]})]
      (is (true? (:parallel? result)))))

  (testing "stage with branch condition"
    (let [result (cf/convert-stage {:name "Deploy"
                                    :when {:branch "main"}
                                    :steps [{:name "Ship" :run "deploy"}]})]
      (is (= {:type :branch :value "main"} (:condition result)))))

  (testing "stage with param condition"
    (let [result (cf/convert-stage {:name "Release"
                                    :when {:param "release" :value "true"}
                                    :steps [{:name "Tag" :run "tag"}]})]
      (is (= {:type :param :param "release" :value "true"} (:condition result))))))

;; ---------------------------------------------------------------------------
;; validate-chengisfile
;; ---------------------------------------------------------------------------

(deftest validate-chengisfile-test
  (testing "valid minimal file"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Compile" :run "make"}]}]})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid full file"
    (let [result (cf/validate-chengisfile
                  {:description "Full pipeline"
                   :stages [{:name "Build"
                             :steps [{:name "Compile" :run "make"
                                      :env {"CC" "gcc"} :timeout 30000}]}
                            {:name "Test"
                             :parallel true
                             :steps [{:name "Unit" :run "test"}
                                     {:name "Lint" :run "lint"}]}
                            {:name "Deploy"
                             :when {:branch "main"}
                             :steps [{:name "Ship" :run "deploy"}]}]})]
      (is (:valid? result))))

  (testing "missing :stages"
    (let [result (cf/validate-chengisfile {:description "No stages"})]
      (is (not (:valid? result)))
      (is (some #(re-find #"Missing required key :stages" %) (:errors result)))))

  (testing "empty :stages"
    (let [result (cf/validate-chengisfile {:stages []})]
      (is (not (:valid? result)))
      (is (some #(re-find #":stages must not be empty" %) (:errors result)))))

  (testing "stage missing :name"
    (let [result (cf/validate-chengisfile
                  {:stages [{:steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :name" %) (:errors result)))))

  (testing "stage missing :steps"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :steps" %) (:errors result)))))

  (testing "step missing :name"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:run "make"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :name" %) (:errors result)))))

  (testing "step missing :run"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Compile"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :run" %) (:errors result)))))

  (testing "invalid :env type"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd" :env "not-a-map"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":env must be a map" %) (:errors result)))))

  (testing "invalid :timeout type"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd" :timeout "slow"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":timeout must be a positive integer" %) (:errors result)))))

  (testing "invalid :when clause"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :when {:foo "bar"}
                             :steps [{:name "A" :run "cmd"}]}]})]
      (is (not (:valid? result)))
      ;; PR3 widened :when to also support :env + expression vectors; the new
      ;; error message names all leaf keys instead of the original two.
      (is (some #(re-find #":when leaf must contain one of :branch, :param, :env" %)
                (:errors result)))))

  (testing "valid :retry with :max only"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Flaky" :run "./flaky.sh"
                                      :retry {:max 3}}]}]})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid :retry with :max + :delay"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Flaky" :run "./flaky.sh"
                                      :retry {:max 5 :delay 1500}}]}]})]
      (is (:valid? result))))

  (testing ":retry without :max is invalid"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:delay 1000}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":retry must have :max" %) (:errors result)))))

  (testing ":retry :max must be a positive integer"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:max 0}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":retry must have :max \(positive integer\)" %)
                (:errors result)))))

  (testing ":retry :max must be a positive integer (negative)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:max -2}}]}]})]
      (is (not (:valid? result)))))

  (testing ":retry :max must be an integer (string rejected)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:max "three"}}]}]})]
      (is (not (:valid? result)))))

  (testing ":retry must be a map (vector rejected)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry [3 1000]}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":retry must be a map" %) (:errors result)))))

  (testing ":retry :delay must be non-negative integer when present"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:max 3 :delay -100}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":retry :delay must be a non-negative integer" %)
                (:errors result))))

    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :retry {:max 3 :delay "slow"}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":retry :delay must be a non-negative integer" %)
                (:errors result)))))

  (testing "not a map"
    (let [result (cf/validate-chengisfile "not a map")]
      (is (not (:valid? result)))))

  (testing "post-action step with invalid :retry is rejected (Codex PR #17 P1)"
    ;; Prior to the helper extraction, :retry validation only ran on stage
    ;; steps; convert-step forwarded :retry on post-action steps too, so
    ;; {:max "3"} would parse clean and crash the executor.
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build" :steps [{:name "OK" :run "echo"}]}]
                   :post {:always [{:name "Notify" :run "./notify.sh"
                                    :retry {:max "three"}}]}})]
      (is (not (:valid? result)))
      (is (some #(re-find #":post always step 1.*:retry must have :max" %)
                (:errors result))
          (str "expected :post step :retry error, got " (:errors result)))))

  (testing "post-action step with valid :retry is accepted"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build" :steps [{:name "OK" :run "echo"}]}]
                   :post {:on-failure [{:name "Notify" :run "./notify.sh"
                                        :retry {:max 2 :delay 500}}]}})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid :continue-on-fail true/false on stage step"
    (doseq [v [true false]]
      (let [result (cf/validate-chengisfile
                    {:stages [{:name "Build"
                               :steps [{:name "A" :run "cmd"
                                        :continue-on-fail v}]}]})]
        (is (:valid? result) (str "value " (pr-str v) " must be valid"))
        (is (empty? (:errors result))))))

  (testing ":continue-on-fail truthy-not-true (string \"yes\") is rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :continue-on-fail "yes"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":continue-on-fail must be true or false" %)
                (:errors result)))))

  (testing ":continue-on-fail integer 1 is rejected (EDN-strict, not truthy-coerced)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :continue-on-fail 1}]}]})]
      (is (not (:valid? result)))))

  (testing ":continue-on-fail :true keyword is rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "A" :run "cmd"
                                      :continue-on-fail :true}]}]})]
      (is (not (:valid? result)))))

  (testing "post-action step with :continue-on-fail validated via shared helper"
    ;; Same shared-helper pattern as :retry — proves the validator runs both
    ;; places convert-step forwards the key.
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build" :steps [{:name "OK" :run "echo"}]}]
                   :post {:always [{:name "Notify" :run "./notify.sh"
                                    :continue-on-fail "maybe"}]}})]
      (is (not (:valid? result)))
      (is (some #(re-find #":post always step 1.*:continue-on-fail must be true or false" %)
                (:errors result))))

    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build" :steps [{:name "OK" :run "echo"}]}]
                   :post {:always [{:name "Notify" :run "./notify.sh"
                                    :continue-on-fail true}]}})]
      (is (:valid? result))))

  (testing "valid step-level :when {:branch ...} (PR3)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Deploy" :run "./d.sh"
                                      :when {:branch "main"}}]}]})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid :when {:env ...}"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "OptIn" :run "./run.sh"
                                      :when {:env "X" :value "y"}}]}]})]
      (is (:valid? result))))

  (testing ":when {:env ...} missing :value rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Bad" :run "./run.sh"
                                      :when {:env "X"}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":when \{:env ...\} must also have :value" %)
                (:errors result)))))

  (testing ":when {:param ...} missing :value rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Bad" :run "./run.sh"
                                      :when {:param "deploy"}}]}]})]
      (is (not (:valid? result)))))

  (testing ":when {:env ... :value nil} rejected (Codex PR #21 P2)"
    ;; The bug: validation only checked (contains? m :value), so nil-as-value
    ;; passed. evaluate-condition then did `(= nil (get-in ctx [:env var]))`,
    ;; and if the env var was absent, get-in returned nil, so the condition
    ;; was true — gating ran the step on a MISSING env var. Reject at parse.
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Bad" :run "./run.sh"
                                      :when {:env "X" :value nil}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":when \{:env ...\} :value must not be nil" %)
                (:errors result)))))

  (testing ":when {:param ... :value nil} rejected (same bug class)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "Bad" :run "./run.sh"
                                      :when {:param "deploy" :value nil}}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":when \{:param ...\} :value must not be nil" %)
                (:errors result)))))

  (testing ":when {:env ... :value \"\"} accepted (empty string is a legit value)"
    ;; Defense against over-correction: nil ≠ "". Users may legitimately want
    ;; to match an env var explicitly set to empty string.
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Build"
                             :steps [{:name "OK" :run "./run.sh"
                                      :when {:env "X" :value ""}}]}]})]
      (is (:valid? result))))

  (testing ":when [:and nil] rejected — nil operand inside expression (Codex PR #21 P2 r2)"
    ;; Top-level nil :when stays valid (means \"no condition\"). But nil INSIDE
    ;; an expression operand is meaningless and would silently evaluate as
    ;; true at runtime, causing :and/:or/:not to behave unexpectedly. Reject.
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:and nil]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"nil operand not allowed inside :when expression" %)
                (:errors result)))))

  (testing ":when [:or {:branch \"main\"} nil] rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:or {:branch "main"} nil]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"nil operand not allowed" %) (:errors result)))))

  (testing ":when [:not nil] rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:not nil]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"nil operand not allowed" %) (:errors result)))))

  (testing "deeply-nested :when [:and {} [:or nil]] rejected (recursion catches nil at any depth)"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:and {:branch "main"}
                                    [:or nil {:env "X" :value "y"}]]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"nil operand not allowed" %) (:errors result)))))

  (testing "valid stage :when [:and ...] vector expression"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "Deploy"
                             :when [:and {:branch "main"}
                                    {:env "DEPLOY" :value "true"}]
                             :steps [{:name "Ship" :run "deploy"}]}]})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid :when [:or ...]"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:or {:branch "main"} {:branch "release"}]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (:valid? result))))

  (testing "valid :when [:not ...]"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:not {:branch "main"}]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (:valid? result))))

  (testing ":when [:not ...] with multiple inner conditions rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:not {:branch "main"} {:branch "release"}]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":not \.\.\.\] takes exactly one inner condition" %)
                (:errors result)))))

  (testing ":when [:and] with no inner conditions rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:and]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":and\] must have at least one inner condition" %)
                (:errors result)))))

  (testing ":when [:xor ...] unknown operator rejected"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:xor {:branch "main"} {:branch "dev"}]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"operator must be :and / :or / :not" %)
                (:errors result)))))

  (testing "nested :when [:and {} [:or {} {}]] validates recursively"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:and
                                    {:branch "main"}
                                    [:or {:env "X" :value "y"}
                                     {:env "X" :value "z"}]]
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (:valid? result))))

  (testing "nested :when expression with malformed inner leaf is caught"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "S"
                             :when [:and
                                    {:branch "main"}
                                    {:env "X"}]  ; missing :value
                             :steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":when \{:env" %) (:errors result)))))

  (testing "post-action step :when validated via shared helper"
    (let [result (cf/validate-chengisfile
                  {:stages [{:name "B" :steps [{:name "A" :run "echo"}]}]
                   :post {:always [{:name "N" :run "./notify.sh"
                                    :when {:env "X"}}]}})]  ; missing :value
      (is (not (:valid? result)))
      (is (some #(re-find #":post always step 1.*:when \{:env" %)
                (:errors result))))))

;; ---------------------------------------------------------------------------
;; chengisfile-exists?
;; ---------------------------------------------------------------------------

(deftest chengisfile-exists-test
  (testing "exists when file is present"
    (let [{:keys [dir]} (write-temp-chengisfile "{:stages []}")]
      (is (cf/chengisfile-exists? dir))))

  (testing "does not exist for empty directory"
    (let [dir (str "/tmp/chengis-test-empty-" (System/nanoTime))]
      (.mkdirs (io/file dir))
      (is (not (cf/chengisfile-exists? dir))))))

;; ---------------------------------------------------------------------------
;; parse-chengisfile (integration)
;; ---------------------------------------------------------------------------

(deftest parse-chengisfile-test
  (testing "valid Chengisfile end-to-end"
    (let [content (pr-str {:description "Test pipeline"
                           :stages [{:name "Build"
                                     :steps [{:name "Compile" :run "make"}
                                             {:name "Link" :run "make link"
                                              :timeout 60000}]}
                                    {:name "Test"
                                     :parallel true
                                     :steps [{:name "Unit" :run "make test"}
                                             {:name "Lint" :run "make lint"}]}
                                    {:name "Deploy"
                                     :when {:branch "main"}
                                     :steps [{:name "Ship" :run "./deploy.sh"
                                              :env {"ENV" "prod"}}]}]})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:error result)))
      (is (some? (:pipeline result)))
      (let [p (:pipeline result)]
        ;; Description
        (is (= "Test pipeline" (:description p)))
        ;; 3 stages
        (is (= 3 (count (:stages p))))
        ;; Stage 1: Build (sequential, 2 steps)
        (let [s1 (first (:stages p))]
          (is (= "Build" (:stage-name s1)))
          (is (false? (:parallel? s1)))
          (is (nil? (:condition s1)))
          (is (= 2 (count (:steps s1))))
          (is (= {:step-name "Compile" :type :shell :command "make"}
                 (first (:steps s1))))
          (is (= 60000 (:timeout (second (:steps s1))))))
        ;; Stage 2: Test (parallel)
        (let [s2 (second (:stages p))]
          (is (= "Test" (:stage-name s2)))
          (is (true? (:parallel? s2)))
          (is (= 2 (count (:steps s2)))))
        ;; Stage 3: Deploy (condition)
        (let [s3 (nth (:stages p) 2)]
          (is (= "Deploy" (:stage-name s3)))
          (is (= {:type :branch :value "main"} (:condition s3)))
          (is (= {"ENV" "prod"} (:env (first (:steps s3)))))))))

  (testing "missing file"
    (let [result (cf/parse-chengisfile "/tmp/nonexistent-chengisfile-xyz")]
      (is (some? (:error result)))
      (is (re-find #"File not found" (:error result)))))

  (testing "invalid EDN syntax"
    (let [{:keys [file]} (write-temp-chengisfile "{:stages not-valid-edn !!}")
          result (cf/parse-chengisfile file)]
      (is (some? (:error result)))))

  (testing "valid EDN but invalid structure"
    (let [{:keys [file]} (write-temp-chengisfile "{:description \"no stages\"}")
          result (cf/parse-chengisfile file)]
      (is (some? (:error result)))
      (is (re-find #"Validation failed" (:error result)))))

  (testing "no :source in output — source comes from job"
    (let [content (pr-str {:stages [{:name "Build"
                                     :steps [{:name "A" :run "echo a"}]}]})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:source (:pipeline result)))))))

;; ---------------------------------------------------------------------------
;; MUT-P2 (Pareto): validation detail — named errors, env/timeout/:when checks.
;; ---------------------------------------------------------------------------

(deftest validate-chengisfile-error-detail-test
  (testing "a named stage missing :steps is named in the error (kills or->and)"
    (let [r (cf/validate-chengisfile {:stages [{:name "build"}]})]
      (is (false? (:valid? r)))
      (is (some #(clojure.string/includes? % "build") (:errors r)))))
  (testing "a named step missing :run is named in the error"
    (let [r (cf/validate-chengisfile {:stages [{:name "deploy" :steps [{:name "push"}]}]})]
      (is (some #(clojure.string/includes? % "push") (:errors r)))))
  (testing ":env must be a map (kills and-guard)"
    (let [r (cf/validate-chengisfile
             {:stages [{:name "s" :steps [{:name "x" :run "echo" :env "nope"}]}]})]
      (is (some #(clojure.string/includes? % ":env must be a map") (:errors r)))))
  (testing ":timeout must be a positive integer (kills and-guard)"
    (let [r (cf/validate-chengisfile
             {:stages [{:name "s" :steps [{:name "x" :run "echo" :timeout -5}]}]})]
      (is (some #(clojure.string/includes? % ":timeout") (:errors r)))))
  (testing ":when leaf must name one of :branch/:param/:env; only :branch is valid (kills or->and)"
    ;; PR3: error message updated to reflect the wider leaf set + expression form.
    (let [bad (cf/validate-chengisfile
               {:stages [{:name "s" :steps [{:name "x" :run "echo"}] :when {}}]})
          ok  (cf/validate-chengisfile
               {:stages [{:name "s" :steps [{:name "x" :run "echo"}] :when {:branch "main"}}]})]
      (is (some #(clojure.string/includes? % ":when leaf must contain one of") (:errors bad)))
      (is (true? (:valid? ok))))))

;; ---------------------------------------------------------------------------
;; :import — shared-library declaration (CHG-FEAT-005 PR2)
;; ---------------------------------------------------------------------------
;;
;; The :import key extends the Chengisfile with a list of shared-library
;; dependencies. Validation at parse time covers shape, version-spec
;; syntax, name format, and duplicate-name detection. Runtime concerns
;; (does the library exist? does the SHA resolve?) live in
;; chengis.engine.library-resolver.

(defn- valid-stages
  "Reusable minimum :stages payload for tests that only care about :import
   validation."
  []
  [{:name "Build" :steps [{:name "A" :run "echo a"}]}])

(deftest validate-import-test
  (testing "no :import is valid (key entirely omitted)"
    (let [r (cf/validate-chengisfile {:stages (valid-stages)})]
      (is (:valid? r))))

  (testing "empty :import vector is REJECTED — omit the key entirely"
    ;; A no-op :import [] adds noise without conveying intent; the parser
    ;; surfaces this as a clear validation error rather than silently
    ;; passing through an empty vector that downstream code would have
    ;; to special-case.
    (let [r (cf/validate-chengisfile {:import [] :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":import must not be empty" %) (:errors r)))))

  (testing ":import must be a vector (map rejected)"
    (let [r (cf/validate-chengisfile
             {:import {"a" "1.2"} :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":import must be a vector" %) (:errors r)))))

  (testing "well-formed :import passes all four version-spec shapes"
    ;; Each spec shape from library-store/resolve-version's contract:
    ;;   - "latest"         pointer to most-recent
    ;;   - "@sha:<hex>"     immutable commit pin
    ;;   - 2+-dot label     exact version match
    ;;   - <2-dot label     floating prefix
    (let [r (cf/validate-chengisfile
             {:import [{:name "shared-utils"   :version "latest"}
                       {:name "deploy-helpers" :version "@sha:abc1234"}
                       {:name "build-toolkit"  :version "v1.2.3"}
                       {:name "core"           :version "v1"}]
              :stages (valid-stages)})]
      (is (:valid? r) (str "errors: " (:errors r)))
      (is (empty? (:errors r)))))

  (testing "owner/path-style :name is allowed (matches library-store UNIQUE key)"
    (let [r (cf/validate-chengisfile
             {:import [{:name "org/shared" :version "v1.0.0"}]
              :stages (valid-stages)})]
      (is (:valid? r))))

  (testing ":import entry must be a map — string rejected"
    (let [r (cf/validate-chengisfile
             {:import ["just-a-name"] :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":import\[0\]: must be a map" %) (:errors r)))))

  (testing ":import entry missing :name reports the index"
    (let [r (cf/validate-chengisfile
             {:import [{:version "v1.0.0"}] :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":import\[0\]: missing :name" %) (:errors r)))))

  (testing ":import entry missing :version reports the index"
    (let [r (cf/validate-chengisfile
             {:import [{:name "shared"}] :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":import\[0\]: missing :version" %) (:errors r)))))

  (testing ":name path-traversal '..' rejected (defence-in-depth for cache layout)"
    ;; The resolver's cache layout is <cache-dir>/<name>/<sha>/...; without
    ;; this guard, a malicious :name like "org/../etc" could be coaxed
    ;; through the registry into resolving outside the cache root. The
    ;; resolver also canonicalizes + asserts-within at runtime, but
    ;; surfacing the error at parse time is the cleaner UX.
    (let [r (cf/validate-chengisfile
             {:import [{:name "org/../etc" :version "v1"}]
              :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":name must not contain '\.\.'" %) (:errors r)))))

  (testing ":name with shell metacharacters rejected"
    (doseq [bad-name ["a$(rm -rf)b" "a;b" "a|b" "a b"]]
      (let [r (cf/validate-chengisfile
               {:import [{:name bad-name :version "v1"}]
                :stages (valid-stages)})]
        (is (not (:valid? r))
            (str ":name " (pr-str bad-name) " must be rejected"))
        (is (some #(re-find #":name contains disallowed characters" %)
                  (:errors r))))))

  (testing ":version non-string rejected"
    (let [r (cf/validate-chengisfile
             {:import [{:name "a" :version 1.2}]
              :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":version must be a string" %) (:errors r)))))

  (testing ":version blank string rejected"
    (let [r (cf/validate-chengisfile
             {:import [{:name "a" :version "   "}]
              :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #":version must not be blank" %) (:errors r)))))

  (testing ":version @sha: pin must contain 4-40 hex chars"
    ;; Too short, too long, non-hex — all rejected. This keeps the
    ;; resolver's @sha: branch free of shell metas / path separators.
    (doseq [bad ["@sha:abc" "@sha:zzz1234" "@sha:" "@sha:not-hex"]]
      (let [r (cf/validate-chengisfile
               {:import [{:name "a" :version bad}]
                :stages (valid-stages)})]
        (is (not (:valid? r))
            (str ":version " (pr-str bad) " must be rejected"))
        (is (some #(re-find #":version @sha: pin must be 4-40 hex chars" %)
                  (:errors r))))))

  (testing ":version with shell metas and LIKE wildcard rejected"
    ;; Defense-in-depth — the resolver uses substr-equality so % can't
    ;; smuggle a wildcard, but rejecting it (and other shell-active
    ;; chars) at parse keeps the contract obvious in user-facing errors.
    ;; Underscore is INTENTIONALLY allowed since git refs like
    ;; "v1_release" are common in the wild.
    (doseq [bad ["v1.%" "v1*" "v1 2" "v1;rm"]]
      (let [r (cf/validate-chengisfile
               {:import [{:name "a" :version bad}]
                :stages (valid-stages)})]
        (is (not (:valid? r))
            (str ":version " (pr-str bad) " must be rejected"))
        (is (some #(re-find #":version spec contains disallowed characters" %)
                  (:errors r))))))

  (testing ":version with underscore is accepted (common in git ref names)"
    (let [r (cf/validate-chengisfile
             {:import [{:name "a" :version "v1_release"}]
              :stages (valid-stages)})]
      (is (:valid? r))))

  (testing "duplicate :name across :import entries surfaces a clear error"
    (let [r (cf/validate-chengisfile
             {:import [{:name "shared" :version "v1.0"}
                       {:name "shared" :version "v2.0"}]
              :stages (valid-stages)})]
      (is (not (:valid? r)))
      (is (some #(re-find #"duplicate :name \"shared\"" %) (:errors r))
          (str "expected duplicate-name error, got " (:errors r)))))

  (testing "errors from multiple bad entries are all surfaced (not just the first)"
    (let [r (cf/validate-chengisfile
             {:import [{:name "" :version "v1.0"}
                       {:name "ok" :version ""}
                       {:name "another-ok" :version "v2.0"}]
              :stages (valid-stages)})]
      (is (not (:valid? r)))
      ;; first entry name-blank + second entry version-blank — both reported
      (is (>= (count (:errors r)) 2)))))

(deftest convert-import-test
  (testing "convert-import is a thin pass-through for PR2 (lockfile in PR3)"
    (is (= {:name "shared" :version "v1.0.0"}
           (cf/convert-import {:name "shared" :version "v1.0.0"})))))

(deftest parse-chengisfile-includes-imports-test
  (testing ":import survives the parse-chengisfile round trip as :imports"
    (let [content (pr-str {:import [{:name "shared-utils" :version "1.2"}
                                    {:name "deploy" :version "@sha:abc1234"}]
                           :stages (valid-stages)})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:error result)) (str "errors: " (:error result)))
      (let [imports (-> result :pipeline :imports)]
        (is (= 2 (count imports)))
        (is (= "shared-utils" (-> imports first :name)))
        (is (= "1.2" (-> imports first :version)))
        (is (= "@sha:abc1234" (-> imports second :version))))))

  (testing "no :imports key when :import was absent (no empty pass-through)"
    (let [content (pr-str {:stages (valid-stages)})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:error result)))
      (is (not (contains? (:pipeline result) :imports)))))

  (testing "malformed :import surfaces a validation error rather than a parse exception"
    ;; A user typo (e.g. forgetting :version) should produce a readable
    ;; error message, not an Exception inside convert-import.
    (let [content (pr-str {:import [{:name "oops"}] :stages (valid-stages)})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (some? (:error result)))
      (is (re-find #":import\[0\]: missing :version" (:error result))))))
