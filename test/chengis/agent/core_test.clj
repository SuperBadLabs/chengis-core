(ns chengis.agent.core-test
  "Tests for agent HTTP endpoint auth token validation (Fix 1 — P0 + A.4 HMAC).
   Verifies that /builds requires a valid Authorization: Bearer token
   or HMAC-signed request, and that /health remains unauthenticated."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.agent.core :as agent-core]
            [chengis.agent.worker :as worker]
            [chengis.distributed.agent-auth :as agent-auth]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reset nonce cache between tests
;; ---------------------------------------------------------------------------

(defn- reset-nonces [f]
  (agent-auth/reset-nonce-cache!)
  (f)
  (agent-auth/reset-nonce-cache!))

(use-fixtures :each reset-nonces)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-builds-request
  "Build a Ring-style POST /builds request with optional auth header."
  [body-map & {:keys [auth-header]}]
  (let [body-str (json/write-str body-map)]
    (cond-> {:request-method :post
             :uri "/builds"
             :headers {"content-type" "application/json"}
             :body (io/input-stream (.getBytes body-str "UTF-8"))}
      auth-header (assoc-in [:headers "authorization"] auth-header))))

(defn- make-hmac-builds-request
  "Build a Ring-style POST /builds request with HMAC-signed headers."
  [body-map secret]
  (let [body-str (json/write-str body-map)
        body-bytes (.getBytes ^String body-str "UTF-8")
        hmac-headers (agent-auth/sign-request secret body-bytes)
        ;; Ring convention: lowercase headers
        lower-headers (into {"content-type" "application/json"}
                            (map (fn [[k v]] [(clojure.string/lower-case k) v]) hmac-headers))]
    {:request-method :post
     :uri "/builds"
     :headers lower-headers
     :body (io/input-stream body-bytes)}))

(defn- route
  "Route a request through the agent router."
  [agent-config req]
  (#'agent-core/agent-router agent-config req))

;; ---------------------------------------------------------------------------
;; Bearer mode tests (backwards compat)
;; ---------------------------------------------------------------------------

(deftest agent-health-no-auth-required-test
  (testing "GET /health returns 200 without any auth"
    (let [config {:auth-token "secret-token-123"}
          resp (route config {:request-method :get :uri "/health"})]
      (is (= 200 (:status resp)))
      (is (= "healthy" (:status (json/read-str (:body resp) :key-fn keyword)))))))

(deftest agent-builds-rejects-missing-token-test
  (testing "POST /builds without Authorization header returns 401"
    (with-redefs [worker/execute-dispatched-build! (fn [& _] nil)]
      (let [config {:auth-token "secret-token-123"}
            req (make-builds-request {:build-id "b1" :job-id "j1"})
            resp (route config req)]
        (is (= 401 (:status resp)))
        (is (= "Unauthorized" (:error (json/read-str (:body resp) :key-fn keyword))))))))

(deftest agent-builds-rejects-wrong-token-test
  (testing "POST /builds with wrong Bearer token returns 401"
    (with-redefs [worker/execute-dispatched-build! (fn [& _] nil)]
      (let [config {:auth-token "secret-token-123"}
            req (make-builds-request {:build-id "b1" :job-id "j1"}
                                     :auth-header "Bearer wrong-token")
            resp (route config req)]
        (is (= 401 (:status resp)))))))

(deftest agent-builds-accepts-correct-token-test
  (testing "POST /builds with correct Bearer token returns 202 (HMAC fallback to Bearer)"
    (let [executed? (atom false)]
      (with-redefs [worker/execute-dispatched-build! (fn [& _] (reset! executed? true))]
        (let [config {:auth-token "secret-token-123"}
              req (make-builds-request {:build-id "b1" :job-id "j1"}
                                       :auth-header "Bearer secret-token-123")
              resp (route config req)]
          (is (= 202 (:status resp)))
          (is @executed? "Build should have been dispatched to worker"))))))

(deftest agent-builds-nil-token-accepts-backwards-compat-test
  (testing "POST /builds with nil config token accepts (backwards compat with warning)"
    (let [executed? (atom false)]
      (with-redefs [worker/execute-dispatched-build! (fn [& _] (reset! executed? true))]
        (let [config {:auth-token nil}
              req (make-builds-request {:build-id "b1" :job-id "j1"})
              resp (route config req)]
          (is (= 202 (:status resp))
              "When no auth-token is configured, requests should be accepted for backwards compatibility")
          (is @executed?))))))

(deftest agent-builds-invalid-json-returns-400-test
  (testing "POST /builds with invalid JSON returns 400 (after auth passes)"
    (let [config {:auth-token "secret-token-123"}
          req {:request-method :post
               :uri "/builds"
               :headers {"content-type" "application/json"
                         "authorization" "Bearer secret-token-123"}
               :body (io/input-stream (.getBytes "not-json" "UTF-8"))}
          resp (route config req)]
      (is (= 400 (:status resp))))))

(deftest agent-unknown-route-returns-404-test
  (testing "GET /unknown returns 404"
    (let [config {:auth-token "secret-token-123"}
          resp (route config {:request-method :get :uri "/unknown"})]
      (is (= 404 (:status resp))))))

;; ---------------------------------------------------------------------------
;; HMAC mode tests (A.4)
;; ---------------------------------------------------------------------------

(deftest agent-builds-hmac-accepts-valid-signature-test
  (testing "POST /builds with valid HMAC signature returns 202"
    (let [executed? (atom false)]
      (with-redefs [worker/execute-dispatched-build! (fn [& _] (reset! executed? true))]
        (let [config {:auth-token "hmac-secret-123" :auth-scheme :hmac}
              req (make-hmac-builds-request {:build-id "b1" :job-id "j1"} "hmac-secret-123")
              resp (route config req)]
          (is (= 202 (:status resp)))
          (is @executed?))))))

(deftest agent-builds-hmac-rejects-wrong-secret-test
  (testing "POST /builds with HMAC signed by wrong secret returns 401"
    (with-redefs [worker/execute-dispatched-build! (fn [& _] nil)]
      (let [config {:auth-token "hmac-secret-123" :auth-scheme :hmac}
            ;; Sign with different secret
            req (make-hmac-builds-request {:build-id "b1" :job-id "j1"} "wrong-secret")
            resp (route config req)]
        (is (= 401 (:status resp)))))))

(deftest agent-builds-hmac-rejects-no-headers-test
  (testing "POST /builds without HMAC or Bearer headers returns 401 in HMAC mode"
    (with-redefs [worker/execute-dispatched-build! (fn [& _] nil)]
      (let [config {:auth-token "hmac-secret-123" :auth-scheme :hmac}
            req (make-builds-request {:build-id "b1" :job-id "j1"})
            resp (route config req)]
        (is (= 401 (:status resp)))))))

(deftest agent-builds-bearer-mode-explicit-test
  (testing "POST /builds in explicit :bearer mode accepts Bearer token"
    (let [executed? (atom false)]
      (with-redefs [worker/execute-dispatched-build! (fn [& _] (reset! executed? true))]
        (let [config {:auth-token "bearer-secret" :auth-scheme :bearer}
              req (make-builds-request {:build-id "b1" :job-id "j1"}
                                       :auth-header "Bearer bearer-secret")
              resp (route config req)]
          (is (= 202 (:status resp)))
          (is @executed?))))))
