;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.audit-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [vaelii.agents.mcp.audit :as audit]))

(def operation-id "123e4567-e89b-12d3-a456-426614174000")

(deftest language-neutral-golden-vectors-are-self-consistent
  (let [vectors (json/parse-string (slurp "test-resources/audit_golden_vectors.json"))]
    (is (= ["assert-input-v1" "authenticated-assert-attempt-v1"
            "matching-input-v1" "authenticated-matching-attempt-v1"
            "matching-completion-v1"]
           (mapv #(get % "name") vectors)))
    (doseq [vector vectors]
      (is (= (get vector "sha256")
             (audit/sha256 (json/parse-string (get vector "canonical"))))
          (get vector "name")))))

(deftest canonical-edn-is-stable-across-map-and-set-order
  (let [left  {:b #{'Beta 'Alpha} :a '(dog Muffet)}
        right (array-map :a '(dog Muffet) :b (sorted-set 'Alpha 'Beta))]
    (is (= (audit/normalize-edn left) (audit/normalize-edn right)))
    (is (= (audit/sha256 (audit/normalize-edn left))
           (audit/sha256 (audit/normalize-edn right))))))

(deftest authenticated-attempt-preserves-normalized-vaelii-content
  (let [input (audit/authenticated-input
               "assert" {:sentence '(dog Muffet) :context 'CxWell})
        record (audit/attempt
                {:operation-id operation-id
                 :verb "assert"
                 :auth-status "authenticated"
                 :principal "agent:quill"
                 :session-id "session-7"
                 :fork-id "fork-9"
                 :input input})]
    (is (= "attempt" (get record "record_type")))
    (is (= "list" (get-in record ["input" "sentence_edn" "$edn"])))
    (is (re-matches #"sha256:[0-9a-f]{64}" (get record "input_digest")))))

(deftest post-auth-attempt-refuses-a-different-input-verb
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"post-auth input tag must match"
       (audit/attempt
        {:operation-id operation-id
         :verb "assert"
         :auth-status "authenticated"
         :principal "agent:quill"
         :session-id "session-7"
         :fork-id "fork-9"
         :input (audit/authenticated-input
                 "query" {:goal '(dog ?x)
                          :context 'CxWell
                          :include-justification false})}))))

(deftest binding-failure-after-authentication-preserves-normalized-content
  (let [input (audit/authenticated-input
               "assert" {:sentence '(dog Muffet) :context 'CxWell})
        record (audit/attempt
                {:operation-id operation-id
                 :verb "assert"
                 :auth-status "binding_missing"
                 :principal "agent:quill"
                 :session-id "session-7"
                 :fork-id nil
                 :input input})]
    (is (= "binding_missing" (get record "auth_status")))
    (is (= "assert" (get-in record ["input" "tag"])))))

(deftest result-summaries-are-typed-and-minimized
  (let [query-summary (audit/result-summary
                       "query" {:answers [{:bindings {"?x" "Muffet"}
                                           :justification {:large "tree"}}]
                                :justifications-requested true})
        completion (audit/completion
                    {:operation-id operation-id
                     :outcome {"tag" "succeeded"
                               "effect" "none"
                               "result_summary" query-summary}})]
    (is (= {"tag" "query"
            "answer_count" 1
            "justifications_requested" true}
           query-summary))
    (is (= query-summary (get-in completion ["outcome" "result_summary"]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"handles/count disagree"
       (audit/completion
        {:operation-id operation-id
         :outcome {"tag" "succeeded"
                   "effect" "changed"
                   "result_summary" {"tag" "unassert"
                                     "excepting_handles" ["41"]
                                     "excepting_count" 2}}}))))

(deftest denial-requires-bounded-untrusted-input
  (let [untrusted (audit/untrusted-input
                   {:raw-sha256 (str "sha256:" (apply str (repeat 64 "a")))
                    :byte-length 8192
                    :content-type "application/json"})]
    (is (= "untrusted" (get untrusted "tag")))
    (is (= "denied"
           (get-in (audit/completion
                    {:operation-id operation-id
                     :outcome {"tag" "denied"
                               "code" "auth_missing"
                               "message" "authentication required"}})
                   ["outcome" "tag"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"bounded untrusted input"
         (audit/attempt
          {:operation-id operation-id
           :verb "query"
           :auth-status "missing"
           :input (audit/authenticated-input
                   "query" {:goal '(dog ?x)
                            :context 'CxWell
                            :include-justification false})})))))

(deftest pre-auth-attempts-cannot-claim-resolved-identity
  (let [input (audit/untrusted-input
               {:raw-sha256 (str "sha256:" (apply str (repeat 64 "b")))
                :byte-length 24})]
    (doseq [identity [{:principal "agent:quill"}
                      {:session-id "session-7"}
                      {:fork-id "fork-9"}]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"cannot claim resolved identity"
           (audit/attempt
            (merge {:operation-id operation-id
                    :verb "query"
                    :auth-status "invalid"
                    :input input}
                   identity)))))))

(deftest direct-untrusted-input-maps-still-enforce-content-type-bounds
  (let [base {"tag" "untrusted"
              "raw_sha256" (str "sha256:" (apply str (repeat 64 "c")))
              "byte_length" 24}
        attempt #(audit/attempt
                  {:operation-id operation-id
                   :verb "query"
                   :auth-status "missing"
                   :input %})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"content_type must be a string"
         (attempt (assoc base "content_type" 42))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"content_type exceeds 256 characters"
         (attempt (assoc base "content_type" (apply str (repeat 257 "a"))))))))

(deftest closed-variants-reject-extra-fields
  (try
    (audit/completion
     {:operation-id operation-id
      :outcome {"tag" "denied"
                "code" "auth_missing"
                "message" "authentication required"
                "raw_token" "must never fit"}})
    (is false "closed variant should reject an extra field")
    (catch clojure.lang.ExceptionInfo e
      (is (= :shape (:type (ex-data e)))
          "audit validation reuses the frozen shape refusal")))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"wrong fields"
       (audit/completion
        {:operation-id operation-id
         :outcome {"tag" "denied"
                   "code" "auth_missing"
                   "message" "authentication required"
                   "raw_token" "must never fit"}}))))
(is (thrown-with-msg?
     clojure.lang.ExceptionInfo
     #"wrong fields"
     (audit/attempt
      {:operation-id operation-id
       :verb "assert"
       :auth-status "authenticated"
       :principal "agent:quill"
       :session-id "session-7"
       :fork-id "fork-9"
       :input {"tag" "assert"
               "sentence_edn" {"$edn" "list" "items" []}
               "context_edn" {"$edn" "symbol" "value" "CxWell"}
               "credential" "must never fit"}})))

(deftest canonical-json-rejects-floating-point-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"outside the canonical JSON subset"
                        (audit/canonical-json {"value" 1.5}))))

(deftest resolution-needs-causal-evidence
  (testing "current-state observations cannot construct a confirmed resolution"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot confirm"
         (audit/resolution
          {:operation-id operation-id
           :resolution {"tag" "effect_confirmed"
                        "evidence_ref" {"type" "matching_postcondition"}}}))))
  (testing "operation-recorded handles can"
    (is (= "effect_confirmed"
           (get-in
            (audit/resolution
             {:operation-id operation-id
              :resolution {"tag" "effect_confirmed"
                           "evidence_ref"
                           {"type" "operation_recorded_handles"
                            "handles" ["41" "42"]}}})
            ["resolution" "tag"]))))
  (testing "operation-recorded evidence requires a nonempty unique handle set"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unique handles"
         (audit/resolution
          {:operation-id operation-id
           :resolution {"tag" "effect_confirmed"
                        "evidence_ref"
                        {"type" "operation_recorded_handles"
                         "handles" ["41" "41"]}}})))))

(deftest confirmed-resolution-evidence-is-outcome-specific
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cannot confirm this resolution outcome"
       (audit/resolution
        {:operation-id operation-id
         :resolution {"tag" "effect_confirmed"
                      "evidence_ref"
                      {"type" "pre_execution_denial"
                       "completion_record_id" operation-id}}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"cannot confirm this resolution outcome"
       (audit/resolution
        {:operation-id operation-id
         :resolution {"tag" "no_effect_confirmed"
                      "evidence_ref"
                      {"type" "operation_recorded_handles"
                       "handles" ["41"]}}}))))

(deftest completion-message-cap-is-measured-in-utf8-bytes
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"4096 UTF-8 bytes"
       (audit/completion
        {:operation-id operation-id
         :outcome {"tag" "failed"
                   "code" "server_error"
                   "message" (apply str (repeat 2049 "é"))}}))))

