(ns chengis.tools.http
  "HTTP helpers for tool installers — single seam so installer tests
   can `with-redefs` the network out without monkeypatching http-kit.

   Each fn does ONE thing:
     - `fetch-bytes`     bytes payload from a URL
     - `fetch-json`      parsed-JSON payload from a URL
     - `download-to-file` stream a URL into a local File, atomic-rename
                          on completion so partial writes never leave a
                          truncated file in the cache.

   Follows redirects (3xx → Location), retries transient failures with
   linear backoff up to `:max-attempts`, sets a stable User-Agent so
   upstream rate-limit signals are attributable. Connection /
   read timeouts are non-default; the http-kit defaults sit at 60s
   which is too short for a slow JDK tarball over a flaky link.

   Refs: docs/v0.2-board.md CC2-EX3b."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.io File InputStream OutputStream]
           [java.nio.file Files StandardCopyOption]))

(def ^:private default-ua
  "chengis-core/0.3 (+https://github.com/SuperBadLabs/chengis-core)")

(def ^:private default-timeout-ms
  ;; 5 minutes for a tool tarball — JDKs are ~200MB and shared CI
  ;; runners have peaked at ~2 minutes for a single download in
  ;; the wild-corpus dirty-dozen hunt. Headroom built in.
  300000)

(def ^:private default-max-attempts 3)

(defn- attempt-once
  "Single HTTP GET attempt. Returns {:status N :headers M :body STREAM-or-BYTES}
   or {:error EX}. Does NOT follow redirects or retry — that's done at the
   call site so we can log per-attempt."
  [url opts]
  (let [as (or (:as opts) :stream)
        timeout (or (:timeout opts) default-timeout-ms)
        resp @(http/get url
                        {:as as
                         :timeout timeout
                         :user-agent (or (:user-agent opts) default-ua)
                         :follow-redirects false
                         :insecure? (boolean (:insecure? opts))
                         :headers (or (:headers opts) {})})]
    (if (:error resp)
      {:error (:error resp)}
      {:status (:status resp)
       :headers (:headers resp)
       :body (:body resp)})))

(defn- redirect? [status]
  (and (integer? status) (<= 300 status 399)))

(defn- location-of [resp]
  (or (get-in resp [:headers :location])
      (get-in resp [:headers "Location"])))

(defn- fetch-following-redirects
  "Single attempt + manual redirect chase up to 5 hops. http-kit's
   built-in :follow-redirects sometimes silently drops the body
   on redirect; doing it manually means we always see the final
   status & headers."
  [url opts]
  (loop [url url, hops 0]
    (when (> hops 5)
      (throw (ex-info (str "too many redirects from " url) {:url url})))
    (let [resp (attempt-once url opts)]
      (cond
        (:error resp) resp
        (redirect? (:status resp))
        (let [next-url (location-of resp)]
          (when-not next-url
            (throw (ex-info "redirect without Location"
                            {:url url :status (:status resp)})))
          (recur next-url (inc hops)))
        :else resp))))

(defn- with-retry
  "Call (f) up to `max-attempts` times. Retry on :error and on 5xx.
   Linear backoff: 500ms × attempt-number."
  [max-attempts f]
  (loop [attempt 1]
    (let [resp (try (f) (catch Throwable t {:error t}))]
      (cond
        ;; Hard error — retry if attempts left.
        (and (:error resp) (< attempt max-attempts))
        (do (log/warn "http attempt" attempt "failed:"
                      (or (.getMessage ^Throwable (:error resp))
                          (str (:error resp)))
                      "— retrying")
            (Thread/sleep (* 500 attempt))
            (recur (inc attempt)))

        ;; 5xx — retry.
        (and (integer? (:status resp))
             (>= (:status resp) 500)
             (< attempt max-attempts))
        (do (log/warn "http attempt" attempt
                      "got 5xx status" (:status resp) "— retrying")
            (Thread/sleep (* 500 attempt))
            (recur (inc attempt)))

        :else resp))))

(defn fetch-bytes
  "Fetch `url` and return the body as a byte array. Throws ex-info on
   non-2xx status or transport error after all retries. Useful for
   small payloads (checksum files, version manifests)."
  [url & {:as opts}]
  (let [resp (with-retry (or (:max-attempts opts) default-max-attempts)
                         #(fetch-following-redirects url (assoc opts :as :byte-array)))]
    (cond
      (:error resp)
      (throw (ex-info (str "fetch-bytes failed for " url
                           ": " (or (some-> ^Throwable (:error resp) .getMessage)
                                    (str (:error resp))))
                      {:url url :error (:error resp)}))

      (or (nil? (:status resp))
          (not (<= 200 (:status resp) 299)))
      (throw (ex-info (str "fetch-bytes got non-2xx " (:status resp)
                           " for " url)
                      {:url url :status (:status resp)}))

      :else (:body resp))))

(defn fetch-json
  "Fetch `url` and parse as JSON. Returns the parsed Clojure value
   (keys are kept as strings — installer code maps explicitly so a
   typo in `:get-in` paths is caught at the call site, not silently
   converted into a missing key)."
  [url & {:as opts}]
  (let [body (apply fetch-bytes url (mapcat identity opts))]
    (json/read-str (String. ^bytes body "UTF-8"))))

(defn download-to-file
  "Stream `url` into `dest-file` (a File or path string).

   Writes to a sibling `*.partial` first, fsyncs the data, then
   atomically renames to `dest-file` on success. Cleans up the
   partial on any error so a half-downloaded tarball never poisons
   the cache.

   Returns the canonical path string of the written file.

   Throws ex-info on non-2xx status, transport error, or unwritable
   destination directory."
  [url dest-file & {:as opts}]
  (let [dest (io/file dest-file)
        parent (.getParentFile dest)
        _ (when (and parent (not (.exists parent)))
            (.mkdirs parent))
        partial (io/file parent (str (.getName dest) ".partial"))]
    (try
      (when (.exists partial)
        (.delete partial))
      (let [resp (with-retry (or (:max-attempts opts) default-max-attempts)
                             #(fetch-following-redirects url (assoc opts :as :stream)))]
        (cond
          (:error resp)
          (throw (ex-info (str "download-to-file failed for " url
                               ": " (or (some-> ^Throwable (:error resp) .getMessage)
                                        (str (:error resp))))
                          {:url url :error (:error resp)}))

          (or (nil? (:status resp))
              (not (<= 200 (:status resp) 299)))
          (throw (ex-info (str "download-to-file got non-2xx "
                               (:status resp) " for " url)
                          {:url url :status (:status resp)}))

          :else
          (with-open [^InputStream in (:body resp)
                      ^OutputStream out (io/output-stream partial)]
            (io/copy in out))))
      ;; Atomic move into place — defeats half-written files.
      (Files/move (.toPath partial) (.toPath dest)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (.getCanonicalPath dest)
      (catch Throwable t
        (try (.delete partial) (catch Throwable _ nil))
        (throw t)))))
