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
  (testing "with no capabilities enabled, the SQLite manifest hides the enterprise migrations"
    (let [m (cm/load-bundled-manifest "sqlite")
          gated (->> (vals (:tags m))
                     (remove #{:core})
                     set)]
      ;; sanity: the manifest does tag enterprise things
      (is (contains? gated :multi-tenant))
      (is (contains? gated :audit-chain))
      (is (contains? gated :saml))
      ;; with no caps on, all gated migrations are filtered out
      (let [eg-migration "025-organizations"]
        (is (false? (cm/migration-applies? m eg-migration #{}))))))
  (testing "with the chengis default bundle, all listed enterprise migrations apply"
    (let [m (cm/load-bundled-manifest "sqlite")
          chengis-caps (cap/effective-set {} :chengis)]
      (doseq [[mid cap-key] (:tags m)
              :when (not= cap-key :core)]
        (is (cm/migration-applies? m mid chengis-caps)
            (str mid " (tagged " cap-key ") should apply under chengis-default but didn't"))))))
