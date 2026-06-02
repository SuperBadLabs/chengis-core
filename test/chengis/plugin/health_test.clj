(ns chengis.plugin.health-test
  "Unit tests for plugin-health rules (M3b): staleness, advisory match,
   auto-vs-manual marker, advisory loading."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.health :as health]
            [clojure.java.io :as io]))

(def day 86400000)

(deftest days-since-test
  (let [now (* 100 day)]
    (is (= 10 (health/days-since (* 90 day) now)))
    (is (= 0 (health/days-since now now)))
    (is (= 0 (health/days-since (+ now day) now)) "future mtime clamps to 0")))

(deftest auto-quarantine-marker-test
  (is (health/auto-quarantine? "auto: stale — no update in 99d (>90d)"))
  (is (health/auto-quarantine? "auto: advisory CVE-1"))
  (is (not (health/auto-quarantine? "vulnerable")) "manual reason is not auto")
  (is (not (health/auto-quarantine? nil))))

(deftest stale-reason-test
  (let [now (* 200 day)]
    (testing "older than threshold => reason"
      (is (some? (health/stale-reason (* 100 day) 90 now)))
      (is (health/auto-quarantine? (health/stale-reason (* 100 day) 90 now))))
    (testing "exactly at threshold is not stale (> not >=)"
      (is (nil? (health/stale-reason (* 110 day) 90 now)) "90d old, threshold 90 => not stale"))
    (testing "fresh file => nil"
      (is (nil? (health/stale-reason (- now day) 90 now))))
    (testing "disabled when staleness-days nil/0"
      (is (nil? (health/stale-reason (* 1 day) nil now)))
      (is (nil? (health/stale-reason (* 1 day) 0 now))))))

(deftest advisory-reason-test
  (let [advs [{:plugin "evil" :id "CVE-2026-1" :reason "RCE"}
              {:plugin "leaky" :id "GHSA-x" :versions ["1.0.0" "1.0.1"]}]]
    (testing "name match, all versions (no :versions)"
      (let [r (health/advisory-reason "evil" "9.9" advs)]
        (is (some? r))
        (is (health/auto-quarantine? r))
        (is (re-find #"CVE-2026-1" r))
        (is (re-find #"RCE" r) "advisory :reason appended")))
    (testing "version-scoped advisory matches only listed versions"
      (is (some? (health/advisory-reason "leaky" "1.0.0" advs)))
      (is (nil? (health/advisory-reason "leaky" "2.0.0" advs)) "unlisted version => no match"))
    (testing "non-matching plugin => nil"
      (is (nil? (health/advisory-reason "innocent" "1.0.0" advs))))))

(deftest quarantine-reason-precedence-test
  (let [now (* 200 day)
        cfg {:staleness-days 90}
        advs [{:plugin "p" :id "CVE-9"}]]
    (testing "stale takes precedence over advisory"
      (let [r (health/quarantine-reason {:plugin-name "p" :version "1" :mtime (* 50 day)} cfg advs now)]
        (is (re-find #"stale" r))))
    (testing "advisory when fresh"
      (let [r (health/quarantine-reason {:plugin-name "p" :version "1" :mtime (- now day)} cfg advs now)]
        (is (re-find #"advisory CVE-9" r))))
    (testing "healthy => nil"
      (is (nil? (health/quarantine-reason {:plugin-name "ok" :version "1" :mtime (- now day)} cfg advs now))))))

(deftest load-advisories-test
  (testing "inline :advisories takes precedence"
    (is (= [{:plugin "a" :id "x"}]
           (health/load-advisories {:advisories [{:plugin "a" :id "x"}]
                                    :advisories-path "/nonexistent"}))))
  (testing "reads EDN from :advisories-path"
    (let [f (io/file (str "/tmp/chengis-adv-" (System/nanoTime) ".edn"))]
      (spit f (pr-str [{:plugin "b" :id "y"}]))
      (is (= [{:plugin "b" :id "y"}] (health/load-advisories {:advisories-path (.getPath f)})))
      (.delete f)))
  (testing "bad/missing path degrades to []"
    (is (= [] (health/load-advisories {:advisories-path "/no/such/file.edn"})))
    (is (= [] (health/load-advisories {})))))
