;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.dispatcher
  "Transport-neutral MCP dispatch with A1 audit integration and effect fencing.

  Audit lifecycle per request:
    1. Health-check the A1 sink → refuse if unhealthy
    2. Mint operation_id
    3. Record durable Attempt → refuse if fails
    4. Acquire generation permit from session registry
    5. Check permit at mutation boundary (inside verb execution)
    6. Execute verb
    7. Record durable Completion → error handling per effect certainty
    8. Return response

  Fencing: the generation counter in the session registry increments on
  revocation or lease retirement. A permit acquired at admission is checked
  before the mutation commits. If the generation changed, the mutation is
  refused even though the session was valid at admission time."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [vaelii.agents.mcp.audit :as audit]
            [vaelii.agents.mcp.session :as session]
            [vaelii.agents.mcp.verbs :as verbs]
            [vaelii.core :as v])
  (:import [java.time Instant]
           [java.util UUID]))

;; ---- Audit sink protocol ----------------------------------------------------

(defprotocol AuditSink
  (record! [this event] "Durably append an audit event. Throws on failure.")
  (healthy? [this] "Returns true if the sink can accept records."))

(deftype RecordingAuditSink [^clojure.lang.Atom records]
  AuditSink
  (record! [_ event] (swap! records conj event))
  (healthy? [_] true))

(defn recording-sink
  "Create a RecordingAuditSink backed by the given atom. For tests only —
  production MUST use a durable A1 sink."
  [records-atom]
  (->RecordingAuditSink records-atom))

;; A2/A4: File-based durable audit journal — canonical JSON, append-only JSONL
;; Satisfies the frozen journal contract (audit-journal-v1-design.md D1-D3):
;;   - Adds record_id, monotonic sequence, recorded_at at the commit point
;;   - Writes canonical JSON (sorted keys, no whitespace, no floats)
;;   - Holds a process-exclusive file lock
;;   - Flushes and fsyncs before returning (the durability boundary)
;;   - Enforces 0700 directory, 0600 segment permissions
(deftype JournalAuditSink [^java.io.File dir
                           ^java.io.File log-file
                           ^java.io.FileOutputStream fos
                           ^java.nio.channels.FileLock flock
                           ^clojure.lang.Atom sequence-counter]
  java.io.Closeable
  (close [_]
    ;; P1#6: orderly shutdown — release lock, close stream.
    ;; Safe to call multiple times; guards against already-released lock.
    (locking fos
      (try (when (.isValid flock) (.release flock)) (catch Exception _))
      (try (.close fos) (catch Exception _))))

  AuditSink
  (record! [_ event]
    ;; P1#4: sequence minting INSIDE the append lock to prevent physical
    ;; reordering (e.g. sequence 2 written before sequence 1).
    (locking fos
      (let [seq-val  (str (swap! sequence-counter inc))
            record   (assoc event
                            "record_id"   (str (UUID/randomUUID))
                            "sequence"    seq-val
                            "recorded_at" (str (Instant/now)))
            line     (str (audit/canonical-json record) "\n")
            bytes    (.getBytes line "UTF-8")]
        (.write fos bytes)
        (.flush fos)
        (.force (.getChannel fos) true))))
  (healthy? [_]
    (and (.canWrite dir)
         (not (nil? flock))
         (.isValid flock))))

(defn- set-posix-perms [^java.io.File f perms-str]
  (try
    (let [path (.toPath f)
          perms (java.nio.file.attribute.PosixFilePermissions/fromString perms-str)]
      (java.nio.file.Files/setPosixFilePermissions path perms))
    (catch UnsupportedOperationException _ nil)))

