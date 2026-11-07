;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.surface-test
  "What the model stack promises outside itself: the `Provider` protocol an out-of-tree
  backend implements, the defaults its environment variables read when unset, and the
  relationship between the model's tool set and the daemon's op table it is generated
  from."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.engine :as engine]
            [vaelii.agents.llm.ollama :as ollama]
            [vaelii.agents.llm.protocol :as proto]
            [vaelii.agents.llm.provider :as provider]
            [vaelii.agents.test-util :as tu]
            [vaelii.agents.tools :as tools]
            [vaelii.core :as v]))

(use-fixtures :each (tu/neutral-fresh tu/fresh))

;; ---- the Provider protocol ---------------------------------------------------

(def ^:private frozen-provider
  "The method set and arglists of `Provider`, frozen. docs/llm.md names `Provider` as the
  extension point a new backend implements, so its shape is a promise to every
  implementer."
  '{complete "[[provider request]]"
    stream   "[[provider request on-event]]"})

(deftest the-provider-protocol-keeps-its-shape
  ;; A removed or changed method breaks every implementer at the call site, not at compile
  ;; time. An ADDED method breaks them too: an existing implementer does not have it, so it
  ;; satisfies the protocol only in part. Prefer a new protocol beside this one.
  (let [current (into {} (map (fn [[_ {:keys [name arglists]}]] [name (pr-str (vec arglists))]))
                      (:sigs proto/Provider))]
    (is (= frozen-provider current)
        "Provider's method set or arglists moved — every out-of-tree backend has the old shape")))

;; ---- the environment defaults ------------------------------------------------

(def ^:private env-defaults
  "The environment variables the model stack reads, each with its reader and the value
  docs/llm.md documents for the unset case.  A row naming several variables is checked
  only when all of them are unset."
  [["VAELII_LLM_PROVIDER"            #(provider/configured)                nil]
   [["VAELII_OLLAMA_HOST" "OLLAMA_HOST"] #(ollama/base-url)              "http://localhost:11434"]
   ["VAELII_OLLAMA_MODEL"            #(ollama/configured-model)            "phi4:14b"]
   ["VAELII_OLLAMA_GENERATION_MODEL" #(ollama/configured-generation-model) "qwen3-coder:30b"]
   ["VAELII_OLLAMA_NUM_CTX"          #(ollama/configured-num-ctx)          8192]
   ["VAELII_OLLAMA_KEEP_ALIVE"       #(ollama/configured-keep-alive)       "30m"]])

(deftest nothing-set-is-the-documented-default
  ;; A JVM cannot clear its own environment, so each variable is read as this machine
  ;; leaves it, and one this machine sets is skipped rather than failed. The
  ;; `vaelii.llm.provider` property outranks `VAELII_LLM_PROVIDER`, so it is cleared for
  ;; the length of the test.
  (let [prop "vaelii.llm.provider"
        was  (System/getProperty prop)]
    (System/clearProperty prop)
    (try
      (let [names (fn [nm] (if (string? nm) [nm] nm))
            unset (remove (fn [[nm _ _]] (some #(System/getenv %) (names nm))) env-defaults)]
        (is (seq unset) "every variable is set here, so this test checked none")
        (doseq [[nm reader expected] unset]
          (is (= expected (reader)) (str nm " unset must read its documented default"))))
      (finally (when was (System/setProperty prop was))))))

;; ---- the tool set and the daemon's op table ----------------------------------

(deftest every-tool-is-a-read-the-daemon-serves
  (let [reads (tools/read-ops)]
    (is (seq reads))
    (is (every? engine/ops reads) "a tool the daemon does not serve has nothing to dispatch to")
    (is (not-any? tools/write-ops reads))
    (is (not-any? #(str/ends-with? (name %) "!") reads))))

(deftest the-feed-ops-are-not-model-tools
  (testing "the change-feed ops are not in the daemon's op table, which is what keeps
            them out of the model's tool set"
    (is (seq engine/feed-ops))
    (is (empty? (filter engine/ops (keys engine/feed-ops)))))
  (testing "and the generated tool set holds none of them: a subscription is daemon heap,
            and heap is not a thing a model allocates"
    (is (empty? (filter (set (keys engine/feed-ops)) (tools/read-ops))))))

(tu/deftest-kb the-models-tool-surface-is-held-to-the-daemons-ceiling
  ;; The tool schemas are generated from the daemon's op table and dispatch back through
  ;; it, so the query ceilings the daemon applies apply to the model too. A ceiling held
  ;; only at the HTTP route would be a ceiling the model does not have.
  (tu/with-terms [dog Muffet]
    (v/assert kb (list dog Muffet) 'CxUniverse)
    (testing "a depth inside the ceiling answers"
      (is (true? (:ok (tools/call kb "kb_query" {"goal" (str (list dog '?x))
                                                 "context" "CxUniverse"
                                                 "opts" "{:max-depth 3}"})))))
    (testing "a depth past it comes back as the refusal a tool result carries"
      (let [r (tools/call kb "kb_query" {"goal" (str (list dog '?x))
                                         "context" "CxUniverse"
                                         "opts" (str {:max-depth Integer/MAX_VALUE})})]
        (is (false? (:ok r)))
        (is (re-find #"over-ceiling" (str (:error r))) "the :type is named for the model too")))
    (testing "and the same at kb_ask, whose ceiling is the clock"
      (let [r (tools/call kb "kb_ask" {"goal" (str (list dog '?x))
                                       "context" "CxUniverse"
                                       "opts" (str {:max-ms Integer/MAX_VALUE})})]
        (is (false? (:ok r)))
        (is (re-find #"over-ceiling" (str (:error r))))))))
