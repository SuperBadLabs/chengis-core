(ns ^:integration chengis.engine.policy-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.policy-store :as policy-store]
            [chengis.engine.policy :as policy]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-db-path "/tmp/chengis-policy-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Branch restriction tests
;; ---------------------------------------------------------------------------

(deftest branch-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction allows matching branch"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Allow main only"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["main" "release/*"] :action "allow"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "main"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

(deftest branch-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction denies non-matching branch"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Allow main only"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["main"] :action "allow"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "feature/test"}
                                                 {:stage-name "build"})]
        (is (false? (:proceed result)))
        (is (clojure.string/includes? (:reason result) "does not match"))))))

(deftest branch-restriction-glob-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction supports glob patterns"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Allow release branches"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["release/*"] :action "allow"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "release/1.0"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Author restriction tests
;; ---------------------------------------------------------------------------

(deftest author-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "author restriction denies matching author"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Deny bots"
                                    :policy-type "author-restriction"
                                    :rules {:authors ["bot-*"] :action "deny"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :author "bot-ci"}
                                                 {:stage-name "build"})]
        (is (false? (:proceed result)))))))

(deftest author-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "author restriction allows non-matching author"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Deny bots"
                                    :policy-type "author-restriction"
                                    :rules {:authors ["bot-*"] :action "deny"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :author "alice"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Time window tests
;; ---------------------------------------------------------------------------

(deftest time-window-allow-only-test
  (testing "time window with unrestricted hours passes"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:feature-flags {:policy-engine true}}}]
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Allow anytime"
                                    :policy-type "time-window"
                                    :rules {:timezone "UTC" :days [] :start_hour 0 :end_hour 24 :action "allow-only"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Parameter restriction tests
;; ---------------------------------------------------------------------------

(deftest parameter-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "parameter restriction denies matching parameter"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "No force"
                                    :policy-type "parameter-restriction"
                                    :rules {:parameter "force" :operator "equals" :value "true" :action "deny"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :parameters {:force "true"}}
                                                 {:stage-name "deploy"})]
        (is (false? (:proceed result)))))))

(deftest parameter-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "parameter restriction allows when parameter doesn't match"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "No force"
                                    :policy-type "parameter-restriction"
                                    :rules {:parameter "force" :operator "equals" :value "true" :action "deny"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :parameters {:force "false"}}
                                                 {:stage-name "deploy"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Required approval override tests
;; ---------------------------------------------------------------------------

(deftest required-approval-override-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "required-approval returns override for matching stage"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Deploy needs 2 approvals"
                                    :policy-type "required-approval"
                                    :rules {:stages ["deploy-*"] :min_approvals 2 :approver_group ["admin1" "admin2"]}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1"}
                                                 {:stage-name "deploy-prod"})]
        (is (true? (:proceed result)))
        (is (seq (:approval-overrides result)))))))

;; ---------------------------------------------------------------------------
;; Feature flag bypass
;; ---------------------------------------------------------------------------

