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
            [chengis.plugin.signing :as signing]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

;; Ed25519 signing helpers (M2a) — sign a plugin's bytes so it can earn :trusted.
(defn- gen-keypair [] (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519")))

(defn- pub->b64 [kp]
  (.encodeToString (java.util.Base64/getEncoder) (.getEncoded (.getPublic kp))))

(defn- write-sig!
  "Sign the combined (clj + sidecar manifest) payload, writing <clj>.sig.
   Call AFTER the .clj and any .edn are written."
  [kp ^java.io.File clj-file]
  (let [edn-file  (io/file (str/replace (.getAbsolutePath clj-file) #"\.clj$" ".edn"))
        clj-bytes (.getBytes (slurp clj-file) "UTF-8")
        edn-bytes (when (.isFile edn-file) (.getBytes (slurp edn-file) "UTF-8"))
        payload   (signing/signed-payload clj-bytes edn-bytes)
        s         (java.security.Signature/getInstance "Ed25519")]
    (.initSign s (.getPrivate kp))
    (.update s payload)
    (spit (io/file (str (.getAbsolutePath clj-file) ".sig"))
          (.encodeToString (java.util.Base64/getEncoder) (.sign s)))))

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

(defn- noop-notifier-src [kw]
  (str "(require '[chengis.plugin.host :as h]) "
       "(h/register-notifier! " kw " (fn [br cfg] {:status :sent}))"))

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

;; (The positive "trusted policy => interop allowed" path is covered by
;; signed-plugin-with-trusted-policy-gets-trusted below, since post-M2a a
;; trusted policy ALSO requires a valid signature.)

(deftest manifest-self-restriction-lowers-trust-through-loader
  (testing "signed + policy trusted, but manifest :requests-trust :sandboxed => sandboxed"
    (let [dir (temp-dir) ds (conn/create-datasource db-path) kp (gen-keypair)]
      (try
        ;; Sign it so the ONLY thing lowering trust is the manifest self-restriction,
        ;; not a missing signature — this isolates effective-trust behavior.
        (mk-plugin! dir "interp" (interop-src ":interp") {:requests-trust :sandboxed})
        (write-sig! kp (io/file dir "interp.clj"))
        (allow! ds "interp" "trusted")             ; ceiling trusted, signature valid...
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                        :signing-keys [(pub->b64 kp)])
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

;; ---------------------------------------------------------------------------
;; Signing gates :trusted (M2a)
;; ---------------------------------------------------------------------------

(deftest signed-plugin-with-trusted-policy-gets-trusted
  (testing "trusted policy + valid signature => :trusted (static interop resolves)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path) kp (gen-keypair)]
      (try
        (let [clj (io/file dir "signedp.clj")]
          (spit clj (interop-src ":signedp"))     ; uses (Math/abs -5) — trusted-only
          (write-sig! kp clj)
          (allow! ds "signedp" "trusted")
          (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                          :signing-keys [(pub->b64 kp)])
          (is (some? (registry/get-notifier :signedp))
              "signed + trusted policy runs in :trusted, so Math/abs resolves and it registers"))
        (finally (rm-dir dir))))))

(deftest unsigned-plugin-with-trusted-policy-forced-sandbox
  (testing "trusted policy but NO valid signature => forced :sandboxed (M2a)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (let [clj (io/file dir "unsignedp.clj")]
          (spit clj (interop-src ":unsignedp"))   ; no .sig written
          (allow! ds "unsignedp" "trusted")       ; policy says trusted...
          (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                          :signing-keys [])
          (is (nil? (registry/get-notifier :unsignedp))
              "...but unsigned is forced sandboxed, where Math/abs is unresolved => not registered"))
        (finally (rm-dir dir))))))

(deftest tampered-signature-does-not-grant-trusted
  (testing "a signature over different bytes does not earn :trusted"
    (let [dir (temp-dir) ds (conn/create-datasource db-path) kp (gen-keypair)]
      (try
        (let [clj (io/file dir "tampered.clj")]
          (spit clj (interop-src ":tampered"))
          (write-sig! kp clj)                     ; sign the current bytes...
          (spit clj (str (interop-src ":tampered") " ;; mutated after signing"))
          (allow! ds "tampered" "trusted")
          (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                          :signing-keys [(pub->b64 kp)])
          (is (nil? (registry/get-notifier :tampered))
              "post-signing source mutation => invalid signature => blocked"))
        (finally (rm-dir dir))))))

(deftest invalid-signature-blocks-and-denies-manifest-grants
  (testing "a signed plugin whose manifest is tampered is BLOCKED — its (now
            unverified) manifest capabilities are never granted (Codex P2)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path) kp (gen-keypair)]
      (try
        ;; a no-op sandboxed plugin that WOULD register if it ran; require-signed off.
        (mk-plugin! dir "capgrab" (noop-notifier-src ":capgrab") {:capabilities [:log]})
        (write-sig! kp (io/file dir "capgrab.clj"))
        ;; tamper the manifest after signing to add :secrets
        (spit (io/file dir "capgrab.edn") (pr-str {:capabilities [:log :secrets]}))
        (allow! ds "capgrab" "untrusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                        :signing-keys [(pub->b64 kp)])
        (is (nil? (registry/get-notifier :capgrab))
            "invalid signature => blocked; tampered manifest's caps never honored")
        (finally (rm-dir dir))))))

