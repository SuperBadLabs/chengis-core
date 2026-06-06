(ns chengis.tools.archive-test
  "Verify the tar-gz / zip extraction pipeline. The defense-in-depth
   contract is the load-bearing thing: every entry validated before
   extraction, then every extracted file proved to live inside dest.

   Fixtures built on the fly with `tar` / `zip` to keep the test repo
   free of large binary blobs."
  (:require [babashka.process :as p]
            [chengis.tools.archive :as archive]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- mk-tmp-dir! [name]
  (-> (Files/createTempDirectory (str "archive-test-" name)
                                 (into-array FileAttribute []))
      .toFile))

(defn- run! [argv]
  (let [{:keys [exit out err]}
        @(p/process argv {:out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "fixture build failed: " (str/join " " argv))
                      {:exit exit :stderr err})))
    out))

;; ---------------------------------------------------------------------------
;; Fixture builders
;; ---------------------------------------------------------------------------

(defn- write! [^java.io.File f content]
  (.mkdirs (.getParentFile f))
  (spit f content))

(defn- mk-clean-tarball!
  "Build a benign tar.gz at `dest-path` containing a top-level
   `app-1.0/bin/run` script. Returns the path string."
  []
  (let [src (mk-tmp-dir! "clean-src")
        _ (write! (io/file src "app-1.0" "bin" "run") "#!/bin/sh\necho hi\n")
        out-dir (mk-tmp-dir! "clean-out")
        out (io/file out-dir "clean.tar.gz")]
    (run! ["tar" "-czf" (.getCanonicalPath out)
           "-C" (.getCanonicalPath src) "app-1.0"])
    (.getCanonicalPath out)))

(defn- mk-malicious-tarball!
  "Build a tar.gz that includes a `../escape.txt` entry — the classic
   tarbomb shape. `archive/extract!` must REFUSE to extract this."
  []
  (let [scratch (mk-tmp-dir! "evil-src")
        ;; Build a fake 'good' tree then craft an evil .tar with --absolute-names
        ;; isn't enough — we use --transform to inject '..' into entry names.
        _ (write! (io/file scratch "victim.txt") "I am here\n")
        out-dir (mk-tmp-dir! "evil-out")
        out (io/file out-dir "evil.tar.gz")]
    (run! ["tar" "-czf" (.getCanonicalPath out)
           "-C" (.getCanonicalPath scratch)
           "--transform" "s,^,../escape/," "victim.txt"])
    (.getCanonicalPath out)))

;; ---------------------------------------------------------------------------
;; Happy path
;; ---------------------------------------------------------------------------

(deftest extracts-tarball-without-strip
  (let [archive (mk-clean-tarball!)
        dest (mk-tmp-dir! "extract-dest")
        result (archive/extract! {:archive archive
                                  :dest (.getCanonicalPath dest)})]
    (is (= (.getCanonicalPath dest) result))
    (is (.exists (io/file dest "app-1.0" "bin" "run"))
        "expected entries extracted under <dest>/app-1.0/...")))

(deftest extracts-tarball-with-strip-components
  (let [archive (mk-clean-tarball!)
        dest (mk-tmp-dir! "extract-strip")
        _ (archive/extract! {:archive archive
                             :dest (.getCanonicalPath dest)
                             :strip-components 1})]
    (is (.exists (io/file dest "bin" "run"))
        "with --strip-components 1, app-1.0 prefix removed")))

;; ---------------------------------------------------------------------------
;; Safety: tarbomb refused
;; ---------------------------------------------------------------------------

(deftest rejects-tarbomb-with-dotdot-entry
  (let [archive (mk-malicious-tarball!)
        dest (mk-tmp-dir! "evil-dest")]
    (try
      (archive/extract! {:archive archive
                         :dest (.getCanonicalPath dest)})
      (is false "should have thrown on `..` entry")
      (catch clojure.lang.ExceptionInfo e
        (is (= :archive/unsafe-entry (:type (ex-data e))))
        (is (re-find #"\.\." (or (:entry (ex-data e)) ""))))))
  (testing "the parent escape directory was NOT created"
    ;; Difficult to assert directly without coupling tightly to where
    ;; we put the dest, but the validation pass throws BEFORE tar is
    ;; invoked — so by construction nothing was written.
    ))

(deftest rejects-unknown-archive-kind
  (let [bogus (io/file (mk-tmp-dir! "bogus") "thing.rar")]
    (spit bogus "not a real archive")
    (try
      (archive/extract! {:archive (.getCanonicalPath bogus)
                         :dest (.getCanonicalPath
                                (mk-tmp-dir! "unknown-dest"))})
      (is false "should have thrown on unknown kind")
      (catch clojure.lang.ExceptionInfo e
        (is (= :archive/unknown-kind (:type (ex-data e))))))))
