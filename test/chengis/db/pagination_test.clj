(ns chengis.db.pagination-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.db.pagination :as pagination]))

(deftest encode-cursor-test
  (testing "encodes timestamp and id into Base64"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "abc-123")]
      (is (string? cursor))
      (is (not (clojure.string/blank? cursor)))))

  (testing "returns nil when timestamp or id is nil"
    (is (nil? (pagination/encode-cursor nil "abc")))
    (is (nil? (pagination/encode-cursor "2024-01-01" nil)))
    (is (nil? (pagination/encode-cursor nil nil)))))

(deftest decode-cursor-test
  (testing "roundtrip encode/decode"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "abc-123")
          decoded (pagination/decode-cursor cursor)]
      (is (= "2024-01-15 10:30:00" (:timestamp decoded)))
      (is (= "abc-123" (:id decoded)))))

  (testing "returns nil for invalid cursor"
    (is (nil? (pagination/decode-cursor nil)))
    (is (nil? (pagination/decode-cursor "")))
    (is (nil? (pagination/decode-cursor "   ")))
    (is (nil? (pagination/decode-cursor "not-valid-base64!!!"))))

  (testing "returns nil for malformed cursor (no separator)"
    (let [bad (.encodeToString (java.util.Base64/getUrlEncoder)
                               (.getBytes "no-separator" "UTF-8"))]
      (is (nil? (pagination/decode-cursor bad))))))

(deftest apply-cursor-where-test
  (testing "returns original where when no cursor"
    (is (= [:= :org-id "org1"]
           (pagination/apply-cursor-where [:= :org-id "org1"] nil :created-at :id :desc))))

  (testing "adds descending cursor condition"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where nil cursor :created-at :id :desc)]
      (is (= [:or
              [:< :created-at "2024-01-15 10:30:00"]
              [:and
               [:= :created-at "2024-01-15 10:30:00"]
               [:< :id "abc-123"]]]
             result))))

  (testing "adds ascending cursor condition"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where nil cursor :created-at :id :asc)]
      (is (= [:or
              [:> :created-at "2024-01-15 10:30:00"]
              [:and
               [:= :created-at "2024-01-15 10:30:00"]
               [:> :id "abc-123"]]]
             result))))

  (testing "combines with existing where clause"
    (let [cursor {:timestamp "2024-01-15 10:30:00" :id "abc-123"}
          result (pagination/apply-cursor-where [:= :org-id "org1"] cursor :created-at :id :desc)]
      (is (= :and (first result)))
      (is (= [:= :org-id "org1"] (second result))))))

(deftest paginated-response-test
  (testing "returns all items when fewer than limit"
    (let [items [{:id "1" :created-at "2024-01-01"} {:id "2" :created-at "2024-01-02"}]
          result (pagination/paginated-response items 10 :id :created-at)]
      (is (= 2 (count (:items result))))
      (is (false? (:has-more result)))
      (is (nil? (:next-cursor result)))))

  (testing "truncates to limit and signals has-more"
    (let [items (mapv (fn [i] {:id (str i) :created-at (str "2024-01-" (format "%02d" i))})
                      (range 1 12))  ;; 11 items
          ;; Simulate: we fetched limit+1=11, so limit=10
          result (pagination/paginated-response items 10 :id :created-at)]
      (is (= 10 (count (:items result))))
      (is (true? (:has-more result)))
      (is (some? (:next-cursor result)))
      ;; Verify cursor decodes to last item in page
      (let [decoded (pagination/decode-cursor (:next-cursor result))]
        (is (= "10" (:id decoded)))
        (is (= "2024-01-10" (:timestamp decoded))))))

  (testing "empty result set"
    (let [result (pagination/paginated-response [] 10 :id :created-at)]
      (is (empty? (:items result)))
      (is (false? (:has-more result)))
      (is (nil? (:next-cursor result)))))

  (testing "exactly limit items means no more"
    (let [items (mapv (fn [i] {:id (str i) :created-at (str "2024-01-" (format "%02d" i))})
                      (range 1 11))  ;; exactly 10
          result (pagination/paginated-response items 10 :id :created-at)]
      (is (= 10 (count (:items result))))
      (is (false? (:has-more result)))
      (is (nil? (:next-cursor result))))))

(deftest cursor-special-characters-test
  (testing "handles special characters in id"
    (let [cursor (pagination/encode-cursor "2024-01-15 10:30:00" "id-with|pipe")
          decoded (pagination/decode-cursor cursor)]
      ;; The id should capture everything after the first |
      (is (= "2024-01-15 10:30:00" (:timestamp decoded)))
      (is (= "id-with|pipe" (:id decoded)))))

  (testing "handles UUIDs in id"
    (let [id "550e8400-e29b-41d4-a716-446655440000"
          cursor (pagination/encode-cursor "2024-01-15 10:30:00" id)
          decoded (pagination/decode-cursor cursor)]
      (is (= id (:id decoded))))))

