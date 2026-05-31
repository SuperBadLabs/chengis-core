(ns chengis.dsl.sandbox
  "SCI-sandboxed evaluation of .clj pipeline definition files.

   Pipeline definition files are Clojure. Historically they were loaded with raw
   `load-file` (chengis.dsl.core/load-pipeline-file) — arbitrary code with full
   JVM privileges. This evaluates them in an SCI sandbox that exposes ONLY a
   caller-supplied vocabulary (the pipeline DSL) plus safe clojure.core: NO Java
   interop (no :classes) and a deny-list closing eval / code-loading /
   filesystem / var-surgery / thread-spawn.

   A pipeline file's job is to BUILD DATA describing what to run — it never needs
   interop or IO itself (a `sh` step is *data*, executed later by the engine), so
   sandboxing the definition changes no legitimate pipeline while removing the
   arbitrary-code-execution primitive. Mirrors the plugin SCI sandbox
   (chengis.plugin.sci) and is kept generic (no chengis.dsl.core dependency) so
   chengis.dsl.core can depend on it without a cycle."
  (:require [sci.core :as sci]))

(def sandbox-deny
  "clojure.core symbols denied inside the pipeline sandbox. Interop is already
   off (no :classes); this closes the remaining doors — eval / code-loading,
   var surgery, filesystem, and thread-spawn that would escape the wall-clock
   timeout. Mirrors chengis.plugin.sci/sandbox-deny."
  '[eval load-file load-string load-reader load
    intern alter-var-root with-redefs with-redefs-fn
    slurp spit file-seq read read-string read+string
    future future-call agent send send-off pmap pcalls pvalues
    add-tap remove-tap tap>])

(defn eval-pipeline-file
  "Evaluate the .clj pipeline file at `path` in an SCI sandbox.

   `vocab` is a map of symbol -> fn/macro, registered as the `chengis.dsl.core`
   namespace inside SCI AND referred unqualified into the eval namespace — so a
   file may use bare `(defpipeline ...)`/`(stage ...)` (matching the old
   load-file behavior, where *ns* was chengis.dsl.core) OR a fully-qualified
   `(chengis.dsl.core/defpipeline ...)`, and a file with its own `ns` form that
   `(:require [chengis.dsl.core :refer :all])` also resolves.

   Side effects flow through the vocab fns (e.g. a `register-pipeline!` that
   mutates the real registry); this returns the eval result. Aborts after
   `:timeout-ms` (default 5000) — throws ex-info {:type :dsl/timeout}. Eval
   errors are rethrown unwrapped (clean messages for `pipeline validate`)."
  [path vocab & {:keys [timeout-ms] :or {timeout-ms 5000}}]
  (let [source (slurp path)
        ctx    (sci/init {:namespaces {'chengis.dsl.core vocab}
                          :deny sandbox-deny})
        run    (fn []
                 ;; Default eval ns refers the DSL so bare top-level forms
                 ;; resolve unqualified, matching the legacy load-file contract.
                 (sci/eval-string* ctx "(ns user (:require [chengis.dsl.core :refer :all]))")
                 (sci/eval-string* ctx source))
        fut    (future (run))
        result (try
                 (deref fut timeout-ms ::timeout)
                 (catch java.util.concurrent.ExecutionException e
                   (throw (or (.getCause e) e))))]
    (if (= result ::timeout)
      (do (future-cancel fut)
          (throw (ex-info "Pipeline file evaluation timed out"
                          {:type :dsl/timeout :timeout-ms timeout-ms :path path})))
      result)))
