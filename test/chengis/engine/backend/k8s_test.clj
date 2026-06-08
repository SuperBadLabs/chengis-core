(ns chengis.engine.backend.k8s-test
  "Acceptance tests for the Kubernetes execution backend.

   Strategy
   --------
   Two test surfaces:

   1. Always-on tests — protocol satisfaction, pod-spec construction,
      kubeconfig resolution, config validation, error paths when no
      cluster is reachable. These run in every CI run.

   2. Cluster-required tests — real `kubectl apply` + pod execution +
      logs + cleanup. Gated on `cluster-reachable?` so they run on the
      dogfood host's `kind` cluster + any CI with a cluster but skip
      cleanly elsewhere. Tagged ^:k8s.

   The skip path emits a single info log line — operators see exactly
   which tests didn't run."
  (:require [chengis.engine.backend :as backend]
            [chengis.engine.backend.k8s :as k8s]
            [clojure.data.json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [taoensso.timbre :as log]))

(def ^:private test-image
  "Tiny ubiquitous busybox — same one the docker backend tests use."
  "busybox:1.36")

(defn- tmp-workspace []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "chengis-k8s-test-" (System/nanoTime)))]
    (.delete d)
    (.getAbsolutePath d)))

(defn- cluster-or-skip
  "Run `body-fn` only if a k8s cluster is reachable; otherwise log skip
   and return :skipped. Used at the top of every ^:k8s test."
  [body-fn]
  (let [kc (k8s/resolve-kubeconfig-path {})]
    (if (and (k8s/kubectl-available? kc) (k8s/cluster-reachable? kc))
      (body-fn)
      (do (log/info "[skip] k8s cluster not reachable; backend acceptance test skipped")
          :skipped))))

;; ---------------------------------------------------------------------------
;; Kubeconfig resolution
;; ---------------------------------------------------------------------------

(deftest resolve-kubeconfig-explicit-wins
  (is (= "/explicit/path"
         (k8s/resolve-kubeconfig-path
          {:kubeconfig-path "/explicit/path"}
          (constantly "/env/path")))))

(deftest resolve-kubeconfig-falls-back-to-env
  (is (= "/env/path"
         (k8s/resolve-kubeconfig-path {} (constantly "/env/path")))))

(deftest resolve-kubeconfig-falls-back-to-home
  (let [home (System/getProperty "user.home")]
    (is (= (str home "/.kube/config")
           (k8s/resolve-kubeconfig-path {} (constantly nil))))))

(deftest resolve-kubeconfig-blank-env-falls-through
  ;; KUBECONFIG="" should be treated as unset (POSIX-ish convention).
  (let [home (System/getProperty "user.home")]
    (is (= (str home "/.kube/config")
           (k8s/resolve-kubeconfig-path {} (constantly ""))))))

;; ---------------------------------------------------------------------------
;; Construction + protocol
;; ---------------------------------------------------------------------------

(deftest construction-requires-image
  (is (thrown? clojure.lang.ExceptionInfo
               (k8s/k8s-backend {}))))

(deftest construction-defaults-mode-and-namespace
  (let [b (k8s/k8s-backend {:image "alpine:3.20"})]
    (is (= :per-step (get-in b [:config :mode])))
    (is (= "default"  (get-in b [:config :namespace])))))

(deftest satisfies-protocol
  (let [b (k8s/k8s-backend {:image "alpine:3.20"})]
    (is (satisfies? backend/ExecutionBackend b))
    (is (str/starts-with? (backend/backend-name b) "kubernetes:"))))

(deftest prepare-workspace-rejects-missing-path
  (let [b (k8s/k8s-backend {:image "alpine:3.20"})
        r (backend/prepare-workspace b {:job-name "j" :build-number 1})]
    (is (= :failed (:result r)))
    (is (string? (:explain r)))))

(deftest execute-step-without-prepare-returns-explain
  (let [b (k8s/k8s-backend {:image "alpine:3.20"})
        r (backend/execute-step b {:job-name "j" :build-number 1
                                   :command "echo hi"})]
    (is (= 125 (:exit-code r)))
    (is (str/includes? (:stderr r) "workspace"))))

