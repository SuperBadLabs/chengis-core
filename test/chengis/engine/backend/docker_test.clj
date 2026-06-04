(ns chengis.engine.backend.docker-test
  "Acceptance tests for the Docker execution backend (CC2-EX1b).

   Strategy
   --------
   Two test surfaces:

   1. Always-on tests — protocol satisfaction, argument building, config
      validation, error paths when docker is NOT available. These run in
      every CI run including GitHub-Actions ubuntu runners.

   2. Docker-required tests — real `docker pull` + `docker run` + step
      execution + cancel. Gated on `docker-available?` so they run on
      the dogfood host + any CI with docker but skip cleanly elsewhere.
      Tagged ^:docker so they can be filtered with `lein test :docker`.

   The skip path emits a single info log line so a green run never silently
   omits a tranche of receipts — operators see exactly which tests didn't
   run."
  (:require [chengis.engine.backend :as backend]
            [chengis.engine.backend.docker :as docker]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [taoensso.timbre :as log]))

(def ^:private test-image
  "Tiny, ubiquitous, no auth. Pulls in <1s on warm cache, ~5s cold."
  "busybox:1.36")

(defn- tmp-workspace []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "chengis-docker-test-" (System/nanoTime)))]
    (.delete d)
    (.getAbsolutePath d)))

(defn- docker-or-skip
  "Run `body-fn` only if docker is reachable; otherwise emit a single
   skip-log and return :skipped. Used at the top of every ^:docker test."
  [body-fn]
  (if (docker/docker-available?)
    (body-fn)
    (do (log/info "[skip] docker not available; chengis-core docker backend acceptance test skipped")
        :skipped)))

;; ---------------------------------------------------------------------------
;; Always-on tests (no docker daemon required)
;; ---------------------------------------------------------------------------

(deftest construction-requires-image
  (is (thrown? clojure.lang.ExceptionInfo
               (docker/docker-backend {}))))

(deftest construction-defaults-mode-to-per-build
  (let [b (docker/docker-backend {:image "alpine:3.20"})]
    (is (= :per-build (get-in b [:config :mode])))))

(deftest satisfies-protocol
  (let [b (docker/docker-backend {:image "alpine:3.20"})]
    (is (satisfies? backend/ExecutionBackend b))
    (is (str/starts-with? (backend/backend-name b) "docker:"))))

(deftest prepare-workspace-rejects-missing-path
  (let [b (docker/docker-backend {:image "alpine:3.20"})
        r (backend/prepare-workspace b {:job-name "j" :build-number 1})]
    (is (= :failed (:result r)))
    (is (string? (:explain r)))))

(deftest execute-step-without-prepare-returns-explain
  ;; In :per-build mode, calling execute-step without prepare-workspace
  ;; first must NOT silently fall through — must return an exit-code with
  ;; a readable explain. Industrial-strength rule: no silent successes.
  (let [b (docker/docker-backend {:image "alpine:3.20"})
        r (backend/execute-step b {:job-name "j" :build-number 1
                                   :command "echo hi"})]
    (is (= 125 (:exit-code r)))
    (is (str/includes? (:stderr r) "prepare-workspace"))))

(deftest cleanup-and-cancel-are-safe-noops-when-nothing-tracked
  (let [b (docker/docker-backend {:image "alpine:3.20"})]
    (is (nil? (backend/cleanup b {:job-name "j" :build-number 1})))
    (is (nil? (backend/cancel  b {:job-name "j" :build-number 1})))
    ;; idempotent
    (is (nil? (backend/cleanup b {:job-name "j" :build-number 1})))))

;; ---------------------------------------------------------------------------
;; Docker-required tests
;; ---------------------------------------------------------------------------

(deftest ^:docker prepare-and-execute-roundtrip
  (docker-or-skip
   (fn []
     (let [b   (docker/docker-backend {:image test-image})
           ws  (tmp-workspace)
           pr  (backend/prepare-workspace b {:job-name "rt"
                                             :build-number 1
                                             :workspace-path ws})]
       (try
         (is (= :ok (:result pr)))
         (is (string? (:container-id (:backend-state pr))))
         (testing "step exec sees the workspace bind-mounted"
           (let [r (backend/execute-step
                    b {:job-name "rt" :build-number 1
                       :backend-state (:backend-state pr)
                       :command (str "echo running-in-container && touch "
                                     ws "/marker && ls -1 " ws)})]
             (is (zero? (:exit-code r)))
             (is (str/includes? (:stdout r) "running-in-container"))
             (is (str/includes? (:stdout r) "marker"))
             (is (.exists (io/file ws "marker")))))
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
           (.delete (io/file ws "marker"))
           (.delete (io/file ws))))))))

