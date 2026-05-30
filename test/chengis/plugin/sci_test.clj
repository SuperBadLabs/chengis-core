(ns chengis.plugin.sci-test
  "Tests for the SCI plugin runtime — the two-context (trusted/sandboxed) model.
   These are the M1d adversarial tests: the sandbox must deny interop, ungranted
   capabilities, code loading, filesystem access, and runaway execution, while
   still letting a well-behaved plugin register handlers and the trusted context
   use interop."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.plugin.sci :as psci]))

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

(defn- blocked?
  "Eval the plugin and return :leaked if it succeeded, or the error message if
   it was denied (the desired outcome for a hostile plugin)."
  [opts]
  (try
    (psci/eval-plugin opts)
    :leaked
    (catch Throwable e (.getMessage e))))

;; ---------------------------------------------------------------------------
;; Sandboxed lane — well-behaved plugins
;; ---------------------------------------------------------------------------

(deftest sandboxed-plugin-registers-handler
  (testing "a sandboxed plugin can register a notifier through the host API"
    (psci/eval-plugin
     {:plugin-name "good-notify" :trust :sandboxed :capabilities #{:http :log}
      :source "(require '[chengis.plugin.host :as h])
               (h/register-notifier! :good
                 (fn [br cfg] (h/log \"sending\") {:status :sent}))"})
    (is (some #{:good} (registry/list-notifiers))
        "notifier should be registered into the host registry")
    (let [n (registry/get-notifier :good)]
      (is (= {:status :sent}
             (chengis.plugin.protocol/send-notification n {} {}))
          "the registered fn should be reified into a real Notifier record"))))

(deftest sandboxed-pure-helpers-available
  (testing "json encode/decode round-trips without any capability grant"
    (is (= {:a 1}
           (psci/eval-plugin
            {:plugin-name "pure" :trust :sandboxed :capabilities #{}
             :source "(require '[chengis.plugin.host :as h])
                      (h/json-decode (h/json-encode {:a 1}))"})))))

;; ---------------------------------------------------------------------------
;; Sandboxed lane — adversarial / denied
;; ---------------------------------------------------------------------------

(deftest sandbox-denies-java-interop
  (testing "Java interop is unavailable in the sandbox (no :classes)"
    (is (not= :leaked (blocked? {:plugin-name "evil-exit" :trust :sandboxed
                                 :source "(System/exit 1)"})))
    (is (not= :leaked (blocked? {:plugin-name "evil-rt" :trust :sandboxed
                                 :source "(.exec (Runtime/getRuntime) \"id\")"})))))

(deftest sandbox-denies-ungranted-capability
  (testing "a capability not declared in the manifest is not bound"
    (is (not= :leaked
              (blocked? {:plugin-name "nohttp" :trust :sandboxed :capabilities #{:log}
                         :source "(require '[chengis.plugin.host :as h])
                                  (h/http-post \"http://x\" {})"})))))

(deftest sandbox-denies-code-loading-and-fs
  (testing "eval / load / filesystem are denied"
    (is (not= :leaked (blocked? {:plugin-name "evil-eval" :trust :sandboxed
                                 :source "(eval '(+ 1 2))"})))
    (is (not= :leaked (blocked? {:plugin-name "evil-slurp" :trust :sandboxed
                                 :source "(slurp \"/etc/passwd\")"})))))

(deftest sandbox-blocks-reflection-escape
  (testing "instance interop cannot be chained into a classloader/reflection escape"
    ;; SCI allows a curated set of instance methods (.getClass, .length on
    ;; Object/String), but DENIES the Class.getClassLoader / Class.getName chain
    ;; that would otherwise reach arbitrary classes and defeat the sandbox.
    ;; This guards that boundary against a future SCI/config regression.
    (is (not= :leaked (blocked? {:plugin-name "refl-loader" :trust :sandboxed
                                 :source "(.getClassLoader (.getClass \"x\"))"})))
    (is (not= :leaked (blocked? {:plugin-name "refl-name" :trust :sandboxed
                                 :source "(.getName (.getClass \"x\"))"})))
    (is (not= :leaked (blocked? {:plugin-name "refl-runtime" :trust :sandboxed
                                 :source "(.loadClass (.getClassLoader (.getClass \"x\")) \"java.lang.Runtime\")"})))))

(deftest sandbox-enforces-wall-clock-timeout
  (testing "a runaway plugin is interrupted at the deadline"
    (let [msg (blocked? {:plugin-name "loop" :trust :sandboxed :timeout-ms 500
                         :source "(loop [] (recur))"})]
      (is (not= :leaked msg))
      (is (re-find #"timed out" (str msg))))))

;; ---------------------------------------------------------------------------
;; Trusted lane
;; ---------------------------------------------------------------------------

(deftest trusted-allows-interop
  (testing "first-party trusted context may use whitelisted interop"
    (is (= 5 (psci/eval-plugin {:plugin-name "fp" :trust :trusted
                                :source "(+ 1 (.length \"abcd\"))"})))))

(deftest unknown-capability-is-ignored-not-granted
  (testing "declaring an unknown capability does not bind anything dangerous"
    ;; :filesystem is not a known capability; it must not magically appear
    (is (not= :leaked
              (blocked? {:plugin-name "sneaky" :trust :sandboxed :capabilities #{:filesystem}
                         :source "(require '[chengis.plugin.host :as h]) (h/fetch-secret \"x\")"})))))

;; ---------------------------------------------------------------------------
;; Hardening regressions (Codex review of PR #167)
;; ---------------------------------------------------------------------------

(deftest sandboxed-cannot-override-existing-handler
  (testing "a sandboxed plugin must not overwrite an existing registry key (no hijack)"
    (registry/register-notifier! :taken
                                 (reify proto/Notifier
                                   (send-notification [_ _ _] {:status :native})))
    (psci/eval-plugin
     {:plugin-name "hijacker" :trust :sandboxed
      :source "(require '[chengis.plugin.host :as h])
               (h/register-notifier! :taken (fn [br cfg] {:status :hijacked}))"})
    (is (= {:status :native}
           (proto/send-notification (registry/get-notifier :taken) {} {}))
        "the original handler must remain; the sandboxed override is refused")))

(deftest failed-eval-rolls-back-registrations
  (testing "a plugin that registers a handler then throws commits nothing"
    (is (thrown? Throwable
                 (psci/eval-plugin
                  {:plugin-name "partial" :trust :sandboxed
                   :source "(require '[chengis.plugin.host :as h])
                            (h/register-notifier! :partial (fn [br cfg] {:status :sent}))
                            (throw (ex-info \"boom\" {}))"})))
    (is (nil? (registry/get-notifier :partial))
        "staged registration must be discarded when evaluation fails")))

(deftest sandboxed-callback-is-timeout-guarded
  (testing "a hung sandboxed handler is bounded at INVOCATION, not just at load"
    ;; The loop is in the fn body, so load succeeds; only invocation hangs.
    (psci/eval-plugin
     {:plugin-name "hang" :trust :sandboxed :call-timeout-ms 400
      :source "(require '[chengis.plugin.host :as h])
               (h/register-notifier! :hang (fn [br cfg] (loop [] (recur))))"})
    (let [n (registry/get-notifier :hang)]
      (is (some? n) "handler registers fine; the body hasn't run yet")
      (is (thrown-with-msg? Throwable #"timed out"
                            (proto/send-notification n {} {}))
          "invoking the hung handler times out instead of blocking forever"))))

(deftest fetch-secret-uses-configured-backend-and-real-config
  (testing ":secrets resolves the configured backend AND passes the real config through"
    ;; the backend echoes a setting it can only see if the real config is passed
    (registry/register-secret-backend! "vault"
                                       (reify proto/SecretBackend
                                         (fetch-secret [_ secret-name _ config]
                                           (str (get-in config [:secrets :vault :url]) "/" secret-name))
                                         (list-secrets [_ _ _] [])
                                         (fetch-secrets-for-build [_ _ _] {})))
    (let [cfg {:secrets {:backend "vault" :vault {:url "https://vault.example"}}}]
      (is (= "https://vault.example/token"
             (psci/eval-plugin
              {:plugin-name "sec" :trust :sandboxed :capabilities #{:secrets}
               :ds :dummy-ds :secret-config cfg
               :source "(require '[chengis.plugin.host :as h]) (h/fetch-secret \"token\")"}))
          "backend key comes from [:secrets :backend]; backend reads its settings from the real config"))))
