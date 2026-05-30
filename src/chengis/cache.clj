(ns chengis.cache
  "Generic TTL cache backed by an atom (Phase 2, item 2.18).)

   Each cache is an independent atom so callers own their eviction scope.
   Keys must be comparable values (strings, keywords, vectors).

   IMPORTANT: every cache key must include org-id (or another tenant identifier)
   so that data from one org is never served to another.

   Usage:
     (def my-cache (make-ttl-cache 60000)) ;; 60-second TTL

     ;; Compute-if-absent — returns cached or fresh value
     (get-or-compute my-cache [:org-id \"pipeline-42\"]
                     (fn [] (db/load-pipeline ds \"pipeline-42\")))

     ;; Explicit put / invalidate
     (put! my-cache [:org-id \"key\"] value)
     (invalidate! my-cache [:org-id \"key\"])
     (clear! my-cache)")

;; ---------------------------------------------------------------------------
;; Cache record
;; ---------------------------------------------------------------------------

(defrecord TtlCache [store ttl-ms])

(defn make-ttl-cache
  "Create a new TTL cache.
   ttl-ms — time-to-live in milliseconds (default 60 000 = 60 s)."
  ([] (make-ttl-cache 60000))
  ([ttl-ms]
   (->TtlCache (atom {}) ttl-ms)))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- now-ms ^long [] (System/currentTimeMillis))

(defn- fresh?
  "Return true if the entry was cached within the TTL window."
  [entry ttl-ms]
  (< (- (now-ms) (:cached-at entry)) ttl-ms))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn get-cached
  "Return the cached value for key, or ::miss if absent or expired."
  [^TtlCache cache k]
  (let [entry (get @(:store cache) k)]
    (if (and entry (fresh? entry (:ttl-ms cache)))
      (:value entry)
      ::miss)))

(defn put!
  "Store value under key with the current timestamp."
  [^TtlCache cache k value]
  (swap! (:store cache) assoc k {:value value :cached-at (now-ms)})
  value)

(defn get-or-compute
  "Return the cached value for key, or call compute-fn to produce and cache it.
   compute-fn is a zero-arity fn; its return value is stored and returned.
   If compute-fn throws, the exception propagates and nothing is cached."
  [^TtlCache cache k compute-fn]
  (let [hit (get-cached cache k)]
    (if (not= hit ::miss)
      hit
      (let [value (compute-fn)]
        (put! cache k value)
        value))))

(defn invalidate!
  "Remove a specific key from the cache."
  [^TtlCache cache k]
  (swap! (:store cache) dissoc k)
  nil)

(defn clear!
  "Remove all entries from the cache."
  [^TtlCache cache]
  (reset! (:store cache) {})
  nil)

(defn evict-stale!
  "Remove all expired entries. Call periodically to reclaim memory."
  [^TtlCache cache]
  (let [ttl-ms (:ttl-ms cache)
        now    (now-ms)]
    (swap! (:store cache)
           (fn [m]
             (into {} (remove (fn [[_ entry]] (>= (- now (:cached-at entry)) ttl-ms)) m))))
    nil))

(defn stats
  "Return {:size N :ttl-ms N} for observability."
  [^TtlCache cache]
  {:size   (count @(:store cache))
   :ttl-ms (:ttl-ms cache)})
