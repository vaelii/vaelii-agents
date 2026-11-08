;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.audit
  "Pure v1 audit-record construction, validation, normalization, and hashing.

  This namespace performs no I/O and does not invent journal metadata. The
  durable writer (`vaelii.agents.mcp.dispatcher`'s `JournalAuditSink`) adds
  record identity, sequence, and server time at the commit point."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util UUID]))

(def schema-version 1)

(def ^:private verbs
  #{"assert" "unassert" "unassert_handle" "query" "argue" "matching"})

(def ^:private auth-statuses
  #{"authenticated" "missing" "invalid" "binding_missing" "fork_unavailable"})

(def ^:private pre-auth-statuses #{"missing" "invalid"})

(def ^:private effects #{"changed" "unchanged" "none"})

(def ^:private max-observations 32)
(def ^:private max-observation-bytes 1024)
(def ^:private max-observations-bytes 8192)

(def ^:private argue-verdicts
  #{"true" "false" "unknown" "contradiction"})

(def ^:private resolution-evidence-types
  {"effect_confirmed" #{"operation_recorded_handles"}
   "no_effect_confirmed" #{"pre_execution_denial"
                           "exclusive_no_write_receipt"}})

(defn- schema-error [message data]
  ;; Audit validation is one more shape boundary, not a new caller-visible
  ;; exception vocabulary. Keep the refusal vocabulary closed.
  ;; The ex-data is written as a map literal (the engine's own type-contract
  ;; scan refuses an assembled payload); the caller's heterogeneous detail
  ;; rides under :schema. Only :type and the message are read downstream.
  (throw (ex-info message {:type :shape :schema data})))

(defn- require-string [value field]
  (when-not (and (string? value) (not (str/blank? value)))
    (schema-error (str field " must be a nonblank string")
                  {:field field :value value})))

(defn- require-uuid [value field]
  (require-string value field)
  (try
    (UUID/fromString value)
    (catch IllegalArgumentException _
      (schema-error (str field " must be a UUID string")
                    {:field field :value value}))))

(defn- require-boolean [value field]
  (when-not (instance? Boolean value)
    (schema-error (str field " must be boolean")
                  {:field field :value value})))

(defn- utf8-length [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- validate-observations [observations field]
  (when-not (vector? observations)
    (schema-error (str field " must be a vector") {:field field}))
  (when (> (count observations) max-observations)
    (schema-error (str field " exceeds " max-observations " entries")
                  {:field field :count (count observations)}))
  (doseq [observation observations]
    (require-string observation field)
    (when (> (utf8-length observation) max-observation-bytes)
      (schema-error (str field " entry exceeds " max-observation-bytes
                         " UTF-8 bytes")
                    {:field field})))
  (when (> (reduce + 0 (map utf8-length observations))
           max-observations-bytes)
    (schema-error (str field " exceeds " max-observations-bytes
                       " aggregate UTF-8 bytes")
                  {:field field})))

(defn- handle-string [value field nullable?]
  (cond
    (and nullable? (nil? value)) nil
    (and (integer? value) (pos? value)) (str value)
    :else (schema-error (str field " must be a positive integer"
                             (when nullable? " or nil"))
                        {:field field :value value})))

(defn- require-exact-keys [value expected label]
  (when-not (map? value)
    (schema-error (str label " must be an object") {:value value}))
  (let [actual (set (keys value))]
    (when-not (= expected actual)
      (schema-error (str label " has the wrong fields")
                    {:expected expected :actual actual}))))

(declare canonical-json)

(defn normalize-edn
  "Convert an EDN value to a deterministic JSON-shaped tagged value.

  Lists, vectors, sets, maps, symbols, and keywords retain their EDN identity.
  Map entries and set values are sorted by their own canonical JSON bytes."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value) (integer? value)) value
    (symbol? value) {"$edn" "symbol" "value" (str value)}
    (keyword? value) {"$edn" "keyword" "value" (str value)}
    (char? value) {"$edn" "character" "value" (str value)}
    (list? value) {"$edn" "list" "items" (mapv normalize-edn value)}
    (vector? value) {"$edn" "vector" "items" (mapv normalize-edn value)}
    (set? value)
    (let [items (mapv normalize-edn value)]
      {"$edn" "set" "items" (vec (sort-by canonical-json items))})
    (map? value)
    (let [entries (mapv (fn [[k v]] [(normalize-edn k) (normalize-edn v)]) value)]
      {"$edn" "map"
       "entries" (vec (sort-by (comp canonical-json first) entries))})
    :else
    (schema-error "EDN value has no canonical audit representation"
                  {:value value :class (str (class value))})))

(defn- json-value
  "Recursively sort JSON object keys and reject values outside canonical v1."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value) (integer? value)) value
    (vector? value) (mapv json-value value)
    (sequential? value) (mapv json-value value)
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 (when-not (string? k)
                   (schema-error "canonical JSON object keys must be strings"
                                 {:key k}))
                 [k (json-value v)]))
          value)
    :else
    (schema-error "value is outside the canonical JSON subset"
                  {:value value :class (str (class value))})))