;; ---------------------------------------------------------------------------
;; T4-INV: Cursor monotonicity property-based invariants
;;
;; The pagination layer is split:
;;   * `apply-cursor-where` adds the SQL cursor predicate (DB-side filter).
;;   * `paginated-response` wraps a fetched page (fetched limit+1 items).
;;
;; To verify cursor-monotonicity end-to-end without a DB, we model the
;; "fetch" step as: sort all rows by (timestamp, id) DESC, then slice
;; using the same predicate `apply-cursor-where` documents:
;;
;;   keep row R iff (R.ts < cursor.ts) OR
;;                  (R.ts = cursor.ts AND R.id < cursor.id)
;;
;; This is the exact comparator a SQL engine would apply given the
;; where-clause emitted by `apply-cursor-where`, so the model is faithful.
;; ---------------------------------------------------------------------------

(defn- desc-sort
  "Sort rows by (timestamp, id) descending — matches `apply-cursor-where :desc`."
  [rows]
  (sort (fn [a b]
          (let [ts-cmp (compare (:created-at b) (:created-at a))]
            (if (zero? ts-cmp)
              (compare (:id b) (:id a))
              ts-cmp)))
        rows))

(defn- cursor-filter-desc
  "Model of the SQL predicate produced by (apply-cursor-where ... :desc):
   keep rows strictly after the cursor under (ts, id) DESC ordering."
  [rows cursor-data]
  (if-not cursor-data
    rows
    (let [{:keys [timestamp id]} cursor-data]
      (filter (fn [r]
                (or (neg? (compare (:created-at r) timestamp))
                    (and (= (:created-at r) timestamp)
                         (neg? (compare (:id r) id)))))
              rows))))

(defn- fetch-page
  "Simulate a paged DB read: filter by cursor, take limit+1 from sorted rows,
   then wrap with `paginated-response` exactly as production code does."
  [all-rows cursor-data limit]
  (let [sorted   (desc-sort all-rows)
        filtered (cursor-filter-desc sorted cursor-data)
        fetched  (take (inc limit) filtered)]
    (pagination/paginated-response fetched limit :id :created-at)))

