(ns chengis.engine.test-parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.test-parser :as tp]))

;; ---------------------------------------------------------------------------
;; JUnit XML
;; ---------------------------------------------------------------------------

(deftest parse-junit-xml-pass-test
  (testing "parse-junit-xml parses passing tests"
    (let [xml "<testsuite name=\"MySuite\" tests=\"2\">
                <testcase name=\"test_add\" classname=\"math\" time=\"0.05\"/>
                <testcase name=\"test_sub\" classname=\"math\" time=\"0.03\"/>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 2 (count results)))
      (is (every? #(= "pass" (:status %)) results))
      (is (= "test_add" (:test-name (first results))))
      (is (= "math" (:test-suite (first results))))
      (is (= 50 (:duration-ms (first results)))))))

(deftest parse-junit-xml-failure-test
  (testing "parse-junit-xml parses failures"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_fail\" classname=\"suite\">
                  <failure message=\"expected 1 got 2\">assertion error</failure>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "fail" (:status (first results))))
      (is (some? (:error-msg (first results)))))))

(deftest parse-junit-xml-error-test
  (testing "parse-junit-xml parses errors"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_error\" classname=\"suite\">
                  <error message=\"NPE\">null pointer</error>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "error" (:status (first results)))))))

(deftest parse-junit-xml-skipped-test
  (testing "parse-junit-xml parses skipped tests"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_skip\" classname=\"suite\">
                  <skipped/>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "skip" (:status (first results)))))))

(deftest parse-junit-xml-nil-test
  (testing "parse-junit-xml returns nil for non-XML"
    (is (nil? (tp/parse-junit-xml "not xml")))
    (is (nil? (tp/parse-junit-xml nil)))
    (is (nil? (tp/parse-junit-xml "")))))

;; ---------------------------------------------------------------------------
;; TAP format
;; ---------------------------------------------------------------------------

(deftest parse-tap-output-test
  (testing "parse-tap-output parses TAP output"
    (let [output "TAP version 13\n1..3\nok 1 - test addition\nnot ok 2 - test subtraction\nok 3 - test multiply # skip reason"
          results (tp/parse-tap-output output)]
      (is (= 3 (count results)))
      (is (= "pass" (:status (first results))))
      (is (= "fail" (:status (second results))))
      (is (= "skip" (:status (nth results 2)))))))

(deftest parse-tap-output-nil-test
  (testing "parse-tap-output returns nil for non-TAP"
    (is (nil? (tp/parse-tap-output "regular output")))
    (is (nil? (tp/parse-tap-output nil)))))

;; ---------------------------------------------------------------------------
;; Generic pattern
;; ---------------------------------------------------------------------------

(deftest parse-generic-passed-failed-test
  (testing "parse-generic-output detects 'X passed, Y failed'"
    (let [result (tp/parse-generic-output "10 passed, 2 failed")]
      (is (some? result))
      (is (= 10 (:total-pass result)))
      (is (= 2 (:total-fail result))))))

(deftest parse-generic-tests-failures-test
  (testing "parse-generic-output detects 'X tests, Y failures'"
    (let [result (tp/parse-generic-output "25 tests, 3 failures")]
      (is (some? result))
      (is (= 22 (:total-pass result)))
      (is (= 3 (:total-fail result))))))

(deftest parse-generic-ran-tests-test
  (testing "parse-generic-output detects 'Ran X tests'"
    (let [result (tp/parse-generic-output "Ran 42 tests in 3.5 seconds")]
      (is (some? result))
      (is (= 42 (:total-run result))))))

(deftest parse-generic-no-match-test
  (testing "parse-generic-output returns nil for no pattern"
    (is (nil? (tp/parse-generic-output "hello world")))
    (is (nil? (tp/parse-generic-output nil)))))

;; ---------------------------------------------------------------------------
;; Combined parser
;; ---------------------------------------------------------------------------

(deftest extract-test-results-junit-test
  (testing "extract-test-results prefers JUnit XML"
    (let [xml "<testsuite><testcase name=\"t1\" classname=\"s\"/></testsuite>"
          results (tp/extract-test-results xml)]
      (is (= 1 (count results)))
      (is (= "t1" (:test-name (first results)))))))

(deftest extract-test-results-tap-test
  (testing "extract-test-results falls back to TAP"
    (let [tap "TAP version 13\n1..1\nok 1 - works"
          results (tp/extract-test-results tap)]
      (is (= 1 (count results)))
      (is (= "pass" (:status (first results)))))))

(deftest extract-test-results-generic-test
  (testing "extract-test-results falls back to generic"
    (let [output "5 passed, 1 failed"
          results (tp/extract-test-results output :stage-name "Test" :step-name "run")]
      (is (= 2 (count results)))
      (is (some #(= "pass" (:status %)) results))
      (is (some #(= "fail" (:status %)) results)))))

(deftest extract-test-results-nil-test
  (testing "extract-test-results returns nil for empty/nil"
    (is (nil? (tp/extract-test-results nil)))
    (is (nil? (tp/extract-test-results "")))
    (is (nil? (tp/extract-test-results "   ")))))

;; ---------------------------------------------------------------------------
;; Malformed-near-valid edge cases
;; ---------------------------------------------------------------------------

(deftest parse-junit-xml-missing-time-attr-test
  (testing "parse-junit-xml: missing time attribute yields nil duration (not 0, not error)"
    (let [xml "<testsuite name=\"S\"><testcase name=\"t\" classname=\"c\"/></testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (nil? (:duration-ms (first results))))
      (is (= "pass" (:status (first results)))))))

(deftest parse-junit-xml-missing-classname-attr-test
  (testing "parse-junit-xml: missing classname falls back to suite name"
    (let [xml "<testsuite name=\"MySuite\"><testcase name=\"t\" time=\"0.01\"/></testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "MySuite" (:test-suite (first results))))
      (is (= "t" (:test-name (first results)))))))

(deftest parse-junit-xml-empty-failure-body-with-message-test
  (testing "parse-junit-xml: empty <failure/> body but message attribute populates error-msg"
    (let [xml "<testsuite name=\"S\"><testcase name=\"t\" classname=\"c\"><failure message=\"expected X got Y\"/></testcase></testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "fail" (:status (first results))))
      (is (= "expected X got Y" (:error-msg (first results)))))))

(deftest parse-tap-output-todo-marker-is-skip-test
  (testing "parse-tap-output: '# TODO' marker yields skip status (per TAP spec), not fail"
    (let [output "TAP version 13\n1..1\nnot ok 1 - foo # TODO not implemented"
          results (tp/parse-tap-output output)]
      (is (= 1 (count results)))
      (is (= "skip" (:status (first results))))
      (is (= "foo" (:test-name (first results)))))))

(deftest parse-tap-output-no-description-test
  (testing "parse-tap-output: 'ok 1' with no description defaults to 'unnamed'"
    (let [output "TAP version 13\n1..1\nok 1"
          results (tp/parse-tap-output output)]
      (is (= 1 (count results)))
      (is (= "unnamed" (:test-name (first results))))
      (is (= "pass" (:status (first results)))))))

(deftest parse-generic-output-ambiguous-phrasing-test
  (testing "parse-generic-output: '1 test passed, 1 test failed, 1 test skipped' extracts pass=1, fail=1"
    (let [result (tp/parse-generic-output "1 test passed, 1 test failed, 1 test skipped")]
      (is (= 1 (:total-pass result)))
      (is (= 1 (:total-fail result))))))

;; ---------------------------------------------------------------------------
;; Fallback-selection precedence (extract-test-results)
;; ---------------------------------------------------------------------------

(deftest extract-test-results-junit-beats-tap-test
  (testing "extract-test-results: JUnit XML wins when TAP-looking text is also present"
    (let [input (str "<testsuite name=\"S\">"
                     "<testcase name=\"junit-t\" classname=\"c\">"
                     "<system-out>TAP version 13\n1..1\nok 1 - tap-test</system-out>"
                     "</testcase></testsuite>")
          results (tp/extract-test-results input)]
      (is (= 1 (count results)))
      (is (= "junit-t" (:test-name (first results))))
      (is (= "c" (:test-suite (first results)))))))

(deftest extract-test-results-tap-beats-generic-test
  (testing "extract-test-results: TAP wins when generic-pattern phrases also appear"
    (let [input "TAP version 13\n1..1\nok 1 - real-tap-test\n5 passed, 2 failed"
          results (tp/extract-test-results input)]
      (is (= 1 (count results)))
      (is (= "real-tap-test" (:test-name (first results))))
      (is (= "pass" (:status (first results)))))))

(deftest extract-test-results-whitespace-only-test
  (testing "extract-test-results: whitespace-only input yields nil (no parser fires)"
    (is (nil? (tp/extract-test-results "   \n\t  ")))
    (is (nil? (tp/extract-test-results "\n\n\n")))
    (is (nil? (tp/extract-test-results "\t")))))

(deftest extract-test-results-malformed-junit-falls-through-test
  (testing "extract-test-results: malformed JUnit-looking XML falls through to generic parser"
    (let [input "<testsuite name=\"unclosed\"><testcase\n3 passed, 1 failed"
          results (tp/extract-test-results input :stage-name "S" :step-name "step")]
      (is (= 2 (count results)))
      (is (some #(= "pass" (:status %)) results))
      (is (some #(= "fail" (:status %)) results))
      (is (every? #(= "S" (:test-suite %)) results)))))

;; ---------------------------------------------------------------------------
;; Semantic oracle — mixed-format extraction with exact counts
;;
;; Pins the FULL precedence contract in ONE scenario: real CI runs often emit
;; JUnit XML that wraps TAP-looking <system-out> AND trails generic phrasing.
;; The parser MUST extract from JUnit and ignore the decoy TAP+generic text.
;; ---------------------------------------------------------------------------

(deftest extract-test-results-mixed-format-oracle-test
  (testing "mixed JUnit+TAP-in-system-out+generic-trailer: JUnit wins, counts come from JUnit only"
    (let [input (str
                 "<testsuite name=\"OracleSuite\" tests=\"4\">"
                 ;; pass with TAP-looking decoy in system-out
                 "<testcase name=\"oracle_pass\" classname=\"oracle\" time=\"0.01\">"
                 "<system-out>TAP version 13\n1..2\nok 1 - decoy-tap-pass\nok 2 - decoy-tap-pass-2</system-out>"
                 "</testcase>"
                 ;; failure
                 "<testcase name=\"oracle_fail\" classname=\"oracle\" time=\"0.02\">"
                 "<failure message=\"expected 1 got 2\">assertion failed</failure>"
                 "</testcase>"
                 ;; skipped
                 "<testcase name=\"oracle_skip\" classname=\"oracle\">"
                 "<skipped/>"
                 "</testcase>"
                 ;; error
                 "<testcase name=\"oracle_error\" classname=\"oracle\">"
                 "<error message=\"NPE\">null pointer</error>"
                 "</testcase>"
                 ;; Generic-phrasing decoys live inside system-out — would trip
                 ;; the generic parser if it ran on the raw string. They MUST be
                 ;; ignored because JUnit takes precedence.
                 "<system-out>=== summary ===\n99 passed, 88 failed\nRan 200 tests in 3.5 seconds\n</system-out>"
                 "</testsuite>")
          results (tp/extract-test-results input :stage-name "Test" :step-name "run")
          by-status (group-by :status results)
          pass-count (count (get by-status "pass" []))
          fail-count (count (get by-status "fail" []))
          skip-count (count (get by-status "skip" []))
          error-count (count (get by-status "error" []))
          names (set (map :test-name results))]
      ;; EXACT counts from JUnit — not 99/88 from generic, not 2 from TAP decoy.
      (is (= 4 (count results)) "total = exactly 4 JUnit testcases")
      (is (= 1 pass-count) "exactly 1 pass from JUnit")
      (is (= 1 fail-count) "exactly 1 fail from JUnit")
      (is (= 1 skip-count) "exactly 1 skip from JUnit")
      (is (= 1 error-count) "exactly 1 error from JUnit")
      ;; total = sum of per-status counts (no straggler statuses).
      (is (= (count results) (+ pass-count fail-count skip-count error-count))
          "total equals sum of per-status counts")
      ;; Per-test names come from JUnit, NOT from the TAP decoy or generic synthesis.
      (is (= #{"oracle_pass" "oracle_fail" "oracle_skip" "oracle_error"} names)
          "test-names sourced from JUnit testcases, not TAP decoy or generic synth")
      (is (not (contains? names "decoy-tap-pass")) "TAP decoy name absent")
      (is (not (contains? names "decoy-tap-pass-2")) "TAP decoy name absent")
      (is (not-any? #(re-find #":passed$|:failed$" (:test-name %)) results)
          "no synthetic generic-parser names (would end in ':passed'/':failed')")
      ;; Per-test suite is from JUnit classname, not the stage-name fallback.
      (is (every? #(= "oracle" (:test-suite %)) results)
          "test-suite from JUnit classname, not stage-name fallback"))))
