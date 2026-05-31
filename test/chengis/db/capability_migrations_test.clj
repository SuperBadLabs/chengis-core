(ns chengis.db.capability-migrations-test
  "Tests for the capability-tagged migration filter.

   Coverage:
     1. Manifest parsing — happy path, missing file = nil, malformed
        files throw with diagnostic types.
     2. Validation against the capability registry — unknown
        capabilities in the manifest fail loud.
     3. Filtering — core stays, gated migrations included only when
        their capability is enabled, order preserved.
     4. Classification — diagnostic split into core / gated-on /
        gated-off buckets.
     5. The bundled manifests shipped with chengis-core actually parse
        and reference only known capabilities."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.capability-migrations :as cm]
            [chengis.product.capability :as cap]))

;; The capability tests already isolate the registry; just confirm the
;; built-ins are loaded by requiring the ns above. No fixture needed
;; for the pure-data tests; reset for any test that mutates state.

;; ---------------------------------------------------------------------------
;; 1. Manifest parsing
;; ---------------------------------------------------------------------------

(defn- write-tmp-manifest [content]
  (let [f (java.io.File/createTempFile "cap-manifest" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest read-manifest-happy-path
  (let [f (write-tmp-manifest
           (pr-str {:version 1
                    :default :core
                    :tags {"025-organizations" :multi-tenant}}))
        m (cm/read-manifest f)]
    (is (= 1 (:version m)))
    (is (= :multi-tenant (get-in m [:tags "025-organizations"])))))

(deftest read-manifest-missing-file-is-nil
  (testing "missing manifest = nil (legal 'no tagging yet' state)"
    (is (nil? (cm/read-manifest (java.io.File. "/nonexistent/manifest.edn"))))
    (is (nil? (cm/read-manifest "/totally/not/here.edn")))))

(deftest read-manifest-rejects-non-map
  (let [f (write-tmp-manifest "[:not :a :map]")]
    (is (thrown-with-msg? Exception #"must be a map"
                          (cm/read-manifest f)))))

(deftest read-manifest-rejects-bad-version
  (let [f (write-tmp-manifest (pr-str {:version 2 :tags {}}))]
    (is (thrown-with-msg? Exception #"Unsupported manifest :version"
                          (cm/read-manifest f)))))

(deftest read-manifest-rejects-bad-tag-types
  (testing "id must be a string"
    (let [f (write-tmp-manifest (pr-str {:version 1 :tags {025 :multi-tenant}}))]
      (is (thrown-with-msg? Exception #"Migration id keys"
                            (cm/read-manifest f)))))
  (testing "capability must be a keyword"
    (let [f (write-tmp-manifest (pr-str {:version 1 :tags {"025-organizations" "multi-tenant"}}))]
      (is (thrown-with-msg? Exception #"Capability values"
                            (cm/read-manifest f))))))

(deftest read-manifest-rejects-syntax-errors
  (let [f (write-tmp-manifest "{ :version 1 :tags {oops")]
    (is (thrown-with-msg? Exception #"Failed to parse"
                          (cm/read-manifest f)))))

;; ---------------------------------------------------------------------------
;; 2. Validation against the capability registry
;; ---------------------------------------------------------------------------

(deftest validate-against-registry-rejects-unknown-caps
  (let [manifest {:version 1
                  :tags {"025-organizations" :multi-tenant
                         "999-fake"          :totally-not-a-thing}}]
    (let [ex (try (cm/validate-manifest-against-registry! manifest)
                  (catch Exception e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (re-find #"unknown capabilities" (.getMessage ex)))
      (is (contains? (:unknown (ex-data ex)) :totally-not-a-thing)))))

(deftest validate-against-registry-accepts-only-known
  (let [manifest {:version 1
                  :tags {"025-organizations"  :multi-tenant
                         "036-audit-seq-num"  :audit-chain
                         "051-saml-identities" :saml}}]
    (is (nil? (cm/validate-manifest-against-registry! manifest)))))

(deftest validate-against-registry-accepts-core-as-explicit-tag
  (let [manifest {:version 1 :tags {"001-initial-schema" :core}}]
    (is (nil? (cm/validate-manifest-against-registry! manifest)))))

(deftest validate-against-registry-passes-empty-manifest
  (is (nil? (cm/validate-manifest-against-registry! {:version 1 :tags {}}))))

;; ---------------------------------------------------------------------------
;; 3. Filtering — pure
;; ---------------------------------------------------------------------------

(def ^:private demo-manifest
  {:version 1
   :tags {"025-organizations"  :multi-tenant
          "026-org-id-columns" :multi-tenant
          "036-audit-seq-num"  :audit-chain
          "051-saml-identities" :saml
          ;; Explicit :core — still always applied
          "001-initial-schema" :core}})

(def ^:private ordered-ids
  ["001-initial-schema"
   "002-git-metadata"
   "025-organizations"
   "026-org-id-columns"
   "036-audit-seq-num"
   "051-saml-identities"
   "099-job-branches"])

(deftest migration-applies-untagged-is-core
  (is (true? (cm/migration-applies? demo-manifest "002-git-metadata" #{}))))

(deftest migration-applies-explicit-core-is-core
  (is (true? (cm/migration-applies? demo-manifest "001-initial-schema" #{}))))

(deftest migration-applies-gated-needs-capability
  (is (false? (cm/migration-applies? demo-manifest "025-organizations" #{})))
  (is (true?  (cm/migration-applies? demo-manifest "025-organizations" #{:multi-tenant}))))

(deftest filter-no-capabilities-keeps-only-core
  (let [filtered (cm/filter-migrations demo-manifest ordered-ids #{})]
    (is (= ["001-initial-schema" "002-git-metadata" "099-job-branches"]
           filtered))))

(deftest filter-multi-tenant-only
  (let [filtered (cm/filter-migrations demo-manifest ordered-ids #{:multi-tenant})]
    (is (= ["001-initial-schema"
            "002-git-metadata"
            "025-organizations"
            "026-org-id-columns"
            "099-job-branches"]
           filtered))))

(deftest filter-multiple-capabilities-union
  (let [filtered (cm/filter-migrations demo-manifest ordered-ids
                                       #{:multi-tenant :audit-chain :saml})]
    (is (= ordered-ids filtered))))

(deftest filter-preserves-order
  (testing "filter never reorders — migratus needs the numeric prefix order intact"
    (let [shuffled-input ["099-job-branches"
                          "025-organizations"
                          "001-initial-schema"]
          filtered (cm/filter-migrations demo-manifest shuffled-input
                                         #{:multi-tenant})]
      (is (= shuffled-input filtered)
          "order matches the input even when the input is out of numeric order"))))

;; ---------------------------------------------------------------------------
;; 4. Classification
;; ---------------------------------------------------------------------------

(deftest classify-buckets-each-migration
  (let [r (cm/classify-migrations demo-manifest ordered-ids #{:audit-chain})]
    (is (= #{"001-initial-schema" "002-git-metadata" "099-job-branches"}
           (set (:core r))))
    (is (= #{"036-audit-seq-num"}
           (set (map :id (:gated-on r)))))
    (is (= #{"025-organizations" "026-org-id-columns" "051-saml-identities"}
           (set (map :id (:gated-off r)))))
    ;; gated entries carry their capability label
    (is (= :audit-chain  (-> r :gated-on first :capability)))
    (is (some #(= [:multi-tenant "025-organizations"]
                  [(:capability %) (:id %)])
              (:gated-off r)))))

;; ---------------------------------------------------------------------------
;; 5. The actual bundled manifests parse + validate
;;
;; These tests catch typos in `resources/migrations/{sqlite,
;; postgresql}/capability-manifest.edn` at test time, not at boot.
;; ---------------------------------------------------------------------------

(deftest bundled-sqlite-manifest-parses
  (let [m (cm/load-bundled-manifest "sqlite")]
    (is (some? m))
    (is (= 1 (:version m)))
    (is (pos? (count (:tags m))))))

(deftest bundled-pg-manifest-parses
  (let [m (cm/load-bundled-manifest "postgresql")]
    (is (some? m))
    (is (= 1 (:version m)))
    (is (pos? (count (:tags m))))))

(deftest bundled-manifests-reference-only-known-capabilities
  (testing "SQLite manifest tags only known capabilities"
    (is (nil? (cm/validate-manifest-against-registry!
               (cm/load-bundled-manifest "sqlite")))))
  (testing "PostgreSQL manifest tags only known capabilities"
    (is (nil? (cm/validate-manifest-against-registry!
               (cm/load-bundled-manifest "postgresql"))))))

(deftest bundled-manifests-anvil-default-vs-chengis-default
  (testing "with no capabilities enabled, the SQLite manifest hides the leaf enterprise migrations"
    (let [m (cm/load-bundled-manifest "sqlite")
          gated (->> (vals (:tags m))
                     (remove #{:core})
                     set)]
      ;; sanity: the manifest does tag enterprise things. Several caps
      ;; have NO tagged migrations anymore — they gate behavior, not
      ;; schema. Those are: :audit-chain (round 5), :multi-tenant
      ;; (round 7). Their migrations stay :core because flag-gated or
      ;; always-on subsystems depend on the tables.
      (is (contains? gated :saml))
      (is (contains? gated :iac))
      (is (contains? gated :deployments))
      ;; with no caps on, gated migrations are filtered out
      (is (false? (cm/migration-applies? m "051-saml-identities" #{})))
      (is (false? (cm/migration-applies? m "070-iac-projects" #{})))
      ;; Pin the :core-by-design migrations so future PRs that re-tag
      ;; them have to also handle the dependency that put them in :core.
      (is (= :core (or (get-in m [:tags "025-organizations"]) :core))
          "025-organizations stays :core (24 downstream migrations depend on org_id)")
      (is (= :core (or (get-in m [:tags "036-audit-seq-num"]) :core))
          "036-audit-seq-num stays :core (audit writer is always-on)")
      (is (= :core (or (get-in m [:tags "097-audit-prev-hash-unique"]) :core))
          "097 stays :core (same audit pipeline)")
      (is (= :core (or (get-in m [:tags "086-org-invitations"]) :core))
          "086-org-invitations stays :core (org-management UI flag fires independently)")
      (is (= :core (or (get-in m [:tags "078-org-quotas"]) :core))
          "078-org-quotas stays :core (org-management UI flag fires independently)")
      (is (= :core (or (get-in m [:tags "092-org-branding"]) :core))
          "092-org-branding stays :core (round 7)")))
  (testing "with the chengis default bundle on Postgres, all listed enterprise migrations apply"
    (let [m (cm/load-bundled-manifest "sqlite")
          chengis-caps (cap/effective-set {:database {:type "postgresql"}} :chengis)]
      (doseq [[mid cap-key] (:tags m)
              :when (not= cap-key :core)]
        (is (cm/migration-applies? m mid chengis-caps)
            (str mid " (tagged " cap-key ") should apply under chengis-default + PG but didn't")))))
  (testing "under chengis + SQLite (dev/demo), the right migrations are filtered out and the right ones stay"
    (let [m (cm/load-bundled-manifest "sqlite")
          chengis-sqlite (cap/effective-set {:database {:type "sqlite"}} :chengis)]
      ;; Behavior-gating caps with no tagged migrations don't affect
      ;; the filter — multi-tenant schema is universal (round 7).
      ;; Things still demoted on sqlite are non-multi-tenant + non-audit caps.
      ;; All multi-tenant schema migrations still apply (now :core):
      (is (true? (cm/migration-applies? m "092-org-branding" chengis-sqlite))
          "092-org-branding is :core after round 7 (org-management UI flag fires independently)")
      (is (true? (cm/migration-applies? m "086-org-invitations" chengis-sqlite)))
      ;; non-PG-only caps still apply (saml etc.)
      (is (true? (cm/migration-applies? m "051-saml-identities" chengis-sqlite)))
      ;; The foundational migrations stay :core — even chengis-on-sqlite
      ;; keeps them. Each represents a substrate that an always-on
      ;; subsystem depends on, so demoting them would break boot.
      (is (true? (cm/migration-applies? m "025-organizations" chengis-sqlite))
          "025-organizations is :core (24 downstream migrations reference org_id)")
      (is (true? (cm/migration-applies? m "036-audit-seq-num" chengis-sqlite))
          "036-audit-seq-num is :core (audit writer is always-on and references seq_num)")
      (is (true? (cm/migration-applies? m "097-audit-prev-hash-unique" chengis-sqlite))
          "097 is :core (same audit pipeline)"))))