(defn canonical-json
  "Return the canonical compact UTF-8 JSON text for a JSON-shaped value."
  [value]
  (json/generate-string (json-value value) {:escape-non-ascii true}))

(defn canonical-json-bytes
  "Return canonical JSON as a byte array."
  [value]
  (.getBytes ^String (canonical-json value) StandardCharsets/UTF_8))

(defn sha256
  "Return a lowercase `sha256:` digest of a canonical JSON-shaped value."
  [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (canonical-json-bytes value))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest)))))

(defn authenticated-input
  "Construct a typed authenticated input from already-parsed verb values."
  [verb input]
  (when-not (contains? verbs verb)
    (schema-error "unknown audit verb" {:verb verb}))
  (let [normalized
        (case verb
          "assert"
          {"tag" verb
           "sentence_edn" (normalize-edn (:sentence input))
           "context_edn" (normalize-edn (:context input))}

          "unassert"
          {"tag" verb
           "sentence_edn" (normalize-edn (:sentence input))
           "context_edn" (normalize-edn (:context input))}

          "unassert_handle"
          (do
            (when-not (and (integer? (:sentex-handle input))
                           (pos? (:sentex-handle input)))
              (schema-error "sentex handle must be a positive integer"
                            {:value (:sentex-handle input)}))
            {"tag" verb
             "sentex_handle" (str (:sentex-handle input))})

          "query"
          (do
            (require-boolean (:include-justification input)
                             "include_justification")
            {"tag" verb
             "goal_edn" (normalize-edn (:goal input))
             "context_edn" (normalize-edn (:context input))
             "include_justification" (:include-justification input)})

          "argue"
          {"tag" verb
           "closed_goal_edn" (normalize-edn (:closed-goal input))
           "context_edn" (normalize-edn (:context input))}

          "matching"
          (let [limit (:limit input)]
            (require-boolean (:asserted-only input) "asserted_only")
            (when-not (or (nil? limit) (and (integer? limit) (pos? limit)))
              (schema-error "matching limit must be a positive integer or nil"
                            {:value limit}))
            {"tag" verb
             "pattern_edn" (normalize-edn (:pattern input))
             "context_edn" (normalize-edn (:context input))
             "asserted_only" (:asserted-only input)
             "limit" limit}))]
    normalized))

