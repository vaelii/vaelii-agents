;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.refusal-vocabulary-test
  "Provokes every refusal `:type` the MCP server raises and fixes the keyword
  each refusal site answers with.

  A refusal keyword is a contract with the caller holding it in a `catch`: the
  same refusal site refuses with the same word every time. Each test here
  drives one refusal site to its refusal and discriminates on
  `(:type (ex-data e))`. Message text is never asserted; the keyword is the
  surface.

  The refusal sites, by owning namespace:

  - `server` / `http-transport` — `:startup-failed` (audit-dir custody).
  - `session` — `:authority-write-failed`, `:authority-store-poisoned`,
    `:expired-fork`, `:principal-revoked`.
  - `dispatcher` — `:audit-unavailable`, `:audit-persistence-failed`,
    `:session-stale`.
  - `http-transport` — `:invalid-json`, `:body-too-large`.
  - `verbs` — `:outcome-indeterminate` (partial multi-handle unassert)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.mcp.dispatcher :as dispatcher]
            [vaelii.agents.mcp.http-transport :as http]
            [vaelii.agents.mcp.server :as server]
            [vaelii.agents.mcp.session :as session]
            [vaelii.agents.mcp.verbs :as verbs]
            [vaelii.core :as v])
  (:import [java.io ByteArrayInputStream]))

(use-fixtures :each
  (fn [f]
    (session/reset-registry!)
    (dispatcher/set-audit-sink! (dispatcher/recording-sink (atom [])))
    (f)
    (session/reset-registry!)
    (dispatcher/set-audit-sink! (dispatcher/recording-sink (atom [])))))

;; ---- helpers ---------------------------------------------------------------

