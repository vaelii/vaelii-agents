;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.anthropic
  "The Anthropic Messages API backend, over `java.net.http` and `cheshire`.  Beyond the
  neutral request keys it reads `:thinking-display` and `:fallbacks`.  See docs/llm.md
  § The Messages API backend."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [vaelii.agents.llm.http :as http]
            [vaelii.agents.llm.protocol :as proto])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.lang ProcessBuilder$Redirect]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Builder HttpRequest HttpRequest$Builder
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def default-model
  "The model this backend talks to unless told otherwise."
  "claude-opus-5-5")

(def api-version "2023-06-01")

(def fallback-beta
  "Beta flag for the scalar `fallbacks: \"default\"` form — routes a policy decline to
  Anthropic's recommended substitute model by refusal category."
  "server-side-fallback-2026-07-01")

(def ^:private endpoint
  "This far end's `vaelii.agents.llm.http` endpoint."
  {:label "the Anthropic API" :slug "anthropic"})

(def ^:private cli-timeout-ms
  "How long `ant auth print-credentials` runs before it is killed, in ms."
  5000)

(defn- clean
  "A credential as read from the environment: trimmed, and nil when blank.  A control
  character mid-string survives; `request-builder` refuses it."
  [s]
  (not-empty (str/trim (str s))))

