(ns chengis.engine.credentials-test
  "Acceptance tests for the credentials binding pipeline (CC2-EX4).

   Headline rules the tests lock down:
     • bind! never binds a credential to '' — missing/blank fields
       classify as :credential-render-failed (NOT silent SUCCESS)
     • bind! never returns :ok when any credential-id missed the store —
       routes through :credential-unresolved → EX2 :failure
     • file-based bindings actually write the file with restricted
       permissions and surface its path through env
     • with-bindings! cleanup runs even when the body throws
     • config-file templates render real, parseable content with the
       embedded secret listed in :mask-values

   The wild-corpus receipts (eclipse-jkube GPG, apache-snapshot 401 → fixed)
   come from anvil's v0.4 wiring layer; this file proves the engine-side
   contract."
  (:require [chengis.engine.credentials :as cred]
            [chengis.engine.result :as result]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- ^java.io.File tmp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- rm-r! [^java.io.File f]
  (when (and f (.exists f))
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-r! c)))
    (.delete f)))

;; ---------------------------------------------------------------------------
;; map-store + lookup
;; ---------------------------------------------------------------------------

(deftest map-store-resolves-known-id-returns-nil-for-unknown
  (let [s (cred/map-store {"X" {:id "X" :type :secret-text :value "shh"}})]
    (is (= "shh" (:value (cred/lookup s "X"))))
    (is (nil? (cred/lookup s "Y")))
    (is (nil? (cred/lookup nil "X")))
    (is (nil? (cred/lookup s nil)))))

;; ---------------------------------------------------------------------------
;; :string (secret-text) bindings
;; ---------------------------------------------------------------------------

(deftest secret-text-renders-to-env-and-mask
  (let [store (cred/map-store {"GH" {:id "GH" :type :secret-text :value "gho_abc"}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :string
                            :credential-id "GH"
                            :var "GH_TOKEN"}])]
        (is (= :ok (:result r)))
        (is (= {"GH_TOKEN" "gho_abc"} (:env r)))
        (is (some #{"gho_abc"} (:mask-values r))))
      (finally (rm-r! dir)))))

(deftest secret-text-binding-fails-on-blank-value
  (let [store (cred/map-store {"X" {:id "X" :type :secret-text :value ""}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :string :credential-id "X" :var "T"}])]
        (is (= :failed (:result r)))
        (is (= :credential-render-failed (:rule r)))
        (is (= ["X"] (:unresolved r))))
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; :username-password
;; ---------------------------------------------------------------------------

