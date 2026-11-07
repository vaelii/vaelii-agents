;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.ollama
  "The Ollama chat API backend, over `java.net.http` and `cheshire`, plus the host
  probes.  Beyond the neutral request keys it reads `:temperature` and `:keep-alive`.
  See docs/llm.md § The local backend."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [vaelii.agents.llm.http :as http]
            [vaelii.agents.llm.protocol :as proto])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Builder HttpRequest HttpRequest$Builder
            HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def default-host
  "The host `base-url` answers when neither variable names one: Ollama's own default."
  "http://localhost:11434")

(def default-model
  "The model a turn runs when `VAELII_OLLAMA_MODEL` and `:model` are unset."
  "phi4:14b")

(def default-generation-model
  "The model a generation turn runs on, a turn that writes new content rather than
  rewriting lines it was shown, when `VAELII_OLLAMA_GENERATION_MODEL` is unset."
  "qwen3-coder:30b")

(def default-keep-alive
  "How long the host holds the model resident after a turn, when
  `VAELII_OLLAMA_KEEP_ALIVE` and `:keep-alive` are unset."
  "30m")

(def default-num-ctx
  "The context length, in tokens, a request asks for when `VAELII_OLLAMA_NUM_CTX` and `:num-ctx` are
  unset."
  8192)

(def default-num-predict
  "`num_predict` when a request carries no `:max-tokens`."
  4096)

