(ns chengis.db.store
  "Macro to generate standard CRUD boilerplate for database stores.)

  Usage example:

    (ns chengis.db.widget-store
      (:require [chengis.db.store :refer [defstore]]))

    (defstore widget
      :table          :widgets
      :org-scoped?    true
      :order-by       [[:name :asc]]
      :updatable-keys #{:name :description :enabled}
      :transform      normalize-widget)   ; optional row post-processor

  Generated functions (entity = 'widget', plural = 'widgets'):
    create-widget!   [ds attrs]
    get-widget       [ds id] | [ds id & {:keys [org-id]}]
    list-widgets     [ds]    | [ds & {:keys [org-id]}]
    update-widget!   [ds id updates]
    delete-widget!   [ds id] | [ds id & {:keys [org-id]}]
    count-widgets    [ds]    | [ds & {:keys [org-id]}]

  Options:
    :table           (required) HoneySQL table keyword, e.g. :pipeline-templates
    :org-scoped?     (default false) when true, get/list/delete/count accept
                     an optional trailing :org-id keyword arg that scopes the
                     query to a single organisation
    :order-by        (default [[:created-at :desc]]) HoneySQL order-by clause
                     used by list-<entity>s
    :updatable-keys  (optional) #{...} whitelist of keys permitted in update!;
                     when omitted the update map is passed through as-is
    :transform       (optional) 1-arg function applied to every returned row
                     (e.g. to coerce JSON columns or normalise boolean fields)

  Any generated function can be replaced by a plain defn that follows the
  defstore call — later defs override earlier ones in Clojure.
  Custom queries that are not covered by the macro can be added freely after
  the defstore call."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defmacro defstore
  "Generate standard CRUD functions for a database entity.
  See `chengis.db.store` namespace docstring for full documentation."
  [entity-sym & {:keys [table org-scoped? order-by updatable-keys transform]
                 :or   {org-scoped? false
                        order-by    [[:created-at :desc]]}}]
  (when (nil? table)
    (throw (IllegalArgumentException.
            (str "defstore " entity-sym " requires :table"))))
  (let [ename    (name entity-sym)
        plural   (str ename "s")
        create!  (symbol (str "create-" ename "!"))
        get-fn   (symbol (str "get-" ename))
        list-fn  (symbol (str "list-" plural))
        update!  (symbol (str "update-" ename "!"))
        delete!  (symbol (str "delete-" ename "!"))
        count-fn (symbol (str "count-" plural))
        ;; wrap-xf: at macro-expand time, wraps a single-row expression with
        ;; the transform function when :transform was provided.
        wrap-xf  (fn [expr] (if transform `(~transform ~expr) expr))]
    `(do
       ;; ── create ──────────────────────────────────────────────────────────
       (defn ~create!
         ~(str "Insert a new " ename ". Generates :id if not provided. "
               "Returns the created row.")
         [~'ds ~'attrs]
         (let [~'row-id (or (:id ~'attrs) (chengis.util/generate-id))
               ~'row    (assoc ~'attrs :id ~'row-id)]
           (next.jdbc/execute-one! ~'ds
                                   (honey.sql/format {:insert-into ~table :values [~'row]}))
           ~(wrap-xf
             `(next.jdbc/execute-one! ~'ds
                                      (honey.sql/format {:select :* :from ~table
                                                         :where  [:= :id ~'row-id]})
                                      {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps}))))

       ;; ── get ─────────────────────────────────────────────────────────────
       ~(if org-scoped?
          `(defn ~get-fn
             ~(str "Get a " ename " by id. "
                   "Optional :org-id verifies ownership.")
             [~'ds ~'id & {:keys [~'org-id]}]
             ~(wrap-xf
               `(next.jdbc/execute-one! ~'ds
                                        (honey.sql/format
                                         {:select :* :from ~table
                                          :where  (if ~'org-id
                                                    [:and [:= :id ~'id] [:= :org-id ~'org-id]]
                                                    [:= :id ~'id])})
                                        {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps})))
          `(defn ~get-fn
             ~(str "Get a " ename " by id.")
             [~'ds ~'id]
             ~(wrap-xf
               `(next.jdbc/execute-one! ~'ds
                                        (honey.sql/format {:select :* :from ~table
                                                           :where  [:= :id ~'id]})
                                        {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps}))))

       ;; ── list ────────────────────────────────────────────────────────────
       ~(if org-scoped?
          `(defn ~list-fn
             ~(str "List " plural ". Optional :org-id filters by org.")
             [~'ds & {:keys [~'org-id]}]
             ~(if transform
                `(mapv ~transform
                       (next.jdbc/execute! ~'ds
                                           (honey.sql/format
                                            (cond-> {:select :* :from ~table :order-by ~order-by}
                                              ~'org-id (assoc :where [:= :org-id ~'org-id])))
                                           {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps}))
                `(next.jdbc/execute! ~'ds
                                     (honey.sql/format
                                      (cond-> {:select :* :from ~table :order-by ~order-by}
                                        ~'org-id (assoc :where [:= :org-id ~'org-id])))
                                     {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps})))
          `(defn ~list-fn
             ~(str "List all " plural ".")
             [~'ds]
             ~(if transform
                `(mapv ~transform
                       (next.jdbc/execute! ~'ds
                                           (honey.sql/format {:select :* :from ~table :order-by ~order-by})
                                           {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps}))
                `(next.jdbc/execute! ~'ds
                                     (honey.sql/format {:select :* :from ~table :order-by ~order-by})
                                     {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps}))))

       ;; ── update ──────────────────────────────────────────────────────────
       ~(if (seq updatable-keys)
          `(defn ~update!
             ~(str "Update a " ename ". "
                   "Allowed keys: " (pr-str updatable-keys))
             [~'ds ~'id ~'updates]
             (let [~'safe (-> (select-keys ~'updates ~updatable-keys)
                              (assoc :updated-at [:raw "CURRENT_TIMESTAMP"]))]
               (when (seq (dissoc ~'safe :updated-at))
                 (next.jdbc/execute-one! ~'ds
                                         (honey.sql/format {:update ~table
                                                            :set    ~'safe
                                                            :where  [:= :id ~'id]})))
               (~get-fn ~'ds ~'id)))
          `(defn ~update!
             ~(str "Update a " ename ".")
             [~'ds ~'id ~'updates]
             (next.jdbc/execute-one! ~'ds
                                     (honey.sql/format
                                      {:update ~table
                                       :set    (assoc ~'updates :updated-at [:raw "CURRENT_TIMESTAMP"])
                                       :where  [:= :id ~'id]}))
             (~get-fn ~'ds ~'id)))

       ;; ── delete ──────────────────────────────────────────────────────────
       ~(if org-scoped?
          `(defn ~delete!
             ~(str "Delete a " ename ". "
                   "Optional :org-id verifies ownership.")
             [~'ds ~'id & {:keys [~'org-id]}]
             (let [~'result (next.jdbc/execute-one! ~'ds
                                                    (honey.sql/format
                                                     {:delete-from ~table
                                                      :where       (if ~'org-id
                                                                     [:and [:= :id ~'id] [:= :org-id ~'org-id]]
                                                                     [:= :id ~'id])}))]
               (pos? (or (:next.jdbc/update-count ~'result) 0))))
          `(defn ~delete!
             ~(str "Delete a " ename " by id.")
             [~'ds ~'id]
             (next.jdbc/execute-one! ~'ds
                                     (honey.sql/format {:delete-from ~table :where [:= :id ~'id]}))))

       ;; ── count ───────────────────────────────────────────────────────────
       ~(if org-scoped?
          `(defn ~count-fn
             ~(str "Count " plural ". Optional :org-id scopes to org.")
             [~'ds & {:keys [~'org-id]}]
             (:count
              (next.jdbc/execute-one! ~'ds
                                      (honey.sql/format
                                       (cond-> {:select [[[:count :*] :count]] :from ~table}
                                         ~'org-id (assoc :where [:= :org-id ~'org-id])))
                                      {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps})))
          `(defn ~count-fn
             ~(str "Count all " plural ".")
             [~'ds]
             (:count
              (next.jdbc/execute-one! ~'ds
                                      (honey.sql/format {:select [[[:count :*] :count]] :from ~table})
                                      {:builder-fn next.jdbc.result-set/as-unqualified-kebab-maps})))))))
