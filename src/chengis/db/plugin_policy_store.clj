(ns chengis.db.plugin-policy-store
  "CRUD for plugin trust policies.
   Controls which external plugins are allowed to load, scoped by org."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn get-plugin-policy
  "Get the policy for a named plugin. Returns nil if no policy exists."
  [ds plugin-name & {:keys [org-id]}]
  (jdbc/execute-one! ds
                     (sql/format {:select :*
                                  :from :plugin-policies
                                  :where [:and
                                          [:= :plugin-name plugin-name]
                                          [:= :org-id org-id]]})
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn set-plugin-policy!
  "Create or update a plugin policy (upsert by org_id + plugin_name).
   Keys: :org-id, :plugin-name, :trust-level, :allowed, :created-by."
  [ds {:keys [org-id plugin-name trust-level allowed created-by]}]
  (let [existing (get-plugin-policy ds plugin-name :org-id org-id)]
    (if existing
      ;; Update
      (do (jdbc/execute-one! ds
                             (sql/format {:update :plugin-policies
                                          :set {:trust-level (or trust-level "untrusted")
                                                :allowed (if allowed 1 0)
                                                :updated-at (str (java.time.Instant/now))}
                                          :where [:= :id (:id existing)]}))
          (assoc existing
                 :trust-level (or trust-level (:trust-level existing))
                 :allowed (boolean allowed)))
      ;; Insert
      (let [id (util/generate-id)]
        (jdbc/execute-one! ds
                           (sql/format {:insert-into :plugin-policies
                                        :values [{:id id
                                                  :org-id org-id
                                                  :plugin-name plugin-name
                                                  :trust-level (or trust-level "untrusted")
                                                  :allowed (if allowed 1 0)
                                                  :created-by created-by}]}))
        {:id id :org-id org-id :plugin-name plugin-name
         :trust-level (or trust-level "untrusted")
         :allowed (boolean allowed) :created-by created-by}))))

(defn list-plugin-policies
  "List all plugin policies, optionally filtered by org."
  [ds & {:keys [org-id]}]
  (let [where (if org-id
                [:= :org-id org-id]
                [:= 1 1])]
    (mapv (fn [row]
            (-> row
                (update :allowed #(= 1 %))
                (update :quarantined #(= 1 %))))
          (jdbc/execute! ds
                         (sql/format {:select :*
                                      :from :plugin-policies
                                      :where where
                                      :order-by [[:plugin-name :asc]]})
                         {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn delete-plugin-policy!
  "Delete a plugin policy by plugin name and org."
  [ds plugin-name & {:keys [org-id]}]
  (jdbc/execute-one! ds
                     (sql/format {:delete-from :plugin-policies
                                  :where [:and
                                          [:= :plugin-name plugin-name]
                                          [:= :org-id org-id]]})))

(defn plugin-allowed?
  "Check whether a plugin is allowed to load for the given org.
   Returns true if an explicit policy with allowed=true exists.
   Returns false if no policy exists or policy has allowed=false."
  [ds plugin-name & {:keys [org-id]}]
  (let [policy (get-plugin-policy ds plugin-name :org-id org-id)]
    (boolean (and policy (= 1 (:allowed policy))))))

;; ---------------------------------------------------------------------------
;; Quarantine (M2b) — hard-block a plugin with an auditable reason, independent
;; of the allowlist. Enforced at load by chengis.plugin.loader.
;; ---------------------------------------------------------------------------

(defn quarantine-plugin!
  "Mark a plugin quarantined with a `reason` (e.g. \"vulnerable\", \"stale\",
   \"deprecated\"). A quarantined plugin is refused at load regardless of
   allowlist/trust. Upserts the policy row (creating one if none exists)."
  [ds plugin-name reason & {:keys [org-id]}]
  (if-let [existing (get-plugin-policy ds plugin-name :org-id org-id)]
    (jdbc/execute-one! ds
                       (sql/format {:update :plugin-policies
                                    :set {:quarantined 1
                                          :quarantine-reason reason
                                          :updated-at (str (java.time.Instant/now))}
                                    :where [:= :id (:id existing)]}))
    (jdbc/execute-one! ds
                       (sql/format {:insert-into :plugin-policies
                                    :values [{:id (util/generate-id)
                                              :org-id org-id
                                              :plugin-name plugin-name
                                              :trust-level "untrusted"
                                              :allowed 0
                                              :quarantined 1
                                              :quarantine-reason reason}]})))
  nil)

(defn unquarantine-plugin!
  "Clear a plugin's quarantine (leaves allowlist/trust untouched). No-op if no
   policy exists."
  [ds plugin-name & {:keys [org-id]}]
  (when-let [existing (get-plugin-policy ds plugin-name :org-id org-id)]
    (jdbc/execute-one! ds
                       (sql/format {:update :plugin-policies
                                    :set {:quarantined 0
                                          :quarantine-reason nil
                                          :updated-at (str (java.time.Instant/now))}
                                    :where [:= :id (:id existing)]})))
  nil)

(defn quarantined?
  "True if the plugin has a policy marking it quarantined."
  [ds plugin-name & {:keys [org-id]}]
  (let [policy (get-plugin-policy ds plugin-name :org-id org-id)]
    (boolean (and policy (= 1 (:quarantined policy))))))
