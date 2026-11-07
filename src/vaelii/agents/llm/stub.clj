;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.stub
  "The default provider: deterministic, offline, no credential, and scripted.  See
  docs/llm.md § The stub is the default.

  A `:script` entry is a full response map, or one of these shorthands:

    \"some text\"                                a plain text answer
    {:tool \"kb_sentexes_matching\" :input {…}}  a tool-use turn (`:id`, `:prose` optional)

  A caller with answer shapes of its own passes `:expand`, which sees each map entry
  first."
  (:require [clojure.string :as str]
            [vaelii.agents.llm.protocol :as proto]))

(defn text-response
  "A plain-text, end-of-turn response holding `text`."
  [text]
  {:stop-reason "end_turn" :model "vaelii-stub"
   :content [{:type :text :text text}] :usage {}})

(def empty-response
  "The no-script answer: an end-of-turn response with no text."
  (assoc (text-response "") :usage {:input-tokens 0 :output-tokens 0}))

(defn- expand
  "Shorthand -> response map."
  [entry i]
  (cond
    (string? entry)
    (text-response entry)

    (contains? entry :tool)
    {:stop-reason "tool_use" :model "vaelii-stub"
     :content (cond-> []
                (:prose entry) (conj {:type :text :text (:prose entry)})
                true (conj {:type :tool-use
                            :id (or (:id entry) (str "stub_tool_" i))
                            :name (:tool entry)
                            :input (or (:input entry) {})}))
     :usage {}}

    :else entry))

(defn provider
  "A stub `Provider`.  `opts`:

    :script   a vector of turns (full response maps or the shorthands above)
    :default  the response for every turn past the script (default `empty-response`)
    :expand   `(fn [entry i])` for the caller's own shorthands, called on a map entry:
              a response map, or nil to leave the entry to the shorthands above
    :log      an atom the provider `conj`es each request onto, for assertions

  The provider's `meta` carries `:log` and `:cursor` (the number of calls so far)."
  ([] (provider {}))
  ([{:keys [script default log] own :expand}]
   (let [cursor (atom 0)
         log    (or log (atom []))
         default (or default empty-response)
         next-response
         (fn [request]
           (swap! log conj request)
           (let [i (dec (swap! cursor inc))]
             (if (< i (count script))
               (let [entry (nth script i)]
                 (or (when (and own (map? entry)) (own entry i)) (expand entry i)))
               default)))]
     (with-meta
       (reify proto/Provider
         (complete [_ request] (next-response request))
         (stream [_ request on-event]
           (let [response (next-response request)]
             ;; one `:text-delta` per whitespace-separated chunk of each text block
             (doseq [block (:content response)]
               (on-event {:type :block-start :block (dissoc block :text)})
               (when (= :text (:type block))
                 (doseq [chunk (re-seq #"\S+\s*" (or (:text block) ""))]
                   (on-event {:type :text-delta :text chunk})))
               (on-event {:type :block-stop}))
             (on-event {:type :done :response response})
             response)))
       {:log log :cursor cursor}))))

(defn requests
  "The requests a stub provider has been handed, oldest first."
  [p]
  @(:log (meta p)))

(defn last-user-text
  "The text of the newest user turn a stub provider saw."
  [p]
  (let [msgs (:messages (last (requests p)))
        content (:content (last (filter #(= "user" (:role %)) msgs)))]
    (if (string? content)
      content
      (str/join "\n" (keep :text content)))))
