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

(deftest mask-secrets-length-descending-order
  ;; Bug 0.4.2#3: reduce over secret-values used iteration order.
  ;; With ["abc" "abcdef"] on input "abcdef token", replacing "abc"
  ;; first turned the text into "***def token" — the longer secret
  ;; "abcdef" never matched again and leaked as "***def".
  ;; Fix: sort by length descending so longer secrets mask first.
  (testing "longer secret containing a shorter secret masks first (no partial leak)"
    (let [out (masker/mask-secrets "abcdef token" ["abcd" "abcdef"])]
      (is (= "*** token" out)
          "the full 6-char secret should be replaced, not just its 4-char prefix")
      (is (not (re-find #"def" out))
          "no partial-leak suffix should survive")))
  (testing "iteration order from the input doesn't matter (both orderings give same result)"
    (is (= (masker/mask-secrets "abcdef" ["abcd" "abcdef"])
           (masker/mask-secrets "abcdef" ["abcdef" "abcd"])))))

(deftest mask-secrets-min-length-guard
  ;; Operator footgun protection: very short "secrets" turn build output
  ;; into a *** soup and aren't real secrets anyway. Refuse to mask
  ;; values shorter than min-secret-length, log a warn.
  (testing "secret shorter than min-secret-length is not masked"
    (is (= "min-secret-length is 4 by default"
           (masker/mask-secrets "min-secret-length is 4 by default" ["is"])))
    (is (= 4 masker/min-secret-length)))
  (testing "secret at exactly min-secret-length IS masked"
    (is (= "x=***" (masker/mask-secrets "x=abcd" ["abcd"]))))
  (testing "short secrets in the same call don't block longer ones from masking"
    (is (= "*** is here"
           (masker/mask-secrets "abcdef is here" ["is" "abcdef"])))))