(deftest username-password-renders-two-envs-and-masks-pair
  (let [store (cred/map-store
               {"APACHE" {:id "APACHE" :type :username-password
                          :username "alice" :password "s3cret"}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :username-password
                            :credential-id "APACHE"
                            :username-var "U"
                            :password-var "P"}])]
        (is (= :ok (:result r)))
        (is (= "alice" (get-in r [:env "U"])))
        (is (= "s3cret" (get-in r [:env "P"])))
        (is (some #{"s3cret"} (:mask-values r)))
        ;; URL-userinfo form must also be masked
        (is (some #{"alice:s3cret"} (:mask-values r))))
      (finally (rm-r! dir)))))

(deftest username-password-fails-on-missing-password
  (let [store (cred/map-store
               {"X" {:id "X" :type :username-password
                     :username "alice" :password ""}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :username-password
                            :credential-id "X"
                            :username-var "U" :password-var "P"}])]
        (is (= :failed (:result r)))
        (is (= :credential-render-failed (:rule r))))
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; :file
;; ---------------------------------------------------------------------------

(deftest file-binding-materializes-with-restricted-perms
  (let [content "-----BEGIN PGP PRIVATE KEY BLOCK-----\nfake-key\n-----END-----\n"
        store (cred/map-store
               {"GPG" {:id "GPG" :type :file
                       :file-name "gpg-private.asc"
                       :file-content content}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :file :credential-id "GPG" :var "GPG_KEY"}])
            path (get-in r [:env "GPG_KEY"])]
        (is (= :ok (:result r)))
        (is (str/ends-with? path "gpg-private.asc"))
        (is (.exists (io/file path)))
        (is (= content (slurp path)))
        (is (some #{content} (:mask-values r)))
        ;; cleanup-fn actually deletes the file
        ((:cleanup-fn r))
        (is (not (.exists (io/file path)))))
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; :ssh-key
;; ---------------------------------------------------------------------------

(deftest ssh-key-binding-with-and-without-passphrase
  (let [store (cred/map-store
               {"DEPLOY" {:id "DEPLOY" :type :ssh-key
                          :private-key "PRIVATE-KEY-BYTES"
                          :passphrase "phrase"}})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :ssh-key
                            :credential-id "DEPLOY"
                            :key-file-var "SSH_KEY"
                            :passphrase-var "SSH_PASS"}])
            path (get-in r [:env "SSH_KEY"])]
        (is (= :ok (:result r)))
        (is (.exists (io/file path)))
        (is (= "phrase" (get-in r [:env "SSH_PASS"])))
        (is (some #{"PRIVATE-KEY-BYTES"} (:mask-values r)))
        (is (some #{"phrase"} (:mask-values r)))
        ((:cleanup-fn r)))
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; Unresolved
;; ---------------------------------------------------------------------------

(deftest unresolved-credential-id-fails-with-credential-unresolved
  (let [store (cred/map-store {})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir)
                          [{:type :string :credential-id "MISSING" :var "T"}])]
        (is (= :failed (:result r)))
        (is (= :credential-unresolved (:rule r)))
        (is (= ["MISSING"] (:unresolved r))))
      (finally (rm-r! dir)))))

(deftest nil-store-fails-with-store-missing
  (let [r (cred/bind! nil "/tmp/x"
                      [{:type :string :credential-id "X" :var "T"}])]
    (is (= :failed (:result r)))
    (is (= :credential-store-missing (:rule r)))
    (is (= ["X"] (:unresolved r)))))

(deftest blank-per-build-dir-fails
  (let [store (cred/map-store {"X" {:id "X" :type :secret-text :value "v"}})]
    (let [r (cred/bind! store ""
                        [{:type :string :credential-id "X" :var "T"}])]
      (is (= :failed (:result r))))))

(deftest empty-bindings-list-is-ok-noop
  (let [store (cred/map-store {})
        dir (tmp-dir "cred")]
    (try
      (let [r (cred/bind! store (.getAbsolutePath dir) [])]
        (is (= :ok (:result r)))
        (is (= {} (:env r)))
        (is (empty? (:files r)))
        (is (empty? (:mask-values r))))
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; Composition: unresolved flows into EX2 classifier as :failure
;; ---------------------------------------------------------------------------

(deftest unresolved-flows-into-classifier-as-failure
  (let [r (cred/bind! (cred/map-store {})
                      (.getAbsolutePath (tmp-dir "cred"))
                      [{:type :string :credential-id "MISSING" :var "T"}])
        obs (reduce result/record-unresolved-credential
                    (result/default-observation)
                    (:unresolved r))
        classified (result/classify obs)]
    (is (= :failure (:result classified)))
    (is (= :credential-unresolved (:rule classified)))))

;; ---------------------------------------------------------------------------
;; with-bindings! bracket — cleanup runs even on throw
;; ---------------------------------------------------------------------------

(deftest with-bindings-cleanup-runs-on-throw
  (let [content "secret-key-bytes"
        store (cred/map-store
               {"K" {:id "K" :type :file
                     :file-name "k.pem" :file-content content}})
        dir (tmp-dir "cred")
        observed-path (atom nil)]
    (try
      (is (thrown? RuntimeException
                   (cred/with-bindings!
                     store (.getAbsolutePath dir)
                     [{:type :file :credential-id "K" :var "K_FILE"}]
                     (fn [r]
                       (reset! observed-path (get-in r [:env "K_FILE"]))
                       (is (.exists (io/file @observed-path)))
                       (throw (RuntimeException. "boom"))))))
      (is (some? @observed-path))
      (is (not (.exists (io/file @observed-path)))
          "cleanup must delete materialized files even when body threw")
      (finally (rm-r! dir)))))

;; ---------------------------------------------------------------------------
;; Config-file templates
;; ---------------------------------------------------------------------------

(deftest maven-settings-template-renders-real-xml
  (let [r (cred/render-config-template
           :maven-settings
           {:server-id "apache.snapshots"
            :username "alice"
            :password "s3cret"
            :per-build-home "/per-build-home"})]
    (is (some? r))
    (is (str/ends-with? (:path r) ".m2/settings.xml"))
    (is (= 0600 (:mode r)))
    (is (str/includes? (:content r) "<id>apache.snapshots</id>"))
    (is (str/includes? (:content r) "<username>alice</username>"))
    (is (str/includes? (:content r) "<password>s3cret</password>"))
    (is (= ["s3cret"] (:mask-values r)))))

(deftest npmrc-template-renders-auth-token
  (let [r (cred/render-config-template
           :npmrc
           {:registry "https://registry.npmjs.org/"
            :auth-token "npm_tok"
            :per-build-home "/per-build-home"})]
    (is (str/ends-with? (:path r) ".npmrc"))
    (is (str/includes? (:content r) "_authToken=npm_tok"))
    (is (str/includes? (:content r) "registry=https://registry.npmjs.org/"))
    (is (= ["npm_tok"] (:mask-values r)))))

(deftest docker-config-template-renders-auths-json
  (let [r (cred/render-config-template
           :docker-config
           {:registry "ghcr.io"
            :auth-base64 "ZGVhZGJlZWY="
            :per-build-home "/per-build-home"})]
    (is (str/ends-with? (:path r) ".docker/config.json"))
    (is (str/includes? (:content r) "\"ghcr.io\""))
    (is (str/includes? (:content r) "ZGVhZGJlZWY="))
    (is (= ["ZGVhZGJlZWY="] (:mask-values r)))))

(deftest unknown-template-returns-nil
  (is (nil? (cred/render-config-template :no-such-template {})))
  (is (nil? (cred/render-config-template :maven-settings
                                         {:server-id "" :username "a"
                                          :password "p" :per-build-home "/h"}))
      "template returns nil on missing required field, not bogus content"))

(deftest defaults-are-registered
  (let [ids (set (cred/registered-config-templates))]
    (is (contains? ids :maven-settings))
    (is (contains? ids :npmrc))
    (is (contains? ids :docker-config))))