(deftest ^:docker per-step-mode-runs-disposable-containers
  (docker-or-skip
   (fn []
     (let [b   (docker/docker-backend {:image test-image :mode :per-step})
           ws  (tmp-workspace)
           pr  (backend/prepare-workspace b {:job-name "ps"
                                             :build-number 1
                                             :workspace-path ws})]
       (try
         (is (= :ok (:result pr)))
         (is (= :per-step (get-in pr [:backend-state :mode])))
         (let [r (backend/execute-step
                  b {:job-name "ps" :build-number 1
                     :backend-state (:backend-state pr)
                     :command "echo per-step-ok"})]
           (is (zero? (:exit-code r)))
           (is (str/includes? (:stdout r) "per-step-ok")))
         (finally
           (backend/cleanup b {:job-name "ps" :build-number 1})
           (.delete (io/file ws))))))))

(deftest ^:docker cleanup-removes-the-container
  (docker-or-skip
   (fn []
     (let [b   (docker/docker-backend {:image test-image})
           ws  (tmp-workspace)
           pr  (backend/prepare-workspace b {:job-name "cl"
                                             :build-number 1
                                             :workspace-path ws})
           cid (get-in pr [:backend-state :container-id])]
       (is (string? cid))
       (backend/cleanup b {:job-name "cl" :build-number 1})
       ;; After cleanup, `docker inspect` on the container should fail.
       (let [{:keys [exit]} (#'docker/run-docker ["inspect" cid] {:timeout 5000})]
         (is (not (zero? exit))
             "container should be gone after cleanup"))
       (.delete (io/file ws))))))

(deftest ^:docker mask-values-redacted-in-returned-output
  (docker-or-skip
   (fn []
     (let [b  (docker/docker-backend {:image test-image})
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

(deftest ^:docker cancel-stops-an-in-flight-build
  (docker-or-skip
   (fn []
     (let [b   (docker/docker-backend {:image test-image
                                       :cancel-grace-ms 3000})
           ws  (tmp-workspace)
           pr  (backend/prepare-workspace b {:job-name "ca"
                                             :build-number 1
                                             :workspace-path ws})
           cid (get-in pr [:backend-state :container-id])
           ;; Fire a long-sleeping step on a side thread so we can cancel
           ;; it. The thread will return when SIGTERM/SIGKILL drops the
           ;; container.
           step-future (future
                         (backend/execute-step
                          b {:job-name "ca" :build-number 1
                             :backend-state (:backend-state pr)
                             :command "sleep 30"
                             :timeout 60000}))]
       (try
         (Thread/sleep 1000)
         (backend/cancel b {:job-name "ca" :build-number 1})
         (let [r (deref step-future 10000 ::timeout)]
           (is (not= ::timeout r) "in-flight step must return after cancel")
           (is (not (zero? (:exit-code r)))
               "cancelled step must NOT report success"))
         ;; container should no longer be running
         (let [{:keys [out]} (#'docker/run-docker
                              ["inspect" "-f" "{{.State.Running}}" cid]
                              {:timeout 3000})]
           (is (not= "true" (str/trim (or out ""))))
           )
         (finally
           (backend/cleanup b {:job-name "ca" :build-number 1})
           (.delete (io/file ws))))))))

(deftest ^:docker resource-limits-applied-to-run
  (docker-or-skip
   (fn []
     (let [b  (docker/docker-backend
               {:image test-image
                :resource-limits {:memory-mb 128
                                  :pids-max 64}})
           ws (tmp-workspace)
           pr (backend/prepare-workspace b {:job-name "rl"
                                            :build-number 1
                                            :workspace-path ws})
           cid (get-in pr [:backend-state :container-id])]
       (try
         (is (= :ok (:result pr)))
         ;; Inspect the container; HostConfig.Memory should be 128MiB == 134217728.
         (let [{:keys [out]} (#'docker/run-docker
                              ["inspect" "-f" "{{.HostConfig.Memory}}" cid]
                              {:timeout 5000})]
           (is (= "134217728" (str/trim out))))
         (let [{:keys [out]} (#'docker/run-docker
                              ["inspect" "-f" "{{.HostConfig.PidsLimit}}" cid]
                              {:timeout 5000})]
           (is (= "64" (str/trim out))))
         (finally
           (backend/cleanup b {:job-name "rl" :build-number 1})
           (.delete (io/file ws))))))))
