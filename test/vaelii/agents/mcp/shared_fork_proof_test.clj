;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.shared-fork-proof-test
  "Focused proof for a trusted, one-shared-fork architecture.

  These tests deliberately separate the two boundaries the design relies on:
  authenticated principals provide write attribution, while belief contexts
  provide private perspective and scratch-context isolation.  They do not claim
  adversarial writer isolation; compromised-writer blast radius and mutation
  ownership remain explicit follow-up security work."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.mcp.config :as config]
            [vaelii.agents.mcp.dispatcher :as dispatcher]
            [vaelii.agents.mcp.session :as session]
            [vaelii.agents.test-util :as tu]
            [vaelii.core :as v]
            [vaelii.starter :as starter]))

(use-fixtures :each
  (fn [f]
    (session/reset-registry!)
    (dispatcher/set-audit-sink! (dispatcher/recording-sink (atom [])))
    (f)
    (session/reset-registry!)))

(defn- temp-dir []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "shared-fork-proof-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- tool-result
  [session name arguments]
  (let [response (dispatcher/dispatch
                  {"jsonrpc" "2.0"
                   "id" 1
                   "method" "tools/call"
                   "params" {"name" name "arguments" arguments}}
                  session)]
    (is (nil? (get response "error")) (str "unexpected JSON-RPC error: " response))
    (-> response (get-in ["result" "content"]) first (get "text") edn/read-string)))