(defn- refusal-type
  "The `:type` of the ExceptionInfo `thunk` throws — `::no-throw` when it
  returns instead, so a refusal site that fails to refuse fails the `=` loudly."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:type (ex-data e)))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "vaelii-refusal-vocab-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

;; ---- :startup-failed -------------------------------------------------------

(deftest startup-failed-refuses-bad-audit-custody
  (let [forks "/srv/vaelii-refusal-probe/forks"
        state "/srv/vaelii-refusal-probe/state"]
    (testing "a relative audit dir refuses startup"
      (is (= :startup-failed
             (refusal-type
              #(server/validate-audit-dir-custody! "relative/audit" forks state)))))
    (testing "an audit dir under /tmp refuses startup"
      (is (= :startup-failed
             (refusal-type
              #(server/validate-audit-dir-custody! "/tmp/vaelii-audit" forks state)))))
    (testing "an audit dir overlapping forks-dir refuses startup"
      (is (= :startup-failed
             (refusal-type
              #(server/validate-audit-dir-custody! forks forks state)))))))

;; ---- :authority-write-failed -----------------------------------------------

(deftest authority-write-failure-refuses-revocation
  (let [store (session/recording-authority-store (atom nil) (atom :write))]
    (session/set-authority-store! store)
    (is (= :authority-write-failed
           (refusal-type #(session/revoke-principal! "muffet")))
        "a persistence failure must refuse the authority change, not ack it")))

;; ---- :authority-store-poisoned ---------------------------------------------

(deftest poisoned-authority-store-refuses-writes
  ;; The poisoned flag is the store's post-replacement-failure state: disk and
  ;; memory may diverge, so every write is refused until reconciliation. The
  ;; flag is constructor state on FileAuthorityStore, so the test builds the
  ;; store already poisoned and drives the write path.
  (let [store (session/->FileAuthorityStore (temp-dir) (atom true))]
    (is (= :authority-store-poisoned
           (refusal-type
            #(session/store-write! store {:revoked #{} :fork-meta {}})))
        "a poisoned store must refuse writes until reconciled")))

;; ---- :audit-unavailable ----------------------------------------------------

(deftest audit-unavailable-refuses-before-verb
  (let [base (v/open-kb {})]
    (session/provision-fork! base "whiskerton" {:lifecycle :durable})
    (let [sess (session/create-session! "whiskerton" "whiskerton")
          params {"name" "query"
                  "arguments" {"goal" "(dog ?x)" "context" "CxWell"}}]
      (testing "a nil sink refuses tools/call before the verb runs"
        (dispatcher/set-audit-sink! nil)
        (is (= :audit-unavailable
               (refusal-type #(#'dispatcher/handle-tools-call 1 params sess)))))
      (testing "an unhealthy sink refuses the same way"
        (dispatcher/set-audit-sink!
         (reify dispatcher/AuditSink
           (record! [_ _] nil)
           (healthy? [_] false)))
        (is (= :audit-unavailable
               (refusal-type #(#'dispatcher/handle-tools-call 2 params sess))))))))

;; ---- :audit-persistence-failed ---------------------------------------------

(deftest audit-persistence-failure-surfaces-on-transport-denial
  (dispatcher/set-audit-sink!
   (reify dispatcher/AuditSink
     (record! [_ _] (throw (ex-info "injected journal write failure" {})))
     (healthy? [_] true)))
  (is (= :audit-persistence-failed
         (refusal-type
          #(dispatcher/record-transport-denial!
            "missing" nil "bearer_missing" "missing Authorization header"
            :content-type "application/json" :byte-length 2)))
      "a denial whose audit pair cannot persist must surface the failure"))

;; ---- :session-stale --------------------------------------------------------

(deftest stale-session-refuses-tools-call
  (let [base (v/open-kb {})
        params {"name" "query"
                "arguments" {"goal" "(dog ?x)" "context" "CxWell"}}]
    (session/provision-fork! base "whiskerton" {:lifecycle :durable})
    (let [sess (session/create-session! "whiskerton" "whiskerton")]
      (session/revoke-session! (:session-id sess))
      (testing "a session removed from the registry refuses tools/call"
        (is (= :session-stale
               (refusal-type #(#'dispatcher/handle-tools-call 1 params sess))))))
    (testing "a session id the registry never held refuses the same way"
      (is (= :session-stale
             (refusal-type
              #(#'dispatcher/handle-tools-call 2 params
                                               {:session-id "never-registered"})))))))

;; ---- :expired-fork ---------------------------------------------------------

(deftest expired-fork-refuses-reprovision
  (let [base (v/open-kb {})]
    (session/provision-fork! base "guest" {:lifecycle :leased :lease-ttl-ms 1})
    (Thread/sleep 10)
    (is (= :expired-fork
           (refusal-type
            #(session/provision-fork! base "guest" {:lifecycle :leased
                                                    :lease-ttl-ms 1})))
        "a leased fork past its deadline must not reopen")))

;; ---- :principal-revoked ----------------------------------------------------

(deftest revoked-principal-refuses-session-creation
  (let [base (v/open-kb {})]
    (session/provision-fork! base "whiskerton" {:lifecycle :durable})
    (session/revoke-principal! "whiskerton")
    (is (= :principal-revoked
           (refusal-type #(session/create-session! "whiskerton" "whiskerton")))
        "a revoked principal must not bind a new session")))

;; ---- :invalid-json ---------------------------------------------------------

(deftest invalid-json-refuses-parse
  (let [request (fn [^String body]
                  {:body (ByteArrayInputStream. (.getBytes body "UTF-8"))})]
    (testing "a truncated body refuses as :invalid-json"
      (is (= :invalid-json
             (refusal-type #(#'http/parse-json-body (request "{"))))))
    (testing "a non-object body refuses as :invalid-json"
      (is (= :invalid-json
             (refusal-type #(#'http/parse-json-body (request "[1,2,3]"))))))))

;; ---- :body-too-large -------------------------------------------------------

(deftest oversized-body-refuses-before-parse
  (let [body (ByteArrayInputStream.
              (.getBytes ^String (apply str (repeat 64 "x")) "UTF-8"))]
    (is (= :body-too-large
           (refusal-type #(#'http/read-bounded-body {:body body} 8)))
        "a body over the ceiling must refuse during the bounded read")))

;; ---- :outcome-indeterminate ------------------------------------------------

(deftest partial-unassert-surfaces-outcome-indeterminate
  ;; Two believed sentexes, one unassert walking both handles; the first
  ;; except write commits and the second dies. The verb must throw effect
  ;; uncertainty — never flatten it into a rejected error map.
  (let [base (v/open-kb {})
        fk   (v/fork base)]
    (v/assert fk '(predicate dog) 'CxWell)
    (binding [verbs/*mcp-kb* fk]
      (doseq [s ["(dog Muffet)" "(dog Tweety)"]]
        (let [r (verbs/verb-assert {"sentence" s "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "setup assert failed: " r))))
      (let [real-assert v/assert
            real-matching v/sentexes-matching
            write-n (atom 0)]
        (with-redefs [v/sentexes-matching
                      (fn [kb _sentence context]
                        (concat (real-matching kb '(dog Muffet) context)
                                (real-matching kb '(dog Tweety) context)))
                      v/assert
                      (fn [kb sentence context opts]
                        (if (= 2 (swap! write-n inc))
                          (throw (ex-info "injected disk full" {:injected true}))
                          (real-assert kb sentence context opts)))]
          (is (= :outcome-indeterminate
                 (refusal-type
                  #(verbs/verb-unassert {"sentence" "(dog Muffet)"
                                         "context" "CxWell"})))
              "a partial multi-handle unassert must state effect uncertainty"))))))
