(ns chengis.engine.log-masker-test
  "Unit tests for secret masking in build output. Pure — runs in the :unit tier."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.log-masker :as masker]))

(deftest mask-secrets-test
  (testing "nil text returns nil (guard branch)"
    (is (nil? (masker/mask-secrets nil #{"secret"}))))
  (testing "empty secret set returns text unchanged (guard branch)"
    (is (= "hello" (masker/mask-secrets "hello" #{})))
    (is (= "hello" (masker/mask-secrets "hello" nil))))
  (testing "single secret is replaced with ***"
    (is (= "token=***" (masker/mask-secrets "token=abc123" #{"abc123"}))))
  (testing "all occurrences of a secret are replaced, not just the first"
    (is (= "*** and ***" (masker/mask-secrets "abc123 and abc123" #{"abc123"}))))
  (testing "multiple distinct secrets all masked"
    (is (= "u=*** p=***"
           (masker/mask-secrets "u=alice p=hunter2" #{"alice" "hunter2"}))))
  (testing "blank/empty secret value in the set is skipped (inner guard)"
    (is (= "unchanged" (masker/mask-secrets "unchanged" #{""})))
    (is (= "x=***" (masker/mask-secrets "x=real" #{"" "real"}))))
  (testing "nil secret value in the set is skipped, does not throw"
    (is (= "x=***" (masker/mask-secrets "x=real" #{nil "real"}))))
  (testing "text without any secret is returned unchanged"
    (is (= "nothing here" (masker/mask-secrets "nothing here" #{"absent"}))))
  (testing "mask replacement constant is ***"
    (is (= "***" masker/mask-replacement))))
