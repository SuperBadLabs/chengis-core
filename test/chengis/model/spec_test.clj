(ns chengis.model.spec-test
  "Unit tests for build state-transition validation. Pure — runs in the :unit tier."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.model.spec :as spec]))

(deftest valid-transition-test
  (testing "queued can move to running or aborted"
    (is (true? (spec/valid-transition? :queued :running)))
    (is (true? (spec/valid-transition? :queued :aborted))))
  (testing "queued cannot jump straight to success/failure"
    (is (false? (spec/valid-transition? :queued :success)))
    (is (false? (spec/valid-transition? :queued :failure))))
  (testing "running can move to success, failure, or aborted"
    (is (true? (spec/valid-transition? :running :success)))
    (is (true? (spec/valid-transition? :running :failure)))
    (is (true? (spec/valid-transition? :running :aborted))))
  (testing "running cannot move back to queued"
    (is (false? (spec/valid-transition? :running :queued))))
  (testing "terminal states allow no further transitions"
    (doseq [terminal [:success :failure :aborted]
            target   [:queued :running :success :failure :aborted]]
      (is (false? (spec/valid-transition? terminal target))
          (str terminal " -> " target " must be invalid"))))
  (testing "unknown from-state yields false, not an exception"
    (is (false? (spec/valid-transition? :bogus :running)))
    (is (false? (spec/valid-transition? nil :running)))))
