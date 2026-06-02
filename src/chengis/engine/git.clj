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
     :url             - git repo URL (required)
     :branch          - branch to checkout (optional, uses repo default)
     :depth           - shallow clone depth (optional, nil = full clone)
     :credentials     - {:ssh-key \"path\"} or {:token \"xxx\"}
     :sparse-checkout - vector of paths/patterns. When non-empty, clones with
                        `--filter=blob:none --no-checkout` (blobless partial
                        clone, no working-tree files until the explicit
                        sparse-checkout + checkout step runs). When empty/nil,
                        the legacy full-checkout clone path is used.

   Returns {:success? bool :error str}"
  [source-config workspace-dir]
  (let [{:keys [url branch depth credentials sparse-checkout]} source-config
        sparse? (seq sparse-checkout)
        clone-url (token-url url (:token credentials))
        ;; Build the command parts, properly quoting the URL. With
        ;; sparse-checkout we suppress the working-tree checkout and the
        ;; blob fetch — the follow-up sparse-checkout init/set + explicit
        ;; checkout produce a smaller, faster workspace.
        ;; CHG-FEAT-007 PR1 round 2 (Codex P2): keep `-b <branch>` even
        ;; in sparse mode. `--no-checkout` only suppresses the working-
        ;; tree population; `-b` is still honored to set HEAD.
        ;; `--depth` implicitly applies `--single-branch`, so without
        ;; `-b` a shallow sparse clone only ever fetches the remote
        ;; default branch — the subsequent `git checkout <branch>` then
        ;; can't find a non-default branch. Always pass `-b` when
        ;; `branch` is set.
        cmd (str "git clone"
                 (when sparse? " --filter=blob:none --no-checkout")
                 (when branch (str " -b " branch))
                 (when depth (str " --depth " depth))
                 " -- " (pr-str clone-url) " .")]
    (log/info "Cloning" (sanitize-url url)
              (when branch (str "branch:" branch))
              (when sparse? "(sparse)")
              "into" workspace-dir)
    (let [token (:token credentials)
          result (process/execute-command
                  (cond-> {:command cmd
                           :dir workspace-dir
                           :env (build-clone-env credentials)
                           :timeout 600000}
                    ;; CHG-FEAT-007 PR1 round 3 (Codex P1): the clone
                    ;; command embeds the PAT in the URL, and
                    ;; process/execute-command logs :command verbatim
                    ;; unless callers pass :mask-values. Without this,
                    ;; the first log line leaks the token (only
                    ;; stderr was previously sanitize-url'd).
                    token (assoc :mask-values [token])))]  ;; 10-minute timeout for large repos
      (if (zero? (:exit-code result))
        {:success? true}
        {:success? false
         :error (str "git clone failed (exit " (:exit-code result) "): "
                     (sanitize-url (or (:stderr result) "")))}))))

;; ---------------------------------------------------------------------------
;; CHG-FEAT-007 — submodules, sparse-checkout, and Git LFS
;; ---------------------------------------------------------------------------
;;
;; The three capabilities are orthogonal opt-ins on the pipeline :source map.
;; Each helper here is idempotent (safe to re-run on a retried build) and
;; composes cleanly with the others — the executor pipeline shape is:
;;
;;   clone (maybe --filter=blob:none --no-checkout)
;;     → sparse-checkout init/set   (if :sparse-checkout non-empty)
;;     → checkout <commit-or-branch>
;;     → submodule update --init --recursive   (if :submodules?)
;;     → lfs install --local + lfs pull        (if :lfs?)

(defn cone-compatible-pattern?
  "Cone-mode sparse-checkout patterns must be unambiguous, glob-free
   directory prefixes. We require:

     - Only `[A-Za-z0-9._/-]` characters. Anything outside that
       allow-list is rejected, which incidentally blocks shell
       metacharacters (`$`, backtick, `;`, `&`, `|`, `(`, `)`, `<`, `>`,
       space, quotes, etc.) — sparse patterns become part of a shell
       command and `pr-str` only double-quotes, which is NOT
       shell-safe against command substitution (CHG-FEAT-007 PR1
       round 6, Codex P2 — `src/$(touch /tmp/pwn)/` would pass
       trailing-slash validation but execute the substitution).
     - Not starting with `!` (cone mode rejects negations) or `-`
       (Codex P2 round 7 — `git sparse-checkout set` parses leading-
       dash arguments as option flags like `--no-cone` or `--stdin`,
       so a path beginning with `-` would change the command's
       behaviour rather than select the path; the command-side `--`
       separator is the belt, and this validator rule is the
       suspenders).
     - Not containing `*`, `?`, `[` (covered by the allow-list above,
       but kept as an explicit safeguard in case the allow-list ever
       widens).
     - NESTED patterns (containing `/`) MUST end with `/`. Cone mode
       silently broadens a nested non-directory pattern to its parent
       directory, defeating the operator's intent (round 4, Codex P2).
       TOP-LEVEL patterns (no `/`) are allowed regardless — `git
       sparse-checkout set -- src` accepts a top-level directory name
       and normalizes to `/src/`; if the operator wrote a top-level
       FILE name like `Chengisfile`, git fails at runtime with a
       clear `fatal: '<X>' is not a directory` rather than us
       false-rejecting valid top-level dir patterns (round 13,
       Codex P2 — the round-9 strict rule was too aggressive).

   Examples:
     - `src/`        → allowed (directory prefix)
     - `src/utils/`  → allowed (nested directory)
     - `src`         → allowed (top-level — git validates at runtime)
     - `Chengisfile` → allowed by validator; git fails with clear error if it's a file
     - `src/utils`   → REJECTED (nested without trailing `/`)
     - `src/Makefile`→ REJECTED (nested file path; cone would broaden)
     - `src/only.clj`→ REJECTED (same)
     - `src/$(x)/`   → REJECTED (shell metacharacter)"
  [pattern]
  (let [s (str pattern)
        allowed-chars? (boolean (re-matches #"[A-Za-z0-9._/-]+" s))
        nested-without-slash? (and (str/includes? s "/")
                                   (not (str/ends-with? s "/")))]
    (and allowed-chars?
         (not (str/starts-with? s "!"))
         (not (str/starts-with? s "-"))
         (not (re-find #"[*?\[]" s))
         (not nested-without-slash?))))

(defn- validate-sparse-patterns!
  "Throw ex-info on non-cone-compatible sparse-checkout patterns so the
   pipeline fails fast at parse/setup time rather than appearing to clone
   but silently selecting too much (cone mode broadens nested file paths
   to their parent directory) or matching nothing (negations / globs)."
  [patterns]
  (doseq [p patterns]
    (when-not (cone-compatible-pattern? p)
      (throw (ex-info (str "Invalid sparse-checkout pattern (cone mode "
                           "requires directory prefixes ending with `/` "
                           "— no negations, glob metacharacters, leading "
                           "dashes, or non-directory paths. Top-level files "
                           "are kept by cone mode automatically and need "
                           "no pattern): " (pr-str p))
                      {:error :invalid-sparse-pattern
                       :pattern p})))))

(defn sparse-checkout-init!
  "Configure cone-mode sparse-checkout in an already-cloned (--no-checkout)
   workspace, restricting future checkouts to the listed paths. Idempotent —
   `sparse-checkout set` overwrites the prior selection.

   patterns must be non-empty and cone-compatible (validated upstream by
   `validate-sparse-patterns!`). Returns {:success? bool :error str}."
  [patterns workspace-dir]
  (let [init-result (process/execute-command
                     {:command "git sparse-checkout init --cone"
                      :dir workspace-dir
                      :timeout 30000})]
    (if-not (zero? (:exit-code init-result))
      {:success? false
       :error (str "git sparse-checkout init failed (exit "
                   (:exit-code init-result) "): " (:stderr init-result))}
      (let [;; CHG-FEAT-007 PR1 round 7 (Codex P2): the `--` operand
            ;; separator stops `git sparse-checkout set` from parsing
            ;; any pattern as an option flag (like `--no-cone`,
            ;; `--stdin`). The validator above already rejects
            ;; leading-`-` patterns; the `--` here is the belt to
            ;; that suspenders. Quoting with pr-str is safe because
            ;; the allow-list validator forbids shell metacharacters.
            quoted (str/join " " (map pr-str patterns))
            set-result (process/execute-command
                        {:command (str "git sparse-checkout set -- " quoted)
                         :dir workspace-dir
                         :timeout 30000})]
        (if (zero? (:exit-code set-result))
          {:success? true}
          {:success? false
           :error (str "git sparse-checkout set failed (exit "
                       (:exit-code set-result) "): " (:stderr set-result))})))))

(defn- token-insteadof-args
  "Build `-c url.<token-url-base>.insteadOf=<plain-url-base>` arguments to
   inject HTTPS token credentials into same-host submodule fetches.

   Submodule URLs in `.gitmodules` are stored verbatim (no token).
   `git submodule update` clones those URLs directly — `build-clone-env`
   only sets SSH env, and the token in the parent's remote URL isn't
   carried over. Without an `insteadOf` rewrite, private same-host
   submodules fail auth (CHG-FEAT-007 PR1 round 2, Codex P2).

   For an HTTPS parent URL `https://github.com/org/parent.git` with a
   token, returns `-c url.\"https://x-access-token:TOKEN@github.com/\".insteadOf=\"https://github.com/\"`.

   Returns an empty string when:
     - credentials has no :token (SSH path inherits GIT_SSH_COMMAND env)
     - the parent URL isn't HTTPS
     - the host can't be extracted

   Cross-host submodules (parent on github.com, submodule on
   gitlab.com) won't get the token — operators need cross-host creds via
   a future :submodule-credentials shape; that's out of scope here."
  [parent-url credentials]
  (let [token (:token credentials)
        host-match (when token (re-find #"^(https?://)([^/]+)/" (str parent-url)))]
    (if (and token host-match)
      (let [scheme (nth host-match 1)
            host (nth host-match 2)
            plain-base (str scheme host "/")
            token-base (str scheme "x-access-token:" token "@" host "/")]
        (str " -c url." (pr-str token-base) ".insteadOf=" (pr-str plain-base)))
      "")))

(defn submodule-update!
  "Initialize and recursively update submodules in the workspace.
   `git submodule update --init --recursive` is idempotent — running it
   against an already-initialized tree is a no-op.

   Credentials from the source-config propagate two ways:
     - SSH key  → inherited via `build-clone-env`'s `GIT_SSH_COMMAND`.
     - HTTPS token → injected as an `-c url.\"...\".insteadOf=...`
       config for same-host submodules so `.gitmodules` URLs get
       rewritten before the submodule fetch. See `token-insteadof-args`.

   When `:sparse-checkout` is set on the source-config, the sparse
   patterns are passed as positional pathspec args so only submodules
   inside the sparse selection get initialized (CHG-FEAT-007 PR1
   round 5, Codex P2 — otherwise sparse + submodules still clones
   every submodule listed in `.gitmodules`, defeating sparse's
   bandwidth-saving intent).

   Returns {:success? bool :error str}."
  [source-config workspace-dir]
  (let [{:keys [credentials url sparse-checkout]} source-config
        config-args (token-insteadof-args url credentials)
        token (:token credentials)
        ;; CHG-FEAT-007 PR1 round 5: scope submodule init to the sparse
        ;; selection. Empty/nil sparse-checkout → no pathspec, original
        ;; behaviour (all submodules).
        pathspec-args (when (seq sparse-checkout)
                        (str " -- " (str/join " " (map pr-str sparse-checkout))))
        result (process/execute-command
                (cond-> {:command (str "git" config-args
                                       " submodule update --init --recursive"
                                       pathspec-args)
                         :dir workspace-dir
                         :env (build-clone-env credentials)
                         :timeout 600000}
                  ;; CHG-FEAT-007 PR1 round 3 (Codex P1): the
                  ;; insteadOf rewrite embeds the PAT directly in
                  ;; the command string, so :mask-values must
                  ;; redact it before logging.
                  token (assoc :mask-values [token])))]
    (if (zero? (:exit-code result))
      {:success? true}
      {:success? false
       :error (str "git submodule update failed (exit "
                   (:exit-code result) "): "
                   (sanitize-url (or (:stderr result) "")))})))

(defn lfs-available?
  "True iff `git lfs version` succeeds — i.e. the git-lfs plugin is installed
   on this agent. Probed before any `lfs install`/`lfs pull` so we can fail
   with a clear error message instead of a cryptic `git: 'lfs' is not a git
   command` line buried in the build log."
  []
  (let [result (process/execute-command
                {:command "git lfs version"
                 :timeout 10000})]
    (zero? (:exit-code result))))

(defn lfs-pull!
  "Initialize Git LFS for the local repository (no global config side
   effects) and pull all LFS-tracked blobs that intersect the current
   checkout. Both commands are idempotent.

   Composes with sparse-checkout: only LFS pointers within the sparse
   selection get resolved into real blobs.

   Composes with submodules: when `:recurse-submodules?` is true, also
   runs `git submodule foreach --recursive git lfs pull` so LFS-tracked
   files inside submodules are resolved too. `git lfs pull` on the
   superproject does NOT recurse on its own (CHG-FEAT-007 PR1 round 2,
   Codex P2). The foreach is a no-op for submodules without LFS, so
   it's safe to enable whenever both opt-ins are set.

   CHG-FEAT-007 PR1 round 3 (Codex P2): credentials propagate so LFS
   transports authenticate the same way as the clone:
     - SSH key  → `GIT_SSH_COMMAND` env via `build-clone-env`
     - HTTPS token → already in remote.origin.url for the superproject
       (clone-url injected the token), AND injected via insteadOf into
       the foreach's inner `git lfs pull` so private same-host
       submodule LFS endpoints authenticate too.

   Throws ex-info `:git-lfs-missing` when the git-lfs plugin is not
   installed on the agent — a hard failure is preferable to silently
   shipping LFS pointer files into the build.

   Returns {:success? bool :error str}."
  ([workspace-dir]
   (lfs-pull! workspace-dir {:recurse-submodules? false}))
  ([workspace-dir {:keys [recurse-submodules? credentials parent-url sparse-include]}]
   (when-not (lfs-available?)
     (throw (ex-info
             (str "git-lfs is not installed on this agent; install via "
                  "apt/yum/brew or disable :lfs? in the pipeline config")
             {:error :git-lfs-missing})))
   (let [token (:token credentials)
         env (build-clone-env credentials)
         masked (when token [token])
         base-opts (cond-> {:dir workspace-dir :env env}
                     (seq masked) (assoc :mask-values masked))
         ;; CHG-FEAT-007 PR1 round 5 (Codex P2): when sparse-checkout
         ;; is set, scope `git lfs pull` to the sparse patterns via
         ;; `--include` so LFS bandwidth is bounded by the sparse
         ;; selection rather than fetching every LFS object in the
         ;; repo. `--include` takes a comma-separated list of globs;
         ;; cone patterns end with `/`, which git-lfs's matcher treats
         ;; as a directory prefix (everything underneath).
         ;;
         ;; Round 12 (Codex P2) — KNOWN LIMITATION: `git lfs pull
         ;; --include` matches against LFS POINTER PATHS in HEAD, not
         ;; against the sparse-materialized working tree. There's no
         ;; clean way to express "top-level files + these subdirs"
         ;; via a single include glob — `*` (round 11 attempt) is
         ;; treated by git-lfs as "any path" and includes all subdir
         ;; files too, defeating the sparse bound. The honest
         ;; trade-off is documented: when both `:sparse-checkout` and
         ;; `:lfs?` are set, LFS pointers OUTSIDE the sparse
         ;; selection (including TOP-LEVEL LFS pointers that cone
         ;; materializes into the working tree) stay unresolved.
         ;; Operators with top-level LFS pointers should either
         ;; disable sparse OR run `git lfs pull` again manually
         ;; against the unresolved subset. See git-lfs#3754 for
         ;; upstream tracking.
         lfs-pull-cmd (str "git lfs pull"
                           (when (seq sparse-include)
                             (str " --include="
                                  (pr-str (str/join "," sparse-include)))))
         install-result (process/execute-command
                         (merge base-opts
                                {:command "git lfs install --local"
                                 :timeout 30000}))]
     (if-not (zero? (:exit-code install-result))
       {:success? false
        :error (str "git lfs install failed (exit "
                    (:exit-code install-result) "): "
                    (:stderr install-result))}
       (let [pull-result (process/execute-command
                          (merge base-opts
                                 {:command lfs-pull-cmd
                                  :timeout 600000}))]
         (cond
           (not (zero? (:exit-code pull-result)))
           {:success? false
            :error (str "git lfs pull failed (exit "
                        (:exit-code pull-result) "): "
                        (:stderr pull-result))}

           recurse-submodules?
           (let [;; Inject insteadOf inside the foreach's inner shell
                 ;; command so same-host HTTPS submodule LFS endpoints
                 ;; pick up the parent's token. SSH submodules inherit
                 ;; GIT_SSH_COMMAND from the foreach's parent env.
                 ;; NOTE: the foreach's `--include` would only apply
                 ;; INSIDE each submodule (paths relative to the
                 ;; submodule root), which isn't useful for bounding
                 ;; by the superproject's sparse selection — the
                 ;; submodule init in round-5's `submodule-update!`
                 ;; already restricts WHICH submodules get initialised
                 ;; via the pathspec args, so submodules outside the
                 ;; sparse selection don't reach this foreach at all.
                 inner-config (token-insteadof-args parent-url credentials)
                 inner-cmd (str "git" inner-config " lfs pull")
                 foreach-cmd (str "git submodule foreach --recursive "
                                  (pr-str inner-cmd))
                 foreach-result
                 (process/execute-command
                  (merge base-opts
                         {:command foreach-cmd
                          :timeout 600000}))]
             (if (zero? (:exit-code foreach-result))
               {:success? true}
               {:success? false
                :error (str "git submodule foreach git lfs pull failed (exit "
                            (:exit-code foreach-result) "): "
                            (:stderr foreach-result))}))

           :else
           {:success? true}))))))

(defn checkout!
  "Checkout a specific commit SHA in an already-cloned workspace.

   Accepts an optional credentials map so checkouts in sparse-cloned
   (`--filter=blob:none --no-checkout`) workspaces can authenticate the
   lazy promisor fetches that materialize the working tree's blobs.
   Without this, sparse + SSH-key auth fails the checkout step even
   though the original clone authenticated (CHG-FEAT-007 PR1 round 6,
   Codex P2). For non-sparse / fully-fetched clones the env is
   harmless — git just doesn't dial the remote.

   Returns {:success? bool :error str}"
  ([commit workspace-dir]
   (checkout! commit workspace-dir nil))
  ([commit workspace-dir credentials]
   (when commit
     (log/info "Checking out commit:" commit)
     (let [token (:token credentials)
           opts (cond-> {:command (str "git checkout " commit)
                         :dir workspace-dir
                         :timeout 30000}
                  credentials (assoc :env (build-clone-env credentials))
                  token (assoc :mask-values [token]))
           result (process/execute-command opts)]
       (if (zero? (:exit-code result))
         {:success? true}
         {:success? false
          :error (str "git checkout failed: " (:stderr result))})))))

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
   ;; CHG-FEAT-007 — destructure the opt-in keys and validate sparse-checkout
   ;; patterns up front so an invalid pattern fails the build before we burn
   ;; a clone on it.
   (let [{:keys [sparse-checkout submodules? lfs? branch]} source-config
         sparse? (boolean (seq sparse-checkout))
         _ (when sparse? (validate-sparse-patterns! sparse-checkout))
         clone-result (clone! source-config workspace-dir)
         ;; When the clone used --no-checkout (sparse mode), populate the
         ;; sparse selection before any checkout step so the working tree is
         ;; correctly filtered. Sparse-init failure is a hard failure — we
         ;; can't fall back to a non-sparse checkout from a blobless partial
         ;; clone without re-fetching everything.
         sparse-init (when (and sparse? (:success? clone-result))
                       (sparse-checkout-init! sparse-checkout workspace-dir))
         ;; Sparse mode passed `--no-checkout`, so when the caller didn't
         ;; provide a commit-override we still need to materialize the
         ;; working tree. Check out the branch (if any) or HEAD otherwise.
         ;; Non-sparse path is unchanged from pre-FEAT-007.
         sparse-branch-co (when (and sparse?
                                     (:success? clone-result)
                                     (or (nil? sparse-init)
                                         (:success? sparse-init))
                                     (nil? commit-override))
                            ;; CHG-FEAT-007 PR1 round 6 (Codex P2): pass
                            ;; credentials so the lazy promisor fetch
                            ;; (--filter=blob:none clones materialize
                            ;; blobs on checkout) inherits the same
                            ;; auth as the original clone.
                            (checkout! (or branch "HEAD") workspace-dir
                                       (:credentials source-config)))
         ;; Attempt commit-override checkout — git itself is the canonical
         ;; test for "is this commit reachable?". URL string-equality
         ;; comparisons are unnecessary (Codex PR #24 P2 r4 — equivalent URL
         ;; forms never trigger a spurious retry).
         ok-so-far? (and (:success? clone-result)
                         (or (nil? sparse-init) (:success? sparse-init))
                         (or (nil? sparse-branch-co) (:success? sparse-branch-co)))
         initial-co (when (and ok-so-far? commit-override)
                      (checkout! commit-override workspace-dir
                                 (:credentials source-config)))
         ;; On checkout failure with a head-repo-url, try fetching the SHA
         ;; from the fork remote and retrying. The fetch is reactive, not
         ;; pre-emptive.
         retry-co
         (when (and ok-so-far?
                    commit-override
                    (or head-repo-url head-repo-url-ssh)
                    initial-co
                    (not (:success? initial-co)))
           (let [fetch-result (fetch-pr-head! head-repo-url head-repo-url-ssh
                                              commit-override workspace-dir
                                              (:credentials source-config))]
             (if (:success? fetch-result)
               (checkout! commit-override workspace-dir
                          (:credentials source-config))
               ;; Fetch failed — surface the fetch error
               {:success? false
                :error (str "checkout of " commit-override
                            " failed and fork fetch failed: "
                            (:error fetch-result))})))
         final-co (or retry-co initial-co)]
     (cond
       ;; Pre-checkout failures short-circuit with nil git-info.
       (not (:success? clone-result))
       (assoc clone-result :git-info nil)

       (and sparse-init (not (:success? sparse-init)))
       (assoc sparse-init :git-info nil)

       (and sparse-branch-co (not (:success? sparse-branch-co)))
       (assoc sparse-branch-co :git-info nil)

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
       (let [_ (when (and final-co (not (:success? final-co)))
                 (log/warn "Commit checkout failed, continuing on branch HEAD:"
                           (:error final-co)))
             ;; CHG-FEAT-007 PR1 round 8 (Codex P2): sparse mode used
             ;; --no-checkout, so when the commit-override checkout
             ;; failed and we're in the lenient branch (no
             ;; require-commit-checkout?), the working tree is still
             ;; empty. Materialize it on branch HEAD now so the build
             ;; runs against actual files instead of a phantom workspace.
             ;; Non-sparse path is unaffected — the clone already
             ;; populated the working tree.
             sparse-fallback-co (when (and sparse?
                                           final-co
                                           (not (:success? final-co)))
                                  (checkout! (or branch "HEAD") workspace-dir
                                             (:credentials source-config)))]
         ;; Round 10 (Codex P2): if the sparse fallback itself fails,
         ;; the working tree is STILL empty — get-git-info would
         ;; happily read HEAD's metadata and report success, but the
         ;; build would run against a phantom workspace. Fail the
         ;; checkout instead. Non-sparse path is unreachable here
         ;; (sparse-fallback-co is nil when sparse? is false).
         (if (and sparse-fallback-co (not (:success? sparse-fallback-co)))
           {:success? false
            :git-info nil
            :error (str "sparse-checkout fallback branch checkout failed "
                        "(commit " commit-override " was unreachable "
                        "and the lenient branch fallback also failed): "
                        (:error sparse-fallback-co))}

         ;; CHG-FEAT-007 — orchestrate the post-checkout opt-ins. Order
         ;; matters: submodules first (so their pointer files + .git
         ;; metadata exist for the LFS step), then LFS. Each step is
         ;; independent and idempotent. When BOTH opt-ins are on,
         ;; `lfs-pull!` is asked to recurse via `git submodule foreach
         ;; --recursive git lfs pull` — the superproject `lfs pull` does
         ;; NOT recurse on its own (round 2, Codex P2).
           (let [sub-result (when submodules?
                              (submodule-update! source-config workspace-dir))
                 lfs-result (when (and lfs?
                                       (or (nil? sub-result)
                                           (:success? sub-result)))
                              (lfs-pull! workspace-dir
                                         {:recurse-submodules? (boolean submodules?)
                                          :credentials (:credentials source-config)
                                          :parent-url (:url source-config)
                                        ;; Round 5 (Codex P2): bound LFS pulls
                                        ;; by the sparse selection so out-of-
                                        ;; scope LFS objects don't get fetched.
                                          :sparse-include sparse-checkout}))]
             (cond
               (and sub-result (not (:success? sub-result)))
               {:success? false :git-info nil :error (:error sub-result)}

               (and lfs-result (not (:success? lfs-result)))
               {:success? false :git-info nil :error (:error lfs-result)}

               :else
               (let [git-info (get-git-info workspace-dir)]
                 {:success? true
                  :git-info git-info})))))))))