(defn- ant-cli-token
  "A short-lived access token from `ant auth print-credentials`, or nil.  The child is
  killed after `cli-timeout-ms`, and the child and its watchdog are released on every
  exit.  An interrupt answers nil with the thread's interrupt flag restored."
  []
  (try
    (let [^java.util.List argv ["ant" "auth" "print-credentials" "--access-token"]
          ^ProcessBuilder pb (ProcessBuilder. argv)
          _ (.redirectError pb ProcessBuilder$Redirect/DISCARD)
          ^Process proc (.start pb)
          ^Thread killer (http/watchdog endpoint cli-timeout-ms
                                        #(.destroyForcibly ^Process proc))]
      (try
        (let [out  (slurp (.getInputStream proc))
              exit (.waitFor proc)]
          (when (zero? exit) (clean out)))
        (finally
          (.interrupt killer)
          (.destroyForcibly proc))))
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      nil)
    (catch Exception _ nil)))

(defn credentials
  "The credential to authenticate with, as `{:kind :api-key|:bearer :value \"…\"}`, or
  nil: `ANTHROPIC_API_KEY`, then `ANTHROPIC_AUTH_TOKEN`, then an `ant auth login`
  profile.  The value is never logged, printed or put in an exception.  See docs/llm.md
  § Credentials."
  []
  (or (when-let [k (clean (System/getenv "ANTHROPIC_API_KEY"))]
        {:kind :api-key :value k})
      (when-let [t (clean (System/getenv "ANTHROPIC_AUTH_TOKEN"))]
        {:kind :bearer :value t})
      (when-let [t (ant-cli-token)]
        {:kind :bearer :value t})))

(defn available?
  "Is a credential reachable: `opts`'s `:credential`, else one `credentials` finds?"
  ([] (available? {}))
  ([{:keys [credential]}] (some? (or credential (credentials)))))

(defn- encode-block
  "One neutral content block -> the API's JSON shape: its `:raw` JSON unchanged when it
  carries one, else `:text`, `:tool-use` or `:tool-result` encoded, else throws
  `:llm-encode` naming the `:block-type`."
  [block]
  (or (:raw block)
      (case (:type block)
        :text        {"type" "text" "text" (:text block)}
        :tool-result (cond-> {"type" "tool_result"
                              "tool_use_id" (:tool-use-id block)
                              "content" (:content block)}
                       (:error? block) (assoc "is_error" true))
        :tool-use    {"type" "tool_use" "id" (:id block)
                      "name" (:name block) "input" (or (:input block) {})}
        (throw (ex-info (str "cannot encode content block of type " (pr-str (:type block))
                             " — want :text, :tool-use or :tool-result, or a block"
                             " carrying its original JSON under :raw")
                        {:type :llm-encode :block-type (:type block)})))))

(defn- encode-message [{:keys [role content]}]
  {"role" role
   "content" (if (string? content) content (mapv encode-block content))})

(defn- encode-system
  "System blocks, with a `cache_control` breakpoint on each block flagged `:cache?`."
  [blocks]
  (mapv (fn [{:keys [text cache?]}]
          (cond-> {"type" "text" "text" text}
            cache? (assoc "cache_control" {"type" "ephemeral"})))
        blocks))

(defn- body
  [{:keys [model system messages tools max-tokens effort thinking-display fallbacks]
    fmt :format
    :or {fallbacks "default"}}
   {:keys [stream?]}]
  ;; `:effort` and `:format` are two keys of one object, so one binding builds both: a
  ;; second `assoc` of "output_config" drops whichever key arrived first.
  (let [output-config (cond-> {}
                        effort (assoc "effort" effort)
                        fmt    (assoc "format" {"type" "json_schema" "schema" fmt}))]
    (cond-> {"model" (or model default-model)
             "max_tokens" (or max-tokens (if stream? 64000 16000))
             "messages" (mapv encode-message messages)}
      (seq system)        (assoc "system" (encode-system system))
      (seq tools)         (assoc "tools" (vec tools))
      (seq output-config) (assoc "output_config" output-config)
      thinking-display    (assoc "thinking" {"type" "adaptive" "display" thinking-display})
      fallbacks           (assoc "fallbacks" fallbacks)
      stream?             (assoc "stream" true))))

(defn- parse-block [b]
  (let [t (get b "type")]
    (merge {:raw b}
           (case t
             "text"     {:type :text :text (get b "text")}
             "thinking" {:type :thinking :text (get b "thinking")}
             "tool_use" {:type :tool-use :id (get b "id")
                         :name (get b "name") :input (get b "input")}
             {:type :other :block-type t}))))

(defn parse-response
  "The API's JSON message -> the neutral response map (`vaelii.agents.llm.protocol`)."
  [m]
  {:stop-reason  (get m "stop_reason")
   :stop-details (get m "stop_details")
   :model        (get m "model")
   :usage        (get m "usage")
   :content      (mapv parse-block (get m "content" []))})

(defn- headers
  [{:keys [kind value]} extra-betas]
  (let [betas (cond-> (vec extra-betas)
                (= :bearer kind) (conj "oauth-2025-04-20"))]
    (cond-> {"content-type" "application/json"
             "anthropic-version" api-version}
      (= :api-key kind) (assoc "x-api-key" value)
      (= :bearer kind)  (assoc "authorization" (str "Bearer " value))
      (seq betas)       (assoc "anthropic-beta" (str/join "," (distinct betas))))))

(defn- request-builder
  "The request to send, headers included.  A header value the JDK rejects throws
  `:llm-bad-credential` naming the header and never the value, which the JDK's own
  exception quotes."
  ^HttpRequest$Builder [conn payload betas]
  (let [^HttpRequest$Builder rb (HttpRequest/newBuilder
                                 (URI/create (str (:base-url conn) "/v1/messages")))]
    (.timeout rb (Duration/ofMillis (long (:timeout-ms conn))))
    (doseq [[k v] (headers (:credential conn) betas)]
      (try
        (.header rb ^String k ^String v)
        (catch Exception _
          (throw (ex-info (str "the " k " header holds a character an HTTP header cannot "
                               "carry — a credential read from the environment with a "
                               "trailing newline or a control character in it is the "
                               "usual cause")
                          {:type :llm-bad-credential :header k})))))
    (.POST rb (HttpRequest$BodyPublishers/ofString ^String (json/generate-string payload)))
    rb))

(defn- decode
  "`http/decode` on this endpoint."
  [text status]
  (http/decode endpoint text status))

(defn- api-error
  "The `:llm-api-error` for a failed call: the message is the excerpt of the API's
  `error.message`, else of the body, and the whole body rides under `:body`."
  [status ^String body-text]
  (let [parsed (try (json/parse-string body-text) (catch Exception _ nil))]
    (ex-info (str "Anthropic API " status ": "
                  (http/excerpt (str (or (get-in parsed ["error" "message"]) body-text))))
             {:type :llm-api-error
              :status status
              :error-type (get-in parsed ["error" "type"])
              :body body-text})))

(defn- betas-for [request]
  (when (:fallbacks request "default") [fallback-beta]))

(defn- do-complete [conn request]
  (let [payload (body request {:stream? false})
        [status ^String text] (http/send-for-text
                               endpoint (:http conn)
                               (.build (request-builder conn payload (betas-for request)))
                               (:timeout-ms conn))]
    (if (<= 200 (long status) 299)
      (parse-response (decode text status))
      (throw (api-error status text)))))

(defn- apply-event
  "Fold one decoded SSE event into the accumulating response, and pass `on-event`
  the caller's form of it.  Returns the next accumulator."
  [acc ev on-event]
  (case (get ev "type")
    "message_start"
    (let [m (get ev "message")]
      (assoc acc :model (get m "model") :usage (get m "usage")))

    "content_block_start"
    (let [i (get ev "index")
          b (get ev "content_block")]
      (on-event {:type :block-start :block (parse-block b)})
      (assoc-in acc [:blocks i] {:raw b :buf (StringBuilder.)}))

    "content_block_delta"
    (let [i (get ev "index")
          d (get ev "delta")
          ^StringBuilder sb (get-in acc [:blocks i :buf])]
      (case (get d "type")
        "text_delta"       (do (when sb (.append sb ^String (get d "text")))
                               (on-event {:type :text-delta :text (get d "text")})
                               acc)
        "thinking_delta"   (do (when sb (.append sb ^String (get d "thinking"))) acc)
        "input_json_delta" (do (when sb (.append sb ^String (get d "partial_json"))) acc)
        ;; the signature arrives as its own delta; the API rejects a thinking block
        ;; echoed back without it
        "signature_delta"  (assoc-in acc [:blocks i :sig] (get d "signature"))
        acc))

    "content_block_stop"
    (do (on-event {:type :block-stop :index (get ev "index")}) acc)

    "message_delta"
    (-> acc
        (assoc :stop-reason (get-in ev ["delta" "stop_reason"])
               :stop-details (get-in ev ["delta" "stop_details"]))
        (update :usage merge (get ev "usage")))

    "error"
    (throw (ex-info (str "Anthropic stream error"
                         (when-let [t (get-in ev ["error" "type"])] (str " (" t ")"))
                         ": " (http/excerpt (str (or (get-in ev ["error" "message"])
                                                     (pr-str ev)))))
                    {:type :llm-api-error :error-type (get-in ev ["error" "type"])}))

    acc))

(defn- finish-block
  "Close one accumulated streamed block back into its JSON shape, then parse it, so
  `:raw` holds what a non-streamed turn would have returned."
  [{:keys [raw sig ^StringBuilder buf]}]
  (let [s (str buf)]
    (parse-block
     (case (get raw "type")
       "text"     (assoc raw "text" s)
       "thinking" (cond-> (assoc raw "thinking" s) sig (assoc "signature" sig))
       "tool_use" (assoc raw "input" (if (str/blank? s) {} (json/parse-string s)))
       raw))))

(defn- collect
  "Fold decoded SSE events into the response map `parse-response` would produce."
  [events on-event]
  (let [acc (reduce (fn [acc ev] (apply-event acc ev on-event))
                    {:blocks (sorted-map)}
                    events)]
    {:stop-reason  (:stop-reason acc)
     :stop-details (:stop-details acc)
     :model        (:model acc)
     :usage        (:usage acc)
     :content      (mapv finish-block (vals (:blocks acc)))}))

(defn- do-stream [conn request on-event]
  (let [^HttpClient http (:http conn)
        payload (body request {:stream? true})
        ;; taken before the send: `:timeout-ms` bounds headers and body together
        deadline (+ (System/currentTimeMillis) (long (:timeout-ms conn)))
        ^HttpResponse resp (http/send-request
                            endpoint http (.build (request-builder conn payload (betas-for request)))
                            (HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode resp)
        ^InputStream in (.body resp)]
    (http/under-read-deadline
     endpoint
     (max 1 (- deadline (System/currentTimeMillis)))
     #(.close ^InputStream in)
     (fn []
       (with-open [rdr (BufferedReader. (InputStreamReader. in StandardCharsets/UTF_8))]
         (let [lines (line-seq rdr)]
           (when-not (<= 200 status 299)
             (throw (api-error status (str/join "\n" lines))))
           (let [events (->> lines
                             (filter #(str/starts-with? ^String % "data: "))
                             (map #(decode (subs ^String % 6) status)))
                 response (collect events on-event)]
             (on-event {:type :done :response response})
             response)))))))

(defn provider
  "An Anthropic-backed `Provider`.

  `opts`:

    :credential  `{:kind :api-key|:bearer :value \"…\"}` — resolved from the
                 environment by `credentials` when absent
    :base-url    default `https://api.anthropic.com` (or `ANTHROPIC_BASE_URL`)
    :timeout-ms  default 600000; bounds the whole turn, the body included

  Throws `:llm-no-credential` when no credential is reachable."
  ([] (provider {}))
  ([{:keys [credential base-url timeout-ms]
     :or {timeout-ms 600000}}]
   (let [credential (or credential (credentials))
         _ (when-not credential
             (throw (ex-info (str "no Anthropic credential: set ANTHROPIC_API_KEY, or "
                                  "ANTHROPIC_AUTH_TOKEN, or run `ant auth login`")
                             {:type :llm-no-credential})))
         ^HttpClient$Builder b (HttpClient/newBuilder)
         _ (.connectTimeout b (Duration/ofMillis (long http/connect-timeout-ms)))
         conn {:base-url (or base-url (System/getenv "ANTHROPIC_BASE_URL") "https://api.anthropic.com")
               :timeout-ms timeout-ms
               :credential credential
               :http (.build b)}]
     (reify proto/Provider
       (complete [_ request] (do-complete conn request))
       (stream [_ request on-event] (do-stream conn request on-event))))))