(deftest cleanup-and-cancel-are-safe-noops-when-nothing-tracked
  (let [b (k8s/k8s-backend {:image "alpine:3.20"})]
    (is (nil? (backend/cleanup b {:job-name "j" :build-number 1})))
    ;; cancel does best-effort `kubectl delete pod -l ...` which fails
    ;; without a cluster — should not throw.
    (is (nil? (backend/cancel  b {:job-name "j" :build-number 1})))))

;; ---------------------------------------------------------------------------
;; Pod-spec construction (pure)
;; ---------------------------------------------------------------------------

(deftest pod-spec-basic-shape
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :namespace "default"
                               :host-user? false}
                              {:command "echo hello"}
                              "chengis-j-1-step-abcdef"
                              {})
        c (-> m :spec :containers first)]
    (is (= "v1" (:apiVersion m)))
    (is (= "Pod" (:kind m)))
    (is (= "chengis-j-1-step-abcdef" (-> m :metadata :name)))
    (is (= "default" (-> m :metadata :namespace)))
    (is (= "busybox:1.36" (:image c)))
    (is (= ["sh" "-c" "echo hello"] (:command c)))
    (is (= "Never" (-> m :spec :restartPolicy)))
    (is (= [{:name "workspace" :emptyDir {}}] (-> m :spec :volumes)))))

(deftest pod-spec-resource-limits-mapped
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :resource-limits {:memory-mb 512 :cpus 0.5}
                               :host-user? false}
                              {:command "echo hi"}
                              "p-1"
                              {})
        res (-> m :spec :containers first :resources)]
    (is (= "512Mi" (-> res :requests :memory)))
    (is (= "512Mi" (-> res :limits :memory)))
    (is (= "0.5" (-> res :requests :cpu)))
    (is (= "0.5" (-> res :limits :cpu)))))

(deftest pod-spec-no-resources-when-empty
  (let [m (k8s/build-pod-spec {:image "busybox:1.36" :host-user? false}
                              {:command "echo hi"}
                              "p-1" {})]
    (is (nil? (-> m :spec :containers first :resources))
        "empty :resource-limits must not emit `resources: {}` block")))

(deftest pod-spec-env-stable-ordered
  (let [m (k8s/build-pod-spec {:image "busybox:1.36" :host-user? false}
                              {:command "echo $A"}
                              "p-1"
                              {"B" "2" "A" "1"})
        env (-> m :spec :containers first :env)]
    (is (= [{:name "A" :value "1"} {:name "B" :value "2"}] env)
        "env entries must be sorted by name for reproducible pod specs")))

(deftest pod-spec-numeric-user-becomes-run-as-user
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :user "1000"
                               :host-user? false}
                              {:command "echo hi"}
                              "p-1" {})]
    (is (= {:runAsUser 1000} (-> m :spec :securityContext)))))

(deftest pod-spec-non-numeric-user-dropped-with-warning
  ;; `:user "root"` is a docker-ism — k8s wants a numeric uid. We log
  ;; a warning and drop the field rather than emitting an invalid
  ;; securityContext that the apiserver will reject opaquely.
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :user "root"
                               :host-user? false}
                              {:command "echo hi"}
                              "p-1" {})]
    (is (nil? (-> m :spec :securityContext))
        "non-numeric :user must drop securityContext (k8s needs a uid)")))

(deftest pod-spec-labels-stamped-for-cancel
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :host-user? false
                               :job-name "myjob"
                               :build-number 7}
                              {:command "echo hi"}
                              "p-1" {})
        labels (-> m :metadata :labels)]
    ;; STRING keys — `clojure.data.json` drops the namespace of
    ;; namespaced keyword keys at serialize time, which would
    ;; silently break `cancel`'s selector. We lock the serialized
    ;; shape in the IR so the assertion catches any regression
    ;; back to keyword keys. PR #14 Copilot review.
    (is (= "chengis-step" (get labels "app")))
    (is (= "myjob" (get labels "chengis.io/job-name")))
    (is (= "7" (get labels "chengis.io/build-number")))
    (testing "labels survive a json round-trip with the namespace intact"
      ;; This is the contract that the bug being fixed broke. Lock it.
      (let [round-trip (-> m
                           clojure.data.json/write-str
                           (clojure.data.json/read-str)
                           (get "metadata")
                           (get "labels"))]
        (is (= "myjob" (get round-trip "chengis.io/job-name"))
            "namespaced label key must survive JSON serialization")))))

