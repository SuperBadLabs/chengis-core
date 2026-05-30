(defproject chengis/chengis-core "1.0.0-SNAPSHOT"
  :description
  "chengis-core — the engine library underlying Chengis (the existing CI
   platform) and anvil (the OSS Jenkins-replacement product).

   This library contains the parts that are not product-specific:
     - Pipeline IR types and orchestration primitives
     - StepDispatcher protocol (the only API contract between core and products)
     - Agent protocol and worker
     - Plugin protocol and registry
     - Observability plumbing (metrics, logging)
     - Storage primitives (DB connection, migration runner, generic store helpers)
     - Feature-flag resolution
     - Configuration loading

   Anything UI-shaped, schema-specific (billing, MFA, branding), or
   product-cli-shaped lives in the consuming products, NOT here.

   Library extraction in progress; see
   `docs/jenkins-compat/library-extraction-strategy.md`."

  :url "https://chengis.io"
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}

  ;; chengis-core takes minimal direct dependencies. Anything not strictly
  ;; required for the engine (HTTP, web framework, billing SDKs, etc.) stays
  ;; in the consuming product.
  :dependencies [[org.clojure/clojure "1.12.4"]
                 [org.clojure/core.async "1.8.741"]
                 ;; Database primitives used by stores. Specific dialect
                 ;; drivers (sqlite, postgres) are picked by the consuming
                 ;; product to keep core dialect-agnostic.
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [com.github.seancorfield/honeysql "2.7.1368"]
                 [hikari-cp/hikari-cp "4.0.0"]
                 [migratus/migratus "1.6.5"]
                 ;; Subprocess primitives used by chengis.engine.process
                 [babashka/process "0.6.25"]
                 ;; SCI — sandboxed/trusted runtime for chengis.plugin.sci
                 [org.babashka/sci "0.9.44"]
                 ;; YAML — chengis.dsl.yaml + workflow config readers
                 [clj-commons/clj-yaml "1.0.29"]
                 ;; JSON — agent client, license scanner, notify
                 [org.clojure/data.json "2.5.2"]
                 ;; HTTP client — chengis.agent.client / artifact_uploader /
                 ;; engine.notify / engine.license_scanner. Without this on
                 ;; chengis-core's classpath, downstream consumers that
                 ;; depend on the library directly (rather than via a
                 ;; product's bundled `:source-paths` merge) hit
                 ;; `Could not locate org/httpkit/client` at load time
                 ;; (Codex P1, PR #164).
                 [http-kit/http-kit "2.8.1"]
                 ;; Time-based scheduling — chengis.metrics + agent.heartbeat
                 [jarohen/chime "0.3.3"]
                 ;; Logging + observability
                 [com.taoensso/timbre "6.8.0"]
                 [viesti/timbre-json-appender "0.2.14"]
                 [clj-commons/iapetos "0.1.14"]
                 [io.prometheus/simpleclient_hotspot "0.16.0"]]

  :source-paths ["src"]
  :test-paths ["test"]
  :resource-paths ["resources"]
  :target-path "target/%s"

  ;; Strict warnings — core is small and must stay clean.
  :global-vars {*warn-on-reflection* true}

  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.3"]
                                  ;; SQLite is the trial / unit-test driver
                                  [org.xerial/sqlite-jdbc "3.51.2.0"]]}})
