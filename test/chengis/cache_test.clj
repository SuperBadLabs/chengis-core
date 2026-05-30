(ns chengis.cache-test
  "Tests for the generic TTL cache (chengis.cache)."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.cache :as cache]))

;; ---------------------------------------------------------------------------
;; make-ttl-cache
;; ---------------------------------------------------------------------------

(deftest make-cache-test
  (testing "creates an empty cache"
    (let [c (cache/make-ttl-cache 5000)]
      (is (= {:size 0 :ttl-ms 5000} (cache/stats c))))))

;; ---------------------------------------------------------------------------
;; put! / get-cached
;; ---------------------------------------------------------------------------

(deftest put-and-get-test
  (testing "put! stores a value and get-cached returns it"
    (let [c (cache/make-ttl-cache 5000)]
      (cache/put! c [:org "k"] "hello")
      (is (= "hello" (cache/get-cached c [:org "k"])))))

  (testing "get-cached returns ::miss for absent key"
    (let [c (cache/make-ttl-cache 5000)]
      (is (= :chengis.cache/miss (cache/get-cached c [:org "absent"])))
      (is (= :chengis.cache/miss (cache/get-cached c "nope"))))))

(deftest get-cached-expired-returns-miss-test
  (testing "expired entry returns ::miss"
    (let [c (cache/make-ttl-cache 1)] ;; 1ms TTL — will expire immediately
      (cache/put! c "k" "val")
      (Thread/sleep 5)
      (is (= :chengis.cache/miss (cache/get-cached c "k"))))))

;; ---------------------------------------------------------------------------
;; get-or-compute
;; ---------------------------------------------------------------------------

(deftest get-or-compute-test
  (testing "computes value on miss and caches it"
    (let [c       (cache/make-ttl-cache 5000)
          call-ct (atom 0)
          compute (fn [] (swap! call-ct inc) "computed")]
      (is (= "computed" (cache/get-or-compute c "k" compute)))
      (is (= "computed" (cache/get-or-compute c "k" compute)))
      (is (= 1 @call-ct) "compute-fn should be called only once")))

  (testing "does not cache on exception"
    (let [c (cache/make-ttl-cache 5000)]
      (is (thrown? Exception
                   (cache/get-or-compute c "bad" (fn [] (throw (Exception. "boom"))))))
      (is (= :chengis.cache/miss (cache/get-cached c "bad"))))))

;; ---------------------------------------------------------------------------
;; invalidate!
;; ---------------------------------------------------------------------------

(deftest invalidate-test
  (testing "removes a specific key"
    (let [c (cache/make-ttl-cache 5000)]
      (cache/put! c "k1" "v1")
      (cache/put! c "k2" "v2")
      (cache/invalidate! c "k1")
      (is (= :chengis.cache/miss (cache/get-cached c "k1")))
      (is (= "v2" (cache/get-cached c "k2"))))))

;; ---------------------------------------------------------------------------
;; clear!
;; ---------------------------------------------------------------------------

(deftest clear-test
  (testing "removes all entries"
    (let [c (cache/make-ttl-cache 5000)]
      (cache/put! c "a" 1)
      (cache/put! c "b" 2)
      (cache/clear! c)
      (is (= 0 (:size (cache/stats c)))))))

;; ---------------------------------------------------------------------------
;; evict-stale!
;; ---------------------------------------------------------------------------

(deftest evict-stale-test
  (testing "removes only expired entries"
    (let [c (cache/make-ttl-cache 50)] ;; 50ms TTL
      (cache/put! c "old" "x")
      (Thread/sleep 60)
      (cache/put! c "fresh" "y")
      (cache/evict-stale! c)
      (is (= :chengis.cache/miss (cache/get-cached c "old")))
      (is (= "y" (cache/get-cached c "fresh"))))))

;; ---------------------------------------------------------------------------
;; Org isolation: different org-id keys are independent
;; ---------------------------------------------------------------------------

(deftest org-isolation-test
  (testing "cache keys with different org-ids are independent"
    (let [c (cache/make-ttl-cache 5000)]
      (cache/put! c ["org-a" "pipeline-1"] {:data "for-a"})
      (cache/put! c ["org-b" "pipeline-1"] {:data "for-b"})
      (is (= {:data "for-a"} (cache/get-cached c ["org-a" "pipeline-1"])))
      (is (= {:data "for-b"} (cache/get-cached c ["org-b" "pipeline-1"])))
      ;; Invalidate one does not affect the other
      (cache/invalidate! c ["org-a" "pipeline-1"])
      (is (= :chengis.cache/miss (cache/get-cached c ["org-a" "pipeline-1"])))
      (is (= {:data "for-b"} (cache/get-cached c ["org-b" "pipeline-1"]))))))
