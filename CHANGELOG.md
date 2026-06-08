# chengis-core — CHANGELOG

All notable changes to `chengis-core` are documented here. The format
loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows [Semantic Versioning](https://semver.org/) but with
the pre-1.0 disclaimer: **API may break across minor releases until
`1.0.0`**.

## [0.4.2] — 2026-06-08

**Theme:** silent-failure follow-up. Three bugs in the K8s backend +
log-masker, all bugs of omission rather than commission — code worked
in the happy path and silently misbehaved in edge cases. Caught by
post-0.4.1 code review.

### Fixed

- **`wait-for-pod-phase` cancel race.** `wait-for-pod-phase` only
  terminated on `Succeeded`/`Failed` or timeout. When `cancel` deleted
  the pod mid-build, kubectl started returning NotFound, phase became
  the empty string, and the loop polled until the full 300s timeout —
  the executor sat for 5 minutes after every cancel before being
  unblocked. Now: a non-zero kubectl exit whose stderr contains
  `NotFound` short-circuits to a new `:deleted` result; the caller in
  `run-disposable-pod` skips the redundant `delete-pod!` (the pod is
  already gone) and returns `{:exit-code 137 :cancelled? true ...}`.
  Cancel acknowledges in <500ms instead of waiting out the timeout.

- **Label values reach the apiserver unsanitized.** Pod label values
  were the raw `job-name` / `build-number` strings. k8s label values
  must match `[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?` and be ≤63
  chars; anvil job-names containing `/` (`"org/repo"`), `:`
  (`"branch:tag"`), or >63 chars failed `kubectl apply` opaquely.
  Worse: `cancel` built a label selector from the unsanitized
  `job-name` too, so a value like `"foo,bar"` formed a 2-clause
  selector at the kubectl level — wide pod-delete blast radius across
  unrelated builds. New `sanitize-label-value` is applied in
  `build-pod-spec` AND `cancel` so the selector matches exactly what
  was stamped.

- **`mask-secrets` order-dependent partial-leak.** The reduce over
  `secret-values` ran in iteration order. With input
  `["abc" "abcdef"]` on the text `"abcdef token"`, replacing `"abc"`
  first turned the text into `"***def token"` — the longer secret
  `"abcdef"` never matched again and leaked as `"***def"`. Now
  secrets are sorted by length descending before the reduce so longer
  values always mask first.

- **Divergent mask copy in `streaming-process`.** A second inline
  reduce in `streaming-process/read-stream` carried subtly different
  rules (`"****"` four stars vs `mask-secrets`'s `"***"` three;
  `str/blank?` vs the inner `(seq ...)` guard) and would have had the
  same partial-leak bug if reached on a long secret. Replaced with a
  direct call to `masker/mask-secrets` so there's one masking
  implementation, one set of invariants.

### Added

- `chengis.engine.log-masker/min-secret-length` — minimum length
  (4) below which `mask-secrets` refuses to mask, emitting a one-line
  warn. Operator footgun protection: stamping `"a"` or `"ok"` as a
  secret turns build output into a `***` soup; secrets that small are
  worthless from a security standpoint anyway.

- `chengis.engine.backend.k8s/sanitize-label-value` — pure utility
  exposed so cancel-by-label callers (and tests) can reproduce the
  exact sanitized form used at apply time.

### Compatibility notes

- Label values for `chengis.io/job-name` and
  `chengis.io/build-number` are now sanitized. Dashboards/alerts
  that filtered on the raw (rejected-by-apiserver) shape will need
  to switch to the sanitized form. Anything that successfully
  matched on 0.4.1 will continue to match on 0.4.2 — sanitization
  is a no-op on already-valid label values.

- `execute-step` may now return `:cancelled? true` on the result map
  when the pod was deleted out from under us. Existing consumers
  that only read `:exit-code` / `:stdout` / `:stderr` are unaffected;
  consumers that want to distinguish cancel from process exit-137
  can opt in to the new key.

[0.4.2]: https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.4.2

## [0.4.1] — 2026-06-08

**Theme:** PR #14 review follow-up. Patch release covering bugs +
docs caught by Copilot review on the 0.4.0 ship.

### Fixed

- **Pod label keys are strings, not namespaced keywords** (#15).
  `clojure.data.json/write-str` silently drops the namespace of
  namespaced keyword map keys at serialize time, so
  `:chengis.io/job-name` was reaching the apiserver as
  `"job-name"`. Result: `cancel`'s
  `kubectl delete pod -l chengis.io/job-name=…` selector matched
  zero pods. With string keys the serialized form is wire-stable;
  the cancel selector now actually targets the build's pods. Test
  extended with a JSON round-trip assertion to lock the contract.

### Changed (docs only)

- Namespace + Cancellation docstrings now match the actual
  implementation: pod names include a per-step salt; `cancel`
  deletes by label selector (not by name). The pre-fix wording
  implied delete-by-name, misleading operators reading the source.
- New "Workspace semantics (first-cut)" section documenting that
  `:workspace-path` is host-side and NOT bind-mounted into the pod
  (every step pod gets a fresh emptyDir). Locks the v0.6 T1
  scope-cut into the doc.

### Compatibility notes

- Wire-incompatible label-key change in the pod manifest. Operators
  who built dashboards/alerts on `metadata.labels` should expect
  string keys (`"chengis.io/job-name"`) rather than the (broken)
  namespaceless `"job-name"` shape that 0.4.0 emitted. Anything
  that used the cancel selector to match pods was no-op on 0.4.0,
  so this is a fix, not a break.

[0.4.1]: https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.4.1

## [0.4.0] — 2026-06-08

**Theme:** the Kubernetes backend. 0.3 closed the tool-installer matrix;
0.4 closes the third execution backend (after LocalShell + Docker) so
consuming products (anvil) can honor `agent { kubernetes { ... } }`
without bundling a k8s SDK.

One new namespace, ~430 LOC, zero new transitive deps on the
classpath. CLI shell-out to `kubectl` mirrors the docker-backend's
shape — same operator-habit story.

### Added

- **`chengis.engine.backend.k8s`** — Kubernetes execution backend
  implementing `chengis.engine.backend/ExecutionBackend`. Per-step
  mode (analog to docker-backend's `:per-step`): each step gets a
  fresh pod with `restartPolicy: Never`, runs `sh -c '<command>'`,
  emits exit code + logs, then `kubectl delete pod`. Honors:
    - `:resource-limits` → pod container resources (`:memory-mb` →
      `<N>Mi`; `:cpus` → `<N>` cpu). `:cpu-shares` and `:pids-max`
      are dropped honestly (no k8s analog).
    - `:env` → pod container env list, stable-ordered.
    - `:user STRING` (numeric uid) → `securityContext.runAsUser`.
      Non-numeric values dropped with a warning (k8s wants a uid).
    - `:host-user?` (default true) → auto-detects host uid via
      `id -u` and stamps `runAsUser`, matching docker-backend
      behaviour so workspace files are host-readable.
    - Kubeconfig lookup: explicit `:kubeconfig-path` → `KUBECONFIG`
      env → `~/.kube/config`. Threaded into every kubectl invocation
      via `KUBECONFIG=` so the backend doesn't mutate the calling
      process env.
    - Pod labels (`chengis.io/job-name`, `chengis.io/build-number`)
      stamp every pod so `cancel` can target by label selector and
      sweep all pods belonging to a build (per-step mode pods are
      individually salted; the label is the only stable handle).
  Cancel issues `kubectl delete pod -l ...` with a configurable
  `:cancel-grace-ms` (default 10000ms — matches docker-backend).
  (#NN anvil v0.6 T1.1)
- New `:k8s` test selector — `lein test :k8s` runs the cluster-required
  acceptance tests against the kubeconfig-default cluster. Tests
  self-skip with a single info log when no cluster is reachable, so
  default `lein test` runs continue to be hermetic.

### Verified against

- Anvil's `agent { kubernetes { yaml '...' } }` translator (anvil
  v0.6 T1.3 / T1.4) routes through this backend. After 0.4.0 lands,
  operators with a kind cluster + `:k8s-agent` flag on observe a
  pod scheduled per step in the configured namespace.
- 19 always-on tests + 3 cluster-required tests. Full chengis-core
  suite remains green.

### Compatibility notes

- Consumers depending only on `chengis.engine.*` (LocalShell + Docker)
  are unaffected — this release only adds a new public namespace
  under `chengis.engine.backend.k8s`.
- No deprecation. The Docker + LocalShell backends keep the exact
  shape they had in 0.3.x.

### Locked decisions referenced

- AV6-2 (anvil v0.6 board): K8s backend lives in chengis-core, NOT
  in anvil. anvil only consumes the protocol.
- CC2-EX6 (chengis-core extraction board): the protocol's third
  reference impl after LocalShell and Docker.

[0.4.0]: https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.4.0

## [0.3.0] — 2026-06-06

**Theme:** the tool-installer matrix. 0.2.x shipped the execution layer +
the file-ownership fix that made it useful at scale. 0.3.0 ships the
real concrete installers — the second piece anvil needed to stop
returning empty `tool('jdk_17_latest')` and start resolving to a real
JDK on disk.

Four installers, ~2,200 LOC of new public surface across two PRs,
zero new transitive deps on the classpath.

### Added

- **`chengis.tools.http`** — Single-seam HTTP client (fetch-bytes,
  fetch-json, download-to-file). Manual redirect chase (http-kit's
  auto-follow drops bodies on some redirect chains), 5xx + transport
  retry with linear backoff, `*.partial` + atomic rename so a
  half-downloaded tarball never poisons the cache. Tests `with-redefs`
  on this ns to keep installer tests hermetic. (#11 CC2-EX3b.1)
- **`chengis.tools.archive`** — tar.gz + zip extraction via native
  `tar` / `unzip`. Layered traversal defense: pre-validate every
  entry against `..`, absolute path, and Windows-style separators;
  post-extract canonical-prefix? walk on every dirent. `:strip-components N`
  knob mirrors tar's flag. Zero new dep — uses the omnipresent system
  tools rather than commons-compress. (#11)
- **`chengis.tools.checksum`** — Streaming SHA-256 / SHA-512
  (64KB buffer). `verify!` refuses to accept a blank expected digest
  (no-silent-success contract); throws `:checksum/mismatch` on miss
  with both digests in the ex-data. (#11)
- **`chengis.tools.platform`** — OS + arch detection through
  `tools/getprop*` so tests can pin without monkeypatching JVM
  globals. Maps amd64/x86_64 → `:x64`, aarch64/arm64 → `:aarch64`;
  linux/mac × x64/aarch64 supported. (#11)
- **`chengis.tools.temurin`** — Eclipse Temurin JDK installer.
  Queries `api.adoptium.net/v3/assets/feature_releases/{major}/ga`,
  picks the latest GA for the host platform, downloads via
  `http/download-to-file`, verifies SHA-256 from the API payload
  inline, extracts with `--strip-components 1`. Supports Jenkins-style
  `jdk_17_latest` and modern `jdk:17.0.1+12` descriptors. Linux/macOS
  × x64/aarch64; Windows surfaces `:result :unsupported`. (#11)
- **`chengis.tools.maven`** — Apache Maven installer via
  `dlcdn.apache.org/maven/maven-3/`. Explicit X.Y.Z versions
  (`_latest` deferred; Apache has no JSON API). SHA-512 sibling file
  for digest. (#12 CC2-EX3b.2)
- **`chengis.tools.gradle`** — Gradle installer via
  `services.gradle.org`. Both explicit and `_latest` via the official
  current-versions JSON. zip distribution with explicit
  walk+flatten (archive's `:strip-components` isn't implemented for
  zip). SHA-256 sibling file. (#12)
- **`chengis.tools.node`** — Node.js installer via `nodejs.org/dist`.
  Both explicit (`node:20.10.0`) and `_latest` via the dist/index.json.
  tar.gz over tar.xz to avoid an xz dep. `parse-shasums-for` matches
  by filename in the aggregate SHASUMS256.txt — never accepts another
  file's digest. (#12)

### Verified against

- Anvil's `tool('jdk_X_latest')` step routes through
  `chengis.tools/resolve!` (anvil AN4-3). After 0.3.0 lands, operators
  register `(temurin/temurin-installer)` at startup and the resolve
  call returns a real on-disk path with `bin/java` executable.
- 24 new tests / 60 new assertions across the new namespaces. Full
  chengis-core suite remains green: 1,013 tests / 3,625 assertions,
  0 failures.

### Compatibility notes

- Consumers depending only on `chengis.engine.*`, `chengis.tools` (the
  protocol ns), or `chengis.dsl.*` are **unaffected** — this release
  only adds new public namespaces under `chengis.tools.*`.
- A consuming product (e.g. anvil) wanting to expose all four
  installers should register them at startup:

  ```clojure
  (require '[chengis.tools :as tools]
           '[chengis.tools.temurin :as temurin]
           '[chengis.tools.maven   :as maven]
           '[chengis.tools.gradle  :as gradle]
           '[chengis.tools.node    :as node])
  (tools/register-installer! (temurin/temurin-installer))
  (tools/register-installer! (maven/maven-installer))
  (tools/register-installer! (gradle/gradle-installer))
  (tools/register-installer! (node/node-installer))
  ```

  The order is resolution priority. With multiple installers handling
  the same descriptor, the first registered wins.

## [0.2.1] — 2026-06-05

**Theme:** the file-ownership fix. 0.2.0 made Docker execution work; 0.2.1
makes the artifacts it produces actually readable on the host. Caught by
anvil's wild-corpus dirty-dozen hunt: containers ran as root, archived
1,040 jars to a bind-mounted workspace, and the host couldn't read its
own files. One-line conceptual fix, real downstream impact.

### Changed

- **`chengis.engine.backend.docker`** — Both `run-disposable-container`
  and `start-build-container!` now default to passing
  `--user $(id -u):$(id -g)` so writes inside the container land owned
  by the invoking host user, not root. Detection uses `id -u` / `id -g`
  via `process/sh`, cached in a `delay` so it runs at most once per JVM.
  Returns `[]` (not nil) on detection failure so the caller can
  unconditionally `concat` the result into the docker args. Operators
  who explicitly want root-mode can pass `:host-user? false` per call;
  default is `true` so the path that surfaced this problem in
  wild-corpus stays fixed by default. (#9 CC2-EX1c)

### Verified against

- anvil wild-corpus dirty-dozen hunt: apache-camel-quarkus produced
  1,040 real jar files (196 MB) into a host-readable bind-mount in
  841 seconds. First measurable wild-corpus build with non-zero
  real-artifact count.

## [0.2.0] — 2026-06-05

**Theme:** the execution layer. 0.1.0 shipped the protocol scaffolding;
0.2.0 ships the concrete machinery that turns "anvil walked the IR"
into "anvil produced an artifact." Six new namespaces, ~2080 lines of
new source, the foundation underneath every classifier rule anvil
reads in its v0.3.1 honesty release.

### Added

- **`chengis.engine.backend`** + **`chengis.engine.backend.docker`** —
  `ExecutionBackend` protocol with `prepare-workspace`, `execute-step`,
  `cleanup`, `cancel`. `LocalShell` reference implementation +
  `DockerBackend` defrecord with per-build / per-step modes, workspace
  bind-mount, cgroup limits, and SIGTERM/SIGKILL cancel signal. Lets
  consuming products (anvil today, Chengis tomorrow) swap execution
  strategy without touching the dispatcher. (#1 CC2-EX1a, #3 CC2-EX1b)
- **`chengis.engine.result`** — Honest 6-class build classifier:
  `:success`, `:failure`, `:unstable`, `:aborted`, `:neutral`,
  `:unsupported`. 10 classification rules, observation recorders, worst-of
  rollup. Replaces consuming products' "if no exception then :success"
  fallback with a real verdict that operators can act on. (#4 CC2-EX2)
- **`chengis.tools`** — Pluggable tool installer framework. `Installer`
  protocol, registry, `resolve!` API, `DirPinnedInstaller` reference.
  Path-traversal guards via `safe-basename` + canonical-prefix check.
  `getenv*`/`getprop*` indirection so tests can override the host env.
  Foundation for CC2-EX3b's concrete Temurin/Maven/Gradle/Node installers.
  (#6 CC2-EX3a)
- **`chengis.engine.credentials`** — `CredentialStore` protocol with
  `bind!` / `with-bindings!` lifecycle. 5 binding types
  (`:env`, `:file`, `:certificate`, `:ssh-userprivatekey`, `:usernamepassword`)
  via multimethod. 3 config templates. Safe-basename path traversal
  defense, XML escape helper, `clojure.data.json/write-str` for safe
  JSON rendering. (#7 CC2-EX4)
- **`chengis.engine.steps`** — `Step` protocol + registry + 3 built-in
  primitives (`artifacts/archive`, `tests/junit`, `problems/record`).
  Glob matcher with `**/` zero-prefix handling. Same plug-in shape the
  classifier reads as "real recorded work" so plugin steps don't read
  as `:neutral`. (#5 CC2-EX5)

### Changed

- Pre-1.0 disclaimer applies — the protocols added here may have minor
  signature evolutions before 1.0. Pin to a specific version. Review
  this changelog before bumping.

### Consuming this version

```bash
git clone --depth 1 --branch v0.2.0 \
  https://github.com/SuperBadLabs/chengis-core.git /tmp/chengis-core
(cd /tmp/chengis-core && lein install)
```

Then declare `[superbadlabs/chengis-core "0.2.0"]` in your project's
`:dependencies`.

### Provenance / consumers at release time

- [**anvil 0.3.1**](https://github.com/SuperBadLabs/anvil) — wires
  `chengis.engine.result/classify` into its Jenkinsfile runner (AN4-1),
  uses `chengis.tools/resolve!` for `tool('X')` calls (AN4-3), and
  `chengis.engine.credentials/bind!` for `withCredentials` blocks (AN4-4).
- **Chengis 1.0.0-rcN** — commercial multi-tenant SaaS (private).

[0.2.0]: https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.2.0

## [0.1.0] — 2026-06-03

First standalone release. Carved from the
[chengis monorepo](https://github.com/SuperBadLabs/chengis) via
`git subtree split --prefix=chengis-core`. History preserved (12 commits
back to PR #164, anvil's introduction).

### Contents

- **Pipeline IR** types + orchestration primitives
- **`StepDispatcher` protocol** — single API contract between engine and
  consuming product
- **Agent protocol + worker** with heartbeat scheduling
- **Plugin protocol + registry**, SCI-sandboxed for safety
- **Storage primitives**: `next.jdbc`, `honeysql`, `hikari-cp`,
  `migratus` for migrations
- **Observability**: `timbre` + JSON appender; `iapetos` for Prometheus
- **Subprocess primitives** via `babashka.process`
- **HTTP client** via `http-kit` (for agent communication +
  artifact upload)
- **DSL helpers** for YAML configuration

### Consuming this version

```bash
git clone --depth 1 --branch v0.1.0 \
  https://github.com/SuperBadLabs/chengis-core.git /tmp/chengis-core
(cd /tmp/chengis-core && lein install)
```

Then declare `[superbadlabs/chengis-core "0.1.0"]` in your project's
`:dependencies`.

### Pre-1.0 disclaimer

Until `1.0.0`, the API may break across releases. Pin to a specific
version. Review this changelog before bumping.

### Provenance / consumers at release time

- [**anvil 0.2.1**](https://github.com/SuperBadLabs/anvil) — OSS
  Jenkins-replacement, single-tenant, single-binary
- **Chengis 1.0.0-rc1** — commercial multi-tenant SaaS (private repo)

[0.1.0]: https://github.com/SuperBadLabs/chengis-core/releases/tag/v0.1.0
