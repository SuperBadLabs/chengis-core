# chengis-core — ARCHIVED

> This repository has been **archived** as of 2026-06-08.
>
> Active development of chengis-core continues in the private monorepo
> **`SuperBadLabs/chengis`** at the `chengis-core/` subdirectory.

## Why

`chengis-core` shipped the `ExecutionBackend` protocol (`LocalShell`,
`DockerBackend`, `K8sBackend`) that anvil + chengis consume. The
contract drift between the protocol and its two consumers — most
visibly the `:extra-args`, `:log-file`, `:stdin`, and `:resource-limits`
fields documented but not honored by every backend — was a structural
integration problem that the 3-repo split made worse. Moving into one
source tree lets protocol changes and consumer fixes land as atomic
single commits.

## What still works here

- All commits, tags, branches, and releases remain accessible for
  historical reference
- Release binaries (0.1.0, 0.2.0, 0.2.1, 0.3.0, 0.4.0, 0.4.1, 0.4.2)
  remain downloadable from the
  [releases page](https://github.com/SuperBadLabs/chengis-core/releases)
- License terms (Apache 2.0) are preserved unchanged on the
  archived code

## What doesn't

- No new PRs, issues, or commits accepted on this archive
- The private monorepo successor is invite-only

## chengis-core's role in the monorepo

`chengis-core/` continues as the execution-backend protocol library
consumed by `anvil/` and `chengis/`. Its `project.clj`, version
numbering, and CHANGELOG continue independently from the other
monorepo components.