(deftest indeterminate-observations-are-bounded-canonical-strings
  (testing "bounded observations work for completion and resolution"
    (is (= ["dispatch_started" "handle:41"]
           (get-in
            (audit/completion
             {:operation-id operation-id
              :outcome {"tag" "outcome_indeterminate"
                        "stage" "dispatch"
                        "observations" ["dispatch_started" "handle:41"]}})
            ["outcome" "observations"])))
    (is (= ["current_state_matches"]
           (get-in
            (audit/resolution
             {:operation-id operation-id
              :resolution {"tag" "indeterminate"
                           "observations" ["current_state_matches"]}})
            ["resolution" "observations"]))))
  (testing "non-string and oversized observations are rejected"
    (doseq [observations [["ok" 42]
                          (vec (repeat 33 "bounded"))
                          [(apply str (repeat 513 "é"))]]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"observations"
           (audit/completion
            {:operation-id operation-id
             :outcome {"tag" "outcome_indeterminate"
                       "stage" "dispatch"
                       "observations" observations}})))))
  (testing "aggregate observation bytes are bounded"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"aggregate UTF-8 bytes"
         (audit/resolution
          {:operation-id operation-id
           :resolution {"tag" "indeterminate"
                        "observations" (vec (repeat 9 (apply str (repeat 1024 "a"))))}})))))

