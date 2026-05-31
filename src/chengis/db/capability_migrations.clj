(ns chengis.db.capability-migrations
  "Filter database migrations by enabled product capabilities.

   Each migration is tagged with a capability key in
   `capability-manifest.edn` (alongside the migration .sql files). The
   runner applies only migrations whose capability is in the active
   effective set; untagged migrations are 'core' and always run.

   This is the schema-fork mechanism for the preferred-shape +
   optionality model:

     - Anvil-default install (SQLite, no opt-ins) gets *only* core
       migrations. The schema doesn't carry empty enterprise tables
       like `organizations` or `audit_events`.
     - Anvil-on-PG with `:enable #{:audit-chain}` gets core + the
       audit-chain migrations. Tables exist; the audit subsystem can
       require-capability! and use them.
     - Chengis-default gets core + everything tagged for capabilities
       in the chengis default set (which is most of them).

   The manifest is the single source of truth. Migration filenames
   stay untouched (so existing schema_migrations rows continue to
   match) and the filter is purely a runtime selection layer.

   This namespace deliberately *does not* call into migratus — it's
   the pure-data layer the runner integrates against. Wiring the
   filter through `chengis.db.migrate/migrate!` is the next step in
   Spike 4."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [chengis.product.capability :as cap]))

;; ---------------------------------------------------------------------------
;; Manifest IO
;; ---------------------------------------------------------------------------

(def ^:private manifest-filename "capability-manifest.edn")

(defn manifest-resource-path
  "Classpath resource path for a given db-type. Mirrors migratus's
   `migrations/<dbtype>/` layout."
  [db-type]
  (str "migrations/" (name db-type) "/" manifest-filename))

(defn read-manifest
  "Load and validate a capability manifest from disk (an io/file or
   path) or from the classpath (a resource path).

   Returns nil if the manifest doesn't exist — that's the legal 'no
   tagging yet' state where every migration is core. Throws on a
   malformed manifest."
  [source]
  (let [content (cond
                  (instance? java.io.File source)
                  (when (.exists ^java.io.File source) (slurp source))

                  (string? source)
                  (let [f (io/file source)]
                    (cond
                      (.exists f)             (slurp f)
                      (io/resource source)    (slurp (io/resource source))
                      :else                   nil))

                  (instance? java.net.URL source)
                  (slurp source)

                  :else nil)]
    (when content
      (let [data (try (edn/read-string {:readers {}} content)
                      (catch Exception e
                        (throw (ex-info (str "Failed to parse capability manifest: "
                                             (.getMessage e))
                                        {:type :capability-manifest-parse-error
                                         :source (str source)}))))]
        (when-not (map? data)
          (throw (ex-info "Capability manifest must be a map"
                          {:type :capability-manifest-invalid
                           :got (type data)})))
        (when-not (= 1 (:version data))
          (throw (ex-info (str "Unsupported manifest :version — got "
                               (pr-str (:version data)) ", want 1")
                          {:type :capability-manifest-version-mismatch
                           :version (:version data)})))
        (let [tags (or (:tags data) {})]
          (when-not (map? tags)
            (throw (ex-info ":tags must be a map of migration-id → capability-key"
                            {:type :capability-manifest-invalid-tags
                             :got (type tags)})))
          (doseq [[mid cap-key] tags]
            (when-not (string? mid)
              (throw (ex-info "Migration id keys in :tags must be strings"
                              {:type :capability-manifest-invalid-id
                               :id mid})))
            (when-not (keyword? cap-key)
              (throw (ex-info "Capability values in :tags must be keywords"
                              {:type :capability-manifest-invalid-capability
                               :id mid :value cap-key})))))
        data))))

(defn validate-manifest-against-registry!
  "Confirm every capability referenced in the manifest is known to
   `chengis.product.capability`. Throws otherwise — typos in the
   manifest are easier to catch at boot than at the point of
   migration."
  [manifest]
  (let [tags (or (:tags manifest) {})
        unknown (->> tags
                     vals
                     (remove #{:core})
                     (remove cap/known?)
                     set)]
    (when (seq unknown)
      (throw (ex-info (str "Capability manifest references unknown capabilities: "
                           (pr-str unknown)
                           ". Register them via chengis.product.capability/register-capability! "
                           "or fix the typo.")
                      {:type :capability-manifest-unknown-capability
                       :unknown unknown
                       :known (set (keys (cap/registry-snapshot)))})))))

;; ---------------------------------------------------------------------------
;; Filtering — pure
;; ---------------------------------------------------------------------------

(defn migration-applies?
  "True iff the migration identified by `migration-id` should be
   applied given the manifest + enabled-capabilities set.

   Decision rule:
     - Untagged migration       → core → always applies
     - Tagged with :core        → always applies
     - Tagged with capability X → applies iff X ∈ enabled-caps"
  [manifest migration-id enabled-caps]
  (let [tag (get-in manifest [:tags migration-id])]
    (cond
      (nil? tag)        true
      (= :core tag)     true
      :else             (contains? (set enabled-caps) tag))))

(defn filter-migrations
  "Given the manifest, the ordered list of `migration-ids` migratus
   would normally apply, and the set of enabled capabilities, return
   the ordered subset to actually apply. Order is preserved."
  [manifest migration-ids enabled-caps]
  (vec (filter #(migration-applies? manifest % enabled-caps) migration-ids)))

(defn classify-migrations
  "Diagnostic: split `migration-ids` into core, gated-on, gated-off
   buckets given the manifest + enabled caps. Used by boot logging
   and `--list-migrations`."
  [manifest migration-ids enabled-caps]
  (let [caps (set enabled-caps)
        tags (or (:tags manifest) {})]
    (reduce
     (fn [acc mid]
       (let [tag (get tags mid)]
         (cond
           (or (nil? tag) (= :core tag))
           (update acc :core conj mid)

           (contains? caps tag)
           (update acc :gated-on conj {:id mid :capability tag})

           :else
           (update acc :gated-off conj {:id mid :capability tag}))))
     {:core [] :gated-on [] :gated-off []}
     migration-ids)))

;; ---------------------------------------------------------------------------
;; Convenience — chengis-core's own bundled migrations
;; ---------------------------------------------------------------------------

(defn load-bundled-manifest
  "Load the manifest shipped with chengis-core for the given db-type
   (\"sqlite\" or \"postgresql\"). Returns nil if no manifest is
   present yet — the current bulk-tagging is in progress."
  [db-type]
  (let [path (manifest-resource-path db-type)]
    (when-let [url (io/resource path)]
      (read-manifest url))))
