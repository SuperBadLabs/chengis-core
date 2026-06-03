# chengis-core

The composable Clojure CI/CD engine that powers
[anvil](https://github.com/SuperBadLabs/anvil) (open-source) and Chengis
(commercial).

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-pre--1.0-orange.svg)](#status)

## What it is

`chengis-core` is a **library**, not an application. It provides the
data-driven primitives that every modern CI server needs:

- **Pipeline IR** — typed, data-driven representation of a build
- **`StepDispatcher` protocol** — the only API contract between the
  engine and the product that runs on it
- **Agent protocol + worker** — distributing work across executors
- **Plugin protocol + registry** — SCI-sandboxed extensibility
- **Observability** — Prometheus + structured logging via timbre
- **Storage primitives** — `next.jdbc` + `honeysql` + `migratus`
- **Feature-flag resolution** + **configuration loading**

Two products consume it today:

- [**anvil**](https://github.com/SuperBadLabs/anvil) — single-tenant,
  single-binary, OSS. Apache 2.0.
- **Chengis** — multi-tenant SaaS / enterprise. Proprietary.

If you want to embed `chengis-core` in your own thing, you can —
Apache 2.0 — but be aware that **pre-1.0 the API may break across
releases.**

## Consuming this library

`chengis-core` is not yet on Clojars (deferred until external demand
justifies the artifact). The supported consumption path is
git-clone-plus-`lein install` from a pinned tag:

```bash
git clone --depth 1 --branch v0.1.0 \
  https://github.com/SuperBadLabs/chengis-core.git /tmp/chengis-core
(cd /tmp/chengis-core && lein install)
```

This populates your local Maven cache (`~/.m2/repository/superbadlabs/chengis-core/0.1.0/`).
Then in your project:

```clojure
:dependencies [[superbadlabs/chengis-core "0.1.0"]
               ;; ...
               ]
```

CI workflows do the same dance as a prelude step before `lein test`/`lein run`.
Switching to Clojars later (when there's a reason) is a one-line
workflow edit — same coordinate, different resolution path.

## Migrations — engine vs product

`chengis-core` ships the migration *runner* (`chengis.db.migrate`),
NOT migration files. **The consuming product owns its schema.**
This is deliberate: anvil's migration set, Chengis's migration set,
and any future consumer's migration set are all independent.

The migration runner expects a directory on the classpath:

```
your-product/resources/migrations/sqlite/      ← your own SQL files
your-product/resources/migrations/postgresql/  ← your own SQL files
```

`chengis-core/resources/migrations/{sqlite,postgresql}/` is intentionally
empty in the published artifact. `chengis-core/test-resources/migrations/`
holds a snapshot of the parent monorepo's migrations purely so this
library's own tests can run against a populated SQLite database — those
test-resources are dev-only and not bundled into the installed artifact.

## Status

| | |
|---|---|
| **Current release** | `0.1.0` |
| **Stability** | Pre-1.0; API may break |
| **Stable API committed at** | `1.0.0` |
| **License** | Apache-2.0 |
| **Required Clojure** | 1.12+ |

## Architecture in 60 seconds

```
                ┌─────────────────────────────┐
   Jenkinsfile  │                             │
   Chengisfile  │   chengis-core (this lib)   │
   <user>       │   ─────────────────────     │  ← consumed by both products
                │   Pipeline IR    Dispatcher │
                │   Agents         Plugins    │
   ───parse──→  │   Storage        Observ.    │
                │                             │
                └──────────────┬──────────────┘
                               │ StepDispatcher protocol
                ┌──────────────┴──────────────┐
                │                             │
       anvil ◄──┤                             ├──► Chengis
       (OSS,    │  product-specific concerns: │   (SaaS,
        single- │  - HTTP / UI / admin views  │    multi-tenant,
        tenant) │  - CLI commands             │    audit / RBAC /
                │  - Product schemas          │    billing / SSO)
                │  - DSL parsers + step       │
                │    adapter implementations  │
                └─────────────────────────────┘
```

The single contract is the `StepDispatcher` protocol. Everything else
about a product (its DB schema, its admin UI, its trigger UX) is the
product's business. `chengis-core` provides the engine; the product
provides the application.

## Development

```bash
lein test
```

## Provenance

Extracted from the [chengis monorepo](https://github.com/SuperBadLabs/chengis)
on 2026-06-03 via `git subtree split --prefix=chengis-core`. The 12
commits in this repo's history correspond to commits in the monorepo
that touched `chengis-core/` between PR #164 (the library extraction
itself) and PR #192 (CHG-FEAT-007 PR3 — the most recent core-touching
commit at extraction time).

See [`CHANGELOG.md`](CHANGELOG.md) for release notes.
