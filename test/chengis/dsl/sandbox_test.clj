(ns chengis.dsl.sandbox-test
  "Tests for SCI-sandboxed pipeline-file evaluation (DSL loader hardening):
   the DSL vocabulary works, dangerous primitives are blocked, runaway files
   time out, and the :trusted opt-out still uses load-file."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]
            [chengis.dsl.sandbox :as sandbox]
            [clojure.java.io :as io]))

(defn clear [f] (dsl/clear-registry!) (f) (dsl/clear-registry!))
(use-fixtures :each clear)

(defn- write-pipeline [content]
  (let [dir (io/file (str "/tmp/chengis-dsl-sandbox-" (System/nanoTime)))
        f   (io/file dir "pipeline.clj")]
    (.mkdirs dir)
    (spit f content)
    (.getAbsolutePath f)))

;; --- happy paths -----------------------------------------------------------

(deftest bare-defpipeline-loads-test
  (testing "a bare (defpipeline ...) file (no ns form) evaluates + registers"
    (let [path (write-pipeline
                "(defpipeline demo {:description \"d\"}
                   (stage \"Build\" (step \"compile\" (sh \"make\")))
                   (stage \"Test\" (parallel (step \"unit\" (sh \"make test\")))))")
          p    (dsl/load-pipeline-file path)]
      (is (= "demo" (:pipeline-name p)))
      (is (= 2 (count (:stages p))))
      (is (= "d" (:description p)))
      (is (some? (dsl/get-pipeline "demo")) "registered in the registry"))))

(deftest ns-require-defpipeline-loads-test
  (testing "a file with its own ns form (:refer :all) also resolves"
    (let [path (write-pipeline
                "(ns my.pipeline (:require [chengis.dsl.core :refer :all]))
                 (defpipeline withns
                   (stage \"Build\" (step \"compile\" (sh \"make\"))))")
          p    (dsl/load-pipeline-file path)]
      (is (= "withns" (:pipeline-name p)))
      (is (= 1 (count (:stages p)))))))

(deftest pure-clojure-data-construction-allowed-test
  (testing "safe clojure.core (let/str) inside a pipeline file is allowed"
    (let [path (write-pipeline
                "(defpipeline gen
                   (let [tool \"mvn\"]
                     (stage \"Build\" (step (str tool \"-compile\") (sh (str tool \" compile\"))))))")
          p    (dsl/load-pipeline-file path)]
      (is (= "gen" (:pipeline-name p)))
      (is (= 1 (count (:stages p))))
      (is (= "mvn-compile" (:step-name (first (:steps (first (:stages p))))))
          "let/str evaluated inside the sandbox"))))

;; --- adversarial: dangerous primitives are blocked -------------------------

(deftest interop-blocked-test
  (testing "Java interop is unavailable (no :classes) — System/exit fails"
    (let [path (write-pipeline "(System/exit 1)")]
      (is (thrown? Exception (dsl/load-pipeline-file path))))))

(deftest slurp-blocked-test
  (testing "filesystem read via slurp is denied"
    (let [path (write-pipeline "(defpipeline x (stage \"S\" (step \"a\" (sh (slurp \"/etc/passwd\")))))")]
      (is (thrown? Exception (dsl/load-pipeline-file path))))))

(deftest eval-blocked-test
  (testing "eval is denied"
    (let [path (write-pipeline "(eval '(+ 1 2))")]
      (is (thrown? Exception (dsl/load-pipeline-file path))))))

(deftest load-file-blocked-test
  (testing "nested load-file is denied (no recursive escape)"
    (let [path (write-pipeline "(load-file \"/tmp/whatever.clj\")")]
      (is (thrown? Exception (dsl/load-pipeline-file path))))))

(deftest runaway-times-out-test
  (testing "a long-running file aborts with :dsl/timeout under a tiny budget"
    (let [path (write-pipeline "(reduce + (range 100000000))")
          ex   (try (sandbox/eval-pipeline-file path {} :timeout-ms 1)
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :dsl/timeout (:type ex))))))

;; --- trusted opt-out -------------------------------------------------------

(deftest trusted-mode-uses-load-file-test
  (testing ":eval-mode :trusted still loads (legacy full-power path)"
    (let [path (write-pipeline
                "(ns my.trusted (:require [chengis.dsl.core :refer :all]))
                 (defpipeline trusted-demo (stage \"Build\" (step \"c\" (sh \"make\"))))")
          p    (dsl/load-pipeline-file path {:eval-mode :trusted})]
      (is (= "trusted-demo" (:pipeline-name p))))))