(deftest matching-is-an-audited-read
  (testing "matching attempts carry the normalized pattern, context, and options"
    (let [input  (audit/authenticated-input
                  "matching" {:pattern '(likes ?x Bacon) :context 'CxWell
                              :asserted-only true :limit 50})
          record (audit/attempt
                  {:operation-id operation-id
                   :verb "matching"
                   :auth-status "authenticated"
                   :principal "agent:whiskerton"
                   :session-id "session-7"
                   :fork-id "fork-9"
                   :input input})]
      (is (= "matching" (get record "verb")))
      (is (= "list" (get-in record ["input" "pattern_edn" "$edn"])))
      (is (true? (get-in record ["input" "asserted_only"])))
      (is (= 50 (get-in record ["input" "limit"])))))
  (testing "a non-integer limit is recorded as null, not as caller text"
    (is (nil? (get (audit/authenticated-input
                    "matching" {:pattern '(likes ?x Bacon) :context 'CxWell
                                :asserted-only false :limit nil})
                   "limit"))))
  (testing "matching completions summarize the row count and truncation only"
    (let [summary    (audit/result-summary
                      "matching" {:rows [{:sentence "(likes Muffet Bacon)"}]
                                  :truncated true})
          completion (audit/completion
                      {:operation-id operation-id
                       :outcome {"tag" "succeeded"
                                 "effect" "none"
                                 "result_summary" summary}})]
      (is (= {"tag" "matching" "row_count" 1 "truncated" true} summary))
      (is (= summary (get-in completion ["outcome" "result_summary"]))))))

(deftest matching-golden-vectors-are-what-the-constructors-produce
  ;; Self-consistency proves only that each digest matches its own bytes; this
  ;; fixes the constructors to the language-neutral bytes for the sixth verb.
  (let [by-name (into {} (map (juxt #(get % "name") identity))
                      (json/parse-string (slurp "test-resources/audit_golden_vectors.json")))
        input   (audit/authenticated-input
                 "matching" {:pattern '(dog ?x) :context 'CxWell
                             :asserted-only false :limit 100})
        attempt (audit/attempt
                 {:operation-id operation-id
                  :verb "matching"
                  :auth-status "authenticated"
                  :principal "agent:quill"
                  :session-id "session-7"
                  :fork-id "fork-9"
                  :input input})
        completion (audit/completion
                    {:operation-id operation-id
                     :outcome {"tag" "succeeded"
                               "effect" "none"
                               "result_summary" (audit/result-summary
                                                 "matching" {:rows [{}] :truncated false})}})]
    (doseq [[vname record] [["matching-input-v1" input]
                            ["authenticated-matching-attempt-v1" attempt]
                            ["matching-completion-v1" completion]]]
      (is (= (get-in by-name [vname "canonical"]) (audit/canonical-json record))
          (str vname " canonical bytes"))
      (is (= (get-in by-name [vname "sha256"]) (audit/sha256 record))
          (str vname " digest")))))
