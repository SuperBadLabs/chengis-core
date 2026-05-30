# chengis-core

The engine library underlying [Chengis](../) (the existing CI platform) and
[anvil](../anvil) (the OSS Jenkins-replacement product, TBD).

## What lives here

- Pipeline IR types and orchestration primitives
- `StepDispatcher` protocol — the **only** API contract between core and the
  products that consume it
- Agent protocol + worker
- Plugin protocol + registry
- Observability plumbing (metrics, logging)
- Storage primitives (DB connection, migration runner, generic store helpers)
- Feature-flag resolution
- Configuration loading

## What does NOT live here

- HTTP handlers, web views, admin UI → in consuming products
- Product-specific schemas (billing, MFA, branding, SAML, SCIM, audit) → in
  consuming products
- DSL parsers (Chengisfile, Jenkinsfile) → in consuming products
- CLI commands → in consuming products
- Seed / simulation / demo data → in consuming products

## Layout

```
chengis-core/
├── project.clj
├── README.md
└── src/         ← to be populated by library extraction (TX1)
    └── chengis/
        └── ...
```

## Status

**Skeleton only.** Library extraction is in progress as Tranche TX1. See
[`../docs/jenkins-compat/library-extraction-strategy.md`](../docs/jenkins-compat/library-extraction-strategy.md)
for the extraction plan, target namespaces, and current state.

During development, the main Chengis project's `project.clj` merges
`chengis-core/src` into its source path so file moves don't require any
consumer-code changes — namespaces stay under `chengis.*`. When chengis-core
is ready to ship as a separate artifact, the main project switches to a
proper `[chengis/chengis-core "x.y.z"]` dependency.
