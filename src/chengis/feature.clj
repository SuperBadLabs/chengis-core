(ns chengis.feature
  "Runtime-toggleable feature flag resolution (Phase 2, item 2.9).)

   Resolution order for (enabled? system flag-key org-id):
     1. Per-org DB row (if org-id provided)
     2. Global DB row
     3. Config :feature-flags map (backward-compat with Phase 1 static flags)
     4. Default → false

   Percentage rollout uses a deterministic hash of (org-id, flag-name) so the
   same org consistently gets the same bucket — enabling stable gradual rollouts.

   DB rows are cached with a 30-second TTL to avoid per-request DB hits while
   still converging to new values within half a minute of an admin toggle."
  (:require [chengis.db.feature-flag-store :as ff-store]
            [chengis.feature-flags :as config-ff]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; TTL cache (same atom pattern used throughout the project)
;; ---------------------------------------------------------------------------

(defonce ^:private flag-cache* (atom {}))
(def ^:private cache-ttl-ns (* 30 1e9)) ;; 30 seconds

(defn- now-nanos ^long [] (System/nanoTime))

(defn- cache-key [flag-name org-id]
  [flag-name (or org-id ::global)])

(defn- cached-get
  "Return cached DB value for (flag-name, org-id), or ::miss when stale/absent."
  [flag-name org-id]
  (let [k   (cache-key flag-name org-id)
        hit (get @flag-cache* k)]
    (if (and hit (< (- (now-nanos) (:at hit)) cache-ttl-ns))
      (:value hit)
      ::miss)))

(defn- cache-put!
  "Store value in the TTL cache."
  [flag-name org-id value]
  (swap! flag-cache* assoc (cache-key flag-name org-id) {:value value :at (now-nanos)}))

(defn invalidate-cache!
  "Invalidate the in-process flag cache. Forces re-read from DB on next check.
   Useful after an admin toggle so the change takes effect immediately."
  ([]
   (reset! flag-cache* {}))
  ([flag-name]
   (swap! flag-cache*
          (fn [m] (into {} (remove (fn [[[n _] _]] (= n flag-name)) m))))))

;; ---------------------------------------------------------------------------
;; Percentage rollout bucketing
;; ---------------------------------------------------------------------------

(defn- org-bucket
  "Return a stable integer 0-99 for (org-id, flag-name) using SHA-256.
   Used to decide whether this org is inside the rollout percentage."
  ^long [org-id flag-name]
  (let [input (.getBytes (str org-id "/" flag-name) "UTF-8")
        digest (.digest (MessageDigest/getInstance "SHA-256") input)
        ;; Take the first 4 bytes as an unsigned int mod 100
        b0 (bit-and (aget digest 0) 0xFF)
        b1 (bit-and (aget digest 1) 0xFF)
        b2 (bit-and (aget digest 2) 0xFF)
        b3 (bit-and (aget digest 3) 0xFF)
        n  (bit-or (bit-shift-left b0 24)
                   (bit-shift-left b1 16)
                   (bit-shift-left b2 8)
                   b3)]
    (mod (Math/abs (long n)) 100)))

(defn- within-rollout?
  "Return true if org-id falls within percentage_rollout bucket."
  [org-id flag-name percentage]
  (cond
    (<= percentage 0)   false
    (>= percentage 100) true
    (nil? org-id)       false ;; no org-id → only 100% rolls out globally
    :else
    (< (org-bucket org-id flag-name) percentage)))

;; ---------------------------------------------------------------------------
;; Core resolution
;; ---------------------------------------------------------------------------

(defn- resolve-from-db
  "Look up flag from DB, using TTL cache. Returns the flag row map or nil."
  [ds flag-name org-id]
  (when ds
    (let [cached (cached-get flag-name org-id)]
      (if (not= cached ::miss)
        cached
        (let [row (try
                    (ff-store/get-flag ds flag-name org-id)
                    (catch Exception e
                      (log/warn "Feature flag DB lookup failed:" (.getMessage e))
                      nil))]
          (cache-put! flag-name org-id row)
          row)))))

(defn enabled?
  "Check whether feature flag flag-key is enabled in this system context.

   system must have :db (datasource) and :config keys.
   org-id is optional; pass it to incorporate per-org overrides.

   Returns true/false. Never throws — failures fall back to config then false."
  ([system flag-key]
   (enabled? system flag-key nil))
  ([system flag-key org-id]
   (let [flag-name (name flag-key)
         ds        (:db system)
         config    (:config system)
         row       (resolve-from-db ds flag-name org-id)]
     (cond
       ;; DB row found — check enabled + percentage
       (some? row)
       (and (:enabled row)
            (within-rollout? org-id flag-name
                             (or (:percentage-rollout row) 100)))

       ;; No DB row — fall back to static config flags (Phase 1 compat)
       :else
       (config-ff/enabled? config flag-key)))))

(defn require-flag!
  "Assert that a feature flag is enabled. Throws ex-info with
   {:type :feature-disabled :flag flag-key} when the flag is off.
   Drop-in replacement for chengis.feature-flags/require-flag! with org context."
  ([system flag-key]
   (require-flag! system flag-key nil))
  ([system flag-key org-id]
   (when-not (enabled? system flag-key org-id)
     (throw (ex-info (str "Feature not enabled: " (name flag-key))
                     {:type :feature-disabled
                      :flag flag-key
                      :org-id org-id})))
   true))

(defn all-flags
  "Return a map of flag-name → effective enabled? for a given org-id.
   Merges global flags with org-specific overrides.
   Useful for the admin UI to render the current state."
  ([system]
   (all-flags system nil))
  ([system org-id]
   (let [ds     (:db system)
         config (:config system)
         ;; Config-based flags as baseline
         config-flags (config-ff/all-flags config)
         ;; DB-backed flags (global + org-specific)
         db-rows (when ds
                   (try
                     (ff-store/list-flags ds org-id)
                     (catch Exception e
                       (log/warn "Failed to list feature flags:" (.getMessage e))
                       nil)))]
     ;; Start with config flags, overlay DB flags
     (reduce (fn [acc row]
               (let [flag-key (keyword (:flag-name row))
                     active?  (and (:enabled row)
                                   (within-rollout? org-id (:flag-name row)
                                                    (or (:percentage-rollout row) 100)))]
                 ;; Only apply org-override row when it actually applies to this org
                 (if (or (nil? (:org-id-override row))
                         (= (:org-id-override row) org-id))
                   (assoc acc flag-key active?)
                   acc)))
             (into {} (map (fn [[k v]] [k (boolean v)]) config-flags))
             (or db-rows [])))))