(defn normalize-host
  "A host string -> the base URL to call, read as Ollama reads `OLLAMA_HOST`: a host with
  no scheme gets `http://`, and `:11434` when it names no port, while a host with a scheme
  keeps that scheme's own port.  The scheme is matched in any case and written in lower
  case, an empty host is `127.0.0.1`, an unbracketed IPv6 address is bracketed and names
  no port, and trailing slashes are dropped."
  [host]
  (let [h (str/replace (str/trim (str host)) #"/+$" "")
        [_ scheme more] (re-matches #"(?i)(?:(https?)://)?(.*)" h)
        [_ authority path] (re-matches #"([^/]*)(.*)" more)
        [_ addr port] (if (and (not (str/starts-with? authority "["))
                               (< 1 (count (filter #{\:} authority))))
                        [nil (str "[" authority "]") nil]
                        (or (re-matches #"(\[[^\]]*\]|[^:]*)(:\d+)?" authority)
                            [nil authority nil]))]
    (str (if scheme (str/lower-case scheme) "http") "://"
         (if (str/blank? addr) "127.0.0.1" addr)
         (or port (when-not scheme ":11434"))
         path)))

(defn- bind-address?
  "Is `host` a wildcard bind address (`0.0.0.0`, `::`, `*`)?"
  [host]
  (some? (re-find #"^(https?://)?(0\.0\.0\.0|\[?::\]?|\*)(:|$)" (str/trim (str host)))))

(defn base-url
  "The Ollama base URL: `VAELII_OLLAMA_HOST`, else `OLLAMA_HOST` when it names a real
  target, else `default-host`."
  []
  (normalize-host
   (or (not-empty (str (System/getenv "VAELII_OLLAMA_HOST")))
       (let [h (not-empty (str (System/getenv "OLLAMA_HOST")))]
         (when (and h (not (bind-address? h))) h))
       default-host)))

(defn- host-url
  "The base URL every call to `host` uses: `host` normalized, else `base-url`."
  [host]
  (if host (normalize-host host) (base-url)))

(defn configured-model
  "The model to run: `VAELII_OLLAMA_MODEL` when set, else `default-model`."
  []
  (or (not-empty (str (System/getenv "VAELII_OLLAMA_MODEL"))) default-model))

(defn configured-generation-model
  "The model a generation turn runs: `VAELII_OLLAMA_GENERATION_MODEL` when set, else
  `default-generation-model`."
  []
  (or (not-empty (str (System/getenv "VAELII_OLLAMA_GENERATION_MODEL")))
      default-generation-model))

(defn configured-num-ctx
  "The context length, in tokens, to ask for: `VAELII_OLLAMA_NUM_CTX` when set and numeric, else
  `default-num-ctx`."
  []
  (or (try (some-> (System/getenv "VAELII_OLLAMA_NUM_CTX") str/trim not-empty Long/parseLong)
           (catch Exception _ nil))
      default-num-ctx))

(defn configured-keep-alive
  "How long to ask the host to hold the model resident: `VAELII_OLLAMA_KEEP_ALIVE` when
  set, else `default-keep-alive`."
  []
  (or (not-empty (str (System/getenv "VAELII_OLLAMA_KEEP_ALIVE"))) default-keep-alive))

(defn- new-client
  "A fresh `HttpClient` on `http/connect-timeout-ms`."
  ^HttpClient []
  (let [^HttpClient$Builder b (HttpClient/newBuilder)]
    (.connectTimeout b (Duration/ofMillis (long http/connect-timeout-ms)))
    (.build b)))

(def probe-client
  "The one `HttpClient`, delayed, that `version`, `show` and `warm` send on.  See
  docs/llm.md § What both HTTP backends share."
  (delay (new-client)))

(defn- post-builder
  ^HttpRequest$Builder [conn path payload]
  (let [^HttpRequest$Builder rb (HttpRequest/newBuilder (URI/create (str (:base-url conn) path)))]
    (.timeout rb (Duration/ofMillis (long (:timeout-ms conn))))
    (.header rb "content-type" "application/json")
    (.POST rb (HttpRequest$BodyPublishers/ofString ^String (json/generate-string payload)))
    rb))

(defn- api-error
  "The `:llm-api-error` for a failed call: the message is the excerpt of the body's
  `error` field (a corrupt model blob answers 500 with `failed to load model …`), else
  of the body, and the whole body rides under `:body`."
  [status ^String body-text]
  (let [parsed (try (json/parse-string body-text) (catch Exception _ nil))]
    (ex-info (str "Ollama API " status ": "
                  (http/excerpt (str (or (get parsed "error") body-text))))
             {:type :llm-api-error :status status :error (get parsed "error")
              :body body-text})))

(defn- throw-on-error-field
  "`m`, or an `:llm-api-error` when it carries an `error` field: Ollama reports a failure
  inside a 200 and inside a streamed chunk."
  [m]
  (if-let [e (and (map? m) (get m "error"))]
    (throw (ex-info (str "Ollama error: " (http/excerpt (str e)))
                    {:type :llm-api-error :error e}))
    m))

(def ^:private endpoint
  "This far end's `vaelii.agents.llm.http` endpoint."
  {:label "the Ollama host" :slug "ollama"})

(defn- decode
  "`http/decode` on this endpoint."
  [text status]
  (http/decode endpoint text status))

(defn version
  "The Ollama server's version string, or nil when it is not reachable within
  `:timeout-ms` (default 2000)."
  ([] (version {}))
  ([{:keys [host timeout-ms] :or {timeout-ms 2000}}]
   (try
     (let [url (host-url host)
           ^HttpClient http @probe-client
           ^HttpRequest$Builder rb (HttpRequest/newBuilder (URI/create (str url "/api/version")))
           _ (.timeout rb (Duration/ofMillis (long timeout-ms)))
           [status ^String text] (http/send-for-text endpoint http (.build (.GET rb)) timeout-ms)]
       (when (<= 200 (long status) 299)
         (get (json/parse-string text) "version")))
     (catch Exception _ nil))))

(defn available?
  "Is an Ollama reachable?  `opts` as `version`'s."
  ([] (available? {}))
  ([opts] (some? (version opts))))

(defn show
  "`/api/show` for one model — its template, parameters, and declared capabilities.
  nil when the host or the model is not reachable."
  ([model] (show model {}))
  ([model {:keys [host timeout-ms] :or {timeout-ms 10000}}]
   (try
     (let [conn {:base-url (host-url host)
                 :timeout-ms timeout-ms}
           ^HttpClient http @probe-client
           [status ^String text] (http/send-for-text
                                  endpoint http
                                  (.build (post-builder conn "/api/show" {"model" model}))
                                  timeout-ms)]
       (when (<= 200 (long status) 299)
         (json/parse-string text)))
     (catch Exception _ nil))))

(defn capabilities
  "The capability keywords a model declares (`#{:completion}`, `#{:completion :tools}`,
  …), or nil when they cannot be read."
  ([model] (capabilities model {}))
  ([model opts]
   (some->> (get (show model opts) "capabilities")
            (map (comp keyword str/lower-case str))
            set)))

(defn supports-tools?
  "Does this model declare `:tools`?  False when the host cannot be reached."
  ([model] (supports-tools? model {}))
  ([model opts] (boolean (some-> (capabilities model opts) :tools))))

(defn- filler
  "About `tokens` tokens of prose for `warm` to prefill with, saying that it is a
  warm-up."
  [tokens]
  (apply str (repeat (max 1 (quot (long tokens) 12))
                     "This request only warms the model up; nothing here is knowledge. ")))

(defn warm
  "Load `model` (default `configured-generation-model`) and hold it resident: one
  `:prefill-tokens` (2048) prefill at the request's context length, generating one token.  Returns
  `{:loaded? bool :elapsed-ms n :model m :prefill-tokens n}` and never throws.

  `opts`: `:host`, `:timeout-ms` (300000), `:keep-alive`, `:num-ctx`, `:prefill-tokens`."
  ([] (warm (configured-generation-model) {}))
  ([model] (warm model {}))
  ([model {:keys [host timeout-ms keep-alive num-ctx prefill-tokens]
           :or {timeout-ms 300000 prefill-tokens 2048}}]
   (let [started (System/currentTimeMillis)
         conn {:base-url (host-url host) :timeout-ms timeout-ms}
         payload {"model" model
                  "messages" [{"role" "system" "content" (filler prefill-tokens)}
                              {"role" "user" "content" "ok"}]
                  "stream" false
                  "options" {"num_ctx" (long (or num-ctx (configured-num-ctx)))
                             "num_predict" 1
                             "temperature" 0}
                  "keep_alive" (or keep-alive (configured-keep-alive))}
         out {:model model :prefill-tokens prefill-tokens}]
     (try
       (let [^HttpClient http @probe-client
             [status _] (http/send-for-text
                         endpoint http (.build (post-builder conn "/api/chat" payload))
                         (:timeout-ms conn))]
         (assoc out :loaded? (<= 200 (long status) 299)
                :elapsed-ms (- (System/currentTimeMillis) started)))
       (catch Exception _
         (assoc out :loaded? false :elapsed-ms (- (System/currentTimeMillis) started)))))))

(defn context-length
  "The model's native context length from `/api/show`'s `model_info`, or nil."
  ([model] (context-length model {}))
  ([model opts]
   (when-let [info (get (show model opts) "model_info")]
     (some (fn [[k v]] (when (str/ends-with? (str k) ".context_length") v)) info))))

(defn- block-text
  "The text one neutral content block contributes to an Ollama message, whose content is
  a plain string: a tool result flattens to text, thinking and tool-use to nothing."
  [block]
  (case (:type block)
    :text        (:text block)
    :thinking    nil
    :tool-result (str "tool result" (when (:error? block) " (error)") ": " (:content block))
    :tool-use    nil
    nil))

(defn- encode-message
  "One neutral message -> Ollama's `{role, content}`, with its tool-use blocks as
  `tool_calls`."
  [{:keys [role content]}]
  (let [blocks (when-not (string? content) content)
        text   (if (string? content)
                 content
                 (str/join "\n" (keep block-text blocks)))
        calls  (for [b blocks :when (= :tool-use (:type b))]
                 {"function" {"name" (:name b) "arguments" (or (:input b) {})}})]
    (cond-> {"role" role "content" text}
      (seq calls) (assoc "tool_calls" (vec calls)))))

(defn- system-text
  "The system blocks' text joined into the one system message, `:cache?` dropped."
  [blocks]
  (str/join "\n\n" (keep :text blocks)))

(defn- encode-tool
  "One tool schema in the protocol's shape (`vaelii.agents.tools/schemas`: `name`,
  `description`, `input_schema`) -> Ollama's `{type: function, function: {name,
  description, parameters}}`.  `strict` has no Ollama counterpart and is dropped."
  [t]
  {"type" "function"
   "function" {"name" (get t "name")
               "description" (get t "description")
               "parameters" (get t "input_schema")}})

(defn body
  "The neutral request -> Ollama's `/api/chat` body.  `keep_alive`, `num_ctx`,
  `num_predict` and `temperature` (default 0) are always sent; `format`, `tools` and
  `think` only when given.  `:think` false stops a thinking model (gemma4, qwen3) from
  spending `num_predict` on thinking and answering nothing; \"low\" etc. for gpt-oss."
  [{:keys [model system messages tools num-ctx max-tokens temperature keep-alive think]
    fmt :format}
   {:keys [stream?]}]
  (let [msgs (cond->> (mapv encode-message messages)
               (seq system) (into [{"role" "system" "content" (system-text system)}]))]
    (cond-> {"model" (or model (configured-model))
             "messages" msgs
             "stream" (boolean stream?)
             "keep_alive" (or keep-alive (configured-keep-alive))
             "options" {"num_ctx" (long (or num-ctx (configured-num-ctx)))
                        "num_predict" (long (or max-tokens default-num-predict))
                        "temperature" (double (or temperature 0))}}
      fmt           (assoc "format" fmt)
      (seq tools)   (assoc "tools" (mapv encode-tool tools))
      (some? think) (assoc "think" think))))

(defn- stop-reason
  "Ollama's `done_reason` -> the protocol's `:stop-reason`: `tool_use` whenever the turn
  carries tool calls, `max_tokens` for `length`, else `end_turn`."
  [m tool-calls]
  (cond
    (seq tool-calls) "tool_use"
    (= "length" (get m "done_reason")) "max_tokens"
    :else "end_turn"))

(defn- usage
  "The run's counts and durations, in ms.  `:input-tokens` is the host's measured prompt
  size."
  [m]
  {:input-tokens  (get m "prompt_eval_count")
   :output-tokens (get m "eval_count")
   :load-ms       (some-> (get m "load_duration") (quot 1000000))
   :prompt-ms     (some-> (get m "prompt_eval_duration") (quot 1000000))
   :eval-ms       (some-> (get m "eval_duration") (quot 1000000))
   :total-ms      (some-> (get m "total_duration") (quot 1000000))})

(defn- tool-blocks
  "The `tool_calls` of a message as neutral `:tool-use` blocks.  A call without an `id`
  gets one from its position in the turn, counted from `offset`, the number of calls
  earlier chunks of a streamed turn carried."
  [message offset]
  (vec (map-indexed
        (fn [i c]
          (let [f (get c "function")]
            {:type :tool-use
             :id (or (get c "id") (str "ollama_tool_" (+ (long offset) (long i))))
             :name (get f "name")
             :input (get f "arguments")}))
        (get message "tool_calls"))))

(defn parse-response
  "Ollama's chat JSON -> the neutral response map (`vaelii.agents.llm.protocol`)."
  [m]
  (let [message (get m "message" {})
        calls   (tool-blocks message 0)
        text    (get message "content")
        think   (get message "thinking")]
    {:stop-reason (stop-reason m calls)
     :model       (get m "model")
     :usage       (usage m)
     :content     (cond-> []
                    (not (str/blank? think)) (conj {:type :thinking :text think})
                    (not (str/blank? text))  (conj {:type :text :text text})
                    true                     (into calls))}))

(defn- do-complete [conn request]
  (let [^HttpClient http (:http conn)
        payload (body (merge (select-keys conn [:model :num-ctx :keep-alive :think]) request)
                      {:stream? false})
        [status ^String text] (http/send-for-text
                               endpoint http (.build (post-builder conn "/api/chat" payload))
                               (:timeout-ms conn))]
    (if (<= 200 (long status) 299)
      (parse-response (throw-on-error-field (decode text status)))
      (throw (api-error status text)))))

(defn- collect
  "Fold a streamed run's chunks into the response map `complete` would have returned:
  the text accumulates each chunk's `message.content` delta, and the counts come from
  the `done` chunk."
  [chunks on-event]
  (let [sb    (StringBuilder.)
        think (StringBuilder.)
        acc   (reduce (fn [acc m]
                        (let [msg   (get m "message")
                              d     (get msg "content")
                              t     (get msg "thinking")
                              calls (tool-blocks msg (count (:calls acc)))]
                          (when (not (str/blank? d))
                            (.append sb ^String d)
                            (on-event {:type :text-delta :text d}))
                          (when (not (str/blank? t)) (.append think ^String t))
                          (cond-> acc
                            (seq calls)    (update :calls into calls)
                            (get m "done") (assoc :final m))))
                      {:calls []}
                      chunks)
        final (or (:final acc) {})]
    (on-event {:type :block-stop})
    {:stop-reason (stop-reason final (:calls acc))
     :model       (get final "model")
     :usage       (usage final)
     :content     (cond-> []
                    (pos? (.length think)) (conj {:type :thinking :text (str think)})
                    (pos? (.length sb))    (conj {:type :text :text (str sb)})
                    true                   (into (:calls acc)))}))

(defn- do-stream [conn request on-event]
  (let [^HttpClient http (:http conn)
        payload (body (merge (select-keys conn [:model :num-ctx :keep-alive :think]) request)
                      {:stream? true})
        ;; taken before the send: `:timeout-ms` bounds headers and body together
        deadline (+ (System/currentTimeMillis) (long (:timeout-ms conn)))
        ^HttpResponse resp (http/send-request
                            endpoint http (.build (post-builder conn "/api/chat" payload))
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
           (on-event {:type :block-start :block {:type :text}})
           (let [chunks   (->> lines
                               (remove str/blank?)
                               (map #(throw-on-error-field (decode ^String % status))))
                 response (collect chunks on-event)]
             (on-event {:type :done :response response})
             response)))))))

(defn provider
  "An Ollama-backed `Provider`.  `opts`:

    :host        default `base-url`
    :model       default `VAELII_OLLAMA_MODEL`, else `default-model`
    :num-ctx     default `VAELII_OLLAMA_NUM_CTX`, else `default-num-ctx`
    :keep-alive  default `configured-keep-alive`
    :timeout-ms  default 300000; bounds the whole turn, the body included
    :think       sent as Ollama's `think` when given: false, true, or \"low\"/\"medium\"/\"high\"

  A request's `:model`, `:num-ctx`, `:keep-alive` and `:think` override these.  Building one opens
  no socket; `available?` is the reachability check.  `meta` carries `:conn`."
  ([] (provider {}))
  ([{:keys [host model num-ctx keep-alive timeout-ms think]
     :or {timeout-ms 300000}}]
   (let [conn (cond-> {:base-url (host-url host)
                       :timeout-ms timeout-ms
                       :http (new-client)}
                model      (assoc :model model)
                num-ctx    (assoc :num-ctx num-ctx)
                keep-alive (assoc :keep-alive keep-alive)
                (some? think) (assoc :think think))]
     (with-meta
       (reify proto/Provider
         (complete [_ request] (do-complete conn request))
         (stream [_ request on-event] (do-stream conn request on-event)))
       {:conn (dissoc conn :http)}))))

(defn generation-provider
  "`provider` on `configured-generation-model`, for a generation turn; an `opts`
  `:model` still wins."
  ([] (generation-provider {}))
  ([opts] (provider (merge {:model (configured-generation-model)} opts))))