(defn untrusted-input
  "Construct the bounded input variant used before authentication succeeds."
  [{:keys [raw-sha256 byte-length content-type]}]
  (when-not (and (string? raw-sha256)
                 (re-matches #"sha256:[0-9a-f]{64}" raw-sha256))
    (schema-error "raw-sha256 must be a lowercase sha256 digest"
                  {:value raw-sha256}))
  (when-not (and (integer? byte-length) (not (neg? byte-length)))
    (schema-error "byte-length must be a nonnegative integer"
                  {:value byte-length}))
  (when (and content-type (not (string? content-type)))
    (schema-error "content-type must be a string when present"
                  {:value content-type}))
  (when (and content-type (> (count content-type) 256))
    (schema-error "content-type exceeds 256 characters" {}))
  (cond-> {"tag" "untrusted"
           "raw_sha256" raw-sha256
           "byte_length" byte-length}
    content-type (assoc "content_type" content-type)))

(defn- validate-input [input]
  (let [tag (get input "tag")]
    (case tag
      ("assert" "unassert")
      (require-exact-keys input #{"tag" "sentence_edn" "context_edn"}
                          (str tag " input"))

      "unassert_handle"
      (do
        (require-exact-keys input #{"tag" "sentex_handle"}
                            "unassert_handle input")
        (when-not (and (string? (get input "sentex_handle"))
                       (re-matches #"[1-9][0-9]*"
                                   (get input "sentex_handle")))
          (schema-error "sentex_handle must be a positive decimal string"
                        {:input input})))

      "query"
      (do
        (require-exact-keys input
                            #{"tag" "goal_edn" "context_edn"
                              "include_justification"}
                            "query input")
        (require-boolean (get input "include_justification")
                         "include_justification"))

      "argue"
      (require-exact-keys input #{"tag" "closed_goal_edn" "context_edn"}
                          "argue input")

      "matching"
      (let [limit (get input "limit")]
        (require-exact-keys input
                            #{"tag" "pattern_edn" "context_edn"
                              "asserted_only" "limit"}
                            "matching input")
        (require-boolean (get input "asserted_only") "asserted_only")
        (when-not (or (nil? limit) (and (integer? limit) (pos? limit)))
          (schema-error "matching limit must be a positive integer or null"
                        {:input input})))

      "untrusted"
      (do
        (when-not (contains? #{#{"tag" "raw_sha256" "byte_length"}
                               #{"tag" "raw_sha256" "byte_length"
                                 "content_type"}}
                             (set (keys input)))
          (schema-error "untrusted input has the wrong fields" {:input input}))
        (when-not (and (string? (get input "raw_sha256"))
                       (re-matches #"sha256:[0-9a-f]{64}"
                                   (get input "raw_sha256")))
          (schema-error "untrusted input digest is invalid" {:input input}))
        (when-not (and (integer? (get input "byte_length"))
                       (not (neg? (get input "byte_length"))))
          (schema-error "untrusted input byte length is invalid" {:input input}))
        (when (contains? input "content_type")
          (let [content-type (get input "content_type")]
            (when-not (string? content-type)
              (schema-error "untrusted content_type must be a string"
                            {:input input}))
            (when (> (count content-type) 256)
              (schema-error "untrusted content_type exceeds 256 characters"
                            {})))))

      (schema-error "unknown audit input tag" {:input input}))
    (json-value input)
    input))