(deftest feature-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine false}}}]
    (testing "policies are skipped when feature flag is disabled"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Should be ignored"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["main"] :action "allow"}
                                    :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "develop"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Disabled policies skipped
;; ---------------------------------------------------------------------------

(deftest disabled-policy-skipped-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "disabled policies are skipped"
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Disabled deny all"
                                    :policy-type "branch-restriction"
                                    :rules {:branches [] :action "allow"}
                                    :enabled false})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "anything"}
                                                 {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Priority ordering
;; ---------------------------------------------------------------------------

(deftest priority-ordering-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "higher priority (lower number) deny stops evaluation"
      ;; Priority 1: deny everything
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Deny all branches"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["forbidden-*"] :action "deny"}
                                    :priority 1 :enabled true})
      ;; Priority 100: allow (should not be reached for denied branches)
      (policy-store/create-policy! ds
                                   {:org-id "org-1" :name "Allow wide"
                                    :policy-type "branch-restriction"
                                    :rules {:branches ["*"] :action "allow"}
                                    :priority 100 :enabled true})
      (let [result (policy/check-stage-policies! system
                                                 {:build-id "b1" :org-id "org-1" :branch "forbidden-test"}
                                                 {:stage-name "build"})]
        (is (false? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Approval override application
;; ---------------------------------------------------------------------------

(deftest apply-approval-overrides-test
  (testing "apply-approval-overrides increases min-approvals"
    (let [stage {:stage-name "deploy" :approval {:min-approvals 1}}
          overrides [{:min-approvals 3 :approver-group ["admin1" "admin2"]}]
          result (policy/apply-approval-overrides stage overrides)]
      (is (= 3 (get-in result [:approval :min-approvals])))
      (is (= ["admin1" "admin2"] (get-in result [:approval :approver-group]))))))

(deftest apply-empty-overrides-test
  (testing "empty overrides return stage unchanged"
    (let [stage {:stage-name "build"}
          result (policy/apply-approval-overrides stage [])]
      (is (= stage result)))))

;; ---------------------------------------------------------------------------
;; MUT-P2 (Pareto): direct unit tests of the pure rule evaluators — killing
;; behavior-bearing survivors (operators, defaults, nil/some guards, globs).
;; ---------------------------------------------------------------------------

(defn- eval-param [rule params]
  (#'policy/eval-parameter-restriction rule {:parameters params}))

(deftest eval-parameter-restriction-operators-test
  (testing ":equals matches identical string values"
    (is (= :deny (:result (eval-param {:parameter "force" :operator "equals" :value "true" :action "deny"}
                                      {:force "true"}))))
    (is (= :allow (:result (eval-param {:parameter "force" :operator "equals" :value "true" :action "deny"}
                                       {:force "false"})))))
  (testing ":not-equals matches when values differ (kills not= -> =)"
    (is (= :deny (:result (eval-param {:parameter "env" :operator "not-equals" :value "prod" :action "deny"}
                                      {:env "staging"}))))
    (is (= :allow (:result (eval-param {:parameter "env" :operator "not-equals" :value "prod" :action "deny"}
                                       {:env "prod"})))))
  (testing ":contains needs a present value AND a substring match (kills and -> or)"
    (is (= :deny (:result (eval-param {:parameter "msg" :operator "contains" :value "skip" :action "deny"}
                                      {:msg "please skip ci"}))))
    ;; absent value must not throw and must not match
    (is (= :allow (:result (eval-param {:parameter "msg" :operator "contains" :value "skip" :action "deny"}
                                       {})))))
  (testing ":exists matches a present param (kills some? -> nil?)"
    (is (= :deny (:result (eval-param {:parameter "force" :operator "exists" :action "deny"}
                                      {:force "anything"}))))
    (is (= :allow (:result (eval-param {:parameter "force" :operator "exists" :action "deny"}
                                       {})))))
  (testing ":not-exists matches an absent param (kills nil? -> some?)"
    (is (= :deny (:result (eval-param {:parameter "force" :operator "not-exists" :action "deny"}
                                      {}))))
    (is (= :allow (:result (eval-param {:parameter "force" :operator "not-exists" :action "deny"}
                                       {:force "x"})))))
  (testing "an unknown operator never matches (kills the false -> true default)"
    (is (= :allow (:result (eval-param {:parameter "force" :operator "bogus-op" :value "x" :action "deny"}
                                       {:force "x"})))))
  (testing "operator defaults to :equals when omitted (kills the or-default)"
    (is (= :deny (:result (eval-param {:parameter "force" :value "true" :action "deny"}
                                      {:force "true"})))))
  (testing "action defaults to :deny when omitted (kills the or-default)"
    (is (= :deny (:result (eval-param {:parameter "force" :operator "equals" :value "true"}
                                      {:force "true"}))))))

(deftest glob-matches-test
  (testing "glob patterns with * and ? match correctly"
    (is (true? (#'policy/glob-matches? "release/*" "release/v1")))
    (is (true? (#'policy/glob-matches? "feature-?" "feature-x")))
    (is (false? (#'policy/glob-matches? "release/*" "main"))))
  (testing "nil pattern or value is a non-match, not an error (kills and -> or)"
    (is (not (#'policy/glob-matches? nil "main")))
    (is (not (#'policy/glob-matches? "main" nil)))))

(deftest eval-author-restriction-default-action-test
  (testing "author restriction defaults to :deny when :action omitted (kills or-default)"
    (let [r (#'policy/eval-author-restriction {:authors ["bot-*"]} {:git-author "bot-ci"})]
      (is (= :deny (:result r))))))

(deftest eval-required-approval-no-match-test
  (testing "a non-matching stage does NOT trigger an approval override (kills and -> or)"
    (let [r (#'policy/eval-required-approval {:stages ["deploy-*"] :min_approvals 2}
                                             {:stage-name "build"})]
      (is (= :allow (:result r))))
    (let [r (#'policy/eval-required-approval {:stages ["deploy-*"] :min_approvals 2}
                                             {:stage-name "deploy-prod"})]
      (is (= :override-approval (:result r))))))

(deftest build-evaluation-context-commit-fallback-test
  (testing ":git-commit falls back across :commit / :git-commit keys (kills or -> and)"
    (is (= "abc" (:git-commit (policy/build-evaluation-context {:commit "abc"} {}))))
    (is (= "xyz" (:git-commit (policy/build-evaluation-context {:git-commit "xyz"} {}))))))
