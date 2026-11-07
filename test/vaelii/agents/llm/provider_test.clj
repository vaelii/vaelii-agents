;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.provider-test
  "The provider stack (`vaelii.agents.llm.*`), offline: encoding and decoding are pure,
  and every transport test redefines the HTTP client it would send on."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [vaelii.agents.llm.anthropic :as anthropic]
            [vaelii.agents.llm.http :as llm-http]
            [vaelii.agents.llm.ollama :as ollama]
            [vaelii.agents.llm.protocol :as proto]
            [vaelii.agents.llm.provider :as provider]
            [vaelii.agents.tools :as tools])
  (:import [java.net ConnectException URI]
           [java.net.http HttpClient HttpConnectTimeoutException HttpRequest HttpResponse
            HttpResponse$BodyHandlers HttpTimeoutException]
           [java.time Duration]))

;; ---- the real backend, offline -----------------------------------------
;; Encoding and decoding are pure: no credential, no socket.

(deftest the-request-body-omits-the-rejected-sampling-parameters
  (let [b (#'anthropic/body {:model "claude-opus-5-5"
                             :system [{:text "stable prefix" :cache? true}]
                             :messages [{:role "user" :content "hi"}]
                             :tools (tools/schemas {:only #{:sentexes-matching}})
                             :effort "high"}
                            {:stream? false})]
    (testing "temperature / top_p / top_k are rejected by this model family"
      (is (not-any? (set (keys b)) ["temperature" "top_p" "top_k"])))
    (testing "thinking takes no token budget — depth is effort"
      (is (nil? (get b "thinking")))
      (is (= {"effort" "high"} (get b "output_config"))))
    (testing "the stable prefix carries the cache breakpoint, the user turn does not"
      (is (= {"type" "ephemeral"} (get-in b ["system" 0 "cache_control"])))
      (is (= "hi" (get-in b ["messages" 0 "content"]))))
    (is (= "claude-opus-5-5" (get b "model")))
    (is (pos? (get b "max_tokens")))
    (is (= 1 (count (get b "tools"))))))

(deftest the-request-body-opts-into-a-refusal-fallback
  (let [b (#'anthropic/body {:messages []} {:stream? false})]
    (is (= "default" (get b "fallbacks")))
    (is (= [anthropic/fallback-beta] (#'anthropic/betas-for {:messages []}))))
  (testing "and it can be dropped for an org that has not enabled the beta"
    (let [b (#'anthropic/body {:messages [] :fallbacks nil} {:stream? false})]
      (is (nil? (get b "fallbacks")))
      (is (nil? (#'anthropic/betas-for {:messages [] :fallbacks nil}))))))

(deftest effort-and-format-share-one-output-config
  (is (= {"effort" "low" "format" {"type" "json_schema" "schema" {"type" "object"}}}
         (get (#'anthropic/body {:messages [] :effort "low" :format {"type" "object"}}
                                {:stream? false})
              "output_config"))))

(deftest streaming-asks-for-more-room-than-a-single-response
  (is (< (get (#'anthropic/body {:messages []} {:stream? false}) "max_tokens")
         (get (#'anthropic/body {:messages []} {:stream? true}) "max_tokens")))
  (is (true? (get (#'anthropic/body {:messages []} {:stream? true}) "stream"))))

(deftest an-oauth-credential-uses-bearer-and-the-oauth-beta
  (let [api (#'anthropic/headers {:kind :api-key :value "test-value"} [])
        oauth (#'anthropic/headers {:kind :bearer :value "test-value"} [])]
    (is (= "test-value" (get api "x-api-key")))
    (is (nil? (get api "authorization")))
    (is (= "Bearer test-value" (get oauth "authorization")))
    (is (nil? (get oauth "x-api-key")))
    (is (str/includes? (get oauth "anthropic-beta") "oauth-2025-04-20"))
    (is (= anthropic/api-version (get api "anthropic-version")))))

(deftest a-refusal-decodes-without-inventing-content
  (let [r (anthropic/parse-response
           {"stop_reason" "refusal"
            "stop_details" {"type" "refusal" "category" "cyber"}
            "content" []})]
    (is (proto/refused? r))
    (is (= [] (:content r)))
    (is (= "" (proto/text r)))))

(deftest tool-use-and-thinking-blocks-round-trip
  (let [r (anthropic/parse-response
           {"stop_reason" "tool_use"
            "content" [{"type" "thinking" "thinking" "hmm" "signature" "sig"}
                       {"type" "text" "text" "checking"}
                       {"type" "tool_use" "id" "toolu_1" "name" "kb_sentexes_matching"
                        "input" {"sentence" "(dog ?x)"}}]})]
    (is (= "checking" (proto/text r)))
    (is (= 1 (count (proto/tool-uses r))))
    (is (= "kb_sentexes_matching" (:name (first (proto/tool-uses r)))))
    (testing "each block keeps its original JSON, so it echoes back unedited"
      (is (= "sig" (get-in (:content r) [0 :raw "signature"])))
      (is (= {"type" "text" "text" "checking"} (#'anthropic/encode-block (nth (:content r) 1)))))))

(deftest a-content-block-the-encoder-cannot-shape-is-refused-rather-than-dropped
  ;; Encoded as nothing, the block would leave a turn missing a block the rest of it
  ;; refers to; encoded as a guess, a thinking block would be edited, which the API rejects.
  (let [encode #'anthropic/encode-block]
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (encode {:type :thinking :thinking "weighing it"})))]
      (is (= :llm-encode (:type (ex-data e))))
      (is (= :thinking (:block-type (ex-data e)))
          "and it names the type it could not shape"))
    (testing "a block carrying its original JSON rides back unchanged, whatever its type"
      (is (= {"type" "thinking" "thinking" "weighing it" "signature" "sig"}
             (encode {:type :thinking
                      :raw {"type" "thinking" "thinking" "weighing it" "signature" "sig"}}))))
    (testing "and the three shapes it does model encode without one"
      (is (= {"type" "text" "text" "checking"} (encode {:type :text :text "checking"}))))))

(deftest sse-frames-reassemble-into-the-same-response-shape
  (let [events (atom [])
        r (#'anthropic/collect
           [{"type" "message_start" "message" {"model" "claude-opus-5" "usage" {"input_tokens" 12}}}
            {"type" "content_block_start" "index" 0
             "content_block" {"type" "thinking" "thinking" ""}}
            {"type" "content_block_delta" "index" 0
             "delta" {"type" "thinking_delta" "thinking" "weighing it"}}
            {"type" "content_block_delta" "index" 0
             "delta" {"type" "signature_delta" "signature" "sig-abc"}}
            {"type" "content_block_stop" "index" 0}
            {"type" "content_block_start" "index" 1 "content_block" {"type" "text" "text" ""}}
            {"type" "content_block_delta" "index" 1 "delta" {"type" "text_delta" "text" "one "}}
            {"type" "content_block_delta" "index" 1 "delta" {"type" "text_delta" "text" "two"}}
            {"type" "content_block_stop" "index" 1}
            {"type" "content_block_start" "index" 2
             "content_block" {"type" "tool_use" "id" "toolu_9" "name" "kb_types_of" "input" {}}}
            {"type" "content_block_delta" "index" 2
             "delta" {"type" "input_json_delta" "partial_json" "{\"x\":"}}
            {"type" "content_block_delta" "index" 2
             "delta" {"type" "input_json_delta" "partial_json" "\"Muffet\"}"}}
            {"type" "content_block_stop" "index" 2}
            {"type" "message_delta" "delta" {"stop_reason" "tool_use"} "usage" {"output_tokens" 30}}]
           #(swap! events conj %))]
    (is (= "one two" (proto/text r)))
    (is (= "tool_use" (:stop-reason r)))
    (is (= {"x" "Muffet"} (:input (first (proto/tool-uses r)))) "partial json reassembles")
    (testing "the signature arrives as its own delta and must ride back out"
      (is (= "sig-abc" (get-in (:content r) [0 :raw "signature"])))
      (is (= "weighing it" (get-in (:content r) [0 :raw "thinking"]))))
    (testing "blocks are ordered by index, whatever order the frames folded in"
      (is (= [:thinking :text :tool-use] (mapv :type (:content r)))))
    (is (= 2 (count (filter #(= :text-delta (:type %)) @events))))))

(deftest ndjson-chunks-reassemble-into-the-same-response-shape
  (let [events (atom [])
        done   {"done" true "done_reason" "stop" "model" "phi4:14b"
                "prompt_eval_count" 12 "eval_count" 3}
        r (#'ollama/collect
           [{"message" {"role" "assistant" "thinking" "hm"}}
            {"message" {"role" "assistant" "content" "one "}}
            {"message" {"role" "assistant" "content" "two"}}
            (assoc done "message" {"role" "assistant" "content" ""})]
           #(swap! events conj %))]
    (is (= (ollama/parse-response
            (assoc done "message" {"role" "assistant" "content" "one two" "thinking" "hm"}))
           r)
        "the streamed map is the one the whole body would have parsed to")
    (is (= ["one " "two"] (keep :text (filter #(= :text-delta (:type %)) @events))))))

(deftest a-missing-credential-is-a-typed-refusal-not-a-crash
  (let [outcome (try (anthropic/provider) :built
                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
    ;; Machine-dependent: a reachable credential builds a provider, an absent one is a
    ;; typed refusal.
    (is (= (if (anthropic/available?) :built :llm-no-credential) outcome))))

;; ---- what the two transports refuse, and how ----------------------------
;; None of it opens a socket: a request is built rather than sent, and the deadline is
;; exercised with the read stubbed.

(deftest a-credential-that-cannot-ride-in-a-header-is-refused-without-quoting-it
  (let [conn (fn [v] {:base-url "http://127.0.0.1:1" :timeout-ms 1000
                      :credential {:kind :api-key :value v}})]
    (doseq [[what value] [["a trailing CRLF, which is what a .env file leaves"
                           "sk-ant-SUPERSECRET\r\n"]
                          ["a control character mid-string, which no trim removes"
                           (str "sk-ant-" (char 7) "SUPERSECRET")]]]
      (testing what
        (let [e (try (#'anthropic/request-builder (conn value) {"model" "m"} [])
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (some? e) "the JDK rejects the value, and the rejection does not escape raw")
          (is (= :llm-bad-credential (:type (ex-data e))))
          (is (= "x-api-key" (:header (ex-data e))) "the header is named")
          (testing "and the value is in neither the message nor the data"
            (is (not (str/includes? (str (ex-message e) " " (pr-str (ex-data e)))
                                    "SUPERSECRET")))))))
    (testing "a credential with nothing wrong with it still builds a request"
      (is (some? (#'anthropic/request-builder (conn "sk-ant-ordinary") {"model" "m"} []))))))

(deftest an-environment-credential-is-trimmed
  (testing "the whitespace a shell or a .env file leaves is not part of the value"
    (is (= "sk-ant-x" (#'anthropic/clean "  sk-ant-x \r\n")))
    (is (nil? (#'anthropic/clean "   ")))
    (is (nil? (#'anthropic/clean nil)))))

(deftest a-body-that-is-not-json-carries-a-type-like-every-other-refusal
  (doseq [[backend decode] [["anthropic" #'anthropic/decode] ["ollama" #'ollama/decode]]]
    (testing backend
      (let [e (try (decode "<html>a proxy answered</html>" 200) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= :llm-bad-response (:type (ex-data e))))
        (is (= 200 (:status (ex-data e)))))
      (testing "and the excerpt is bounded"
        (let [e (try (decode (apply str (repeat 5000 "x")) 200) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (>= 200 (count (:excerpt (ex-data e)))))
          (is (not (str/includes? (ex-message e) "xxxx"))
              "the body is data, not the message"))))))

(deftest an-error-field-in-a-200-is-an-api-error
  (let [e (try (#'ollama/throw-on-error-field {"error" "model 'x' not found"}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :llm-api-error (:type (ex-data e))))
    (is (= "model 'x' not found" (:error (ex-data e)))))
  (is (= {"done" true} (#'ollama/throw-on-error-field {"done" true}))))

(deftest the-connect-deadline-is-not-the-turns-budget
  (testing "the shared deadline is seconds, not minutes"
    (is (<= 1000 llm-http/connect-timeout-ms 10000)))
  (testing "and a client built for a five-minute turn still connects on that deadline"
    (let [^java.net.http.HttpClient c (#'ollama/new-client)]
      (is (= (java.time.Duration/ofMillis llm-http/connect-timeout-ms)
             (.orElse (.connectTimeout c) nil))
          "the connect deadline is the constant, whatever a turn was allowed")))
  (testing "the Anthropic backend builds its client on the same constant"
    (is (str/includes? (slurp (io/file "src/vaelii/agents/llm/anthropic.clj"))
                       "(.connectTimeout b (Duration/ofMillis (long http/connect-timeout-ms)))"))))

(deftest the-probes-share-one-http-client
  ;; This opens no socket: the host is a string no URI can be made of, so each probe
  ;; takes its client and then throws in `URI/create`, which `version` answers nil to.
  (let [built (atom 0)]
    (with-redefs [ollama/probe-client (delay (swap! built inc) (#'ollama/new-client))]
      (is (nil? (ollama/version {:host "not a url"})))
      (is (nil? (ollama/version {:host "not a url"})))
      (is (= 1 @built) "two probes, one client")
      (testing "and the probes above available? are the same client again"
        (is (nil? (ollama/capabilities "nonesuch" {:host "not a url"})))
        (is (= 1 @built))))))

(deftest an-error-body-rides-bounded-in-the-message-and-whole-in-the-data
  (let [big (apply str (repeat 10000 \x))]
    (doseq [[label api-error prefix json-body]
            [["ollama"    #'ollama/api-error    "Ollama API"
              (str "{\"error\": \"" big "\"}")]
             ["anthropic" #'anthropic/api-error "Anthropic API"
              (str "{\"error\": {\"type\": \"overloaded_error\", \"message\": \"" big "\"}}")]]]
      (testing label
        (testing "a body that is not JSON at all — the proxy case"
          (let [e (api-error 502 big)]
            (is (= :llm-api-error (:type (ex-data e))))
            (is (= 502 (:status (ex-data e))))
            (is (str/starts-with? (ex-message e) (str prefix " 502: ")))
            (is (>= 250 (count (ex-message e)))
                "the message is the excerpt, not the body")
            (is (= big (:body (ex-data e)))
                "and the whole 10 KB is there for a caller that asks for it")))
        (testing "and a JSON body whose own error text is the megabyte"
          (let [e (api-error 529 json-body)]
            (is (>= 250 (count (ex-message e))))
            (is (= json-body (:body (ex-data e))))))))))

(deftest an-error-out-of-the-body-read-is-reported-like-any-other-failure
  (let [endpoint {:label "the test endpoint" :slug "error-arm"}
        under    (partial llm-http/under-read-deadline endpoint)
        alive?   (fn [] (some (fn [^Thread t]
                                (and (.isAlive t)
                                     (= "vaelii-error-arm-deadline" (.getName t))))
                              (keys (Thread/getAllStackTraces))))]
    (testing "an Error raised after the deadline fired takes the :llm-timeout rewrite"
      (let [closed (promise)
            e (try (under 20 #(deliver closed true)
                          (fn []
                            (deref closed 5000 :never)
                            (throw (StackOverflowError. "a body nested past the stack"))))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (realized? closed) "the watchdog fired")
        (is (some? e) "and the Error was rewritten")
        (is (= :llm-timeout (:type (ex-data e))))
        (is (instance? StackOverflowError (ex-cause e)) "the Error rides as the cause")))
    (testing "with the deadline unspent the Error is the caller's and propagates unchanged"
      (is (thrown? StackOverflowError
                   (under 60000 (fn [] nil)
                          (fn [] (throw (StackOverflowError. "a body nested past the stack")))))))
    (testing "and the watchdog is cancelled on the way out, whichever arm was taken"
      (loop [tries 50]                                   ; the interrupt lands promptly
        (when (and (alive?) (pos? tries))
          (Thread/sleep 10)
          (recur (dec tries))))
      (is (not (alive?))
          "no 60-second watchdog left running behind an Error that propagated"))))

(deftest a-host-that-goes-quiet-mid-body-releases-the-thread
  ;; Both stream paths call `llm-http/under-read-deadline`, so it is exercised once.
  (let [endpoint {:label "the test endpoint" :slug "test"}
        under    (partial llm-http/under-read-deadline endpoint)]
    (let [closed (promise)
          e (try (under 20 #(deliver closed true)
                        ;; stands in for a read blocked on a socket nobody is writing
                        ;; to: it ends when the watchdog closes the body under it
                        (fn []
                          (deref closed 5000 :never)
                          (throw (java.io.IOException. "stream closed"))))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (realized? closed) "the watchdog fired and closed the body")
      (is (= :llm-timeout (:type (ex-data e))))
      (is (= 20 (:timeout-ms (ex-data e))))
      (is (str/includes? (ex-message e) "the test endpoint")
          "and the message names the far end that went quiet"))
    (testing "a failure with the deadline unspent belongs to the caller and is rethrown"
      (is (thrown? java.io.IOException
                   (under 60000 (fn [] nil)
                          (fn [] (throw (java.io.IOException. "connection reset")))))))
    (testing "and a read that finishes hands back what it read"
      (is (= :read-it (under 60000 (fn [] nil) (fn [] :read-it)))))))

(defn- stalled-client
  "An `HttpClient` whose every response has its headers and a body that blocks until it
  is closed, then throws."
  []
  (proxy [HttpClient] []
    (send [_ _]
      (let [closed  (promise)
            blocked (fn [] (deref closed 5000 nil) (throw (java.io.IOException. "stream closed")))
            body    (proxy [java.io.InputStream] []
                      (read ([] (blocked)) ([_] (blocked)) ([_ _ _] (blocked)))
                      (close [] (deliver closed true)))]
        (reify HttpResponse
          (statusCode [_] 200)
          (body [_] body))))))

(deftest a-body-that-stops-after-its-headers-ends-at-the-turns-deadline
  ;; The request's own `.timeout` ends when the headers arrive, so only the read deadline
  ;; ends a turn whose body stops there.
  (let [request (.build (HttpRequest/newBuilder (URI/create "http://127.0.0.1:1/")))]
    (doseq [[label expected turn]
            [["the shared read" :llm-timeout
              #(llm-http/send-for-text {:label "the test endpoint" :slug "test"}
                                       (stalled-client) request 50)]
             ["an Ollama turn" :llm-timeout
              #(with-redefs [ollama/new-client stalled-client]
                 (proto/complete (ollama/provider {:host "h" :timeout-ms 50}) {:messages []}))]
             ["an Ollama probe, which answers nil for a host it cannot read" nil
              #(with-redefs [ollama/probe-client (delay (stalled-client))]
                 (ollama/version {:host "h" :timeout-ms 50}))]]]
      (let [started (System/currentTimeMillis)
            outcome (try (turn)
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))]
        (is (= expected outcome) label)
        (is (> 4000 (- (System/currentTimeMillis) started))
            (str label ": the read ended at the deadline, not at the stub's own 5 s"))))))

(deftest every-selection-probe-carries-only-the-host
  (let [probed (atom [])
        opts   {:host "h" :timeout-ms 60000}]
    (with-redefs [ollama/available? (fn [o] (swap! probed conj o) false)]
      (is (satisfies? proto/Provider (provider/generation-provider :ollama opts)))
      (is (satisfies? proto/Provider (provider/provider :ollama opts)))
      (with-redefs [provider/configured (constantly :ollama)]
        (is (= :stub (provider/active-kind opts))))
      (is (= (repeat 3 {:host "h"}) @probed)))))

(deftest a-bare-host-reaches-the-probes
  ;; The redefined client records each request's URI and answers it without a socket.
  (let [sent   (atom [])
        body   "{\"version\":\"0.test\",\"capabilities\":[\"tools\"]}"
        client (proxy [HttpClient] []
                 (send [^HttpRequest req _]
                   (swap! sent conj (str (.uri req)))
                   (reify HttpResponse
                     (statusCode [_] 200)
                     (body [_] (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))))))
        host   "127.0.0.1:1"]
    (with-redefs [ollama/probe-client (delay client)]
      (is (= "0.test" (ollama/version {:host host})))
      (is (ollama/supports-tools? "m" {:host host}))
      (is (:loaded? (ollama/warm "m" {:host host :prefill-tokens 12}))))
    (is (= ["http://127.0.0.1:1/api/version" "http://127.0.0.1:1/api/show"
            "http://127.0.0.1:1/api/chat"]
           @sent))
    (is (= "http://127.0.0.1:1" (:base-url (:conn (meta (ollama/provider {:host host}))))))))

(deftest unfence-strips-a-markdown-fence
  (are [text out] (= out (proto/unfence text))
    "```json\n{\"a\": 1}\n```" "{\"a\": 1}\n"
    "```\n(dog Muffet)```"       "(dog Muffet)"
    "no fence here"             "no fence here"
    nil                         ""))

(deftest the-protocol-reads-stop-reason-before-content
  (is (proto/refused? {:stop-reason "refusal" :content []}))
  (is (not (proto/refused? {:stop-reason "end_turn" :content []})))
  (is (= "" (proto/text {:stop-reason "refusal" :content []})))
  (is (= "ab" (proto/text {:content [{:type :text :text "a"}
                                     {:type :thinking :text "zzz"}
                                     {:type :text :text "b"}]}))))

;; ---- what an Ollama request carries, and what a turn hands back ---------

(deftest ollama-receives-the-tool-schemas-in-its-own-shape
  (let [schema (first (tools/schemas {:only #{:sentexes-matching}}))
        b      (ollama/body {:messages [] :tools [schema]} {:stream? false})]
    (is (= [{"type" "function"
             "function" {"name" "kb_sentexes_matching"
                         "description" (get schema "description")
                         "parameters" (get schema "input_schema")}}]
           (get b "tools")))))

(deftest streamed-tool-calls-get-distinct-ids
  (let [call (fn [n] {"message" {"role" "assistant"
                                 "tool_calls" [{"function" {"name" n "arguments" {}}}]}})
        r    (#'ollama/collect [(call "kb_a") (call "kb_b")
                                {"message" {"role" "assistant"
                                            "tool_calls" [{"id" "call_7"
                                                           "function" {"name" "kb_c"
                                                                       "arguments" {}}}]}}
                                {"done" true "done_reason" "stop"}]
                               (fn [_]))]
    (is (= ["ollama_tool_0" "ollama_tool_1" "call_7"] (mapv :id (proto/tool-uses r))))
    (is (= "tool_use" (:stop-reason r)))))

(deftest a-host-is-read-as-ollama-reads-one
  (are [host url] (= url (ollama/normalize-host host))
    "void.local"           "http://void.local:11434"
    "void.local:8080"      "http://void.local:8080"
    " 127.0.0.1:1/ "       "http://127.0.0.1:1"
    "[::1]"                "http://[::1]:11434"
    "proxy/ollama"         "http://proxy:11434/ollama"
    "http://void.local"    "http://void.local"
    "https://gpu.example/" "https://gpu.example"
    ":11434"               "http://127.0.0.1:11434"
    "::1"                  "http://[::1]:11434"
    "[::1]:8080"           "http://[::1]:8080"
    "HTTP://Void.local"    "http://Void.local"
    "Https://gpu.example"  "https://gpu.example"))

(deftest an-explicit-credential-makes-the-messages-api-available
  (let [opts {:credential {:kind :api-key :value "test-value"}}]
    (is (true? (provider/available? :anthropic opts)))
    (is (nil? (:log (meta (provider/provider :anthropic opts))))
        "the backend is built, not the stub, whatever the environment holds")))

(deftest a-send-that-fails-carries-a-type
  (let [request (.build (doto (HttpRequest/newBuilder (URI/create "http://127.0.0.1:1/"))
                          (.timeout (Duration/ofMillis 1234))))
        failing (fn [^Throwable t] (proxy [HttpClient] [] (send [_ _] (throw t))))
        outcome (fn [t]
                  (try (llm-http/send-request {:label "the test endpoint"} (failing t) request
                                              (HttpResponse$BodyHandlers/ofString))
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         [(:type (ex-data e)) (:timeout-ms (ex-data e)) (class (ex-cause e))])))]
    (is (= [:llm-timeout 1234 HttpTimeoutException]
           (outcome (HttpTimeoutException. "request timed out"))))
    (is (= [:llm-unreachable nil HttpConnectTimeoutException]
           (outcome (HttpConnectTimeoutException. "connect timed out"))))
    (is (= [:llm-unreachable nil ConnectException]
           (outcome (ConnectException.))))
    (testing "and a turn's send goes through it"
      (with-redefs [ollama/new-client (fn [] (failing (ConnectException.)))]
        (is (= :llm-unreachable
               (try (proto/complete (ollama/provider {:host "h"}) {:messages []}) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))
