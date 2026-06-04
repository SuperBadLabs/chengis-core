(ns chengis.engine.steps-test
  "Acceptance tests for the plugin-step framework (CC2-EX5).

   Coverage:
     - Protocol + registry semantics
     - Honest unsupported-step fallback that flows into the EX2 classifier
       as :unsupported-construct (no silent successes)
     - Each built-in primitive (:artifacts/archive, :tests/junit,
       :problems/record) executes against a temp workspace and reports
       the right :effects + return shape

   The receipt the board demands — anvil v0.4's archiveArtifacts writing a
   real artifact on apache-zookeeper — happens in the anvil mapping PR.
   This file proves the engine-side contract."
  (:require [chengis.engine.result :as result]
            [chengis.engine.steps :as steps]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [t]
    (steps/clear-registry!)
    (steps/register-defaults!)
    (t)
    (steps/clear-registry!)))

(defn- ^java.io.File tmp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- write-file! [^java.io.File f content]
  (.mkdirs (.getParentFile f))
  (spit f content))

(defn- rm-r! [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-r! c)))
    (.delete f)))

;; ---------------------------------------------------------------------------
;; Registry semantics
;; ---------------------------------------------------------------------------

(deftest defaults-are-registered
  (let [r (steps/registered)]
    (is (contains? r :artifacts/archive))
    (is (contains? r :tests/junit))
    (is (contains? r :problems/record))))

(deftest unknown-step-returns-unsupported-not-silent-success
  (let [r (steps/dispatch :no-such-step {} {})]
    (is (= 125 (:exit-code r)))
    (is (true? (:unsupported? r)))
    (is (str/includes? (:explain r) "no-such-step"))))

(deftest unsupported-flows-into-observation-as-unsupported-construct
  (testing "the EX5 fallback shape is consumed by EX2 as :unsupported"
    (let [r (steps/dispatch :slack-notify {} {})
          obs (-> (result/default-observation)
                  (steps/record-into-observation r))
          classified (result/classify obs)]
      (is (= :unsupported (:result classified)))
      (is (= :unsupported-construct (:rule classified))))))

