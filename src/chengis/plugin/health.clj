(ns chengis.plugin.health
  "Descriptive plugin-health signals and the rules that turn them into an
   AUTO-quarantine decision (M3b).

   Pure logic — no DB, no scheduling, no IO beyond reading the file mtime /
   manifest values the caller passes in — so it is straightforward to test. The
   actual sweep (enumerate the plugins dir, apply quarantine, schedule) lives in
   chengis.engine.plugin-health.

   Auto vs manual: every reason this namespace produces starts with
   `auto-reason-prefix`. The sweep only ever LIFTS quarantines whose reason
   carries that prefix, so a manual operator quarantine (no prefix) is never
   touched, and an operator who manually un-quarantines isn't immediately
   re-quarantined unless the plugin still trips an auto rule."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def auto-reason-prefix
  "Marker prefixing every auto-generated quarantine reason."
  "auto:")

(defn auto-quarantine?
  "True if `reason` was set by the auto-sweep (vs a manual operator quarantine)."
  [reason]
  (boolean (and reason (str/starts-with? reason auto-reason-prefix))))

(defn days-since
  "Whole days between epoch-millis `then` and `now` (negative clamped to 0)."
  [then now]
  (max 0 (quot (- now then) 86400000)))

(defn stale-reason
  "An auto-quarantine reason string when the plugin file is older than
   `staleness-days`, else nil. `mtime` is the .clj file's last-modified millis;
   `now` is current epoch millis. A nil/zero `staleness-days` disables the rule."
  [mtime staleness-days now]
  (when (and (number? staleness-days) (pos? staleness-days) mtime)
    (let [age (days-since mtime now)]
      (when (> age staleness-days)
        (str auto-reason-prefix " stale — no update in " age "d (>" staleness-days "d)")))))

(defn advisory-reason
  "An auto-quarantine reason when `plugin-name`@`version` matches an entry in
   `advisories`, else nil. Each advisory is a map
     {:plugin <name> :id <ident> :reason <text>? :versions [<v> ...]?}
   matching when the plugin name is equal AND (no :versions, meaning all
   versions, OR `version` is listed in :versions)."
  [plugin-name version advisories]
  (some (fn [{:keys [plugin id reason versions]}]
          (when (and (= plugin plugin-name)
                     (or (empty? versions)
                         (some #(= % version) versions)))
            (str auto-reason-prefix " advisory " id
                 (when (seq reason) (str " — " reason)))))
        advisories))

(defn load-advisories
  "Resolve the advisory list from a :plugin-health config map. An inline
   `:advisories` vector takes precedence; otherwise an EDN vector is read from
   `:advisories-path`. Returns [] on any problem (missing/malformed file), so a
   bad advisories file degrades to 'no advisories' rather than breaking the
   sweep."
  [{:keys [advisories advisories-path]}]
  (cond
    (seq advisories) (vec advisories)
    advisories-path  (try
                       (let [v (edn/read-string (slurp advisories-path))]
                         (if (sequential? v) (vec v) []))
                       (catch Exception _ []))
    :else            []))

(defn quarantine-reason
  "The auto-quarantine reason for a plugin, or nil if healthy. Staleness is
   checked first, then advisories. `mtime` is the .clj last-modified millis,
   `version` the manifest version (may be nil)."
  [{:keys [plugin-name version mtime]} {:keys [staleness-days] :as cfg} advisories now]
  (or (stale-reason mtime staleness-days now)
      (advisory-reason plugin-name version advisories)))
