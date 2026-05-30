(ns chengis.dsl.docker-test
  "Unit tests for the Docker DSL helpers. Pure — runs in the :unit tier."
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.dsl.docker :as docker]))

(deftest docker-step-test
  (testing "minimal step has only required keys, no optional keys leak in"
    (let [s (docker/docker-step "maven:3.9" "Build" "mvn test")]
      (is (= :docker (:type s)))
      (is (= "maven:3.9" (:image s)))
      (is (= "Build" (:step-name s)))
      (is (= "mvn test" (:command s)))
      (is (not (contains? s :env)))
      (is (not (contains? s :volumes)))
      (is (not (contains? s :workdir)))
      (is (not (contains? s :network)))
      (is (not (contains? s :timeout)))
      (is (not (contains? s :pull-policy)))
      (is (not (contains? s :docker-args)))))
  (testing "each option is conditionally assoc'd only when supplied"
    (let [s (docker/docker-step "img" "n" "c"
                                :env {"K" "V"}
                                :volumes ["/a:/b"]
                                :workdir "/w"
                                :network "host"
                                :timeout 5000
                                :pull-policy :always
                                :docker-args ["--rm"])]
      (is (= {"K" "V"} (:env s)))
      (is (= ["/a:/b"] (:volumes s)))
      (is (= "/w" (:workdir s)))
      (is (= "host" (:network s)))
      (is (= 5000 (:timeout s)))
      (is (= :always (:pull-policy s)))
      (is (= ["--rm"] (:docker-args s)))))
  (testing "a single option assocs that key and not the others"
    (let [s (docker/docker-step "img" "n" "c" :workdir "/w")]
      (is (= "/w" (:workdir s)))
      (is (not (contains? s :env)))
      (is (not (contains? s :network))))))

(deftest docker-compose-step-test
  (testing "minimal compose step"
    (let [s (docker/docker-compose-step "api" "IT" "pytest")]
      (is (= :docker-compose (:type s)))
      (is (= "api" (:service s)))
      (is (= "IT" (:step-name s)))
      (is (= "pytest" (:command s)))
      (is (not (contains? s :compose-file)))
      (is (not (contains? s :env)))
      (is (not (contains? s :timeout)))))
  (testing "options assoc'd only when supplied"
    (let [s (docker/docker-compose-step "api" "IT" "pytest"
                                        :compose-file "dc.test.yml"
                                        :env {"E" "1"}
                                        :timeout 1000)]
      (is (= "dc.test.yml" (:compose-file s)))
      (is (= {"E" "1"} (:env s)))
      (is (= 1000 (:timeout s))))))

(deftest container-test
  (testing "injects :container config into every stage (varargs)"
    (let [opts {:image "node:18"}
          result (docker/container opts
                                   {:stage-name "Build" :steps []}
                                   {:stage-name "Test" :steps []})]
      (is (vector? result))
      (is (= 2 (count result)))
      (is (every? #(= opts (:container %)) result))))
  (testing "stage maps get container injected, non-stage maps pass through"
    (let [opts {:image "node:18"}
          result (docker/container opts
                                   {:stage-name "Build" :steps []}
                                   {:post-actions [:cleanup]})]
      (is (= 2 (count result)))
      (is (= opts (:container (first result))))
      (is (= "Build" (:stage-name (first result))))
      ;; the non-stage map is passed through untouched (no :container key)
      (is (not (contains? (second result) :container)))
      (is (= [:cleanup] (:post-actions (second result)))))))