(deftest manifest-tamper-after-signing-is-blocked
  (testing "altering the manifest after signing invalidates the signature => blocked (P2)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path) kp (gen-keypair)]
      (try
        ;; signed interop plugin WITH a manifest; the signature covers clj + edn
        (mk-plugin! dir "mtamper" (interop-src ":mtamper") {:capabilities [:log]})
        (write-sig! kp (io/file dir "mtamper.clj"))
        ;; tamper the manifest after signing (e.g. try to add a capability)
        (spit (io/file dir "mtamper.edn") (pr-str {:capabilities [:log :secrets]}))
        (allow! ds "mtamper" "trusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                        :signing-keys [(pub->b64 kp)])
        (is (nil? (registry/get-notifier :mtamper))
            "manifest tamper breaks the signature => blocked => not registered")
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; Quarantine + require-signed enforcement (M2b)
;; ---------------------------------------------------------------------------

(deftest quarantined-plugin-is-blocked
  (testing "a quarantined plugin is refused even when allowed (M2b)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "quarp" (noop-notifier-src ":quarp") nil)
        (allow! ds "quarp" "untrusted")                       ; allowed...
        (pps/quarantine-plugin! ds "quarp" "known-vulnerable") ; ...but quarantined
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (nil? (registry/get-notifier :quarp))
            "quarantine hard-blocks regardless of allowlist")
        (finally (rm-dir dir))))))

(deftest unquarantine-restores-load
  (testing "clearing quarantine lets the plugin load again"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "rehab" (noop-notifier-src ":rehab") nil)
        (allow! ds "rehab" "untrusted")
        (pps/quarantine-plugin! ds "rehab" "stale")
        (pps/unquarantine-plugin! ds "rehab")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (is (some? (registry/get-notifier :rehab))
            "un-quarantined + allowed plugin loads")
        (finally (rm-dir dir))))))

(deftest require-signed-blocks-unsigned-entirely
  (testing "require-signed? blocks an unsigned plugin outright, not merely sandbox (M2b)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        ;; A plugin that WOULD succeed in :sandboxed (no interop, no caps); so if
        ;; it registers nothing, it was BLOCKED, not just sandboxed.
        (mk-plugin! dir "reqsig" (noop-notifier-src ":reqsig") nil)
        (allow! ds "reqsig" "untrusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil
                        :require-signed? true :signing-keys [])
        (is (nil? (registry/get-notifier :reqsig))
            "unsigned + require-signed => blocked outright")
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; Capability-grant audit (M2c)
;; ---------------------------------------------------------------------------

(deftest grant-is-recorded-for-loaded-plugin
  (testing "loading a plugin records its effective capability grant (M2c)"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (mk-plugin! dir "grantp" (noop-notifier-src ":grantp") {:capabilities [:log]})
        (allow! ds "grantp" "untrusted")
        (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
        (let [g (registry/get-grant "grantp")]
          (is (some? g) "grant recorded for the loaded plugin")
          (is (= :sandboxed (:trust g)))
          (is (= [:log] (:capabilities g)))
          (is (false? (:signed? g))))
        (finally (rm-dir dir))))))

;; ---------------------------------------------------------------------------
;; Per-plugin error isolation (Codex P2: read failures must not abort the rest)
;; ---------------------------------------------------------------------------

(deftest unreadable-file-does-not-abort-siblings
  (testing "an unreadable plugin file is skipped; sibling plugins still load"
    (let [dir (temp-dir) ds (conn/create-datasource db-path)]
      (try
        (let [bad  (io/file dir "aaa-bad.clj")
              good (io/file dir "zzz-good.clj")]
          (spit bad "(+ 1 2)")
          (spit good (noop-notifier-src ":survivor"))
          (allow! ds "aaa-bad" "untrusted")
          (allow! ds "zzz-good" "untrusted")
          ;; Make the bad file unreadable to force a read error during load. If the
          ;; runtime doesn't enforce it (e.g. running as root), the file just loads
          ;; harmlessly — the sibling-loads assertion holds either way.
          (.setReadable bad false false)
          (load-external! (.getAbsolutePath dir) :ds ds :org-id nil)
          (is (some? (registry/get-notifier :survivor))
              "a readable sibling loads even if another file can't be read")
          (.setReadable bad true true))
        (finally (rm-dir dir))))))