(defn- paginate-all
  "Walk all pages until `:has-more` is false. Returns the seq of pages
   (each a `paginated-response` envelope). Caps iterations as a safety net
   against runaway cursors — should never trigger if pagination terminates."
  [all-rows limit]
  (loop [cursor nil
         pages  []
         guard  0]
    (let [page (fetch-page all-rows cursor limit)
          pages' (conj pages page)]
      (cond
        (> guard 1000)        pages'      ;; safety: bail out
        (not (:has-more page)) pages'
        :else (recur (pagination/decode-cursor (:next-cursor page))
                     pages'
                     (inc guard))))))

;; Generator: rows with distinct (timestamp, id) pairs. Distinct ids are
;; sufficient since cursor uses (ts, id) as the tie-breaker.
(def ^:private gen-row
  (gen/let [ts-day (gen/choose 1 28)
            id     gen/uuid]
    {:id (str id)
     :created-at (format "2024-01-%02d 12:00:00" ts-day)}))

(def ^:private gen-rows
  (gen/fmap (fn [rs]
              ;; Dedup by :id to keep cursor-pair uniqueness.
              (->> rs
                   (group-by :id)
                   vals
                   (map first)
                   vec))
            (gen/vector gen-row 0 30)))

;; ---------------------------------------------------------------------------
;; Invariant 1: encode-cursor / decode-cursor round-trip
;; ---------------------------------------------------------------------------

(defspec prop-cursor-roundtrip 200
  (prop/for-all [ts (gen/such-that #(not (str/blank? %))
                                   (gen/fmap str gen/uuid))
                 id (gen/such-that #(not (str/blank? %))
                                   (gen/fmap str gen/uuid))]
                (let [decoded (pagination/decode-cursor (pagination/encode-cursor ts id))]
                  (and (= ts (:timestamp decoded))
                       (= id (:id decoded))))))

;; ---------------------------------------------------------------------------
;; Invariant 2: cursor monotone progression — page N+1 is strictly after page N
;; ---------------------------------------------------------------------------

(defspec prop-cursor-strictly-advances 60
  (prop/for-all [rows  gen-rows
                 limit (gen/choose 1 8)]
                (let [pages (paginate-all rows limit)]
      ;; For every adjacent pair of pages, the first item of page (i+1)
      ;; must compare strictly LESS (under DESC order) than the last
      ;; item of page i. No overlap, no skip-back.
                  (every? (fn [[p1 p2]]
                            (let [last1  (last (:items p1))
                                  first2 (first (:items p2))]
                              (if (or (nil? last1) (nil? first2))
                                true ;; empty page — vacuously fine
                                (let [ts-cmp (compare (:created-at first2)
                                                      (:created-at last1))]
                                  (or (neg? ts-cmp)
                                      (and (zero? ts-cmp)
                                           (neg? (compare (:id first2) (:id last1)))))))))
                          (partition 2 1 pages)))))

;; ---------------------------------------------------------------------------
;; Invariant 3: no duplicates across pages + full coverage
;; ---------------------------------------------------------------------------

(defspec prop-pages-cover-all-rows-exactly-once 60
  (prop/for-all [rows  gen-rows
                 limit (gen/choose 1 8)]
                (let [pages       (paginate-all rows limit)
                      all-items   (mapcat :items pages)
                      ids-out     (map :id all-items)
                      ids-in      (map :id rows)]
                  (and
       ;; Concatenation equals the full input set (as a set of ids).
                   (= (set ids-in) (set ids-out))
       ;; No duplicates emitted across pages.
                   (= (count ids-out) (count (distinct ids-out)))
       ;; Total count matches input.
                   (= (count rows) (count all-items))))))

;; ---------------------------------------------------------------------------
;; Invariant 4: empty collection is robust — empty items, nil next-cursor
;; ---------------------------------------------------------------------------

(defspec prop-empty-collection-robust 25
  (prop/for-all [limit (gen/choose 1 20)]
                (let [page (fetch-page [] nil limit)]
                  (and (= [] (:items page))
                       (false? (:has-more page))
                       (nil? (:next-cursor page))))))

(deftest empty-collection-explicit-test
  (testing "paginated-response on empty seq does not throw, returns nil cursor"
    (let [page (pagination/paginated-response [] 10 :id :created-at)]
      (is (= [] (:items page)))
      (is (false? (:has-more page)))
      (is (nil? (:next-cursor page))))))

;; ---------------------------------------------------------------------------
;; Invariant 5: last-page sentinel — when fetched < limit+1, next-cursor is nil
;; ---------------------------------------------------------------------------

(defspec prop-last-page-has-nil-cursor 60
  (prop/for-all [rows  gen-rows
                 limit (gen/choose 1 8)]
                (let [pages    (paginate-all rows limit)
                      last-pg  (last pages)]
                  (and (false? (:has-more last-pg))
                       (nil? (:next-cursor last-pg))
           ;; Non-last pages must have a cursor (otherwise pagination stopped early).
                       (every? (fn [p] (some? (:next-cursor p)))
                               (butlast pages))))))

(defspec prop-short-page-signals-end 100
  (prop/for-all [n      (gen/choose 0 9)
                 limit  (gen/choose 10 15)]
    ;; When the result set is smaller than `limit`, paginated-response
    ;; must report has-more=false and next-cursor=nil.
                (let [items (mapv (fn [i] {:id (str i)
                                           :created-at (format "2024-01-%02d 00:00:00" (inc i))})
                                  (range n))
                      page  (pagination/paginated-response items limit :id :created-at)]
                  (and (= n (count (:items page)))
                       (false? (:has-more page))
                       (nil? (:next-cursor page))))))

;; ---------------------------------------------------------------------------
;; Invariant 6: apply-cursor-where structural invariants
;; ---------------------------------------------------------------------------

(defspec prop-apply-cursor-where-nil-is-identity 50
  (prop/for-all [direction (gen/elements [:asc :desc])]
                (let [base [:= :org-id "x"]]
                  (and (= base (pagination/apply-cursor-where base nil :created-at :id direction))
                       (nil? (pagination/apply-cursor-where nil nil :created-at :id direction))))))

(defspec prop-apply-cursor-where-uses-correct-operator 100
  (prop/for-all [ts        (gen/fmap str gen/uuid)
                 id        (gen/fmap str gen/uuid)
                 direction (gen/elements [:asc :desc])]
                (let [result (pagination/apply-cursor-where nil
                                                            {:timestamp ts :id id}
                                                            :created-at :id direction)
                      expected-op (if (= direction :asc) :> :<)]
      ;; Top-level is [:or [op ts-col ts] [:and [:= ts-col ts] [op id-col id]]]
                  (and (= :or (first result))
                       (= expected-op (first (second result)))
                       (= expected-op (first (last (last result))))))))
