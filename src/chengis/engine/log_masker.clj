(ns chengis.engine.log-masker
  "Log masking for secret values in build output.
   Replaces any occurrence of a secret value with *** in text."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:const mask-replacement "***")

(def ^:const min-secret-length
  "Minimum length a secret value must have to be masked.

   Short tokens (\"a\", \"x\", \"ok\") are almost always legitimate substrings
   of other log content; masking them turns build output into a soup of
   `***`. Operator footgun: secrets that small are also worthless from
   a security standpoint. Refuse to mask, emit a one-line warn so the
   misconfiguration surfaces."
  4)

(defn mask-secrets
  "Replace all occurrences of secret values in text with ***.

   `secret-values` is a set (or collection) of strings to mask.
   Returns the masked text, or the original text if no secrets.

   Two invariants the caller can rely on:

   1. **Length-descending application order.** Secrets are sorted by
      `count` descending before the reduce so that a longer secret which
      happens to contain a shorter secret as a substring is masked FIRST.
      Otherwise replacing the substring first would shred the longer
      value before it can match, leaking the longer secret as
      `<replacement><tail>`. Example without sort: with
      `[\"abc\" \"abcdef\"]`, `\"abcdef\"` would become `\"***def\"` —
      the longer secret never matches, partial leak.

   2. **Minimum-length guard.** Values shorter than `min-secret-length`
      are skipped with a warn (see `min-secret-length` docstring).
      Nil/empty/blank values are skipped silently (already covered by
      the inner guard)."
  [text secret-values]
  (if (or (nil? text) (empty? secret-values))
    text
    (let [ordered (->> secret-values
                       (filter #(and % (seq %)))
                       (sort-by count #(compare %2 %1)))]
      (reduce (fn [t secret-val]
                (cond
                  (str/blank? secret-val)
                  t

                  (< (count secret-val) min-secret-length)
                  (do (log/warn (str "log-masker: refusing to mask secret of length "
                                     (count secret-val)
                                     " (< " min-secret-length
                                     "); too short to be a real secret"
                                     " and would shred unrelated log content"))
                      t)

                  :else
                  (str/replace t secret-val mask-replacement)))
              text
              ordered))))
