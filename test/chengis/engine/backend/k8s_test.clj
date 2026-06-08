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
;; Label value sanitization (0.4.2#2 regression)
;; ---------------------------------------------------------------------------

(deftest sanitize-label-value-strips-disallowed-chars
  ;; k8s label values must match [A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?
  ;; Slash, colon, comma are the common offenders in anvil job-names.
  (is (= "foo-bar"     (k8s/sanitize-label-value "foo/bar")))
  (is (= "foo-bar-tag" (k8s/sanitize-label-value "foo/bar:tag")))
  (is (= "foo-bar"     (k8s/sanitize-label-value "foo,bar"))
      "comma in job-name must collapse — otherwise it splits the cancel selector"))

(deftest sanitize-label-value-truncates-to-63
  (let [long-name (apply str (repeat 80 "x"))
        out (k8s/sanitize-label-value long-name)]
    (is (<= (count out) 63))
    (is (every? #(Character/isLetterOrDigit ^char %) [(first out) (last out)])
        "first + last char must be alphanumeric after truncation")))

(deftest sanitize-label-value-blank-input
  (is (= "" (k8s/sanitize-label-value nil)))
  (is (= "" (k8s/sanitize-label-value "")))
  (is (= "" (k8s/sanitize-label-value "   "))))

(deftest pod-spec-label-values-sanitized
  ;; Bug 0.4.2#2: raw job-name reached labels unsanitized. k8s would
  ;; reject `kubectl apply` with an opaque error on names like
  ;; "org/repo" or "feature/branch:tag".
  (let [m (k8s/build-pod-spec {:image "busybox:1.36"
                               :host-user? false
                               :job-name "org/repo"
                               :build-number 7}
                              {:command "echo hi"}
                              "p-1" {})
        labels (-> m :metadata :labels)
        job-label (get labels "chengis.io/job-name")]
    (is (= "org-repo" job-label)
        "slash in job-name must be sanitized to a dash before reaching the label")
    (is (re-matches #"[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?" job-label)
        "label value must match k8s label-value regex")))

(deftest cancel-selector-is-single-clause-on-comma-job-name
  ;; Bug 0.4.2#2: cancel built its label selector from the raw
  ;; `job-name`. A value like "foo,bar" would form a 2-clause
  ;; selector (`job-name=foo,bar=…`) — wide pod-delete blast radius.
  ;; We can't run kubectl, but we can mock `run-kubectl` and capture
  ;; the args, asserting the selector contains exactly one comma
  ;; (separating the two label clauses).
  (let [captured-args (atom nil)
        b (k8s/k8s-backend {:image "busybox:1.36"})]
    (with-redefs [k8s/run-kubectl (fn [_kc args & _opts]
                                    (reset! captured-args args)
                                    {:exit 0 :out "" :err ""})]
      (backend/cancel b {:job-name "foo,bar" :build-number 7}))
    (is (some? @captured-args) "cancel must invoke run-kubectl")
    (let [selector (->> @captured-args
                        (drop-while #(not= "-l" %))
                        second)]
      (is (string? selector))
      ;; Exactly 1 comma — the one between the two label clauses.
      ;; A 2-clause-from-job-name bug would yield 2+ commas.
      (is (= 1 (count (filter #{\,} selector)))
          (str "selector must have exactly one comma (the inter-clause separator); "
               "got `" selector "`. A second comma indicates the raw job-name's "
               "comma broke the selector into extra clauses."))
      (is (str/includes? selector "chengis.io/job-name=foo-bar")
          "sanitized job-name must appear in the selector"))))

;; ---------------------------------------------------------------------------
;; wait-for-pod-phase cancel race (0.4.2#1 regression)
;; ---------------------------------------------------------------------------

(deftest wait-for-pod-phase-returns-deleted-on-notfound
  ;; Bug 0.4.2#1: when an external cancel deleted the pod mid-build,
  ;; kubectl started returning NotFound; the loop polled until the
  ;; full 300s timeout because nothing distinguished
  ;; "deleted out from under us" from "still pending".
  ;; Mock run-kubectl to return the NotFound shape and assert the
  ;; loop returns :deleted promptly.
  (let [wait-fn @#'k8s/wait-for-pod-phase
        call-count (atom 0)
        start (System/currentTimeMillis)]
    (with-redefs [k8s/run-kubectl (fn [_kc _args & _opts]
                                    (swap! call-count inc)
                                    {:exit 1
                                     :out ""
                                     :err "Error from server (NotFound): pods \"x\" not found"})]
      ;; Use a generous timeout (5s) — if the fix works, we return
      ;; in <500ms; if it regresses, this would hang for 5s before
      ;; failing on the assertion.
      (let [phase (wait-fn "/dev/null/kubeconfig" "default" "pod-x" 5000)
            elapsed (- (System/currentTimeMillis) start)]
        (is (= :deleted phase)
            "NotFound from kubectl must short-circuit to :deleted, not poll to timeout")
        (is (< elapsed 2000)
            (str "must return promptly (got " elapsed "ms); regression to the "
                 "pre-fix timeout-only behavior."))
        (is (= 1 @call-count)
            "should only need one kubectl call to detect NotFound")))))

(deftest wait-for-pod-phase-still-honors-terminal-phases
  ;; Sanity: the new :deleted branch must not break the Succeeded/Failed
  ;; happy path.
  (let [wait-fn @#'k8s/wait-for-pod-phase]
    (with-redefs [k8s/run-kubectl (fn [_kc _args & _opts]
                                    {:exit 0 :out "Succeeded" :err ""})]
      (is (= "Succeeded" (wait-fn "/x" "default" "pod-x" 5000))))
    (with-redefs [k8s/run-kubectl (fn [_kc _args & _opts]
                                    {:exit 0 :out "Failed" :err ""})]
      (is (= "Failed" (wait-fn "/x" "default" "pod-x" 5000))))))

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
