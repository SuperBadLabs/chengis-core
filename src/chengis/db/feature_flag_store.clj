(ns chengis.db.feature-flag-store
  "CRUD operations for the feature_flags table.
   Supports global flags (org_id_override = nil) and per-org overrides.
   Lookup order: org-specific row → global row → nil."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- row->flag
  "Normalize a DB row to a plain map with boolean :enabled."
  [row]
  (when row
    (-> row
        (update :enabled #(if (number? %) (pos? %) (boolean %))))))

;; ---------------------------------------------------------------------------
;; Read
;; ---------------------------------------------------------------------------

(defn get-flag
  "Get a single flag by name. If org-id is provided, returns the org-specific
   row when it exists, otherwise falls back to the global row. Returns nil when
   no row is found."
  ([ds flag-name]
   (get-flag ds flag-name nil))
  ([ds flag-name org-id]
   (if org-id
     ;; Try org-specific first, fall back to global
     (or (row->flag
          (jdbc/execute-one! ds
                             (sql/format {:select :*
                                          :from :feature-flags
                                          :where [:and [:= :flag-name flag-name]
                                                  [:= :org-id-override org-id]]})
                             {:builder-fn rs/as-unqualified-kebab-maps}))
         (get-flag ds flag-name nil))
     (row->flag
      (jdbc/execute-one! ds
                         (sql/format {:select :*
                                      :from :feature-flags
                                      :where [:and [:= :flag-name flag-name]
                                              [:is :org-id-override nil]]})
                         {:builder-fn rs/as-unqualified-kebab-maps})))))

(defn list-flags
  "List all feature flag rows, optionally filtered to a specific org's effective
   flags (global + org overrides)."
  ([ds]
   (mapv row->flag
         (jdbc/execute! ds
                        (sql/format {:select :*
                                     :from :feature-flags
                                     :order-by [[:flag-name :asc] [:org-id-override :asc]]})
                        {:builder-fn rs/as-unqualified-kebab-maps})))
  ([ds org-id]
   (mapv row->flag
         (jdbc/execute! ds
                        (sql/format {:select :*
                                     :from :feature-flags
                                     :where [:or [:is :org-id-override nil]
                                             [:= :org-id-override org-id]]
                                     :order-by [[:flag-name :asc] [:org-id-override :asc]]})
                        {:builder-fn rs/as-unqualified-kebab-maps}))))

;; ---------------------------------------------------------------------------
;; Internal: exact-match lookup (no fallback)
;; ---------------------------------------------------------------------------

(defn- get-flag-exact
  "Return the row that exactly matches (flag-name, org-id-override).
   Does NOT fall back to the global row when org-id-override is set."
  [ds flag-name org-id-override]
  (row->flag
   (jdbc/execute-one! ds
                      (sql/format {:select :*
                                   :from :feature-flags
                                   :where [:and [:= :flag-name flag-name]
                                           (if org-id-override
                                             [:= :org-id-override org-id-override]
                                             [:is :org-id-override nil])]})
                      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Upsert
;; ---------------------------------------------------------------------------

(defn upsert-flag!
  "Create or update a feature flag.
   flag-map keys: :flag-name (required), :enabled, :org-id-override, :percentage-rollout
   Returns the updated row."
  [ds flag-map]
  (let [{:keys [flag-name enabled org-id-override percentage-rollout]
         :or   {enabled false percentage-rollout 100}} flag-map
        _ (assert (string? flag-name) "flag-name is required")
        existing (get-flag-exact ds flag-name org-id-override)]
    (if existing
      (do
        (jdbc/execute-one! ds
                           (sql/format {:update :feature-flags
                                        :set {:enabled (if enabled 1 0)
                                              :percentage-rollout percentage-rollout
                                              :updated-at [:raw "CURRENT_TIMESTAMP"]}
                                        :where [:and [:= :flag-name flag-name]
                                                (if org-id-override
                                                  [:= :org-id-override org-id-override]
                                                  [:is :org-id-override nil])]}))
        (get-flag ds flag-name org-id-override))
      (let [id (util/generate-id)
            row (cond-> {:id id
                         :flag-name flag-name
                         :enabled (if enabled 1 0)
                         :percentage-rollout percentage-rollout}
                  org-id-override (assoc :org-id-override org-id-override))]
        (jdbc/execute-one! ds
                           (sql/format {:insert-into :feature-flags
                                        :values [row]}))
        (get-flag ds flag-name org-id-override)))))

;; ---------------------------------------------------------------------------
;; Delete
;; ---------------------------------------------------------------------------

(defn delete-flag!
  "Delete a flag row. Pass org-id to delete only the org override, nil for global."
  ([ds flag-name]
   (delete-flag! ds flag-name nil))
  ([ds flag-name org-id]
   (let [count (:next.jdbc/update-count
                (jdbc/execute-one! ds
                                   (sql/format {:delete-from :feature-flags
                                                :where [:and [:= :flag-name flag-name]
                                                        (if org-id
                                                          [:= :org-id-override org-id]
                                                          [:is :org-id-override nil])]})))]
     (when (pos? (or count 0))
       (log/info "Deleted feature flag" flag-name
                 (if org-id (str "(org: " org-id ")") "(global)")))
     (or count 0))))