;; ---------------------------------------------------------------------------
;; Pod name sanitization
;; ---------------------------------------------------------------------------

(deftest pod-name-lowercased-and-sanitized
  (let [n (k8s/pod-name {:job-name "Apache_Zookeeper" :build-number 12} "abcdef")]
    (is (re-matches #"[a-z0-9-]+" n))
    (is (str/starts-with? n "chengis-apache-zookeeper-12-step-abcdef"))))

(deftest pod-name-truncated-to-63
  (let [long-job (apply str (repeat 80 "x"))
        n (k8s/pod-name {:job-name long-job :build-number 1} "abcdef")]
    (is (<= (count n) 63))
    (is (not (str/ends-with? n "-")) "no trailing hyphen after truncation")))

;; ---------------------------------------------------------------------------
;; Cluster-required tests
;; ---------------------------------------------------------------------------

(deftest ^:k8s prepare-and-execute-roundtrip
  (cluster-or-skip
   (fn []
     (let [b   (k8s/k8s-backend {:image test-image :host-user? false})
           ws  (tmp-workspace)
           pr  (backend/prepare-workspace b {:job-name "rt"
                                             :build-number 1
                                             :workspace-path ws})]
       (try
         (is (= :ok (:result pr)))
         (is (= :per-step (get-in pr [:backend-state :mode])))
         (testing "step exec sees stdout"
           (let [r (backend/execute-step
                    b {:job-name "rt" :build-number 1
                       :backend-state (:backend-state pr)
                       :command "echo running-in-pod"})]
             (is (zero? (:exit-code r)))
             (is (str/includes? (:stdout r) "running-in-pod"))))
         (testing "env passed through to step"
           (let [r (backend/execute-step
                    b {:job-name "rt" :build-number 1
                       :backend-state (:backend-state pr)
                       :env {"BRASS" "tacks"}
                       :command "echo brass=$BRASS"})]
             (is (str/includes? (:stdout r) "brass=tacks"))))
         (testing "non-zero exit propagated"
           (let [r (backend/execute-step
                    b {:job-name "rt" :build-number 1
                       :backend-state (:backend-state pr)
                       :command "exit 7"})]
             (is (= 7 (:exit-code r)))))
         (finally
           (backend/cleanup b {:job-name "rt" :build-number 1})
           (.delete (io/file ws))))))))

(deftest ^:k8s mask-values-redacted-in-returned-output
  (cluster-or-skip
   (fn []
     (let [b  (k8s/k8s-backend {:image test-image :host-user? false})
           ws (tmp-workspace)
           pr (backend/prepare-workspace b {:job-name "mk"
                                            :build-number 1
                                            :workspace-path ws})]
       (try
         (let [r (backend/execute-step
                  b {:job-name "mk" :build-number 1
                     :backend-state (:backend-state pr)
                     :command "echo my-secret-token-shhhh"
                     :mask-values ["my-secret-token-shhhh"]})]
           (is (zero? (:exit-code r)))
           (is (not (str/includes? (:stdout r) "my-secret-token-shhhh"))
               "secret value must not appear in returned stdout"))
         (finally
           (backend/cleanup b {:job-name "mk" :build-number 1})
           (.delete (io/file ws))))))))

(deftest ^:k8s resource-limits-applied-on-pod
  (cluster-or-skip
   (fn []
     ;; We can't easily assert the pod's cgroup limit from inside busybox,
     ;; but we CAN assert k8s accepted the pod spec with limits — i.e.
     ;; the pod ran to Succeeded. That's enough to prove the resource
     ;; map serialized correctly.
     (let [b  (k8s/k8s-backend {:image test-image
                                :host-user? false
                                :resource-limits {:memory-mb 64 :cpus 0.1}})
           ws (tmp-workspace)
           pr (backend/prepare-workspace b {:job-name "rl"
                                            :build-number 1
                                            :workspace-path ws})]
       (try
         (let [r (backend/execute-step
                  b {:job-name "rl" :build-number 1
                     :backend-state (:backend-state pr)
                     :command "echo limits-ok"})]
           (is (zero? (:exit-code r)))
           (is (str/includes? (:stdout r) "limits-ok")))
         (finally
           (backend/cleanup b {:job-name "rl" :build-number 1})
           (.delete (io/file ws))))))))
