# chengis-core — CHANGELOG

All notable changes to `chengis-core` are documented here. The format
loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows [Semantic Versioning](https://semver.org/) but with
the pre-1.0 disclaimer: **API may break across minor releases until
`1.0.0`**.

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
