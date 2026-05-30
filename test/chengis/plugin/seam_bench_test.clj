(ns chengis.plugin.seam-bench-test
  "CI guard for the plugin seam (M1e): asserts an SCI-plugin step's per-call
   dispatch overhead stays in the microsecond regime — i.e. the extension
   mechanism never reintroduces the container/pod-per-step overhead (~10^5–10^6
   µs) that Chengis structurally avoids. Self-contained (no benchmarks/src dep)
   so it runs on the normal test classpath; see chengis.bench.plugin-seam for
   the full reporting harness."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.plugin.sci :as plugin-sci]
            [taoensso.timbre :as log]))

(use-fixtures :each
  (fn [f] (registry/reset-registry!) (f) (registry/reset-registry!)))

(defn- native-ex []
  (reify proto/StepExecutor
    (execute-step [_ _ctx _step] {:exit-code 0 :duration-ms 0})))

(defn- sci-ex []
  (plugin-sci/eval-plugin
   {:plugin-name "seam-test-noop" :trust :sandboxed
    :source "(require '[chengis.plugin.host :as h])
             (h/register-step-executor! :seam-test-noop (fn [c s] {:exit-code 0 :duration-ms 0}))"})
  (registry/get-step-executor :seam-test-noop))

(defn- median-ms-per-call
  "Batched timing -> median milliseconds per execute-step call. Accumulates the
   result into a blackhole to defeat dead-code elimination."
  [ex {:keys [warmup batches batch-size]}]
  (let [ctx {} step {:type :seam-test-noop}]
    (dotimes [_ warmup] (proto/execute-step ex ctx step))
    (let [bh (volatile! 0)
          samples
          (vec (for [_ (range batches)]
                 (let [s   (System/nanoTime)
                       acc (loop [i 0 a (long 0)]
                             (if (< i batch-size)
                               (recur (inc i)
                                      (unchecked-add a (long (:exit-code
                                                              (proto/execute-step ex ctx step)))))
                               a))
                       e   (System/nanoTime)]
                   (vswap! bh unchecked-add acc)
                   (/ (- e s) (double batch-size) 1.0e6))))]
      (log/debug "seam blackhole (ignore):" @bh)
      (nth (sort samples) (quot (count samples) 2)))))

(deftest sci-step-seam-stays-microsecond
  (testing "SCI plugin step dispatch overhead is sub-millisecond (microsecond regime)"
    (let [opts    {:warmup 20000 :batches 50 :batch-size 2000}
          native  (median-ms-per-call (native-ex) opts)
          sci     (median-ms-per-call (sci-ex) opts)
          seam-us (* 1000.0 (- sci native))]
      (log/info (format "Seam guard: native %.4f µs, SCI %.4f µs, seam +%.4f µs/call"
                        (* 1000.0 native) (* 1000.0 sci) seam-us))
      (is (some? (registry/get-step-executor :seam-test-noop))
          "SCI step executor registered through the host API")
      ;; Architectural ceiling: well under 1ms (real value ~µs). A pod-per-step
      ;; engine would be ~10^5–10^6 µs here — so this 1000× margin is non-flaky
      ;; yet still fails loudly if the seam ever regressed to that regime.
      (is (< native 1.0) "native step should be sub-millisecond")
      (is (< sci 1.0)
          (format "SCI step median %.4f ms exceeded the 1ms architectural ceiling" sci)))))