(defn attempt
  "Construct an unstored Attempt. Identity, sequence, and time remain the
  durable writer's job."
  [{:keys [operation-id verb auth-status principal session-id fork-id
           correlation-id input]}]
  (require-uuid operation-id "operation_id")
  (when-not (contains? verbs verb)
    (schema-error "unknown audit verb" {:verb verb}))
  (when-not (contains? auth-statuses auth-status)
    (schema-error "unknown auth status" {:auth-status auth-status}))
  (validate-input input)
  (when (and (= "authenticated" auth-status)
             (some nil? [principal session-id fork-id]))
    (schema-error "authenticated attempts require principal, session, and fork"
                  {}))
  (when (and (contains? #{"binding_missing" "fork_unavailable"} auth-status)
             (some nil? [principal session-id]))
    (schema-error "post-auth admission failures require principal and session"
                  {:auth-status auth-status}))
  (if (contains? pre-auth-statuses auth-status)
    (do
      (when-not (= "untrusted" (get input "tag"))
        (schema-error "pre-auth attempts require bounded untrusted input"
                      {:auth-status auth-status}))
      (when (some some? [principal session-id fork-id])
        (schema-error "pre-auth attempts cannot claim resolved identity"
                      {:auth-status auth-status})))
    (when-not (= verb (get input "tag"))
      (schema-error "post-auth input tag must match the verb"
                    {:verb verb :input-tag (get input "tag")})))
  (doseq [[field value] [["principal" principal]
                         ["session_id" session-id]
                         ["fork_id" fork-id]
                         ["correlation_id" correlation-id]]]
    (when (some? value) (require-string value field)))
  {"schema_version" schema-version
   "record_type" "attempt"
   "operation_id" operation-id
   "verb" verb
   "auth_status" auth-status
   "principal" principal
   "session_id" session-id
   "fork_id" fork-id
   "correlation_id" correlation-id
   "input" input
   "input_digest" (sha256 input)})

(defn result-summary
  "Construct the minimized successful result variant for one verb."
  [verb result]
  (when-not (contains? verbs verb)
    (schema-error "unknown audit verb" {:verb verb}))
  (case verb
    "assert"
    (do
      (require-boolean (:already-there result) "already_there")
      {"tag" verb
       "handle" (handle-string (:handle result) "handle" true)
       "already_there" (:already-there result)})

    "unassert"
    (let [handles (:excepting-handles result)]
      (when-not (vector? handles)
        (schema-error "excepting handles must be a vector" {:value handles}))
      (let [wire-handles (mapv #(handle-string % "excepting_handle" false)
                               handles)]
        {"tag" verb
         "excepting_handles" wire-handles
         "excepting_count" (count wire-handles)}))

    "unassert_handle"
    {"tag" verb
     "excepting_handle" (handle-string (:excepting-handle result)
                                       "excepting_handle" true)}

    "query"
    (do
      (when-not (sequential? (:answers result))
        (schema-error "query answers must be sequential"
                      {:value (:answers result)}))
      (require-boolean (:justifications-requested result)
                       "justifications_requested")
      {"tag" verb
       "answer_count" (count (:answers result))
       "justifications_requested" (:justifications-requested result)})

    "argue"
    (let [verdict (:verdict result)]
      (when-not (contains? argue-verdicts verdict)
        (schema-error "unknown argue verdict" {:verdict verdict}))
      {"tag" verb
       "verdict" verdict
       "for_proof_present" (some? (:for-proof result))
       "against_proof_present" (some? (:against-proof result))})

    "matching"
    (do
      (when-not (sequential? (:rows result))
        (schema-error "matching rows must be sequential"
                      {:value (:rows result)}))
      (require-boolean (:truncated result) "truncated")
      {"tag" verb
       "row_count" (count (:rows result))
       "truncated" (:truncated result)})))

(defn- validate-result-summary [summary]
  (let [tag (get summary "tag")]
    (when-not (contains? verbs tag)
      (schema-error "unknown result summary tag" {:summary summary}))
    (case tag
      "assert"
      (do
        (require-exact-keys summary #{"tag" "handle" "already_there"}
                            "assert result summary")
        (require-boolean (get summary "already_there") "already_there")
        (when-let [handle (get summary "handle")]
          (when-not (re-matches #"[1-9][0-9]*" handle)
            (schema-error "assert handle must be a positive decimal string"
                          {:value handle}))))

      "unassert"
      (let [handles (get summary "excepting_handles")]
        (require-exact-keys summary
                            #{"tag" "excepting_handles" "excepting_count"}
                            "unassert result summary")
        (when-not (and (vector? handles)
                       (every? #(and (string? %)
                                     (re-matches #"[1-9][0-9]*" %))
                               handles)
                       (= (count handles) (get summary "excepting_count")))
          (schema-error "unassert summary handles/count disagree"
                        {:summary summary})))

      "unassert_handle"
      (do
        (require-exact-keys summary #{"tag" "excepting_handle"}
                            "unassert_handle result summary")
        (when-let [handle (get summary "excepting_handle")]
          (when-not (re-matches #"[1-9][0-9]*" handle)
            (schema-error "excepting handle must be a positive decimal string"
                          {:value handle}))))

      "query"
      (do
        (require-exact-keys summary
                            #{"tag" "answer_count" "justifications_requested"}
                            "query result summary")
        (when-not (and (integer? (get summary "answer_count"))
                       (not (neg? (get summary "answer_count"))))
          (schema-error "answer count must be a nonnegative integer"
                        {:summary summary}))
        (require-boolean (get summary "justifications_requested")
                         "justifications_requested"))

      "argue"
      (do
        (require-exact-keys summary
                            #{"tag" "verdict" "for_proof_present"
                              "against_proof_present"}
                            "argue result summary")
        (when-not (contains? argue-verdicts (get summary "verdict"))
          (schema-error "unknown argue verdict" {:summary summary}))
        (require-boolean (get summary "for_proof_present")
                         "for_proof_present")
        (require-boolean (get summary "against_proof_present")
                         "against_proof_present"))

      "matching"
      (do
        (require-exact-keys summary #{"tag" "row_count" "truncated"}
                            "matching result summary")
        (when-not (and (integer? (get summary "row_count"))
                       (not (neg? (get summary "row_count"))))
          (schema-error "row count must be a nonnegative integer"
                        {:summary summary}))
        (require-boolean (get summary "truncated") "truncated")))))

(defn completion
  "Construct an unstored Completion with one closed outcome variant."
  [{:keys [operation-id outcome]}]
  (require-uuid operation-id "operation_id")
  (let [tag (get outcome "tag")]
    (case tag
      "succeeded"
      (do
        (require-exact-keys outcome #{"tag" "effect" "result_summary"}
                            "succeeded outcome")
        (when-not (contains? effects (get outcome "effect"))
          (schema-error "unknown succeeded effect" {:outcome outcome}))
        (validate-result-summary (get outcome "result_summary")))

      ("rejected" "denied" "failed")
      (do
        (require-exact-keys outcome #{"tag" "code" "message"}
                            (str tag " outcome"))
        (require-string (get outcome "code") "code")
        (require-string (get outcome "message") "message")
        (when (> (alength (.getBytes ^String (get outcome "message")
                                     StandardCharsets/UTF_8))
                 4096)
          (schema-error "completion message exceeds 4096 UTF-8 bytes" {})))

      "outcome_indeterminate"
      (do
        (require-exact-keys outcome #{"tag" "stage" "observations"}
                            "outcome_indeterminate outcome")
        (require-string (get outcome "stage") "stage")
        (validate-observations (get outcome "observations")
                               "indeterminate observations"))

      (schema-error "unknown completion outcome" {:outcome outcome})))
  {"schema_version" schema-version
   "record_type" "completion"
   "operation_id" operation-id
   "outcome" outcome})

(defn resolution
  "Construct an unstored Resolution, enforcing the causal-evidence boundary."
  [{:keys [operation-id resolution]}]
  (require-uuid operation-id "operation_id")
  (let [tag (get resolution "tag")]
    (case tag
      ("effect_confirmed" "no_effect_confirmed")
      (let [evidence (get resolution "evidence_ref")]
        (require-exact-keys resolution #{"tag" "evidence_ref"}
                            (str tag " resolution"))
        (when-not (contains? (get resolution-evidence-types tag)
                             (get evidence "type"))
          (schema-error "evidence class cannot confirm this resolution outcome"
                        {:resolution-tag tag :evidence evidence}))
        (case (get evidence "type")
          "operation_recorded_handles"
          (let [handles (get evidence "handles")]
            (require-exact-keys evidence #{"type" "handles"}
                                "operation-recorded-handle evidence")
            (when-not (and (vector? handles)
                           (seq handles)
                           (= (count handles) (count (distinct handles)))
                           (every? #(and (string? %)
                                         (re-matches #"[1-9][0-9]*" %))
                                   handles))
              (schema-error "operation-recorded evidence needs unique handles"
                            {:evidence evidence})))

          "pre_execution_denial"
          (do
            (require-exact-keys evidence #{"type" "completion_record_id"}
                                "pre-execution-denial evidence")
            (require-uuid (get evidence "completion_record_id")
                          "completion_record_id"))

          "exclusive_no_write_receipt"
          (do
            (require-exact-keys evidence #{"type" "evidence_id"}
                                "exclusive-no-write evidence")
            (require-string (get evidence "evidence_id") "evidence_id"))))

      "indeterminate"
      (do
        (require-exact-keys resolution #{"tag" "observations"}
                            "indeterminate resolution")
        (validate-observations (get resolution "observations")
                               "resolution observations"))

      (schema-error "unknown resolution outcome" {:resolution resolution})))
  {"schema_version" schema-version
   "record_type" "resolution"
   "operation_id" operation-id
   "resolution" resolution})