(deftest register-rejects-non-keyword-id
  (let [bogus (reify steps/Step
                (step-id [_] "string-id")
                (describe [_] "bad")
                (execute [_ _ _] {:exit-code 0}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (steps/register! bogus)))))

;; ---------------------------------------------------------------------------
;; :artifacts/archive
;; ---------------------------------------------------------------------------

(deftest archive-copies-matching-files
  (let [ws  (tmp-dir "ws")
        art (tmp-dir "art")]
    (try
      (write-file! (io/file ws "target/app.jar")     "JARBYTES")
      (write-file! (io/file ws "target/other.jar")   "JAR2")
      (write-file! (io/file ws "target/notes.txt")   "skip me")
      (write-file! (io/file ws "deeply/nested/foo.jar") "deep")
      (let [r (steps/dispatch :artifacts/archive
                              {:workspace (.getAbsolutePath ws)
                               :artifact-dir (.getAbsolutePath art)}
                              {:patterns ["target/*.jar"]})]
        (is (zero? (:exit-code r)))
        (is (= 2 (:archived-count r)))
        (is (= [:artifact-archived] (:effects r)))
        (is (.exists (io/file art "target/app.jar")))
        (is (.exists (io/file art "target/other.jar")))
        (is (not (.exists (io/file art "target/notes.txt")))))
      (finally
        (rm-r! ws) (rm-r! art)))))

(deftest archive-doublestar-glob
  (let [ws  (tmp-dir "ws")
        art (tmp-dir "art")]
    (try
      (write-file! (io/file ws "a/b/c/x.tar.gz") "1")
      (write-file! (io/file ws "x.tar.gz") "2")
      (let [r (steps/dispatch :artifacts/archive
                              {:workspace (.getAbsolutePath ws)
                               :artifact-dir (.getAbsolutePath art)}
                              {:patterns ["**/*.tar.gz" "*.tar.gz"]})]
        (is (zero? (:exit-code r)))
        (is (>= (:archived-count r) 2)))
      (finally
        (rm-r! ws) (rm-r! art)))))

(deftest archive-no-match-fails-honestly
  (let [ws  (tmp-dir "ws")
        art (tmp-dir "art")]
    (try
      (write-file! (io/file ws "README.md") "hi")
      (let [r (steps/dispatch :artifacts/archive
                              {:workspace (.getAbsolutePath ws)
                               :artifact-dir (.getAbsolutePath art)}
                              {:patterns ["*.jar"]})]
        (is (= 1 (:exit-code r)))
        (is (empty? (:effects r)))
        (is (str/includes? (:explain r) "no files matched")))
      (finally
        (rm-r! ws) (rm-r! art)))))

;; ---------------------------------------------------------------------------
;; :tests/junit
;; ---------------------------------------------------------------------------

(def ^:private junit-pass-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <testsuite name=\"smoke\" tests=\"2\" failures=\"0\" errors=\"0\">
     <testcase name=\"t1\" classname=\"a\" time=\"0.01\"/>
     <testcase name=\"t2\" classname=\"a\" time=\"0.02\"/>
   </testsuite>")

(def ^:private junit-fail-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <testsuite name=\"frizz\" tests=\"3\" failures=\"1\" errors=\"1\">
     <testcase name=\"ok\" classname=\"b\" time=\"0.01\"/>
     <testcase name=\"fail1\" classname=\"b\" time=\"0.02\">
       <failure message=\"expected x got y\">stack here</failure>
     </testcase>
     <testcase name=\"err1\" classname=\"b\" time=\"0.03\">
       <error message=\"NPE\">stack here</error>
     </testcase>
   </testsuite>")

(deftest junit-finds-and-summarizes
  (let [ws (tmp-dir "ws")]
    (try
      (write-file! (io/file ws "module-a/surefire-reports/TEST-A.xml") junit-pass-xml)
      (write-file! (io/file ws "module-b/surefire-reports/TEST-B.xml") junit-fail-xml)
      (let [r (steps/dispatch :tests/junit
                              {:workspace (.getAbsolutePath ws)}
                              {})]
        (is (zero? (:exit-code r)))
        (is (= [:tests-recorded] (:effects r)))
        (is (= {:tests 5 :failures 1 :errors 1 :skipped 0}
               (:test-summary r))))
      (finally (rm-r! ws)))))

(deftest junit-empty-workspace-fails
  (let [ws (tmp-dir "ws")]
    (try
      (let [r (steps/dispatch :tests/junit
                              {:workspace (.getAbsolutePath ws)}
                              {})]
        (is (= 1 (:exit-code r)))
        (is (empty? (:effects r)))
        (is (= 0 (get-in r [:test-summary :tests]))))
      (finally (rm-r! ws)))))

(deftest junit-summary-flows-into-observation-as-unstable
  (let [ws (tmp-dir "ws")]
    (try
      (write-file! (io/file ws "surefire-reports/TEST.xml") junit-fail-xml)
      (let [r (steps/dispatch :tests/junit
                              {:workspace (.getAbsolutePath ws)} {})
            obs (-> (result/default-observation)
                    (result/record-shell-step {:exit-code 0})
                    (steps/record-into-observation r))
            classified (result/classify obs)]
        (is (= :unstable (:result classified))))
      (finally (rm-r! ws)))))

;; ---------------------------------------------------------------------------
;; :problems/record
;; ---------------------------------------------------------------------------

(deftest problems-record-persists-edn
  (let [art (tmp-dir "art")
        problems [{:file "Foo.java" :line 12 :severity :warning :message "unused import"}
                  {:file "Bar.java" :line 9  :severity :error   :message "missing return"}]]
    (try
      (let [r (steps/dispatch :problems/record
                              {:artifact-dir (.getAbsolutePath art)}
                              {:problems problems})]
        (is (zero? (:exit-code r)))
        (is (= 2 (:problems-recorded-count r)))
        (is (= [:problems-recorded] (:effects r)))
        (let [persisted (read-string (slurp (io/file art "problems.edn")))]
          (is (= problems persisted))))
      (finally (rm-r! art)))))

(deftest problems-record-with-empty-list-still-succeeds-no-effect
  (let [art (tmp-dir "art")]
    (try
      (let [r (steps/dispatch :problems/record
                              {:artifact-dir (.getAbsolutePath art)}
                              {:problems []})]
        (is (zero? (:exit-code r)))
        (is (empty? (:effects r)))
        (is (.exists (io/file art "problems.edn"))))
      (finally (rm-r! art)))))

;; ---------------------------------------------------------------------------
;; End-to-end shape: archive + junit + problems compose into a green build
;; ---------------------------------------------------------------------------

(deftest compose-three-primitives-into-an-honest-success
  (let [ws  (tmp-dir "ws")
        art (tmp-dir "art")]
    (try
      (write-file! (io/file ws "target/app.jar") "BYTES")
      (write-file! (io/file ws "surefire-reports/TEST.xml") junit-pass-xml)
      (let [ctx {:workspace (.getAbsolutePath ws)
                 :artifact-dir (.getAbsolutePath art)}
            r1 (steps/dispatch :artifacts/archive ctx {:patterns ["target/*.jar"]})
            r2 (steps/dispatch :tests/junit ctx {})
            r3 (steps/dispatch :problems/record ctx {:problems []})
            obs (-> (result/default-observation)
                    (result/record-shell-step {:exit-code 0})
                    (steps/record-into-observation r1)
                    (steps/record-into-observation r2)
                    (steps/record-into-observation r3))
            classified (result/classify obs)]
        (is (= :success (:result classified)))
        (is (str/includes? (:explain classified) "effect")))
      (finally (rm-r! ws) (rm-r! art)))))
