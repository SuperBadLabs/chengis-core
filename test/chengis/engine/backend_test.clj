(ns chengis.engine.backend-test
  "Acceptance tests for the ExecutionBackend protocol + LocalShell reference
   impl. Per Brasstacks CC2-EX1a, the receipts are:
     1. LocalShell satisfies the protocol
     2. prepare-workspace creates the dir
     3. execute-step runs the command and returns the standard map
     4. cleanup + cancel are safe no-ops
     5. default-backend returns a LocalShell"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [chengis.engine.backend :as backend]))

(defn- tmp-workspace []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "chengis-backend-test-" (System/currentTimeMillis)))]
    (.delete d)
    (.getAbsolutePath d)))

(deftest local-shell-satisfies-protocol
  (let [b (backend/local-shell-backend)]
    (is (satisfies? backend/ExecutionBackend b))
    (is (= "local-shell" (backend/backend-name b)))))

(deftest default-backend-is-local-shell
  (testing "default-backend convention preserves v0.3 behavior"
    (let [b (backend/default-backend)]
      (is (= "local-shell" (backend/backend-name b))))))

(deftest prepare-workspace-creates-the-dir
  (let [b   (backend/local-shell-backend)
        ws  (tmp-workspace)
        r   (backend/prepare-workspace b {:job-name "demo"
                                          :build-number 1
                                          :workspace-path ws})]
    (is (= :ok (:result r)))
    (is (= ws (:workspace r)))
    (is (.exists (io/file ws)))
    (.delete (io/file ws))))

(deftest prepare-workspace-requires-path
  (let [b (backend/local-shell-backend)
        r (backend/prepare-workspace b {:job-name "demo" :build-number 1})]
    (is (= :failed (:result r)))
    (is (string? (:explain r)))))

(deftest execute-step-runs-the-command
  (let [b   (backend/local-shell-backend)
        ws  (tmp-workspace)
        _   (backend/prepare-workspace b {:job-name "demo"
                                          :build-number 1
                                          :workspace-path ws})
        r   (backend/execute-step b {:command "echo hello-backend"
                                     :dir ws})]
    (is (zero? (:exit-code r)))
    (is (= "hello-backend" (clojure.string/trim (:stdout r))))
    (is (integer? (:duration-ms r)))
    (is (false? (:timed-out? r)))
    (.delete (io/file ws))))

(deftest execute-step-honors-env
  (let [b  (backend/local-shell-backend)
        ws (tmp-workspace)
        _  (backend/prepare-workspace b {:job-name "demo"
                                         :build-number 1
                                         :workspace-path ws})
        r  (backend/execute-step b {:command "echo \"value=${CCH_TEST_VAR}\""
                                    :dir ws
                                    :env {"CCH_TEST_VAR" "brasstacks"}})]
    (is (zero? (:exit-code r)))
    (is (= "value=brasstacks" (clojure.string/trim (:stdout r))))
    (.delete (io/file ws))))

(deftest execute-step-captures-nonzero-exit
  (let [b  (backend/local-shell-backend)
        ws (tmp-workspace)
        _  (backend/prepare-workspace b {:job-name "demo"
                                         :build-number 1
                                         :workspace-path ws})
        r  (backend/execute-step b {:command "exit 3"
                                    :dir ws})]
    (is (= 3 (:exit-code r)))
    (.delete (io/file ws))))

(deftest cleanup-and-cancel-are-safe-noops
  (let [b (backend/local-shell-backend)]
    (is (nil? (backend/cleanup b {:job-name "demo" :build-number 1})))
    (is (nil? (backend/cancel  b {:job-name "demo" :build-number 1})))
    ;; idempotent — second call still nil
    (is (nil? (backend/cleanup b {:job-name "demo" :build-number 1})))))
