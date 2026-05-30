(ns ^:integration chengis.plugin.loader-sci-test
  "End-to-end M1d tests through the REAL loader entry point
   (chengis.plugin.loader/load-external-plugins!) — proving the SCI runtime,
   the policy trust ceiling, and the capability manifest all compose correctly
   when a plugin is actually loaded from disk.

   A loaded plugin is observed by the side effect it produces (registering a
   notifier via the host API). A plugin that fails to evaluate — denied interop,
   ungranted capability, hostile code — registers nothing, which is exactly how
   we assert it was contained."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.plugin-policy-store :as pps]
            [chengis.plugin.loader]
            [chengis.plugin.registry :as registry]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]))

(def db-path "/tmp/chengis-loader-sci-test.db")

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (let [dbf (io/file db-path)] (when (.exists dbf) (.delete dbf)))
    (migrate/migrate! db-path)
    (f)
    (registry/reset-registry!)
    (let [dbf (io/file db-path)] (when (.exists dbf) (.delete dbf)))))

(defn- load-external! [& args]
  (apply (var-get #'chengis.plugin.loader/load-external-plugins!) args))

(defn- temp-dir ^java.io.File []
  (doto (io/file (str "/tmp/chengis-loader-sci-" (System/nanoTime))) (.mkdirs)))

(defn- rm-dir [^java.io.File dir]
  (doseq [^java.io.File c (.listFiles dir)] (.delete c))
  (.delete dir))

(defn- mk-plugin! [^java.io.File dir base clj-src manifest-map]
  (spit (io/file dir (str base ".clj")) clj-src)
  (when manifest-map
    (spit (io/file dir (str base ".edn")) (pr-str manifest-map))))

(defn- allow! [ds plugin-name trust-level]
  (pps/set-plugin-policy! ds {:org-id nil :plugin-name plugin-name
                              :trust-level trust-level :allowed true :created-by "test"}))

(defn- capture-warnings
  "Run thunk and return the vector of WARN-level log messages it emitted."
  [thunk]
  (let [warns (atom [])]
    (timbre/with-merged-config
      {:appenders {:println {:enabled? false}
                   :capture {:enabled? true :min-level :warn :async? false
                             :fn (fn [data] (swap! warns conj (str (force (:msg_ data)))))}}}
      (thunk))
    @warns))

;; A plugin whose source needs the :log capability at load time (top-level call).
(defn- needs-log-src [notifier-kw]
  (str "(require '[chengis.plugin.host :as h]) "
       "(h/log \"loaded\") "
       "(h/register-notifier! " notifier-kw " (fn [br cfg] {:status :sent}))"))

;; A plugin that uses STATIC class access at load time. Static access requires
;; a class in the context's :classes allowlist, which only the trusted lane has;
;; in the sandboxed lane `Math/abs` is an unresolved symbol. (NB: instance-method
;; interop like (.length s) is allowed in BOTH lanes and would NOT discriminate —
;; static access is the real trusted-vs-sandboxed boundary.)
(defn- interop-src [notifier-kw]
  (str "(def n (Math/abs -5)) "
       "(require '[chengis.plugin.host :as h]) "
       "(h/register-notifier! " notifier-kw " (fn [br cfg] {:status :sent :n n}))"))

;; ---------------------------------------------------------------------------
;; Capability manifest, through the loader
;; ---------------------------------------------------------------------------

(deftest manifest-grants-capability-through-loader
  (testing "a sandboxed plugin with :log declared in its manifest loads"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "capped" (needs-log-src ":capped") {:capabilities [:log]})
        (allow! ds "capped" "untrusted")          ; allowed, but sandboxed
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (some? (registry/get-notifier :capped))
            "granted :log => h/log resolves => plugin registers")
        (finally (rm-dir dir))))))

(deftest missing-capability-denied-in-sandbox-through-loader
  (testing "the same plugin WITHOUT the manifest is denied :log and fails to load"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "needslog" (needs-log-src ":needslog") nil) ; no manifest
        (allow! ds "needslog" "untrusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (nil? (registry/get-notifier :needslog))
            "no :log in sandbox => h/log unresolved => not registered")
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; effective-trust (least privilege), through the loader
;; ---------------------------------------------------------------------------

(deftest trusted-policy-allows-interop-through-loader
  (testing "policy trust-level trusted, no self-restriction => interop allowed"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "interp2" (interop-src ":interp2") nil)
        (allow! ds "interp2" "trusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (some? (registry/get-notifier :interp2))
            "trusted lane => (.length ..) resolves => registered")
        (finally (rm-dir dir))))))

(deftest manifest-self-restriction-lowers-trust-through-loader
  (testing "policy trusted but manifest :requests-trust :sandboxed => sandboxed"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "interp" (interop-src ":interp") {:requests-trust :sandboxed})
        (allow! ds "interp" "trusted")           ; ceiling is trusted...
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (nil? (registry/get-notifier :interp))
            "...but plugin self-restricts to sandboxed => interop denied => not registered")
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; Containment & blocking, through the loader
;; ---------------------------------------------------------------------------

(deftest hostile-plugin-is-contained-others-still-load
  (testing "a hostile plugin fails harmlessly and does not stop its siblings"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "evil" "(System/exit 1)" nil)
        (mk-plugin! dir "good"
                    "(require '[chengis.plugin.host :as h]) (h/register-notifier! :good (fn [br cfg] {:status :sent}))"
                    nil)
        (allow! ds "evil" "untrusted")
        (allow! ds "good" "untrusted")
        ;; must return normally — no real System/exit, no thrown exception escapes
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (some? (registry/get-notifier :good))
            "good plugin loads despite a hostile sibling")
        (finally (rm-dir dir))))))

(deftest unsigned-plugin-blocked-before-evaluation
  (testing "a plugin with no allow policy is never evaluated"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "stranger"
                    "(require '[chengis.plugin.host :as h]) (h/register-notifier! :stranger (fn [br cfg] {:status :sent}))"
                    nil)
        ;; no allow! call -> plugin-allowed? is false
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (nil? (registry/get-notifier :stranger))
            "no policy => blocked, never evaluated, registers nothing")
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; Manifest warnings surface (non-fatal)
;; ---------------------------------------------------------------------------

(deftest manifest-name-spoof-warning-surfaces-through-loader
  (testing "a manifest :name that disagrees with the file is logged but non-fatal"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "honest"
                    "(require '[chengis.plugin.host :as h]) (h/register-notifier! :honest (fn [br cfg] {:status :sent}))"
                    {:name "evil-twin"})
        (allow! ds "honest" "untrusted")
        (let [warns (capture-warnings
                     #(load-external! (.getAbsolutePath dir) :ds ds :org-id nil))]
          (is (some (fn [w] (re-find #"does not match" w)) warns)
              "loader surfaces the name-spoof manifest warning")
          (is (some? (registry/get-notifier :honest))
              "warning is non-fatal; plugin still loads"))
        (finally (rm-dir dir))))))
