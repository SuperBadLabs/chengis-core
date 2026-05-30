(ns ^:integration chengis.db.job-branches-store-test
  "DB-integration tests for migration 099 (job_branches) and the
   upsert / list-active / archive / get helpers in
   chengis.db.job-branches-store."
  (:require [chengis.db.connection :as conn]
            [chengis.db.job-branches-store :as jbs]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private test-db-path "/tmp/chengis-job-branches-store-test.db")

(defn- setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

;; ---------------------------------------------------------------------------
;; upsert-branch!
;; ---------------------------------------------------------------------------

(deftest upsert-branch-insert-path-test
  (testing "first sight of a branch inserts a row with the supplied SHA"
    (let [ds  (test-ds)
          id  (jbs/upsert-branch! ds "job-1" "main" "sha-aaaa")
          row (jbs/get-branch ds "job-1" "main")]
      (is (string? id))
      (is (= id (:id row)))
      (is (= "job-1"   (:job-id row)))
      (is (= "main"    (:branch-name row)))
      (is (= "sha-aaaa" (:head-sha row)))
      (is (some? (:discovered-at row)))
      (is (some? (:last-seen-at row)))
      (is (nil?  (:archived-at row))))))

(deftest upsert-branch-update-path-preserves-id-and-discovered-at-test
  (testing "re-discovering a branch updates head-sha + last-seen-at but
            keeps the original id and discovered-at"
    (let [ds      (test-ds)
          id1     (jbs/upsert-branch! ds "job-1" "main" "sha-1")
          row1    (jbs/get-branch ds "job-1" "main")
          ;; small sleep so last-seen-at can move forward at millisecond
          ;; resolution (created_at default is ms-resolution; bare
          ;; CURRENT_TIMESTAMP on the UPDATE is second-resolution, so the
          ;; equality assertion below proves stability rather than
          ;; movement).
          _       (Thread/sleep 5)
          id2     (jbs/upsert-branch! ds "job-1" "main" "sha-2")
          row2    (jbs/get-branch ds "job-1" "main")]
      (is (= id1 id2) "upsert returns the existing id, not a new uuid")
      (is (= "sha-2" (:head-sha row2)))
      (is (= (:discovered-at row1) (:discovered-at row2))
          "discovered_at is set at INSERT time and not rewritten on update"))))

(deftest upsert-branch-clears-archived-at-on-resurrection-test
  (testing "a deleted-then-recreated branch comes back active automatically"
    (let [ds (test-ds)]
      (jbs/upsert-branch! ds "job-1" "feature/foo" "sha-1")
      (jbs/archive-branch! ds "job-1" "feature/foo")
      (is (some? (:archived-at (jbs/get-branch ds "job-1" "feature/foo"))))
      (jbs/upsert-branch! ds "job-1" "feature/foo" "sha-2")
      (let [row (jbs/get-branch ds "job-1" "feature/foo")]
        (is (nil? (:archived-at row))
            "archived_at must be cleared so the branch shows up in
             list-active-branches again")
        (is (= "sha-2" (:head-sha row)))))))

;; ---------------------------------------------------------------------------
;; list-active-branches
;; ---------------------------------------------------------------------------

(deftest list-active-branches-excludes-archived-and-other-jobs-test
  (let [ds (test-ds)]
    (jbs/upsert-branch! ds "job-1" "main"           "sha-m")
    (jbs/upsert-branch! ds "job-1" "feature/a"      "sha-a")
    (jbs/upsert-branch! ds "job-1" "feature/legacy" "sha-l")
    (jbs/upsert-branch! ds "job-2" "main"           "sha-m2")
    (jbs/archive-branch! ds "job-1" "feature/legacy")
    (let [rows (jbs/list-active-branches ds "job-1")]
      (testing "scoped to the right job"
        (is (every? #(= "job-1" (:job-id %)) rows)))
      (testing "archived branch excluded"
        (is (not-any? #(= "feature/legacy" (:branch-name %)) rows)))
      (testing "ordered by branch_name asc"
        (is (= ["feature/a" "main"] (mapv :branch-name rows)))))))

;; ---------------------------------------------------------------------------
;; archive-branch!
;; ---------------------------------------------------------------------------

(deftest archive-branch-is-idempotent-and-noop-on-missing-test
  (let [ds (test-ds)]
    (jbs/upsert-branch! ds "job-1" "main" "sha-1")
    (testing "first archive stamps archived_at"
      (jbs/archive-branch! ds "job-1" "main")
      (is (some? (:archived-at (jbs/get-branch ds "job-1" "main")))))
    (testing "second archive is a no-op (just refreshes the timestamp)"
      (jbs/archive-branch! ds "job-1" "main")
      (is (some? (:archived-at (jbs/get-branch ds "job-1" "main")))))
    (testing "archiving an unknown branch doesn't throw and creates no row"
      (jbs/archive-branch! ds "job-1" "does-not-exist")
      (is (nil? (jbs/get-branch ds "job-1" "does-not-exist"))))))

;; ---------------------------------------------------------------------------
;; get-branch
;; ---------------------------------------------------------------------------

(deftest get-branch-returns-archived-rows-test
  (testing "get-branch returns the row regardless of archived state — it's
            the lookup the webhook handler uses to decide whether the
            (job_id, branch_name) tuple has ever been seen"
    (let [ds (test-ds)]
      (jbs/upsert-branch! ds "job-1" "main" "sha-1")
      (jbs/archive-branch! ds "job-1" "main")
      (let [row (jbs/get-branch ds "job-1" "main")]
        (is (some? row))
        (is (some? (:archived-at row)))))))

(deftest get-branch-returns-nil-when-absent-test
  (let [ds (test-ds)]
    (is (nil? (jbs/get-branch ds "no-such-job" "no-such-branch")))))

;; --- Codex PR #83 r2 P2: written timestamps must match migration default shape

(deftest written-timestamps-use-utc-ms-format-test
  (testing "Codex PR #83 r2 P2 — upsert (refresh path) and archive both
            WRITE last_seen_at / archived_at, so the column's UTC + ms
            text format must come from the store side (the column DEFAULT
            only fires on INSERT-with-missing-col). A bare CURRENT_TIMESTAMP
            would yield second-only in SQLite and locale-bearing text in
            Postgres, breaking cross-DB lex-ordering against the migration's
            stated guarantee. Format is exactly 'yyyy-MM-dd HH:mm:ss.SSS'."
    (let [ds (test-ds)
          fmt-re #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$"]
      (jbs/upsert-branch! ds "job-1" "main" "sha-1")
      (testing "upsert refresh path stamps last_seen_at in UTC-ms format"
        (jbs/upsert-branch! ds "job-1" "main" "sha-2")
        (let [row (jbs/get-branch ds "job-1" "main")]
          (is (re-matches fmt-re (:last-seen-at row))
              (str "last_seen_at must be UTC + ms text, got: "
                   (pr-str (:last-seen-at row))))))
      (testing "archive path stamps archived_at in UTC-ms format"
        (jbs/archive-branch! ds "job-1" "main")
        (let [row (jbs/get-branch ds "job-1" "main")]
          (is (re-matches fmt-re (:archived-at row))
              (str "archived_at must be UTC + ms text, got: "
                   (pr-str (:archived-at row))))))
      (testing "insert path also writes last_seen_at in the same format"
        (jbs/upsert-branch! ds "job-1" "feature/x" "sha-new")
        (let [row (jbs/get-branch ds "job-1" "feature/x")]
          (is (re-matches fmt-re (:last-seen-at row))))))))
