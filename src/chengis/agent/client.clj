(ns chengis.agent.client
  "HTTP client functions for agent→master communication.
   Handles registration, heartbeat, event streaming, and result reporting.
   Supports HMAC-signed requests when auth-scheme is :hmac (default)."
  (:require [chengis.distributed.agent-auth :as agent-auth]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- auth-headers
  "Build auth headers based on config auth-scheme.
   :hmac (default) - HMAC-SHA256 signed headers
   :bearer - Simple Bearer token"
  [config body-bytes]
  (let [auth-scheme (or (:auth-scheme config) :hmac)
        base-headers {"Content-Type" "application/json"}]
    (case auth-scheme
      :hmac (if (:auth-token config)
              (merge base-headers (agent-auth/sign-request (:auth-token config) body-bytes))
              base-headers)
      :bearer (cond-> base-headers
                (:auth-token config)
                (assoc "Authorization" (str "Bearer " (:auth-token config))))
      base-headers)))

(defn- post-json!
  "POST JSON to a URL. Returns the parsed response body or nil."
  [url body config]
  (try
    (let [body-str (json/write-str body)
          body-bytes (.getBytes ^String body-str "UTF-8")
          resp @(http/post url
                           {:headers (auth-headers config body-bytes)
                            :body body-str
                            :timeout 15000})]
      (when (< (:status resp 500) 300)
        (when-let [b (:body resp)]
          (try
            (json/read-str b :key-fn keyword)
            (catch Exception _ nil)))))
    (catch Exception e
      (log/warn "HTTP POST failed:" url (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Agent→Master API calls
;; ---------------------------------------------------------------------------

(defn register-with-master!
  "Register this agent with the master.
   Returns the agent-id on success, nil on failure."
  [master-url agent-info config]
  (let [url (str master-url "/api/agents/register")
        result (post-json! url agent-info config)]
    (when result
      (log/info "Registered with master as:" (:agent-id result))
      (:agent-id result))))

(defn send-heartbeat!
  "Send a heartbeat to the master. Returns true on success."
  [master-url agent-id status-info config]
  (let [url (str master-url "/api/agents/" agent-id "/heartbeat")
        result (post-json! url status-info config)]
    (some? result)))

(defn send-build-event!
  "Stream a build event to the master.
   Posts to /api/builds/:id/agent-events (the master ingest route)."
  [master-url build-id event config]
  (let [url (str master-url "/api/builds/" build-id "/agent-events")]
    (post-json! url event config)))

(defn send-build-result!
  "Send the final build result to the master."
  [master-url build-id result agent-id config]
  (let [url (str master-url "/api/builds/" build-id "/result")]
    (post-json! url (assoc result :agent-id agent-id) config)))
