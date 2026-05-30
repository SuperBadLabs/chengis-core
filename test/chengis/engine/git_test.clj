(ns chengis.engine.git-test
  "Unit tests for git credential handling and result parsing. Pure logic plus
   process-call branches exercised via with-redefs. No DB — runs in :unit tier."
  (:require [clojure.test :refer [deftest is testing]]
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
