(ns chengis.db.job-branches-store
  "Database persistence for the per-job branch catalog (CHG-FEAT-003 PR1).

   One row per (job_id, branch_name). The SCM-discovery layer calls
   `upsert-branch!` for every branch it sees; that idempotent contract is
   what lets the discovery loop run on a schedule (or react to webhooks)
   without leaving duplicate rows or losing prior `discovered_at`
   timestamps.

   Lifecycle rules:
     * `upsert-branch!`   — insert on first sight, otherwise refresh
                            `head_sha` + `last_seen_at`. Also clears
                            `archived_at` so a branch that was previously
                            deleted upstream and is now re-created cleanly
                            transitions back to active without manual
                            intervention.
     * `archive-branch!`  — soft-delete: sets `archived_at` so the row stays
                            in the table (per-branch build numbering in PR3
                            needs the historical row to keep build numbers
                            monotonically increasing across delete+recreate
                            cycles). The branch is then excluded from
                            `list-active-branches`.

   Conventions: ds first, kebab-map result rows (`rs/as-unqualified-kebab-maps`),
   HoneySQL for query construction. No org-id scoping at this layer — job_id
   is already org-scoped one level up via the jobs table.

   Webhook/scheduler/executor wiring is deliberately out of scope: PR1 only
   establishes the persistence + lookup contract that later PRs build on."
  (:require [chengis.util :as util]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private ^DateTimeFormatter db-timestamp-formatter
  "Matches the UTC + millisecond text default from migration 099 (which in
   turn mirrors migration 098). last_seen_at / archived_at are written by
   this namespace via UPDATE, NOT by the column DEFAULT, so we must format
   the value to the same shape — otherwise SQLite stores a bare
   CURRENT_TIMESTAMP (second-only) and Postgres stores a session-locale /
   timezone-bearing string, both of which break the cross-DB lexical
   ordering the migration's defaults guarantee (Codex PR #83 r2 P2)."
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS"))

(defn- db-now
  "UTC + millisecond timestamp string in the format used by the table's
   created_at-style defaults."
  []
  (.format db-timestamp-formatter (.atZone (Instant/now) ZoneOffset/UTC)))

(defn upsert-branch!
  "Insert a branch row for `job-id`+`branch-name`, or — if one already
   exists — refresh its `head_sha` + `last_seen_at` and clear any prior
   `archived_at` (a re-created branch comes back active).

   Returns the row id (newly minted on insert; existing id on update).
   The two-statement implementation (SELECT-then-INSERT-or-UPDATE) keeps the
   surface portable across SQLite + Postgres without depending on
   RETURNING-on-ON-CONFLICT (SQLite only added RETURNING in 3.35 and
   `EXCLUDED.*` rewrites for the DO UPDATE branch differ between dialects).
   The UNIQUE(job_id, branch_name) constraint on the table is the source of
   truth — concurrent inserts will surface as a constraint violation that
   the caller can retry."
  [ds job-id branch-name head-sha]
  (let [existing (jdbc/execute-one!
                  ds
                  (sql/format {:select [:id]
                               :from [:job-branches]
                               :where [:and
                                       [:= :job-id job-id]
                                       [:= :branch-name branch-name]]})
                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (if existing
      (do
        (jdbc/execute-one!
         ds
         (sql/format {:update :job-branches
                      :set    {:head-sha     head-sha
                               :last-seen-at (db-now)
                               :archived-at  nil}
                      :where  [:= :id (:id existing)]}))
        (:id existing))
      (let [id (util/generate-id)]
        (jdbc/execute-one!
         ds
         (sql/format {:insert-into :job-branches
                      :values [{:id           id
                                :job-id       job-id
                                :branch-name  branch-name
                                :head-sha     head-sha
                                :last-seen-at (db-now)}]}))
        id))))

(defn list-active-branches
  "Return all branches for `job-id` that are still present on the remote
   (`archived_at IS NULL`), ordered by `branch_name` for stable UI
   rendering. Archived branches are excluded so callers iterating to fire
   per-branch builds don't accidentally schedule work on a deleted branch."
  [ds job-id]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:job-branches]
                              :where    [:and
                                         [:= :job-id job-id]
                                         [:= :archived-at nil]]
                              :order-by [[:branch-name :asc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-all-branches
  "Return ALL branches for `job-id` — including soft-archived rows —
   ordered by `branch_name`. Unlike `list-active-branches`, this view
   surfaces `archived_at` so retention/cleanup paths can decide
   whether a row is past its sweep cutoff. PR4 workspace retention
   uses this to walk on-disk per-branch trees and match them back to
   their (possibly archived) DB rows."
  [ds job-id]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:job-branches]
                              :where    [:= :job-id job-id]
                              :order-by [[:branch-name :asc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-archived-branches
  "Return only soft-archived branches for `job-id` (`archived_at IS NOT
   NULL`), ordered by `archived_at` DESC so the most-recently-disappeared
   branches surface first in the UI. Backs the PR8 'Archived branches'
   collapsed section on the job-detail multibranch panel."
  [ds job-id]
  (jdbc/execute! ds
                 (sql/format {:select   [:*]
                              :from     [:job-branches]
                              :where    [:and
                                         [:= :job-id job-id]
                                         [:not= :archived-at nil]]
                              :order-by [[:archived-at :desc]
                                         [:branch-name :asc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn archive-branch!
  "Soft-delete a branch by stamping `archived_at`. The row is preserved so
   PR3's per-branch build numbering can stay monotonic across a
   delete-then-recreate cycle (a new branch with the same name re-uses the
   historical sequence rather than restarting at #1, which would collide
   with prior build artifacts).

   Idempotent: re-archiving an already-archived branch just refreshes the
   timestamp; archiving a non-existent branch is a no-op."
  [ds job-id branch-name]
  (jdbc/execute-one!
   ds
   (sql/format {:update :job-branches
                :set    {:archived-at (db-now)}
                :where  [:and
                         [:= :job-id job-id]
                         [:= :branch-name branch-name]]})))

(defn get-branch
  "Look up a single (job_id, branch_name) row. Returns nil when absent.

   Returns the row whether or not it's archived — callers that want the
   active-only view should use `list-active-branches`. This is the lookup
   the webhook handler (PR2) will use to decide whether an incoming push
   refers to a known branch."
  [ds job-id branch-name]
  (jdbc/execute-one! ds
                     (sql/format {:select [:*]
                                  :from   [:job-branches]
                                  :where  [:and
                                           [:= :job-id job-id]
                                           [:= :branch-name branch-name]]})
                     {:builder-fn rs/as-unqualified-kebab-maps}))
