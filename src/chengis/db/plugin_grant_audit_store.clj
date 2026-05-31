(ns chengis.db.plugin-grant-audit-store
  "Persistent, append-only audit trail of plugin capability grants (M3c).

   Every external-plugin load records what it was actually granted — trust
   context, capabilities, and signing provenance (which key signed, if any) —
   into `plugin_grant_audit`. This complements chengis.plugin.registry, which
   holds only the CURRENT in-memory grant per plugin (lost on restart,
   overwritten on reload). The table is the durable stream governance needs:
   'who loaded what, when, with which trust/capabilities', ordered for an admin
   feed and surviving restarts.

   Append-only: rows are inserted, never updated. Capabilities are stored as an
   EDN vector string and parsed back on read."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.edn :as edn]))

(defn record-grant-audit!
  "Append one grant-audit row. `grant` keys:
     :org-id       org scope (nil for the default/global org)
     :plugin-name  string
     :trust-level  :trusted | :sandboxed (keyword) or the equivalent string
     :capabilities seq of capability keywords/strings
     :signed?      boolean — did a valid signature verify
     :signed-by    key-id of the verifying key, or nil
   Returns nil. Caller is expected to wrap in try/catch so a DB hiccup never
   blocks plugin loading."
  [ds {:keys [org-id plugin-name trust-level capabilities signed? signed-by]}]
  (jdbc/execute-one!
   ds
   (sql/format {:insert-into :plugin-grant-audit
                :values [{:org-id       org-id
                          :plugin-name  plugin-name
                          ;; accept a keyword (:trusted) or a string
                          :trust-level  (some-> trust-level name)
                          :capabilities (pr-str (vec capabilities))
                          :signed       (if signed? 1 0)
                          :signed-by    signed-by}]}))
  nil)

(defn- parse-capabilities
  "Read the stored EDN capabilities string back to a vector; tolerant of
   malformed/legacy values (=> empty vector)."
  [s]
  (try
    (let [v (edn/read-string s)]
      (if (sequential? v) (vec v) []))
    (catch Exception _ [])))

(defn list-grant-audit
  "Grant-audit entries, newest first. :limit caps the rows returned (default
   200). `:capabilities` is parsed back to a vector and `:signed` coerced to a
   boolean.

   Org scoping: when :org-id is given, returns that org's rows AND the
   GLOBAL (org_id IS NULL) rows. External plugins loaded at server startup are
   recorded with org_id NULL (chengis.plugin.loader/load-plugins! passes
   :org-id nil — startup has no org context), so an org-scoped admin view would
   otherwise never see them. Including the NULL scope surfaces those global
   grants without leaking other orgs' rows."
  [ds & {:keys [org-id limit] :or {limit 200}}]
  (let [where (if org-id
                [:or [:= :org-id org-id] [:= :org-id nil]]
                [:= 1 1])]
    (mapv (fn [row]
            (-> row
                (update :signed #(= 1 %))
                (update :capabilities parse-capabilities)))
          (jdbc/execute! ds
                         (sql/format {:select   :*
                                      :from     :plugin-grant-audit
                                      :where    where
                                      :order-by [[:granted-at :desc] [:id :desc]]
                                      :limit    limit})
                         {:builder-fn rs/as-unqualified-kebab-maps}))))
