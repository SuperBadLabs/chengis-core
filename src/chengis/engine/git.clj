(ns chengis.engine.git
  "Git operations via CLI. Uses process/execute-command for all git calls.
   Never logs credentials — URLs are sanitized before logging."
  (:require [chengis.engine.process :as process]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Credential handling
;; ---------------------------------------------------------------------------

(defn sanitize-url
  "Remove credentials from a git URL for safe logging.
   https://user:token@github.com/foo/bar.git -> https://***@github.com/foo/bar.git"
  [url]
  (str/replace (str url) #"(https?://)([^@]+)@" "$1***@"))

(defn- build-clone-env
  "Build environment map for git commands with credential support.
   :ssh-key  - path to private key → sets GIT_SSH_COMMAND
   :token    - HTTPS personal access token → injected into URL instead"
  [credentials]
  (let [{:keys [ssh-key]} credentials]
    (if ssh-key
      {"GIT_SSH_COMMAND" (str "ssh -i " ssh-key " -o StrictHostKeyChecking=accept-new")}
      {})))

(defn- token-url
  "Inject HTTPS token into clone URL if present.
   https://github.com/foo/bar.git -> https://x-access-token:TOKEN@github.com/foo/bar.git"
  [url token]
  (if token
    (str/replace url #"(https?://)" (str "$1x-access-token:" token "@"))
    url))

(defn- https->ssh
  "Convert an https URL to its SSH-clone equivalent so a configured ssh-key
   can authenticate the fork-fetch.

     https://HOST/PATH              → git@HOST:PATH          (SCP-style)
     https://HOST:PORT/PATH         → ssh://git@HOST:PORT/PATH
                                      (URL-style — SCP-style cannot encode
                                      a port on self-hosted Git servers that
                                      expose HTTPS on non-default ports;
                                      Codex PR #24 P2 r6).

   Non-https inputs (already SSH or ssh:// form) pass through unchanged."
  [url]
  (let [s (str url)]
    (cond
      ;; https with explicit port → ssh:// URL form
      (re-matches #"https?://[^/:]+:\d+/.+" s)
      (let [[_ host port path] (re-matches #"https?://([^/:]+):(\d+)/(.+)" s)]
        (str "ssh://git@" host ":" port "/" path))

      ;; plain https → SCP form
      (re-matches #"https?://[^/]+/.+" s)
      (let [[_ host path] (re-matches #"https?://([^/]+)/(.+)" s)]
        (str "git@" host ":" path))

      :else s)))

(defn- effective-fetch-url
  "Pick the right URL form to fetch from, matching the source's auth transport.

     :ssh-key cred → prefer head-url-ssh if provided (canonical SSH URL
                     straight from the SCM webhook — GitHub head.repo.ssh_url
                     or GitLab source.git_ssh_url). Falls back to the
                     heuristic https->ssh rewrite when no SSH URL is
                     available (Codex PR #24 P2 r9 — fixes self-hosted
                     instances where SSH runs on a different host/port).
     :token cred   → inject token into https URL.
     no cred       → use the URL as-is (anonymous; will fail on private forks)."
  [head-url head-url-ssh credentials]
  (cond
    (:ssh-key credentials) (or head-url-ssh (https->ssh head-url))
    (:token credentials)   (token-url      head-url (:token credentials))
    :else                  (str head-url)))

;; ---------------------------------------------------------------------------
;; Core git operations
;; ---------------------------------------------------------------------------

(defn clone!
  "Clone a git repository into workspace-dir.

   source-config keys:
     :url         - git repo URL (required)
     :branch      - branch to checkout (optional, uses repo default)
     :depth       - shallow clone depth (optional, nil = full clone)
     :credentials - {:ssh-key \"path\"} or {:token \"xxx\"}

   Returns {:success? bool :error str}"
  [source-config workspace-dir]
  (let [{:keys [url branch depth credentials]} source-config
        clone-url (token-url url (:token credentials))
        ;; Build the command parts, properly quoting the URL
        cmd (str "git clone"
                 (when branch (str " -b " branch))
                 (when depth (str " --depth " depth))
                 " -- " (pr-str clone-url) " .")]
    (log/info "Cloning" (sanitize-url url)
              (when branch (str "branch:" branch))
              "into" workspace-dir)
    (let [result (process/execute-command
                  {:command cmd
                   :dir workspace-dir
                   :env (build-clone-env credentials)
                   :timeout 600000})]  ;; 10-minute timeout for large repos
      (if (zero? (:exit-code result))
        {:success? true}
        {:success? false
         :error (str "git clone failed (exit " (:exit-code result) "): "
                     (sanitize-url (or (:stderr result) "")))}))))

(defn checkout!
  "Checkout a specific commit SHA in an already-cloned workspace.
   Returns {:success? bool :error str}"
  [commit workspace-dir]
  (when commit
    (log/info "Checking out commit:" commit)
    (let [result (process/execute-command
                  {:command (str "git checkout " commit)
                   :dir workspace-dir
                   :timeout 30000})]
      (if (zero? (:exit-code result))
        {:success? true}
        {:success? false
         :error (str "git checkout failed: " (:stderr result))}))))

(defn fetch-pr-head!
  "Fetch a specific commit from an alternate remote URL into the workspace.
   Used by `checkout-source!` for forked PRs/MRs where the head SHA does not
   exist in the base clone (CHG-FEAT-002 — fork-safety).

   Credentials are reused from the source-config so the fetch can read private
   forks via the same auth method as the base clone.

   Returns {:success? bool :error str}."
  [head-repo-url head-repo-url-ssh commit workspace-dir credentials]
  (when (and (or head-repo-url head-repo-url-ssh) commit)
    (let [fetch-url (effective-fetch-url head-repo-url head-repo-url-ssh credentials)
          cmd (str "git fetch -- " (pr-str fetch-url) " " commit)]
      (log/info "Fetching PR head" (sanitize-url head-repo-url) "@" commit "into workspace")
      (let [result (process/execute-command
                    {:command cmd
                     :dir workspace-dir
                     :env (build-clone-env credentials)
                     :timeout 600000})]
        (if (zero? (:exit-code result))
          {:success? true}
          {:success? false
           :error (str "git fetch from PR head failed (exit " (:exit-code result) "): "
                       (sanitize-url (or (:stderr result) "")))})))))

(defn get-git-info
  "Extract git metadata from the workspace after clone/checkout.
   Returns {:branch str :commit str :commit-short str :author str :message str
            :remote-url str} or nil if not a git repo."
  [workspace-dir]
  (let [run-git (fn [args]
                  (let [result (process/execute-command
                                {:command (str "git " args)
                                 :dir workspace-dir
                                 :timeout 10000})]
                    (when (zero? (:exit-code result))
                      (str/trim (:stdout result)))))]
    (when-let [commit (run-git "rev-parse HEAD")]
      {:commit       commit
       :commit-short (or (run-git "rev-parse --short HEAD") (subs commit 0 (min 7 (count commit))))
       :branch       (or (run-git "rev-parse --abbrev-ref HEAD") "detached")
       :author       (or (run-git "log -1 --format=%an") "")
       :author-email (or (run-git "log -1 --format=%ae") "")
       :message      (or (run-git "log -1 --format=%s") "")
       :remote-url   (run-git "config --get remote.origin.url")})))

;; ---------------------------------------------------------------------------
;; High-level entry point (called by executor)
;; ---------------------------------------------------------------------------

(defn checkout-source!
  "Clone repo, optionally checkout specific commit, return git metadata.

   Arguments:
     source-config   - the :source map from the pipeline definition
     workspace-dir   - the build workspace path
     commit-override - optional commit SHA (e.g., from webhook payload)
     opts            - optional map:
                       :head-repo-url     - HTTPS URL to fetch the commit from
                                        on checkout failure. Used for forked
                                        PRs/MRs (CHG-FEAT-002). The fetch is
                                        REACTIVE — only attempted if the
                                        initial `git checkout` fails — so
                                        equivalent URL forms for the same
                                        repo never trigger a spurious retry.
                       :head-repo-url-ssh - canonical SSH URL from the SCM
                                        webhook payload (GitHub
                                        head.repo.ssh_url, GitLab
                                        source.git_ssh_url). Preferred over
                                        :head-repo-url when source uses
                                        :ssh-key cred (Codex PR #24 P2 r9 —
                                        avoids heuristic https->ssh rewrite
                                        breaking on self-hosted instances).
                       :require-commit-checkout? - when true (PR/MR builds),
                                        a final failed `git checkout` of
                                        commit-override is treated as a hard
                                        failure instead of degrading to
                                        branch HEAD. Defaults false so
                                        non-PR triggers retain the legacy
                                        lenient behavior.

   Returns {:success? bool :git-info {...} :error str}"
  ([source-config workspace-dir commit-override]
   (checkout-source! source-config workspace-dir commit-override nil))
  ([source-config workspace-dir commit-override
    {:keys [head-repo-url head-repo-url-ssh require-commit-checkout?]}]
   (let [clone-result (clone! source-config workspace-dir)]
     (if-not (:success? clone-result)
       (assoc clone-result :git-info nil)
       ;; Attempt checkout first — git itself is the canonical test for
       ;; "is this commit reachable?". This makes URL string-equality
       ;; comparisons unnecessary (Codex PR #24 P2 r4 — equivalent URL forms
       ;; never trigger a spurious retry).
       (let [initial-co (when commit-override
                          (checkout! commit-override workspace-dir))
             ;; On checkout failure with a head-repo-url, try fetching the
             ;; SHA from the fork remote and retrying. The fetch is reactive,
             ;; not pre-emptive.
             retry-co
             (when (and commit-override
                        (or head-repo-url head-repo-url-ssh)
                        initial-co
                        (not (:success? initial-co)))
               (let [fetch-result (fetch-pr-head! head-repo-url head-repo-url-ssh
                                                  commit-override workspace-dir
                                                  (:credentials source-config))]
                 (if (:success? fetch-result)
                   (checkout! commit-override workspace-dir)
                   ;; Fetch failed — surface the fetch error
                   {:success? false
                    :error (str "checkout of " commit-override
                                " failed and fork fetch failed: "
                                (:error fetch-result))})))
             final-co (or retry-co initial-co)]
         (cond
           ;; Strict mode (PR/MR) without a commit SHA at all (incomplete
           ;; webhook payload) — the build CANNOT pin to the head SHA, so
           ;; running on whatever-branch-HEAD-happens-to-be is wrong.
           ;; Codex PR #24 P2 r10 — was previously silently succeeding.
           (and require-commit-checkout? (nil? commit-override))
           {:success? false
            :git-info nil
            :error (str "PR/MR build requires a commit SHA but the webhook "
                        "payload provided none — cannot pin to the PR head "
                        "revision; refusing to run on branch HEAD.")}

           ;; Strict mode (PR/MR): the commit-override checkout (after any
           ;; reactive fetch retry) MUST succeed. Without this guard, a fork
           ;; PR whose head-repo-url is nil (deleted/missing) would silently
           ;; build base-branch HEAD on the wrong SHA.
           (and require-commit-checkout?
                final-co
                (not (:success? final-co)))
           {:success? false
            :git-info nil
            :error (str "PR/MR build requires checkout of commit "
                        commit-override " but checkout failed: "
                        (:error final-co))}

           :else
           (do
             (when (and final-co (not (:success? final-co)))
               (log/warn "Commit checkout failed, continuing on branch HEAD:"
                         (:error final-co)))
             (let [git-info (get-git-info workspace-dir)]
               {:success? true
                :git-info git-info}))))))))