(defn journal-audit-sink
  "Create a JournalAuditSink at the given directory path. A2/A4: production-grade
  durable journal. Append-only, canonical JSON, fsync'd, process-locked.
  Replaces the old FileAuditSink."
  [dir-path]
  ;; Everything after stream acquisition runs under a catch that releases
  ;; the lock and closes the stream before rethrowing. Without this, a
  ;; validation throw (locked journal, malformed records) leaks an open
  ;; FileOutputStream holding an exclusive lock no owner can ever release —
  ;; a same-JVM reopen then fails forever with OverlappingFileLockException.
  (let [d   (io/file dir-path)
        _   (.mkdirs d)
        _   (set-posix-perms d "rwx------")
        f   (io/file d "audit.jsonl")
        fos (java.io.FileOutputStream. f true)
        lk  (try
              (try (.tryLock (.getChannel fos))
                   (catch java.nio.channels.OverlappingFileLockException _
                     (throw (ex-info "audit journal already locked by this process"
                                     {:type :startup-failed :path (str f)}))))
              (catch Throwable t (.close fos) (throw t)))]
    (try
      (when-not lk
        (throw (ex-info "audit journal locked by another process"
                        {:type :startup-failed :path (str f)})))
      (set-posix-perms f "rw-------")
      ;; P1#4: Initialize sequence from existing records.
      ;; Malformed/truncated records are counted and rejected, not silently skipped.
      (let [malformed-count (atom 0)
            seq (atom (if (.exists f)
                        (with-open [rdr (io/reader f)]
                          (reduce (fn [mx line]
                                    (try
                                      (let [rec (json/parse-string line)
                                            s   (get rec "sequence")]
                                        (if s (max mx (Long/parseLong s)) mx))
                                      (catch Exception _
                                        (swap! malformed-count inc)
                                        mx)))
                                  0 (line-seq rdr)))
                        0))]
        (when (pos? @malformed-count)
          (throw (ex-info (str "audit journal contains " @malformed-count
                               " malformed record(s); refusing startup")
                          {:type :startup-failed
                           :path (str f)
                           :malformed-count @malformed-count})))
        (->JournalAuditSink d f fos lk seq))
      (catch Throwable t
        (try (when (and lk (.isValid ^java.nio.channels.FileLock lk))
               (.release ^java.nio.channels.FileLock lk))
             (catch Exception _))
        (try (.close fos) (catch Exception _))
        (throw t)))))

(defonce ^:private audit-sink (atom nil))

;; Mutation fencing: holds the generation permit for the current request.
;; C3/C4: verbs can check this before committing.
(def ^:dynamic *mutation-permit* nil)

(defn set-audit-sink!
  "Set the global audit sink. A4: production path MUST reject RecordingAuditSink."
  ([sink] (set-audit-sink! sink false))
  ([sink production?]
   (when (and production?
              (instance? RecordingAuditSink sink))
     (throw (ex-info "A4: production MUST NOT use RecordingAuditSink"
                     {:type :startup-failed})))
   (reset! audit-sink sink)))

(defn audit-healthy? []
  (when-let [s @audit-sink] (healthy? s)))

;; ---- Protocol constants (single source of truth) ----------------------------