(deftest config-allows-distinct-principals-to-name-one-shared-fork
  (let [dir (temp-dir)
        cfg (io/file dir "config.edn")
        digest-a (apply str (repeat 64 "a"))
        digest-b (apply str (repeat 64 "b"))]
    (spit cfg
          (pr-str {:state-dir "state"
                   :forks-dir "forks"
                   :principals
                   {"alice" {:token-sha256 digest-a
                             :fork-id "team"
                             :lifecycle :durable}
                    "bob"   {:token-sha256 digest-b
                             :fork-id "team"
                             :lifecycle :durable}}}))
    (let [parsed (try
                   (config/read-config (.getPath cfg))
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (map? parsed)
          (if (instance? Throwable parsed)
            (str "shared-fork config was rejected: " (.getMessage ^Throwable parsed))
            "shared-fork config must parse"))
      (when (map? parsed)
        (is (= #{"alice" "bob"} (set (keys (:principals parsed)))))
        (is (= #{"team"} (set (map :fork-id (vals (:principals parsed)))))
            "principal identity and shared-fork identity are separate dimensions")))))

(deftest two-principals-share-belief-state-with-distinct-server-creators
  (let [base (-> (v/open-kb {:backend :memory :space [::two-principals]})
                 starter/load-into)]
    (v/assert base '(genlCx CxTeam CxUniverse) 'CxUniverse)
    (v/assert base '(binary_predicate teamObservation) 'CxUniverse)
    (let [fk (session/provision-fork! base "team" {:lifecycle :durable})
          alice (session/create-session! "alice" "team")
          bob   (session/create-session! "bob" "team")
          alice-sentence "(teamObservation Alice One)"
          bob-sentence   "(teamObservation Bob Two)"
          alice-write (tool-result alice "assert" {"sentence" alice-sentence
                                                   "context" "CxTeam"})
          bob-write   (tool-result bob "assert" {"sentence" bob-sentence
                                                 "context" "CxTeam"})]
      (testing "both authenticated sessions read the same shared context"
        (is (seq (:answers (tool-result bob "query" {"goal" alice-sentence
                                                     "context" "CxTeam"}))))
        (is (seq (:answers (tool-result alice "query" {"goal" bob-sentence
                                                       "context" "CxTeam"})))))
      (testing "the server, not the caller, records the acting principal"
        (is (= "alice" (:creator (v/provenance fk (:handle alice-write)))))
        (is (= "bob" (:creator (v/provenance fk (:handle bob-write)))))
        (is (not= (:creator (v/provenance fk (:handle alice-write)))
                  (:creator (v/provenance fk (:handle bob-write)))))))))

(deftest unlinked-contexts-isolate-perspective-until-an-explicit-edge-connects-them
  (let [kb (v/open-kb {:backend :memory :space [::unlinked-contexts]})]
    (tu/with-terms [CxScratchA CxScratchB CxTeam
                    contested privateClaim aOnly bOnly]
      (doseq [ctx [CxScratchA CxScratchB CxTeam]]
        (v/assert kb (list 'genlCx ctx 'CxUniverse) 'CxUniverse))
      (let [p (list contested 'Case)
            private (list privateClaim 'Case)
            a-only (list aOnly 'Case)
            b-only (list bOnly 'Case)
            private-a (v/assert kb private CxScratchA)]
        (v/assert kb (list 'not p) CxScratchA)
        (v/assert kb p CxScratchA)
        (v/assert kb (list 'except (list 'sentexHandle private-a)) CxScratchA)
        (v/assert kb private CxScratchB)
        (v/assert kb a-only CxScratchA)
        (v/assert kb b-only CxScratchB)

        (testing "unlinked belief contexts hold independent perspectives"
          (is (= :contradiction (:verdict (v/argue kb p CxScratchA))))
          (is (= :unknown (:verdict (v/argue kb p CxScratchB))))
          (is (= :unknown (:verdict (v/argue kb p CxTeam))))
          (is (empty? (v/query kb p CxScratchB)))
          (is (empty? (v/query kb p CxTeam)))
          (is (empty? (v/sentexes-matching kb private CxScratchA))
              "A's copy is excepted in A")
          (is (seq (v/sentexes-matching kb private CxScratchB))
              "A's except does not defeat B's distinct copy"))

        (testing "global diagnostics expose scratch noise and settle cost"
          (is (some (fn [d]
                      (= #{CxScratchA}
                         (set (map :context (:sides d)))))
                    (v/contradictions kb))
              "the global dilemma ledger names the scratch context that created it")
          (let [{:keys [passes histogram]} (v/settle-stats kb)]
            (is (pos? passes))
            (is (pos? (reduce + 0 (vals histogram))))))

        (v/assert kb (list 'genlCx CxScratchB CxScratchA) 'CxUniverse)
        (testing "one explicit edge changes visibility only child-to-parent"
          (is (= :contradiction (:verdict (v/argue kb p CxScratchB))))
          (is (seq (v/query kb a-only CxScratchB)))
          (is (empty? (v/query kb b-only CxScratchA))
              "the parent does not inherit the child's private assertion")
          (is (= :unknown (:verdict (v/argue kb p CxTeam)))
              "the shared context stays unaffected by the scratch context"))))))

(deftest principals-disagreeing-about-a-shared-fork-shape-are-refused
  ;; The first principal to name a fork fixes its shape; a later principal
  ;; declaring a different lifecycle/ttl for the same fork-id is refused, and
  ;; the refusal carries both shapes so the operator can see the disagreement.
  (let [dir (temp-dir)
        cfg (io/file dir "config.edn")
        digest-a (apply str (repeat 64 "a"))
        digest-b (apply str (repeat 64 "b"))]
    (spit cfg
          (pr-str {:state-dir "state"
                   :forks-dir "forks"
                   :principals
                   {"whiskerton"    {:token-sha256 digest-a
                                     :fork-id "team"
                                     :lifecycle :durable}
                    "muffet" {:token-sha256 digest-b
                              :fork-id "team"
                              :lifecycle :leased
                              :lease-ttl-ms 5000}}}))
    (let [thrown (try (config/read-config (.getPath cfg)) nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown)
          "a lifecycle/ttl disagreement about one fork-id must be refused")
      (when thrown
        (let [data (ex-data thrown)]
          (testing "the refusal names the fork and carries both shapes"
            (is (= "team" (:fork-id data)))
            (is (contains? data :declared))
            (is (contains? data :established))
            (is (not= (:declared data) (:established data)))
            (is (= #{{:lifecycle :durable :lease-ttl-ms nil}
                     {:lifecycle :leased :lease-ttl-ms 5000}}
                   #{(:declared data) (:established data)}))))))))

(deftest a-shared-fork-is-forked-once-and-later-provisions-attach
  ;; One fork-id, one v/fork: the second provision must return the very handle
  ;; the first created, without re-forking the base — a re-fork over the same
  ;; disk dir would double-open the store and orphan the first handle.
  (let [base (v/open-kb {:backend :memory :space [::fork-once]})
        fork-calls (atom 0)
        orig-fork v/fork]
    (with-redefs [v/fork (fn [kb opts]
                           (swap! fork-calls inc)
                           (orig-fork kb opts))]
      (let [first-fk  (session/provision-fork! base "team" {:lifecycle :durable})
            second-fk (session/provision-fork! base "team" {:lifecycle :durable})]
        (is (= 1 @fork-calls)
            "the second provision attaches instead of forking again")
        (is (identical? first-fk second-fk)
            "both provisions hand back the same live fork handle")))))
