;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.http-transport
  "Streamable HTTP transport for the Vaelii MCP server.

  Implements MCP Streamable HTTP (POST/DELETE/GET on /mcp) and the POST /op
  raw-operation route.
  Service handle owns Jetty + lease sweeper; stop is idempotent."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [vaelii.agents.mcp.config :as config]
            [vaelii.agents.mcp.dispatcher :as dispatcher]
            [vaelii.agents.mcp.session :as session])
  (:import [java.io ByteArrayInputStream]))

;; ---- Server state -----------------------------------------------------------
;; Bounded-state census: body-limit is the only overlay atom that holds a
;; resource bound. Registered with the engine's cache registry at server start
;; (see server.clj). allowed-origins and server-config are configuration state,
;; not bounded caches.

(defonce ^:private server-config (atom nil))
(defonce ^:private allowed-origins (atom #{"http://localhost"}))
(defonce body-limit (atom (* 1024 1024)))

;; Configuration surface: environment variables used by this server.
;; Documented here per the engine's frozen-configuration-surface check.
;;   VAELII_AUDIT_DIR  — absolute path to the audit journal directory (required)
;;   VAELII_CONFIG     — path to config.edn (default: "config.edn")
;;   VAELII_TOKEN      — REMOVED: tokens are in config.edn, not env vars
;; See config.clj for how these resolve.

;; ---- Media type parsing (F1, F2 — structural, not substring) ----------------

(defn- parse-media-type
  "Parse a single media type string into [type subtype].
  Strips parameters (;charset=utf-8 etc). Case-insensitive."
  [s]
  (when (and s (string? s))
    (let [trimmed (str/trim s)
          ;; strip parameters
          base (first (str/split trimmed #";" 2))
          parts (str/split (str/trim base) #"/" 2)]
      (when (= 2 (count parts))
        [(str/lower-case (first parts))
         (str/lower-case (second parts))]))))

(defn- media-type-matches?
  "Check if a parsed [type subtype] exactly matches an expected type."
  [[t s] expected-type expected-subtype]
  (and (= t expected-type) (= s expected-subtype)))

(defn valid-content-type?
  "POST body must be application/json (case-insensitive, with optional params)."
  [request]
  (let [ct (get-in request [:headers "content-type"] "")
        parsed (parse-media-type ct)]
    (and parsed (media-type-matches? parsed "application" "json"))))

(defn- parse-accept-ranges
  "Parse Accept header into a list of [{:type :subtype :q}]."
  [accept-str]
  (when (and accept-str (not (str/blank? accept-str)))
    (for [range-str (str/split accept-str #",")]
      (let [trimmed (str/trim range-str)
            [media-part & param-parts] (str/split trimmed #";")
            [t s] (str/split (str/trim media-part) #"/" 2)
            q-param (some (fn [p]
                            (let [kv (str/split (str/trim p) #"=" 2)]
                              (when (and (= 2 (count kv))
                                         (= "q" (str/trim (first kv))))
                                (try (Double/parseDouble (str/trim (second kv)))
                                     (catch NumberFormatException _ 1.0)))))
                          param-parts)]
        {:type    (when t (str/lower-case (str/trim t)))
         :subtype (when s (str/lower-case (str/trim s)))
         :q       (or q-param 1.0)}))))

(defn valid-accept?
  "Client must accept both application/json AND text/event-stream.
  Blank, wildcard-only, missing one type, or a required type with q=0 → false."
  [request]
  (let [accept (get-in request [:headers "accept"] "")]
    (if (str/blank? accept)
      false
      (let [ranges (parse-accept-ranges accept)
            has-json? (some (fn [r]
                              (and (= "application" (:type r))
                                   (= "json" (:subtype r))
                                   (pos? (:q r))))
                            ranges)
            has-sse?  (some (fn [r]
                              (and (= "text" (:type r))
                                   (= "event-stream" (:subtype r))
                                   (pos? (:q r))))
                            ranges)]
        (boolean (and has-json? has-sse?))))))

;; ---- HTTP helpers -----------------------------------------------------------

(defn- json-response [status body & [extra-headers]]
  {:status  status
   :headers (merge {"Content-Type" "application/json"} extra-headers)
   :body    (when body (json/generate-string body))})

;; ---- Protocol version -------------------------------------------------------

(defn- validate-protocol-version [request]
  (let [version (get-in request [:headers "mcp-protocol-version"])]
    (cond
      (nil? version) [true dispatcher/default-version]
      (contains? dispatcher/supported-versions version) [true version]
      :else [false (json-response 400
                                  {"error"     "unsupported protocol version"
                                   "supported" (vec dispatcher/supported-versions)})])))

(defn- extract-bearer [request]
  (when-let [auth (get-in request [:headers "authorization"])]
    (when (and (string? auth)
               (.regionMatches ^String auth true 0 "Bearer " 0 7))
      (subs auth 7))))

(defn- validate-origin [request]
  (let [origin (get-in request [:headers "origin"])]
    (or (nil? origin) (contains? @allowed-origins origin))))

(defn- authenticate-bearer [raw-token]
  (when raw-token (config/lookup-principal @server-config raw-token)))

;; ---- Body reading (F4, F5 — bounded, exception-safe) -----------------------

(defn- read-bounded-body
  "Read at most limit+1 bytes from request body, streaming. Returns the byte
  array (at most limit bytes) or throws :body-too-large if the stream exceeds
  limit. Never reads the full stream into memory before checking — safe for
  network InputStreams of arbitrary size."
  ^bytes [request limit]
  (when-let [body (:body request)]
    (let [^java.io.InputStream is (if (instance? ByteArrayInputStream body)
                                    body
                                    body)
          buf   (java.io.ByteArrayOutputStream. (min limit 8192))
          chunk (byte-array 8192)
          max-read (inc limit)]  ;; read one extra byte to detect overflow
      (loop [total 0]
        (let [remaining (- max-read total)
              to-read   (min (alength chunk) remaining)
              n         (.read is chunk 0 to-read)]
          (cond
            (neg? n) ;; EOF — return what we have
            (let [result (.toByteArray buf)]
              (when (zero? (alength result)) nil)
              result)

            :else
            (let [new-total (+ total n)]
              (.write buf chunk 0 n)
              (if (> new-total limit)
                (throw (ex-info "request body exceeds limit"
                                {:type :body-too-large :limit limit :size new-total}))
                (recur new-total)))))))))

(defn- parse-json-body
  "Parse JSON from request body with bounded reading. Returns parsed map or
  throws a bounded error."
  [request]
  (let [bytes (read-bounded-body request @body-limit)]
    (when (and bytes (pos? (alength bytes)))
      (let [s (String. bytes "UTF-8")]
        (when-not (str/blank? s)
          (try
            (let [parsed (json/parse-string s)]
              (when-not (map? parsed)
                (throw (ex-info "JSON body must be an object"
                                {:type :invalid-json})))
              parsed)
            (catch com.fasterxml.jackson.core.JsonParseException e
              (throw (ex-info "malformed JSON"
                              {:type :invalid-json :detail (.getMessage e)})))
            (catch Exception e
              (throw (ex-info "malformed JSON"
                              {:type :invalid-json :detail (.getMessage e)})))))))))

;; ---- POST /mcp handler ----------------------------------------------------

(defn- handle-post [request]
  (cond
    ;; F6: Origin check first — D2: denied, audit as pre-auth
    (not (validate-origin request))
    (do (dispatcher/record-transport-denial!
         "missing" nil "origin_rejected" "origin not allowed")
        (json-response 403 {"error" "origin not allowed"}))

    ;; F6: Bearer auth — D2: denied, audit missing/invalid
    (nil? (extract-bearer request))
    (do (dispatcher/record-transport-denial!
         "missing" nil "bearer_missing" "missing Authorization header")
        (json-response 401 {"error" "missing Authorization header"}))

    (nil? (authenticate-bearer (extract-bearer request)))
    (do (dispatcher/record-transport-denial!
         "invalid" nil "bearer_invalid" "invalid credentials")
        (json-response 401 {"error" "invalid credentials"}))

    ;; F1: Content-Type
    (not (valid-content-type? request))
    (json-response 415 {"error" "Content-Type must be application/json"})

    ;; F2: Accept
    (not (valid-accept? request))
    (json-response 406 {"error" "must accept both application/json and text/event-stream"})

    :else
    (let [[version-ok? version-or-err] (validate-protocol-version request)]
      (if-not version-ok?
        version-or-err
        ;; F4: Parse body with error boundary
        (let [parse-result (try {:ok (parse-json-body request)}
                                (catch clojure.lang.ExceptionInfo e
                                  {:error e :type (:type (ex-data e))}))]
          (cond
            (= :body-too-large (:type parse-result))
            (json-response 413 {"error" "request body too large"})

            (:error parse-result)
            (json-response 400
                           {"jsonrpc" "2.0" "id" nil
                            "error" {"code" -32700 "message" "parse error"}})

            :else
            (let [msg      (:ok parse-result)
                  method   (get msg "method")
                  sess-id  (get-in request [:headers "mcp-session-id"])
                  bearer   (extract-bearer request)
                  [pid pmap] (authenticate-bearer bearer)]
              (cond
                ;; Initialize — create session
                (= method "initialize")
                (if (session/principal-revoked? pid)
                  (json-response 403 {"error" "principal revoked"})
                  (let [sess (session/create-session! pid (:fork-id pmap))]
                    (if sess
                      (let [result (dispatcher/dispatch msg sess)]
                        (json-response 200 (:response result)
                                       {"Mcp-Session-Id" (:session-id sess)}))
                      (json-response 503 {"error" "fork not available"}))))

                ;; notifications/initialized — E1: principal binding check
                (= method "notifications/initialized")
                (if (nil? sess-id)
                  (json-response 400 {"error" "missing Mcp-Session-Id"})
                  (let [sess (session/get-session sess-id)]
                    (cond
                      (not sess)
                      (json-response 404 {"error" "session not found"})
                      (not= pid (:principal-id sess))
                      (json-response 403 {"error" "session binding mismatch"})
                      :else
                      {:status 202 :headers {} :body ""})))

                ;; All other methods — require session + principal binding
                (nil? sess-id)
                (json-response 400 {"error" "missing Mcp-Session-Id"})

                :else
                (let [sess (session/check-session-lease sess-id)]
                  (cond
                    (not sess)
                    (json-response 404 {"error" "session not found"})
                    (not= pid (:principal-id sess))
                    (json-response 403 {"error" "session binding mismatch"})
                    :else
                    (let [result (dispatcher/dispatch msg sess)]
                      (cond
                        (nil? result)
                        {:status 202 :headers {} :body ""}
                        (and (map? result) (= :initialize-result (:type result)))
                        (json-response 200 (:response result))
                        :else
                        (json-response 200 result)))))))))))))

;; ---- DELETE /mcp -----------------------------------------------------------

(defn- handle-delete [request]
  (cond
    (not (validate-origin request))
    (json-response 403 {"error" "origin not allowed"})

    (nil? (extract-bearer request))
    (json-response 401 {"error" "missing Authorization header"})

    (nil? (authenticate-bearer (extract-bearer request)))
    (json-response 401 {"error" "invalid credentials"})

    :else
    (let [sess-id (get-in request [:headers "mcp-session-id"])
          [pid _] (authenticate-bearer (extract-bearer request))]
      (cond
        (nil? sess-id)
        (json-response 400 {"error" "missing Mcp-Session-Id"})

        :else
        (let [sess (session/get-session sess-id)]
          (cond
            (not sess)
            (json-response 404 {"error" "session not found"})
            (not= pid (:principal-id sess))
            (json-response 403 {"error" "session binding mismatch"})
            :else
            (do (session/revoke-session! sess-id)
                (json-response 200 {"status" "session closed"}))))))))

;; ---- GET /mcp (405 — no SSE in V1) ----------------------------------------

(defn- handle-get [_request]
  {:status 405 :headers {"Allow" "POST, DELETE"} :body ""})

;; ---- POST /op (raw-operation endpoint) ---------------------------------------
;; A client-side bridge may POST application/edn to /op:
;;   {:op :edit :args [{:add [[<sentence> <context>] ...]}]}
;; The engine's own vaelii.host.serve/handle-op is not mounted here: it runs its
;; whole op table (retract, export to a daemon-host path, ...) against a bare KB
;; under one shared token, with no principal, audit journal, write-fence or
;; recorded creator. This route keeps the overlay's model instead: the /mcp origin
;; and config.edn bearer checks (denials audited the same way), then each :add
;; goes through the MCP `assert` verb on an ephemeral session over the
;; principal's fork, so audit, permit/write-fence, recorded creator and fork
;; scoping apply unchanged. Only :edit with :add is accepted. The caller reads
;; the status.

(defn- valid-edit-op? [op]
  (and (map? op)
       (= :edit (:op op))
       (sequential? (:args op))
       (seq (:args op))
       (every? (fn [a]
                 (and (map? a)
                      (= #{:add} (set (keys a)))
                      (sequential? (:add a))
                      (every? (fn [pair] (and (sequential? pair) (= 2 (count pair))))
                              (:add a))))
               (:args op))))

(defn- edit-op-adds [op]
  (for [a (:args op), pair (:add a)] pair))

(defn- dispatch-error? [resp]
  (boolean (or (get resp "error")
               (get-in resp ["result" "isError"]))))

(defn- read-edn-op
  "Read the bounded body as one EDN form: the form, ::too-large, or ::bad."
  [request]
  (let [raw (try (some-> (read-bounded-body request @body-limit) (String. "UTF-8"))
                 (catch clojure.lang.ExceptionInfo e
                   (if (= :body-too-large (:type (ex-data e)))
                     ::too-large
                     (throw e))))]
    (cond
      (= raw ::too-large) ::too-large
      (not (string? raw)) ::bad
      :else (try (edn/read-string raw) (catch Exception _ ::bad)))))

(defn- handle-op [request]
  (cond
    (not (validate-origin request))
    (do (dispatcher/record-transport-denial!
         "missing" nil "origin_rejected" "origin not allowed")
        (json-response 403 {"error" "origin not allowed"}))

    (nil? (extract-bearer request))
    (do (dispatcher/record-transport-denial!
         "missing" nil "bearer_missing" "missing Authorization header")
        (json-response 401 {"error" "missing Authorization header"}))

    (nil? (authenticate-bearer (extract-bearer request)))
    (do (dispatcher/record-transport-denial!
         "invalid" nil "bearer_invalid" "invalid credentials")
        (json-response 401 {"error" "invalid credentials"}))

    :else
    (let [[pid pmap] (authenticate-bearer (extract-bearer request))]
      (if (session/principal-revoked? pid)
        (json-response 403 {"error" "principal revoked"})
        (let [op (read-edn-op request)]
          (cond
            (= op ::too-large)
            (json-response 413 {"error" "request body too large"})

            (= op ::bad)
            (json-response 400 {"error" "malformed EDN body"})

            (not (valid-edit-op? op))
            (json-response 400 {"error" "unsupported op — expected {:op :edit :args [{:add [[sentence context] ...]}]}"})

            :else
            (let [sess (session/create-session! pid (:fork-id pmap))]
              (if (nil? sess)
                (json-response 503 {"error" "fork not available"})
                (try
                  (let [adds    (vec (edit-op-adds op))
                        results (mapv (fn [[sentence ctx]]
                                        (dispatcher/dispatch
                                         {"method" "tools/call" "id" 1
                                          "params" {"name"      "assert"
                                                    "arguments" {"sentence" (pr-str sentence)
                                                                 "context"  (pr-str ctx)}}}
                                         sess))
                                      adds)
                        failed  (count (filter dispatch-error? results))]
                    (if (pos? failed)
                      (json-response 502 {"error"  "one or more asserts failed"
                                          "failed" failed
                                          "total"  (count adds)})
                      (json-response 200 {"ok" true "added" (count adds)})))
                  (finally
                    (session/revoke-session! (:session-id sess))))))))))))

;; ---- Ring handler ----------------------------------------------------------

(defn- wrap-errors
  "Bounded error boundary shared by /mcp and /op."
  [f request]
  (try
    (f request)
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          :body-too-large (json-response 413 {"error" "request body too large"})
          :invalid-json   (json-response 400
                                         {"jsonrpc" "2.0" "id" nil
                                          "error" {"code" -32700 "message" "parse error"}})
          :principal-revoked (json-response 403 {"error" "principal revoked"})
          (json-response 500 {"error" "internal server error"}))))
    (catch Throwable _
      (json-response 500 {"error" "internal server error"}))))

(defn handler [request]
  (let [uri (:uri request) method (:request-method request)]
    (cond
      (= uri "/op")
      (if (= method :post)
        (wrap-errors handle-op request)
        {:status 405 :headers {"Allow" "POST"} :body ""})

      (= uri "/mcp")
      (try
        (case method
          :post   (handle-post request)
          :delete (handle-delete request)
          :get    (handle-get request)
          {:status 405 :headers {"Allow" "POST, DELETE, GET"} :body ""})
        (catch clojure.lang.ExceptionInfo e
          (let [t (:type (ex-data e))]
            (case t
              :body-too-large (json-response 413 {"error" "request body too large"})
              :invalid-json   (json-response 400
                                             {"jsonrpc" "2.0" "id" nil
                                              "error" {"code" -32700 "message" "parse error"}})
              (json-response 500 {"error" "internal server error"}))))
        (catch Throwable _
          (json-response 500 {"error" "internal server error"})))

      :else {:status 404 :body "not found"})))

;; ---- Service handle (G1-G4) ------------------------------------------------

(defrecord ServiceHandle [jetty sweeper-thread sweeper-running health-atom])

(defn- start-lease-sweeper!
  "Start a lease sweeper that runs in a dedicated thread.
  Returns {:thread thread :running atom :health atom}."
  [interval-ms health-atom]
  (let [running (atom true)
        t (Thread.
           (fn []
             (while @running
               (try
                 (Thread/sleep (long interval-ms))
                 (when @running
                   (session/sweep-expired-leases!))
                 (catch InterruptedException _
                   (reset! running false))
                 (catch Throwable t
                   ;; G4: sweep failure degrades health
                   (reset! health-atom {:healthy false :error (.getMessage t)})))))
           "vaelii-lease-sweeper")]
    (.setDaemon t true)
    (.start t)
    {:thread t :running running}))

(defn start-http!
  "Start the HTTP service. Returns a ServiceHandle owning Jetty + sweeper.
  G1: one handle owns everything. G3: partial startup cleanup."
  [{:keys [port host origins config join? sweep-interval body-limit-bytes
           audit-sink authority-store]
    :or   {port 3000 host "localhost" join? false sweep-interval 60000
           body-limit-bytes (* 1024 1024)}}]
  ;; A3: startup ordering
  ;; 1. Validate config (already done by caller)
  (reset! server-config config)
  (when origins (reset! allowed-origins origins))
  (reset! body-limit body-limit-bytes)
  ;; 2. Open and health-check authority store
  (when authority-store
    (session/set-authority-store! authority-store)
    (when-not (session/authority-healthy?)
      (throw (ex-info "authority store unhealthy at startup" {:type :startup-failed}))))
  ;; 3. Load persisted state
  (session/load-persisted-state!)
  ;; 4. Health-check A1 audit sink
  (when audit-sink
    (dispatcher/set-audit-sink! audit-sink)
    (when-not (dispatcher/audit-healthy?)
      (throw (ex-info "audit sink unhealthy at startup" {:type :startup-failed}))))
  ;; A4: production MUST have a real audit sink — no test sinks
  ;; (caller is responsible for providing a durable A1 sink)
  ;; 5. Open KB + provision forks (done by caller)
  ;; 6. Start sweeper
  (let [health-atom (atom {:healthy true})
        sweeper-info (start-lease-sweeper! sweep-interval health-atom)]
    (try
      ;; 7. Start Jetty
      (let [jetty-server (jetty/run-jetty handler
                                          {:port port :host host :join? join?})]
        (->ServiceHandle jetty-server (:thread sweeper-info)
                         (:running sweeper-info) health-atom))
      (catch Throwable t
        ;; G3: partial startup cleanup — stop the sweeper we already started
        (reset! (:running sweeper-info) false)
        (.interrupt ^Thread (:thread sweeper-info))
        (throw t)))))

(defn stop!
  "Stop the service. G2: idempotent, joins sweeper."
  [^ServiceHandle handle]
  (when handle
    ;; Stop sweeper
    (when-let [running (:sweeper-running handle)]
      (reset! running false))
    (when-let [t (:sweeper-thread handle)]
      (.interrupt ^Thread t)
      (.join ^Thread t 5000))
    ;; Stop Jetty
    (when-let [j (:jetty handle)]
      (.stop ^org.eclipse.jetty.server.Server j))))

(defn service-healthy?
  "Check if the service is healthy (sweeper hasn't errored)."
  [^ServiceHandle handle]
  (and handle
       (:healthy @(:health-atom handle))))
