;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns ^{:clojure.tools.namespace.repl/load false :clojure.tools.namespace.repl/unload false}
 vaelii.agents.llm.protocol
  "The `Provider` extension point: two methods over a provider-neutral request and
  response map, neither naming HTTP, JSON or a vendor field.  See docs/llm.md § Providers.

  Request:

      {:model      \"claude-opus-5-5\"   ; provider-resolved when absent
       :system     [{:text \"…\" :cache? true} …]   ; blocks, in order
       :messages   [{:role \"user\"|\"assistant\" :content <string | [block …]}]
       :tools      [<tool schema> …]      ; vaelii.agents.tools/schemas
       :format     {…}                    ; a JSON schema decoding is constrained to
       :max-tokens 8192
       :effort     \"low\"|\"medium\"|\"high\"|\"xhigh\"|\"max\"
       :thinking-display \"summarized\"     ; Anthropic only
       :num-ctx    8192}                  ; context length, tokens; local providers only

  `:cache?` on a system block asks for a cache breakpoint after it.  This list is the
  whole of what a provider reads, so a key any path sends belongs here.  A provider
  ignores a key it cannot express: `:cache?` on Ollama, `:num-ctx` on Anthropic.

  Response:

      {:stop-reason  \"end_turn\"|\"tool_use\"|\"refusal\"|\"max_tokens\"|…
       :stop-details {…}                ; populated only on a refusal
       :content      [{:type :text     :text \"…\"}
                      {:type :tool-use :id \"…\" :name \"…\" :input {…}}
                      {:type :thinking :text \"…\"}]
       :model        \"…\"
       :usage        {…}}

  A refusal is a successful answer with empty or partial `:content`: a caller reads
  `:stop-reason` first, and a provider never fabricates a text block.  Assistant content
  is echoed back verbatim in the next request's `:messages`, so a provider may carry its
  own vendor blocks through `:content`.")

(defprotocol Provider
  (complete [provider request]
    "Run one turn and return the whole response map.  Blocks until the turn ends.")
  (stream [provider request on-event]
    "Run one turn incrementally: call `(on-event ev)` for each event as it arrives,
    then return the same response map `complete` would have.  Events are
    `{:type :text-delta :text \"…\"}`, `{:type :block-start :block {…}}`,
    `{:type :block-stop}`, `{:type :done :response {…}}`.  The contract is the return
    value, not the delta granularity."))

(defn text
  "The concatenated text of a response's text blocks, tool-use and thinking blocks
  dropped.  Empty on a refusal."
  [response]
  (->> (:content response)
       (filter #(= :text (:type %)))
       (map :text)
       (apply str)))

(defn tool-uses
  "The tool-use blocks of a response, in order."
  [response]
  (filterv #(= :tool-use (:type %)) (:content response)))

(defn refused?
  "True when `:stop-reason` is `\"refusal\"`.  A caller checks this before reading
  `:content`."
  [response]
  (= "refusal" (:stop-reason response)))

(def ^:private fenced-block
  #"(?s)```(?:json|edn|clojure|clj|text)?\s*\n?(.*?)```")

(defn unfence
  "The contents of the first markdown fence in `text`, else `text` itself.  Models fence
  an answer unprompted, one of them while decoding under a JSON schema."
  [text]
  (or (second (re-find fenced-block (str text))) (str text)))
