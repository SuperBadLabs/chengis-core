(ns chengis.plugin.manifest
  "Plugin manifest format and validation (M1c).

   Every external plugin MAY ship a sidecar manifest `<plugin>.edn` next to its
   `<plugin>.clj`. The manifest declares, up front and inspectably, what the
   plugin is and what host capabilities it needs — so the sandbox can grant
   exactly that and nothing more (declared-capability model from the strategy
   doc, §4.3/§5).

   Manifest schema (all keys optional unless noted):

     {:name          \"my-notifier\"      ; SHOULD match the .clj base name
      :version       \"1.0.0\"
      :description   \"Posts build results to my chat\"
      :provides      [:notifier]          ; extension points it registers
      :capabilities  [:http :log]         ; host fns it needs (subset of
                                           ;   chengis.plugin.sci/known-capabilities)
      :requests-trust :sandboxed}         ; OPTIONAL self-restriction; see below

   Trust is NOT granted by the manifest. `plugin-policy-store` is authoritative.
   A plugin may only *lower* its own privilege via `:requests-trust :sandboxed`
   (least-privilege opt-in); it can never request its way INTO the trusted lane.
   See `effective-trust`.

   Validation is non-fatal by design: a malformed or over-reaching manifest is
   sanitized (unknown capabilities dropped, bad EDN ignored) and the plugin still
   loads with the safe subset. Problems are returned as `:warnings` for the
   loader to log — a bad manifest must never silently escalate privilege, but it
   also shouldn't be a foot-gun that bricks an otherwise-fine plugin."
  (:require [chengis.plugin.sci :as plugin-sci]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:private valid-trust #{:trusted :sandboxed})

(defn manifest-file
  "The expected manifest File for a plugin .clj File (sibling `<plugin>.edn`)."
  ^java.io.File [^java.io.File clj-file]
  (io/file (.getParentFile clj-file)
           (str/replace (.getName clj-file) #"\.clj$" ".edn")))

(defn- plugin-base-name [^java.io.File clj-file]
  (str/replace (.getName clj-file) #"\.clj$" ""))

(defn validate
  "Validate a raw manifest map for `plugin-name`. Returns
     {:capabilities #{..known..}  :requests-trust :trusted|:sandboxed|nil
      :provides [..] :name .. :version .. :description ..
      :warnings [..strings..]}
   Unknown/over-reaching values are sanitized out; reasons land in :warnings."
  [raw plugin-name]
  (let [raw            (if (map? raw) raw {})
        declared-caps  (set (:capabilities raw))
        known          plugin-sci/known-capabilities
        granted        (into #{} (filter known) declared-caps)
        unknown        (remove known declared-caps)
        req-trust      (:requests-trust raw)
        req-trust*     (when (valid-trust req-trust) req-trust)
        warnings       (cond-> []
                         (seq unknown)
                         (conj (str "unknown capabilities dropped: " (vec unknown)))

                         (and (some? req-trust) (nil? req-trust*))
                         (conj (str ":requests-trust must be :trusted or :sandboxed, got "
                                    (pr-str req-trust) " (ignored)"))

                         (and (:name raw) (not= (:name raw) plugin-name))
                         (conj (str ":name \"" (:name raw) "\" does not match plugin file name \""
                                    plugin-name "\"")))]
    {:name           (:name raw)
     :version        (:version raw)
     :description    (:description raw)
     :provides       (vec (:provides raw))
     :capabilities   granted
     :requests-trust req-trust*
     :warnings       warnings}))

(defn parse
  "Validate a manifest from its raw EDN `edn-string` (nil/blank => empty
   manifest). Unreadable/invalid EDN is downgraded to the empty manifest with a
   warning — never throws. Use this when you already hold the manifest bytes
   (e.g. the exact bytes covered by a signature) to avoid re-reading the file."
  [edn-string plugin-name]
  (if (str/blank? edn-string)
    (validate {} plugin-name)
    (try
      (validate (edn/read-string edn-string) plugin-name)
      (catch Exception e
        (log/warn "Ignoring unreadable plugin manifest for" plugin-name ":" (.getMessage e))
        (assoc (validate {} plugin-name)
               :warnings [(str "unreadable manifest: " (.getMessage e))])))))

(defn read-manifest
  "Read and validate the sidecar manifest for a plugin .clj File.
   Returns a validated manifest map (see `validate`). When the manifest is
   absent, returns the empty/most-restrictive manifest. Never throws."
  [^java.io.File clj-file]
  (let [mf (manifest-file clj-file)]
    (parse (when (.isFile mf) (slurp mf)) (plugin-base-name clj-file))))

(defn effective-trust
  "Compute the trust context a plugin actually runs in.
   `policy-trust` (:trusted | :sandboxed) is the authoritative ceiling from
   plugin-policy-store. `requested` is the plugin's optional self-restriction.
   Result is the MORE RESTRICTIVE of the two: a plugin can drop to :sandboxed,
   but can never lift itself to :trusted."
  [policy-trust requested]
  (if (or (= policy-trust :sandboxed) (= requested :sandboxed))
    :sandboxed
    :trusted))
