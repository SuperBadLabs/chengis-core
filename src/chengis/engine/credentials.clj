(ns chengis.engine.credentials
  "Credentials binding pipeline — CC2-EX4.

   Why this exists
   ===============
   Jenkinsfiles wrap shell steps with `withCredentials([…]) { … }` to inject
   secrets into env vars or files for the duration of the block. anvil v0.3
   evaluated the block but bound every credential to `\"\"` — leaving
   downstream code reading empty USER/PASS, empty `~/.m2/settings.xml`,
   empty GPG key files, and *silently* failing in unintuitive ways
   (`401 Unauthorized` against private artifact repos was the headline
   wild-corpus symptom).

   This namespace is the chengis-core side of the fix: a credential
   *binding pipeline* that takes a list of credential references against
   a store, renders them into the env / files / log-mask set that a build
   step needs, and surfaces unresolved refs as
   `chengis.engine.result/record-unresolved-credential` so the EX2
   classifier reports `:failure` instead of vacuous SUCCESS.

   The store itself lives in the consuming product (anvil's AES-256-GCM
   encrypted store, Chengis's Vault integration, …). This ns ships:
     - the descriptor + binding shapes
     - the rendering rules (text → env, username-password → 2 envs,
       file → materialized file + ENV pointing at it, ssh-key → file,
       cert → file + passphrase env)
     - per-build config-file injection: three default templates ship
       (~/.m2/settings.xml, ~/.npmrc, ~/.docker/config.json); pip's
       ~/.pip/pip.conf and other shapes can be added via
       `register-config-template!` in the consuming product
     - `bind!` — top-level: resolve refs against a store, render, emit
     - `with-bindings!` — bracket helper that materializes files, runs
       a body, then guarantees cleanup of the materialized files
       (note: in-process JVM string interning is out of scope — secret
       values exist on the JVM heap for the build's lifetime; operators
       who need stronger memory hygiene should pair this with a JVM
       restart between builds or a sealed-secrets backend)

   The store contract
   ==================
   A *store* is anything satisfying `CredentialStore`:

     resolve  — credential-id STRING → credential-record OR nil
                where credential-record is
                  {:id STRING
                   :type :secret-text | :username-password
                         | :file | :ssh-key | :certificate
                   :description STRING?
                   ;; type-specific fields:
                   :value STRING?       (secret-text)
                   :username STRING?    (username-password)
                   :password STRING?    (username-password)
                   :file-content STRING/BYTES?  (file)
                   :file-name STRING?           (file)
                   :private-key STRING?         (ssh-key)
                   :passphrase STRING?          (ssh-key, certificate)
                   :keystore BYTES?             (certificate)
                   :keystore-format STRING?}    (certificate)

   The binding shape
   =================
   A *binding* is what a caller declares in their Jenkinsfile-equivalent
   form. The binding maps a credential ID to one or more variable names:

     {:type :string :credential-id \"X\" :var \"GH_TOKEN\"}
     {:type :username-password
      :credential-id \"X\" :username-var \"U\" :password-var \"P\"}
     {:type :file :credential-id \"X\" :var \"GPG_KEY_FILE\"}
     {:type :ssh-key
      :credential-id \"X\" :key-file-var \"SSH_KEY\" :passphrase-var \"PP\"}
     {:type :certificate :credential-id \"X\"
      :keystore-file-var \"KS\" :passphrase-var \"PP\"}

   `bind!` returns
     {:result :ok
      :env {STRING STRING}
      :files [{:path STRING :mode INT :content STRING/BYTES} ...]
      :mask-values [STRING ...]
      :cleanup-fn fn? }
     or
     {:result :failed :explain STRING :unresolved [STRING ...] :rule KW}

   The unresolved sentinel
   =======================
   `bind!` returns `:result :failed` with the list of credential IDs that
   the store couldn't resolve. Callers route those into the EX2 observation
   via `record-unresolved-credential` — classification becomes
   `:credential-unresolved` → `:failure`. No silent successes.

   Refs: docs/v0.2-board.md CC2-EX4."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- xml-escape
  "Escape a string for safe interpolation into an XML element body or
   attribute value. Handles `&`, `<`, `>`, `\"`, and `'`. Returns the
   empty string for nil input so callers can render-with-replace
   without npe."
  [s]
  (-> (str s)
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'"  "&apos;")))

;; ---------------------------------------------------------------------------
;; Store protocol
;; ---------------------------------------------------------------------------

(defprotocol CredentialStore
  "Read-only credential lookup. Products implement this against their
   own backing (anvil: SQLite + AES-256-GCM; Chengis: Vault; tests: a
   plain map)."
  (-resolve [this credential-id]
    "Return the credential record (see ns doc) or nil if not found."))

(defn map-store
  "Wrap a plain {id → record} map as a CredentialStore. Convenient for
   tests + for in-memory fixtures."
  [m]
  (reify CredentialStore
    (-resolve [_ id] (get m id))))

(defn lookup
  "Public façade over `-resolve`. Returns the record or nil."
  [store credential-id]
  (when (and store credential-id)
    (-resolve store credential-id)))

;; ---------------------------------------------------------------------------
;; Rendering rules: one fn per binding :type
;;
;; Each render-fn takes [binding credential-record per-build-dir] and
;; returns
;;   {:env {STRING STRING}?
;;    :files [{:path :mode :content} ...]?
;;    :mask-values [STRING ...]}
;; or nil if the binding/record shapes don't agree (e.g. binding asks
;; for username-password but record is :secret-text).
;; ---------------------------------------------------------------------------

(defn- non-blank? [s]
  (and (string? s) (not (str/blank? s))))

(defn- file-mode-600 [] 0600)

(defn- safe-basename
  "Reduce an attacker-controlled `file-name` to a basename within the
   per-build dir, defeating `..` segments and absolute paths. Returns
   nil for blank input, `.`, `..`, or names that contain a path
   separator after `.getName` resolved them out — caller falls back to
   a default."
  [^String file-name]
  (when (non-blank? file-name)
    (let [f (io/file file-name)
          base (.getName f)]
      (when (and (non-blank? base)
                 (not (#{"." ".."} base))
                 (not (str/includes? base "/"))
                 (not (str/includes? base "\\")))
        base))))

(defmulti render-binding
  "Multimethod dispatch on (:type binding)."
  (fn [binding _record _per-build-dir] (:type binding)))

(defmethod render-binding :string
  [{:keys [var]} {:keys [type value]} _]
  (when (and (= :secret-text type)
             (non-blank? var)
             (non-blank? value))
    {:env {var value}
     :mask-values [value]}))

(defmethod render-binding :username-password
  [{:keys [username-var password-var]}
   {:keys [type username password]}
   _]
  (when (and (= :username-password type)
             (non-blank? username-var)
             (non-blank? password-var)
             (non-blank? username)
             (non-blank? password))
    {:env {username-var username password-var password}
     ;; mask the password and the password-component-of-userinfo shape
     ;; used in many URL forms (e.g. https://user:pass@host)
     :mask-values [password (str username ":" password)]}))

(defmethod render-binding :file
  [{:keys [var]}
   {:keys [type file-content file-name]}
   per-build-dir]
  (when (and (= :file type)
             (non-blank? var)
             (or (string? file-content) (bytes? file-content)))
    ;; file-name comes from the credential record which can carry
    ;; operator-set values. Reduce it to a basename so values like
    ;; `../etc/passwd` cannot escape the per-build-dir sandbox.
    (let [name (or (safe-basename file-name) "credential.bin")
          path (str per-build-dir java.io.File/separatorChar name)
          masks (cond-> []
                  (string? file-content) (conj file-content))]
      {:env {var path}
       :files [{:path path :mode (file-mode-600) :content file-content}]
       :mask-values masks})))

(defmethod render-binding :ssh-key
  [{:keys [key-file-var passphrase-var]}
   {:keys [type private-key passphrase]}
   per-build-dir]
  (when (and (= :ssh-key type)
             (non-blank? key-file-var)
             (non-blank? private-key))
    (let [path (str per-build-dir java.io.File/separatorChar "id_credential")
          env (cond-> {key-file-var path}
                (and (non-blank? passphrase-var)
                     (non-blank? passphrase))
                (assoc passphrase-var passphrase))
          masks (cond-> [private-key]
                  (non-blank? passphrase) (conj passphrase))]
      {:env env
       :files [{:path path :mode (file-mode-600) :content private-key}]
       :mask-values masks})))

(defmethod render-binding :certificate
  [{:keys [keystore-file-var passphrase-var]}
   {:keys [type keystore passphrase]}
   per-build-dir]
  ;; `write-file!` only handles string + bytes content, so we MUST
  ;; pre-validate that the record's :keystore is one of those — otherwise
  ;; `bind!` throws instead of returning {:result :failed ...}, breaking
  ;; its non-throwing contract. Any other type → render nil → handled
  ;; upstream as :credential-render-failed.
  (when (and (= :certificate type)
             (non-blank? keystore-file-var)
             (or (bytes? keystore)
                 (non-blank? keystore)))
    (let [path (str per-build-dir java.io.File/separatorChar "credential.keystore")
          env (cond-> {keystore-file-var path}
                (and (non-blank? passphrase-var)
                     (non-blank? passphrase))
                (assoc passphrase-var passphrase))
          masks (cond-> []
                  (non-blank? passphrase) (conj passphrase))]
      {:env env
       :files [{:path path :mode (file-mode-600) :content keystore}]
       :mask-values masks})))

(defmethod render-binding :default
  [binding _record _]
  (log/warn "credentials: unknown binding type"
            (pr-str (:type binding)))
  nil)

;; ---------------------------------------------------------------------------
;; bind!
;; ---------------------------------------------------------------------------

(defn- ensure-dir! [^String path]
  (let [f (io/file path)]
    (.mkdirs f)
    f))

(defn- write-file!
  "Write a file with restricted permissions. Returns the absolute path."
  [{:keys [path mode content]}]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent (.mkdirs parent))
    (cond
      (string? content) (spit f content)
      (bytes? content) (with-open [out (io/output-stream f)]
                         (.write out ^bytes content))
      :else (throw (ex-info "unknown content type" {:path path})))
    ;; Best-effort 0600 / 0700 via Files.setPosixFilePermissions when
    ;; supported. Windows + non-POSIX filesystems silently fall through.
    (try
      (let [mode-l (long (or mode 0600))
            perms-str (case (int mode-l)
                        0600 "rw-------"
                        0700 "rwx------"
                        0644 "rw-r--r--"
                        "rw-------")
            perms (java.nio.file.attribute.PosixFilePermissions/fromString perms-str)]
        (java.nio.file.Files/setPosixFilePermissions
         (.toPath f) perms))
      (catch UnsupportedOperationException _ nil)
      (catch Exception e
        (log/warn "credentials: could not set posix permissions on"
                  path "—" (.getMessage e))))
    (.getAbsolutePath f)))

(defn- delete-quietly! [^String path]
  (try (when path (.delete (io/file path)))
       (catch Exception _ nil)))

(defn bind!
  "Resolve `bindings` against `store` and render them into env vars,
   files, and a log-mask set scoped to a per-build directory.

   Arguments
     store         — CredentialStore
     per-build-dir — absolute path the binding may write files into.
                      Created if missing. Caller is responsible for
                      cleanup of the directory at build end; `bind!`
                      writes its own files inside.
     bindings      — sequence of binding maps (see ns docstring)

   Returns on success:
     {:result :ok
      :env {STRING STRING}                — to merge over a step's env
      :files [{:path :mode :content} ...] — already materialized to disk
      :mask-values [STRING ...]           — feed into log-masker + the
                                             backend's :mask-values
      :cleanup-fn (fn [] ...)}            — call after step exits to
                                             delete materialized files

   Returns on failure:
     {:result :failed
      :explain STRING
      :unresolved [credential-id STRING ...]   — when store missed
      :unsupported [binding ...]?              — when render returned nil
      :rule :credential-unresolved
             | :credential-render-failed
             | :credential-store-missing}

   This function NEVER returns :ok with a binding that bound to \"\" —
   if a record was found but its required field is blank/missing, the
   binding is reported as :unsupported and the result is :failed."
  [store per-build-dir bindings]
  (cond
    (nil? store)
    {:result :failed
     :explain "no credential store provided"
     :rule :credential-store-missing
     :unresolved (mapv :credential-id bindings)}

    (or (nil? per-build-dir) (str/blank? per-build-dir))
    ;; per-build-dir missing is an environment/config bug, but the
    ;; build still cannot resolve its credentials — emit a distinct
    ;; rule AND surface :unresolved with every requested id so callers
    ;; that only feed (:unresolved r) into EX2 still classify the
    ;; build as :failure. No silent successes.
    {:result :failed
     :explain "per-build-dir is required"
     :rule :credential-per-build-dir-missing
     :unresolved (mapv :credential-id bindings)}

    (empty? bindings)
    {:result :ok :env {} :files [] :mask-values [] :cleanup-fn (constantly nil)}

    :else
    (let [_ (ensure-dir! per-build-dir)
          {:keys [resolved unresolved]}
          (reduce (fn [acc b]
                    (let [id (:credential-id b)
                          rec (lookup store id)]
                      (if rec
                        (update acc :resolved conj [b rec])
                        (update acc :unresolved conj id))))
                  {:resolved [] :unresolved []}
                  bindings)]
      (if (seq unresolved)
        {:result :failed
         :explain (str "credential(s) not found in store: "
                       (str/join ", " unresolved))
         :unresolved unresolved
         :rule :credential-unresolved}
        (let [renders (mapv (fn [[b rec]]
                              (let [r (render-binding b rec per-build-dir)]
                                {:binding b :rendered r}))
                            resolved)
              unsupported (->> renders
                               (remove :rendered)
                               (map :binding))]
          (if (seq unsupported)
            {:result :failed
             :explain (str (count unsupported)
                           " binding(s) could not be rendered against"
                           " the store records (type mismatch or missing"
                           " required field)")
             :unsupported (vec unsupported)
             :unresolved (mapv :credential-id unsupported)
             :rule :credential-render-failed}
            (let [merged-env (apply merge {} (map (comp :env :rendered) renders))
                  all-files  (vec (mapcat (comp :files :rendered) renders))
                  all-masks  (vec (mapcat (comp :mask-values :rendered) renders))
                  written-paths (mapv write-file! all-files)
                  cleanup (fn [] (run! delete-quietly! written-paths))]
              {:result :ok
               :env merged-env
               :files all-files
               :mask-values all-masks
               :cleanup-fn cleanup})))))))

(defn with-bindings!
  "Bracket helper: bind, run `body-fn` with the binding result, always
   call cleanup-fn. `body-fn` takes the binding map (whether :ok or
   :failed). Returns whatever body-fn returned."
  [store per-build-dir bindings body-fn]
  (let [r (bind! store per-build-dir bindings)]
    (try
      (body-fn r)
      (finally
        (when-let [c (:cleanup-fn r)] (c))))))

;; ---------------------------------------------------------------------------
;; Per-build config-file injection
;;
;; Apache Maven, npm, pip and Docker each read a per-user config file
;; with embedded credentials. CI builds traditionally render those files
;; from the current credential set, drop them in a per-build $HOME, and
;; tear down at exit. This namespace provides the three most common
;; templates; products can extend via `register-config-template!`.
;; ---------------------------------------------------------------------------

(defonce ^:private config-templates (atom {}))

(defn register-config-template!
  "Register a config-file template. `id` is a keyword; `f` is
   (fn [opts] → {:path STRING :mode INT :content STRING :mask-values [STRING …]?})"
  [id f]
  (swap! config-templates assoc id f)
  id)

(defn registered-config-templates []
  (keys @config-templates))

(defn render-config-template
  "Render a registered template by id. Returns the file spec or nil."
  [id opts]
  (when-let [f (get @config-templates id)]
    (f opts)))

;; -- maven settings.xml ----------------------------------------------------

(defn- maven-settings-template
  "opts: {:server-id :username :password :per-build-home}"
  [{:keys [server-id username password per-build-home]}]
  (when (and (non-blank? server-id)
             (non-blank? username)
             (non-blank? password)
             (non-blank? per-build-home))
    (let [content (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                       "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\">\n"
                       "  <servers>\n"
                       "    <server>\n"
                       "      <id>" (xml-escape server-id) "</id>\n"
                       "      <username>" (xml-escape username) "</username>\n"
                       "      <password>" (xml-escape password) "</password>\n"
                       "    </server>\n"
                       "  </servers>\n"
                       "</settings>\n")]
      {:path (str per-build-home java.io.File/separatorChar
                  ".m2" java.io.File/separatorChar "settings.xml")
       :mode (file-mode-600)
       :content content
       :mask-values [password]})))

;; -- npm .npmrc ----------------------------------------------------------

(defn- npmrc-template
  "opts: {:registry :auth-token :per-build-home}"
  [{:keys [registry auth-token per-build-home]}]
  (when (and (non-blank? registry)
             (non-blank? auth-token)
             (non-blank? per-build-home))
    (let [;; npm wants `//registry.npmjs.org/:_authToken=…` syntax
          host (-> registry
                   (str/replace #"^https?:" "")
                   (str/replace #"/$" ""))
          content (str host ":_authToken=" auth-token "\n"
                       "registry=" registry "\n")]
      {:path (str per-build-home java.io.File/separatorChar ".npmrc")
       :mode (file-mode-600)
       :content content
       :mask-values [auth-token]})))

;; -- docker config.json --------------------------------------------------

(defn- docker-config-template
  "opts: {:registry :auth-base64 :per-build-home}"
  [{:keys [registry auth-base64 per-build-home]}]
  (when (and (non-blank? registry)
             (non-blank? auth-base64)
             (non-blank? per-build-home))
    ;; Render via clojure.data.json so registries / auth blobs that
    ;; contain `"` or `\` produce valid JSON instead of subtle
    ;; auth-failures from a broken config.json. data.json is already on
    ;; the classpath via project.clj.
    (let [content (str (json/write-str
                        {:auths {registry {:auth auth-base64}}}
                        :escape-slash false)
                       "\n")]
      {:path (str per-build-home java.io.File/separatorChar
                  ".docker" java.io.File/separatorChar "config.json")
       :mode (file-mode-600)
       :content content
       :mask-values [auth-base64]})))

(defn register-default-templates!
  "Idempotent — register the bundled templates."
  []
  (register-config-template! :maven-settings maven-settings-template)
  (register-config-template! :npmrc npmrc-template)
  (register-config-template! :docker-config docker-config-template)
  nil)

(register-default-templates!)