(def supported-versions #{"2025-03-26"})
(def default-version "2025-03-26")
(def ^:private server-name "vaelii")
(def ^:private server-version "0.1.0")

;; ---- JSON-RPC response builders --------------------------------------------

(defn jsonrpc-result [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn jsonrpc-error [id code message & [data]]
  (cond-> {"jsonrpc" "2.0"
           "id"      id
           "error"   {"code" code "message" message}}
    data (assoc-in ["error" "data"] data)))

;; ---- Transport-level denial audit (D2: every denial gets Attempt+Completion) -

(defn record-transport-denial!
  "Record a transport-level denial (origin, auth, content-type, accept) as an
  Attempt + denied Completion pair. Called by http-transport before the dispatcher
  is reached. auth-status is one of: missing, invalid, or authenticated (for
  post-auth transport rejections like content-type/accept)."
  [auth-status principal denial-code denial-message
   & {:keys [content-type byte-length]}]
  (when-let [sink @audit-sink]
    (let [op-id (str (UUID/randomUUID))
          input (audit/untrusted-input
                 {:raw-sha256   "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                  :byte-length  (or byte-length 0)
                  :content-type (or content-type "unknown")})
          attempt (audit/attempt
                   {:operation-id   op-id
                    :verb           "query" ;; transport denials have no verb; use "query" as read-only sentinel
                    :auth-status    auth-status
                    :principal      principal
                    :session-id     nil
                    :fork-id        nil
                    :correlation-id nil
                    :input          input})
          completion (audit/completion
                      {:operation-id op-id
                       :outcome      {"tag"     "denied"
                                      "code"    denial-code
                                      "message" denial-message}})]
      (try
        (record! sink attempt)
        (record! sink completion)
        (catch Throwable t
          ;; P1#1: transport denial audit failures must not be silent.
          ;; We can't refuse the denial (it already happened), but the
          ;; persistence failure must be visible.
          (throw (ex-info "transport denial audit persistence failed"
                          {:type :audit-persistence-failed
                           :denial-code denial-code}
                          t)))))))  ;; transport denials: refuse if audit fails

;; ---- Audit recording (A1 schema) -------------------------------------------

(defn- record-attempt! [sink operation-id verb auth-status principal
                        session-id fork-id input]
  (let [attempt (audit/attempt
                 {:operation-id   operation-id
                  :verb           verb
                  :auth-status    auth-status
                  :principal      principal
                  :session-id     session-id
                  :fork-id        fork-id
                  :correlation-id nil
                  :input          input})]
    (record! sink attempt)))

(defn- record-completion! [sink operation-id outcome]
  (let [completion (audit/completion
                    {:operation-id operation-id
                     :outcome      outcome})]
    (record! sink completion)))

;; ---- A1 audit input construction -------------------------------------------

(defn- build-authenticated-input [verb params]
  (case verb
    "assert"
    (audit/authenticated-input "assert"
                               {:sentence (edn/read-string (get params "sentence" "nil"))
                                :context  (edn/read-string (get params "context" "'CxUniverse"))})

    "unassert"
    (audit/authenticated-input "unassert"
                               {:sentence (edn/read-string (get params "sentence" "nil"))
                                :context  (edn/read-string (get params "context" "'CxUniverse"))})

    "unassert_handle"
    (audit/authenticated-input "unassert_handle"
                               {:sentex-handle (get params "sentex_handle" 0)})

    "query"
    (audit/authenticated-input "query"
                               {:goal                  (edn/read-string (get params "goal" "nil"))
                                :context               (edn/read-string (get params "context" "'CxUniverse"))
                                :include-justification (boolean (get params "include_justification" false))})

    "argue"
    (audit/authenticated-input "argue"
                               {:closed-goal (edn/read-string (get params "closed_goal" "nil"))
                                :context     (edn/read-string (get params "context" "'CxUniverse"))})

    "matching"
    ;; Options are recorded as the verb will read them: an absent limit is the
    ;; default; a limit the verb will refuse (non-integer, non-positive) is
    ;; recorded null rather than as caller text — the completion says rejected.
    (let [limit (get params "limit" verbs/matching-default-limit)]
      (audit/authenticated-input "matching"
                                 {:pattern       (edn/read-string (get params "pattern" "nil"))
                                  :context       (edn/read-string (get params "context" "'CxUniverse"))
                                  :asserted-only (true? (get params "asserted_only" false))
                                  :limit         (when (and (integer? limit) (pos? limit)) limit)}))

    (audit/untrusted-input
     {:raw-sha256   (str "sha256:" (apply str (repeat 64 "0")))
      :byte-length  0
      :content-type "application/json"})))

(defn- build-result-summary [verb result]
  (when-not (:mcp-error result)
    (let [;; Translate verb result keys to audit schema keys
          translated (case verb
                       "assert"
                       {:handle (:handle result)
                        :already-there (:already_there result)}
                       "unassert"
                       {:excepting-handles (:excepting_handles result)}
                       "unassert_handle"
                       {:excepting-handle (:excepting_handle result)}
                       "query"
                       {:answers (:answers result)
                        :justifications-requested false}
                       "argue"
                       {:verdict (:verdict result)
                        :for-proof (:for_justification result)
                        :against-proof (:against_justification result)}
                       "matching"
                       {:rows      (:rows result)
                        :truncated (:truncated result)}
                       result)]
      (audit/result-summary verb translated))))

;; ---- MCP method handlers ---------------------------------------------------

(defn- handle-initialize [id params]
  (let [requested  (get params "protocolVersion")
        negotiated (if (and requested (supported-versions requested))
                     requested
                     default-version)]
    (jsonrpc-result id
                    {"protocolVersion" negotiated
                     "capabilities"    {"tools" {}}
                     "serverInfo"      {"name" server-name "version" server-version}})))

(defn- handle-tools-list [id _params]
  (jsonrpc-result id {"tools" verbs/schemas}))

(defn- handle-tools-call [id params session]
  (when-not session
    (throw (ex-info "no authenticated session" {:type :unauthorized})))
  ;; Re-check session liveness
  (let [live-sess (session/get-session (:session-id session))]
    (when-not live-sess
      (throw (ex-info "session revoked" {:type :session-stale})))
    (let [fk (:fork-kb live-sess)]
      (when-not fk
        (throw (ex-info "fork not available" {:type :session-stale})))
      (let [tool-name  (get params "name")
            arguments  (get params "arguments" {})
            pid        (:principal-id live-sess)
            sid        (:session-id live-sess)
            fid        (:fork-id live-sess)
            op-id      (str (UUID/randomUUID))
            sink       @audit-sink
            ;; Audit records carry the tool name exactly as invoked. Aliasing
            ;; here (e.g. "unassert-handle" → "unassert_handle") would record a
            ;; real verb's name on an unknown-tool call that never dispatched.
            verb-name  tool-name]
        ;; D1: health check — nil sink or unhealthy sink both refuse (P1#1)
        (when-not (and sink (healthy? sink))
          (throw (ex-info "audit unavailable"
                          {:type :audit-unavailable :verb verb-name})))
        ;; D2: Attempt BEFORE dispatch
        (let [audit-input (try (build-authenticated-input verb-name arguments)
                               (catch Exception _
                                 (audit/untrusted-input
                                  {:raw-sha256   (str "sha256:" (apply str (repeat 64 "0")))
                                   :byte-length  0
                                   :content-type "application/json"})))]
          (when sink
            (record-attempt! sink op-id verb-name "authenticated" pid sid fid
                             audit-input))
          ;; C3/C4: Acquire permit BEFORE dispatch
          (let [permit (session/acquire-permit)
                ;; C5: Bind the write-fence checker so verbs check at
                ;; the mutation boundary, not just pre/post dispatch
                permit-checker (fn []
                                 (when-not (session/permit-valid? permit)
                                   (throw (ex-info "authority revoked during write"
                                                   {:type :fenced :verb verb-name}))))]
            ;; The creator is the authenticated acting principal, server-recorded:
            ;; bound here, read by the engine's assert when opts name none, and
            ;; never accepted from tool arguments (the unknown-arguments check
            ;; refuses a smuggled creator field before dispatch).  Workspace says
            ;; where; creator says who.
            (binding [verbs/*mcp-kb* fk
                      verbs/*check-write-permit!* permit-checker
                      verbs/*authority-lock* session/authority-lock
                      v/*creator* pid
                      *mutation-permit* permit]
              (try
                ;; C3: Check permit pre-dispatch
                (when-not (session/permit-valid? permit)
                  (throw (ex-info "authority changed during request"
                                  {:type :fenced :verb verb-name})))
                (let [result (verbs/dispatch tool-name arguments)]
                  ;; C3: the under-lock fence check inside each verb IS the
                  ;; linearization decision. No post-dispatch recheck — it would
                  ;; report refusal after the effect is committed, contradicting
                  ;; the audit record and leaving KB state inconsistent with the
                  ;; response. Revocation after a committed mutation fences the
                  ;; NEXT request, it doesn't retroactively deny the last.
                  ;; D3: Completion AFTER dispatch, before response
                  (when sink
                    (let [outcome (if (:mcp-error result)
                                    {"tag"     "rejected"
                                     "code"    (str (:code result))
                                     "message" (str (:error result))}
                                    (let [effect (if (contains? verbs/read-verbs verb-name)
                                                   "none"
                                                   (if (:already_there result)
                                                     "unchanged"
                                                     "changed"))
                                          summary (build-result-summary verb-name result)]
                                      (if summary
                                        {"tag"            "succeeded"
                                         "effect"         effect
                                         "result_summary" summary}
                                        {"tag"     "succeeded"
                                         "effect"  effect
                                         "result_summary" {"tag" verb-name
                                                           "answer_count" 0
                                                           "justifications_requested" false}})))]
                      (try
                        (record-completion! sink op-id outcome)
                        (catch Throwable t
                          ;; D6: completion failure propagation
                          (if (= "none" (get outcome "effect"))
                            (throw (ex-info "audit completion failed (no effect)"
                                            {:type :audit-unavailable}
                                            t))
                            (throw (ex-info "outcome indeterminate"
                                            {:type :outcome-indeterminate
                                             :cause :completion_write_failed}
                                            t)))))))
                  ;; Build response
                  (jsonrpc-result id
                                  (if (:mcp-error result)
                                    {"isError" true
                                     "content" [{"type" "text"
                                                 "text" (pr-str {"code"  (:code result)
                                                                 "error" (:error result)})}]}
                                    {"content" [{"type" "text"
                                                 "text" (pr-str result)}]})))
                (catch clojure.lang.ExceptionInfo e
                  (let [t (:type (ex-data e))
                        data (ex-data e)]
                    (cond
                      ;; Verb-raised effect uncertainty: record the completion
                      ;; that states it. "We don't know what committed" must
                      ;; never flatten to rejected/denied — that claims no effect.
                      (and sink
                           (= t :outcome-indeterminate)
                           (not= (:cause data) :completion_write_failed))
                      (try
                        (record-completion! sink op-id
                                            {"tag" "outcome_indeterminate"
                                             "stage" "verb_execution"
                                             "observations"
                                             (cond-> [(subs (str (.getMessage e)) 0
                                                            (min 512 (count (str (.getMessage e)))))]
                                               (:committed data)
                                               (conj (str "committed " (:committed data)
                                                          " of " (:total data)
                                                          " writes before failure")))})
                        (catch Throwable _))

                      ;; D6: the completion write itself failed after an effect —
                      ;; the sink is the casualty, nothing more can be recorded.
                      (and sink (= t :outcome-indeterminate)) nil

                      (and sink (not= t :audit-unavailable))
                      (try
                        (record-completion! sink op-id
                                            {"tag"     "denied"
                                             "code"    (name (or t :unknown))
                                             "message" (.getMessage e)})
                        (catch Throwable _)))
                    (case t
                      :fenced
                      (jsonrpc-error id -32600 "authority changed: request refused")
                      :audit-unavailable
                      (jsonrpc-error id -32603 "audit unavailable")
                      :outcome-indeterminate
                      (jsonrpc-error id -32603 "outcome indeterminate")
                      (throw e))))
                (catch Throwable t
                  ;; D5: thrown outcomes
                  (when sink
                    (try
                      (record-completion! sink op-id
                                          {"tag"     "failed"
                                           "code"    "internal_error"
                                           "message" (.getMessage t)})
                      (catch Throwable _)))
                  (throw t))))))))))

(defn- handle-ping [id _params]
  (jsonrpc-result id {}))

;; ---- dispatch ----------------------------------------------------------------

(defn dispatch
  "Dispatch one parsed JSON-RPC message."
  [msg session]
  (let [id     (get msg "id")
        method (get msg "method")
        params (get msg "params" {})]
    (case method
      "initialize"
      {:type     :initialize-result
       :response (handle-initialize id params)}

      "notifications/initialized"
      nil

      "tools/list"
      (handle-tools-list id params)

      "tools/call"
      (try
        (handle-tools-call id params session)
        (catch clojure.lang.ExceptionInfo e
          (let [t (:type (ex-data e))]
            (case t
              :unauthorized  (jsonrpc-error id -32600 "unauthorized")
              :session-stale (jsonrpc-error id -32600 "session stale")
              :audit-unavailable (jsonrpc-error id -32603 "audit unavailable")
              :outcome-indeterminate (jsonrpc-error id -32603 "outcome indeterminate")
              (jsonrpc-error id -32603 (.getMessage e)))))
        (catch Throwable t
          (jsonrpc-error id -32603 (.getMessage t))))

      "ping"
      (handle-ping id params)

      (when id
        (jsonrpc-error id -32601 (str "unknown method: " method))))))
