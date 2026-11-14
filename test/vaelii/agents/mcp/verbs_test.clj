;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.verbs-test
  "Tests for the six MCP verb tools (`vaelii.agents.mcp.verbs`).

  Each test creates a fresh KB, forks it, and exercises the verb through the
  fork — the same path the MCP server takes.  The cross-fork except test
  verifies that excepting in one fork hides from that fork only, and that a
  sibling fork cannot except a handle it cannot see."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.engine :as eh]
            [vaelii.agents.mcp.verbs :as verbs]
            [vaelii.agents.test-util :as tu]
            [vaelii.core :as v]
            [vaelii.starter :as starter]))

(use-fixtures :each (tu/neutral-fresh tu/fresh))

;; ---- helpers ---------------------------------------------------------------

(defmacro with-fork
  "Create a fork of the test KB and bind `sym` to it.  The fork is the KB
  an MCP client writes through."
  [[sym] & body]
  `(let [~sym (v/fork tu/*kb*)]
     ~@body))

(defmacro with-verb-kb
  "Bind *mcp-kb* and run body."
  [kb & body]
  `(binding [verbs/*mcp-kb* ~kb]
     ~@body))

(defn- declare-fixture-predicates!
  "Explicit vocabulary for positive fixtures; never derived from assert input."
  [kb & predicates]
  (doseq [pred predicates]
    (v/assert kb (list 'predicate pred) 'CxWell)))

;; ---- verb: assert ----------------------------------------------------------

(deftest assert-refuses-an-undeclared-predicate-before-storage
  (with-fork [fk]
    (with-verb-kb fk
      (let [pred (tu/fresh-term :predicate "undeclaredProbe")
            subject (tu/fresh-term :individual "ProbeSubject")
            sentence (list pred subject)
            result (verbs/verb-assert {"sentence" (pr-str sentence)
                                       "context" "CxWell"})
            stored? (some? (v/handle-of fk sentence 'CxWell))]
        (is (true? (:mcp-error result)))
        (is (= "invalid_sentence" (:code result)))
        (is (false? stored?) "refusal must not store an undeclared fact")))))

(deftest assert-does-not-grandfather-an-undeclared-stored-fact
  (with-fork [fk]
    (starter/load-into fk)
    (with-verb-kb fk
      (let [sentence '(legacyFixtureProbe FixtureSubject FixtureOther)
            handle (v/assert fk sentence 'CxWell)
            result (verbs/verb-assert {"sentence" (pr-str sentence) "context" "CxWell"})]
        (is (= "invalid_sentence" (:code result)))
        (is (not (:already_there result)))
        (is (= handle (v/handle-of fk sentence 'CxWell)))))))

(deftest assert-checks-executable-positions-not-data
  (with-fork [fk]
    (starter/load-into fk)
    (with-verb-kb fk
      (doseq [sentence ['(unary_fixture FixtureSubject)
                        '(binaryFixture FixtureSubject "fixture")
                        '(not (unary_fixture FixtureOther))
                        '(implies (unary_fixture ?x) (binaryFixture ?x "fixture"))
                        '(set/softConstraint 2
                                             (implies (unary_fixture ?x) (binaryFixture ?x "soft")))
                        '(exceptWhen (set/monotonic (unary_fixture ?x))
                                     (implies (unary_fixture ?x) (binaryFixture ?x "guarded")))
                        '(defnNecessary fixture_collection (unary_fixture ?x))
                        '(comment FixtureSubject "(undefinedPayload FixtureSubject)")
                        '(comment FixtureSubject (QuoteFn (undefinedPayload FixtureSubject)))
                        '(instance_relation_predicate newFixtureRelation)
                        '(newFixtureRelation FixtureSubject FixtureOther)]]
        ;; Establish vocabulary through the real bootstrap declaration path.
        (when (= sentence '(unary_fixture FixtureSubject))
          (doseq [declaration ['(unary_predicate unary_fixture)
                               '(binary_predicate binaryFixture)]]
            (is (not (:mcp-error
                      (verbs/verb-assert {"sentence" (pr-str declaration)
                                          "context" "CxUniverse"}))))))
        (let [result (verbs/verb-assert {"sentence" (pr-str sentence)
                                         "context" "CxUniverse"})]
          (is (not (:mcp-error result)) (str sentence " => " result)))))))

(deftest assert-preserves-variable-predicates-and-function-arguments
  (with-fork [fk]
    (starter/load-into fk)
    (with-verb-kb fk
      (doseq [declaration ['(ternary_predicate holdsFixture)
                           '(binary_predicate fixtureRelation)
                           '(unary_function FixtureFn)]]
        (let [result (verbs/verb-assert {"sentence" (pr-str declaration)
                                         "context" "CxUniverse"})]
          (is (not (:mcp-error result)) (str result))))
      (is (not (v/ask? fk '(predicate FixtureFn) 'CxUniverse)))
      (doseq [sentence ['(set/forwardRule
                          (implies (holdsFixture ?p ?x ?y) (?p ?x ?y)))
                        '(holdsFixture fixtureRelation FixtureSource FixtureTarget)
                        '(fixtureRelation (FixtureFn FixtureSource) FixtureTarget)]]
        (let [result (verbs/verb-assert {"sentence" (pr-str sentence)
                                         "context" "CxUniverse"})]
          (is (integer? (:handle result)) (str sentence " => " result))))
      (is (v/ask? fk '(fixtureRelation FixtureSource FixtureTarget) 'CxUniverse)
          "the variable-consequent rule actually fires")
      (is (v/ask? fk '(fixtureRelation (FixtureFn FixtureSource) FixtureTarget) 'CxUniverse)
          "a function application remains argument data, not a predicate use"))))

(deftest assert-refuses-undefined-predicates-in-executable-forms
  (with-fork [fk]
    (starter/load-into fk)
    (v/assert fk '(genlCx CxWell CxUniverse) 'CxUniverse)
    (declare-fixture-predicates! fk 'known_fixture)
    (with-verb-kb fk
      (doseq [sentence ['(not (undefined_fixture FixtureSubject))
                        '(implies (known_fixture ?x) (undefined_fixture ?x))
                        '(set/forwardRule (implies (undefined_fixture ?x) (known_fixture ?x)))
                        '(exceptWhen (undefined_fixture ?x)
                                     (implies (known_fixture ?x) (known_fixture ?x)))
                        '(implies (and (known_fixture ?x)
                                       (forall ?y (implies (known_fixture ?y) (undefined_fixture ?y))))
                                  (known_fixture ?x))
                        '(defnNecessary fixture_collection (undefined_fixture ?x))
                        '(set/softConstraint 2
                                             (implies (undefined_fixture ?x) (known_fixture ?x)))
                        '(exceptWhen (set/monotonic (undefined_fixture ?x))
                                     (implies (known_fixture ?x) (known_fixture ?x)))
                        '(asp/atMost 1 ?x (undefined_fixture ?x))
                        '(asp/minimize 1 ?weight (undefinedFixtureWeight ?x ?weight))]]
        (let [result (verbs/verb-assert {"sentence" (pr-str sentence) "context" "CxWell"})]
          (is (= "invalid_sentence" (:code result)) (str sentence " => " result))
          (is (re-find #"undefined_fixture|undefinedFixtureWeight" (:error result)) (str result))
          (is (nil? (v/handle-of fk sentence 'CxWell))))))))

(deftest assert-declarations-follow-the-effective-context
  (with-fork [fk]
    (starter/load-into fk)
    (doseq [sentence ['(genlCx CxFixtureA CxUniverse)
                      '(genlCx CxFixtureChild CxFixtureA)
                      '(genlCx CxFixtureOther CxUniverse)]]
      (v/assert fk sentence 'CxUniverse))
    (v/assert fk '(predicate scopedFixture) 'CxFixtureA)
    (with-verb-kb fk
      (doseq [[sentence context allowed?]
              [['(scopedFixture FixtureA FixtureValue) "CxFixtureA" true]
               ['(scopedFixture FixtureChild FixtureValue) "CxFixtureChild" true]
               ['(scopedFixture FixtureOther FixtureValue) "CxFixtureOther" false]
               ['(ist CxFixtureA (scopedFixture FixtureIst FixtureValue)) "CxFixtureOther" true]
               ['(ist CxFixtureOther (scopedFixture FixtureNoIst FixtureValue)) "CxFixtureA" false]]]
        (let [result (verbs/verb-assert {"sentence" (pr-str sentence) "context" context})]
          (if allowed?
            (is (not (:mcp-error result)) (str sentence " => " result))
            (is (= "invalid_sentence" (:code result)) (str sentence " => " result))))))))

(deftest assert-stores-a-fact-and-returns-its-handle
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [dog Muffet]
        (declare-fixture-predicates! fk dog)
        (let [r (verbs/verb-assert {"sentence" (pr-str (list dog Muffet))
                                    "context"  "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (integer? (:handle r)))
          (is (false? (:already_there r)))
          ;; The fact is visible in the fork
          (is (v/ask? fk (list dog Muffet) 'CxWell)))))))

(deftest assert-is-idempotent
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (let [r1 (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                                     "context"  "CxWell"})
              r2 (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                                     "context"  "CxWell"})]
          (is (not (:mcp-error r1)))
          (is (not (:mcp-error r2)))
          (is (= (:handle r1) (:handle r2))
              "same sentence+context -> same handle")
          (is (true? (:already_there r2))))))))

(deftest assert-refuses-invalid-edn
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-assert {"sentence" "not valid edn ((("
                                  "context"  "CxWell"})]
        (is (:mcp-error r))
        (is (= "invalid_sentence" (:code r)))))))

(deftest assert-refuses-non-list-sentence
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-assert {"sentence" "42"
                                  "context"  "CxWell"})]
        (is (:mcp-error r))
        (is (= "invalid_sentence" (:code r)))))))

(deftest assert-refuses-invalid-context
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-assert {"sentence" "(dog Muffet)"
                                  "context"  "42"})]
        (is (:mcp-error r))
        (is (= "invalid_context" (:code r)))))))

(deftest assert-refuses-non-context-symbol
  (testing "a symbol that doesn't match Cx… naming is rejected"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-assert {"sentence" "(dog Muffet)"
                                    "context"  "Foo"})]
          (is (:mcp-error r))
          (is (= "invalid_context" (:code r)))
          (is (re-find #"not a valid context" (:error r))))))))

(deftest query-refuses-non-context-symbol
  (testing "query with a non-Cx context symbol returns invalid_context"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-query {"goal"    "(dog ?x)"
                                   "context" "Foo"})]
          (is (:mcp-error r))
          (is (= "invalid_context" (:code r))))))))

;; ---- verb: unassert --------------------------------------------------------

(deftest unassert-hides-a-believed-fact
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        ;; Assert first
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                            "context"  "CxWell"})
        (is (v/ask? fk (list shiny Gold) 'CxWell) "visible before unassert")
        ;; Unassert
        (let [r (verbs/verb-unassert {"sentence" (pr-str (list shiny Gold))
                                      "context"  "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (seq (:excepting_handles r)) "should have excepted something")
          (is (not (v/ask? fk (list shiny Gold) 'CxWell))
              "hidden after unassert"))))))

(deftest unassert-hides-a-fact-asserted-monotonic
  ;; The assert verb takes no strength, so the fact goes into the fork at
  ;; {:strength :monotonic} through the vaelii API, as another writer would.
  ;; unassert states an `except`, and an `except` hides its target whatever
  ;; the strength it was asserted at (upstream except_monotonic_test).
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (let [fact (list shiny Gold)
              fh   (v/assert fk fact 'CxWell {:strength :monotonic})]
          (is (= :monotonic (:strength (v/sentex fk fh))) "stored monotonic")
          (is (v/ask? fk fact 'CxWell) "visible before unassert")
          (let [r (verbs/verb-unassert {"sentence" (pr-str fact)
                                        "context"  "CxWell"})]
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (seq (:excepting_handles r)) "should have excepted something")
            (is (not (v/ask? fk fact 'CxWell))
                "a monotonic fact is hidden after unassert")))))))

(deftest unassert-is-idempotent-vacuous-when-not-believed
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        ;; Unassert something that was never asserted
        (let [r (verbs/verb-unassert {"sentence" (pr-str (list shiny Gold))
                                      "context"  "CxWell"})]
          (is (not (:mcp-error r)))
          (is (= [] (:excepting_handles r))
              "vacuous — nothing to except"))))))

(deftest unassert-idempotent-after-first-unassert
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                            "context"  "CxWell"})
        ;; Unassert twice
        (let [r1 (verbs/verb-unassert {"sentence" (pr-str (list shiny Gold))
                                       "context"  "CxWell"})
              r2 (verbs/verb-unassert {"sentence" (pr-str (list shiny Gold))
                                       "context"  "CxWell"})]
          (is (seq (:excepting_handles r1)) "first unassert does something")
          (is (= [] (:excepting_handles r2))
              "second unassert is vacuous — already hidden"))))))

;; ---- verb: unassert-handle -------------------------------------------------

(deftest unassert-handle-hides-a-specific-sentex
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (let [ar (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                                     "context"  "CxWell"})
              h  (:handle ar)
              r  (verbs/verb-unassert-handle {"sentex_handle" h})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (integer? (:excepting_handle r)))
          (is (not (v/ask? fk (list shiny Gold) 'CxWell))
              "hidden after unassert-handle"))))))

(deftest unassert-handle-returns-nil-when-already-withdrawn
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (let [ar (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                                     "context"  "CxWell"})
              h  (:handle ar)]
          (verbs/verb-unassert-handle {"sentex_handle" h})
          ;; Second time
          (let [r (verbs/verb-unassert-handle {"sentex_handle" h})]
            (is (not (:mcp-error r)))
            (is (nil? (:excepting_handle r))
                "already withdrawn")))))))

(deftest unassert-handle-withdraws-a-rule
  (testing "a rule's handle is withdrawn, not reported already-withdrawn"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny glittery]
          (declare-fixture-predicates! fk shiny glittery)
          (let [ar (verbs/verb-assert {"sentence" (pr-str (list 'implies
                                                                (list shiny '?x)
                                                                (list glittery '?x)))
                                       "context"  "CxWell"})
                h  (:handle ar)
                r  (verbs/verb-unassert-handle {"sentex_handle" h})]
            (is (integer? h) (str "rule assert failed: " ar))
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (integer? (:excepting_handle r))
                "a believed rule is excepted, so the verb names the except")
            (is (not (v/believed? fk h 'CxWell))
                "the rule is withdrawn from its context")))))))

(deftest unassert-handle-errors-on-unknown-handle
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-unassert-handle {"sentex_handle" 999999999})]
        (is (:mcp-error r))
        (is (= "not_found" (:code r)))))))

;; ---- verb: query -----------------------------------------------------------

(deftest query-returns-bindings-for-a-match
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [dog Muffet]
        (declare-fixture-predicates! fk dog)
        (verbs/verb-assert {"sentence" (pr-str (list dog Muffet))
                            "context"  "CxWell"})
        (let [r (verbs/verb-query {"goal"    (pr-str (list dog '?x))
                                   "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (seq (:answers r)) "should have at least one answer")
          (let [ans (first (:answers r))]
            (is (= (pr-str Muffet) (get (:bindings ans) "?x"))
                "?x should bind to Muffet")))))))

(deftest query-returns-empty-for-no-match
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-query {"goal"    "(tmpNonsense99 ?x)"
                                 "context" "CxWell"})]
        (is (not (:mcp-error r)))
        (is (= [] (:answers r)))))))

(deftest query-ground-success-returns-empty-bindings
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                            "context"  "CxWell"})
        (let [r (verbs/verb-query {"goal"    (pr-str (list shiny Gold))
                                   "context" "CxWell"})]
          (is (not (:mcp-error r)))
          (is (= 1 (count (:answers r))))
          (is (= {} (:bindings (first (:answers r))))
              "ground success -> empty bindings"))))))

(deftest query-conjunctive-goal
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [dog likes Muffet Bacon]
        (declare-fixture-predicates! fk dog likes)
        (verbs/verb-assert {"sentence" (pr-str (list dog Muffet))
                            "context"  "CxWell"})
        (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon))
                            "context"  "CxWell"})
        (let [r (verbs/verb-query {"goal"    (pr-str [(list dog '?x) (list likes '?x '?y)])
                                   "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (seq (:answers r)) "should find the conjunctive answer")
          (let [ans (first (:answers r))]
            (is (= (pr-str Muffet) (get (:bindings ans) "?x")))
            (is (= (pr-str Bacon) (get (:bindings ans) "?y")))))))))

(deftest query-with-justification
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                            "context"  "CxWell"})
        (let [r (verbs/verb-query {"goal"                  (pr-str (list shiny Gold))
                                   "context"               "CxWell"
                                   "include_justification" true})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (seq (:answers r)))
          ;; Wire shape check: kind must be a string, depth_used must be an integer
          (let [j (:justification (first (:answers r)))]
            (when j
              (is (string? (:kind j))
                  "justification :kind must be a string on the wire")
              (is (integer? (:depth_used j))
                  "justification :depth_used must be an integer on the wire"))))))))

;; ---- verb: argue -----------------------------------------------------------

(deftest argue-returns-true-for-a-believed-fact
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                            "context"  "CxWell"})
        (let [r (verbs/verb-argue {"closed_goal" (pr-str (list shiny Gold))
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "true" (:verdict r))))))))

(deftest argue-returns-unknown-for-absent-fact
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-argue {"closed_goal" "(tmpNonsense99 TmpFoo99)"
                                 "context"     "CxWell"})]
        (is (not (:mcp-error r)))
        (is (= "unknown" (:verdict r)))))))

(deftest argue-false-for-a-defeated-claim-carries-the-defeat-against-it
  ;; v0.31.0: `argue` answers :false for a stored sentence a placed nogood defeats,
  ;; with the defeat reports under :defeated-by and no (not …) provable.  Ruled
  ;; 2026-10-31: no new wire field; the defeat rides `against_justification` as
  ;; kind "defeat".
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [ageOf Tom]
        (v/assert fk (list 'binary_predicate ageOf) 'CxWell {:strength :monotonic})
        (v/assert fk (list 'functional ageOf) 'CxWell {:strength :monotonic})
        (v/assert fk (list ageOf Tom 3) 'CxWell)
        (v/assert fk (list ageOf Tom 4) 'CxWell {:strength :monotonic})
        (let [r       (verbs/verb-argue {"closed_goal" (pr-str (list ageOf Tom 3))
                                         "context"     "CxWell"})
              against (:against_justification r)
              report  (first (:tree against))]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "false" (:verdict r)))
          (is (nil? (:for_justification r))
              "false verdict must not claim positive proof")
          (is (= "defeat" (:kind against))
              "a defeat is reported as kind \"defeat\", not as a provable denial")
          (is (integer? (:depth_used against)))
          (is (= (list ageOf Tom 3) (get-in report [:loser :sentence])))
          (is (= [(list ageOf Tom 4)] (mapv :sentence (:winners report))))
          (is (= ['CxWell] (:placed-in report)))
          (is (contains? (:loser report) :provenance)
              "the loser is a stored claim, so it carries the provenance key")
          (is (= r (edn/read-string (pr-str r)))
              "the envelope round-trips as EDN, the wire's text form"))))))

(deftest argue-conjunction-with-a-defeated-conjunct-nests-the-defeat
  ;; Ruled by the upgrade lead 2026-10-31: the conjunction's verdict is derived,
  ;; so its against side stays kind "inference"; the defeated conjunct's entry
  ;; inside that tree is the defeat, kind "defeat".
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [ageOf Tom shiny Gold]
        (declare-fixture-predicates! fk shiny)
        (verbs/verb-assert {"sentence" (pr-str (list shiny Gold)) "context" "CxWell"})
        (v/assert fk (list 'binary_predicate ageOf) 'CxWell {:strength :monotonic})
        (v/assert fk (list 'functional ageOf) 'CxWell {:strength :monotonic})
        (v/assert fk (list ageOf Tom 3) 'CxWell)
        (v/assert fk (list ageOf Tom 4) 'CxWell {:strength :monotonic})
        (let [r       (verbs/verb-argue
                       {"closed_goal" (pr-str (list 'and (list shiny Gold) (list ageOf Tom 3)))
                        "context"     "CxWell"})
              against (:against_justification r)
              entry   (first (:tree against))]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "false" (:verdict r)))
          (is (nil? (:for_justification r)))
          (is (= "inference" (:kind against))
              "the conjunction's against side is derived, kind \"inference\"")
          (is (= 1 (count (:tree against))))
          (is (= "defeat" (:kind entry))
              "the defeated conjunct's entry is the defeat")
          (is (= (list ageOf Tom 3) (get-in entry [:tree 0 :loser :sentence])))
          (is (= [(list ageOf Tom 4)] (mapv :sentence (get-in entry [:tree 0 :winners]))))
          (is (= r (edn/read-string (pr-str r)))))))))

(deftest argue-false-for-a-stated-denial-stays-an-inference
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [dull Lead]
        (declare-fixture-predicates! fk dull)
        (verbs/verb-assert {"sentence" (pr-str (list 'not (list dull Lead)))
                            "context"  "CxWell"})
        (let [r (verbs/verb-argue {"closed_goal" (pr-str (list dull Lead))
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "false" (:verdict r)))
          (is (nil? (:for_justification r)))
          (is (= "inference" (get-in r [:against_justification :kind]))))))))

(deftest argue-refuses-free-variables
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/verb-argue {"closed_goal" "(dog ?x)"
                                 "context"     "CxWell"})]
        (is (:mcp-error r))
        (is (= "invalid_sentence" (:code r)))
        (is (re-find #"free variable" (:error r)))))))

;; ---- cross-fork except test --------------------------------------------------

(deftest cross-fork-except-hides-only-in-the-fork
  (testing "except in a fork hides from that fork only; base and sibling are unaffected"
    (let [kb tu/*kb*]
      (tu/with-terms [shiny Gold Silver]
        ;; Step 1: assert P in base KB
        (let [h-base (v/assert kb (list shiny Gold) 'CxWell {:strength :default})]
          (is (v/ask? kb (list shiny Gold) 'CxWell) "P visible in base")

          ;; Step 2: fork the base
          (let [fork1 (v/fork kb)]
            (is (v/ask? fork1 (list shiny Gold) 'CxWell) "P visible in fork1 before except")

            ;; Step 3: in fork1, except the base handle — P hidden in fork1
            (v/assert fork1
                      (list 'except (eh/sentex-handle h-base))
                      'CxWell
                      {:strength :default})

            ;; Step 4: verify — P still visible in base, hidden in fork1
            (testing "P is hidden in fork1 but visible in base"
              (is (v/ask? kb (list shiny Gold) 'CxWell)
                  "P still visible in base after fork except")
              (is (not (v/ask? fork1 (list shiny Gold) 'CxWell))
                  "P hidden in fork1 after except"))

            ;; Step 5: create a sibling fork
            (let [fork2 (v/fork kb)]
              (testing "P is visible in sibling fork (isolated from fork1's except)"
                (is (v/ask? fork2 (list shiny Gold) 'CxWell)
                    "P visible in sibling fork — fork1's except does not leak"))

              ;; Step 6: in sibling fork, try to except a handle that only exists in fork1
              ;; The except of h-base should work (h-base is in the base, visible to fork2).
              ;; But let's first assert something only in fork1 and try to except it from fork2.
              (let [fork1-only-h (v/assert fork1 (list shiny Silver) 'CxWell {:strength :default})]
                ;; fork1-only-h exists only in fork1's overlay
                (testing "a sentex only in fork1 is not resolvable in fork2"
                  ;; The sentex is not visible in fork2 at all
                  (is (nil? (v/sentex fork2 fork1-only-h))
                      "fork2 cannot see fork1's private sentex")
                  ;; Attempting to except it — since v0.22.0 an except naming a handle
                  ;; no sentex is stored under is refused :unknown-handle (it used to
                  ;; store and hide whichever sentex received that handle next), so
                  ;; fork1's private handle cannot reach into fork2 even as an orphan.
                  (let [t (try (v/assert fork2
                                         (list 'except (eh/sentex-handle fork1-only-h))
                                         'CxWell
                                         {:strength :default})
                               nil
                               (catch clojure.lang.ExceptionInfo e
                                 (:type (ex-data e))))]
                    (is (= :unknown-handle t)
                        "an except of a handle fork2 does not hold is refused")
                    ;; The original P (from base) is still visible in fork2
                    (is (v/ask? fork2 (list shiny Gold) 'CxWell)
                        "P is still visible in fork2 — the refused except left nothing")))))))))))

(deftest verb-assert-does-not-leak-to-base
  (testing "a fact asserted through the verb in a fork is invisible to the base"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny Gold]
          (declare-fixture-predicates! fk shiny)
          (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                              "context"  "CxWell"})
          (is (v/ask? fk (list shiny Gold) 'CxWell)
              "visible in fork")
          (is (not (v/ask? tu/*kb* (list shiny Gold) 'CxWell))
              "invisible in base"))))))

(deftest verb-unassert-then-query-round-trip
  (testing "assert, query sees it, unassert, query doesn't"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny Gold]
          (declare-fixture-predicates! fk shiny)
          (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                              "context"  "CxWell"})
          (let [r1 (verbs/verb-query {"goal"    (pr-str (list shiny Gold))
                                      "context" "CxWell"})]
            (is (seq (:answers r1)) "visible after assert"))
          (verbs/verb-unassert {"sentence" (pr-str (list shiny Gold))
                                "context"  "CxWell"})
          (let [r2 (verbs/verb-query {"goal"    (pr-str (list shiny Gold))
                                      "context" "CxWell"})]
            (is (= [] (:answers r2)) "hidden after unassert")))))))

(deftest dispatch-routes-and-rejects-unknown
  (with-fork [fk]
    (with-verb-kb fk
      (let [r (verbs/dispatch "bogus_verb" {})]
        (is (:mcp-error r))
        (is (= "not_found" (:code r)))))))

;; ---- concurrent assert atomicity ------------------------------------------

(deftest concurrent-assert-atomic-already-there
  (testing "concurrent identical asserts: exactly one gets already_there=false"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny Gold]
          (declare-fixture-predicates! fk shiny)
          (let [n       32
                barrier (java.util.concurrent.CyclicBarrier. n)
                results (java.util.Collections/synchronizedList (java.util.ArrayList.))
                ;; Capture dynamic bindings — they don't propagate to child threads
                the-kb  verbs/*mcp-kb*
                sent    (pr-str (list shiny Gold))
                threads (mapv (fn [_]
                                (Thread.
                                 (fn []
                                   (.await barrier)
                                   (binding [verbs/*mcp-kb* the-kb]
                                     (let [r (verbs/verb-assert
                                              {"sentence" sent
                                               "context"  "CxWell"})]
                                       (.add results r))))))
                              (range n))]
            (doseq [^Thread t threads] (.start t))
            (doseq [^Thread t threads] (.join t 5000))
            (let [rs      (vec results)
                  handles (mapv :handle rs)
                  firsts  (filter #(false? (:already_there %)) rs)
                  dupes   (filter #(true? (:already_there %)) rs)]
              (is (every? some? handles) "every thread got a handle")
              (is (apply = handles) "all handles are the same")
              (is (= 1 (count firsts))
                  "exactly one thread returned already_there=false")
              (is (= (dec n) (count dupes))
                  "all others returned already_there=true"))))))))

;; ---- pre-ship gate: nullary identities ------------------------------------

(deftest query-nullary-and-returns-true
  (testing "(and) with no arguments is the identity element — trivially true"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-query {"goal"    "(and)"
                                   "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 1 (count (:answers r)))
              "nullary and produces exactly one answer")
          (is (= {} (:bindings (first (:answers r))))
              "the answer has empty bindings"))))))

(deftest query-nullary-and-returns-justification-when-requested
  (testing "(and) with include_justification=true produces a proof"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-query {"goal"                  "(and)"
                                   "context"               "CxWell"
                                   "include_justification" true})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 1 (count (:answers r))))
          (let [j (:justification (first (:answers r)))]
            (is (some? j) "nullary and should include justification when requested")
            (is (= "inference" (:kind j)))
            (is (= 0 (:depth_used j)))))))))

(deftest query-nullary-or-returns-false
  (testing "(or) with no arguments is the identity element — trivially false"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-query {"goal"    "(or)"
                                   "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= [] (:answers r))
              "nullary or produces zero answers (false)"))))))

(deftest argue-nullary-and-returns-true
  (testing "(and) as closed_goal to argue → verdict true"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue {"closed_goal" "(and)"
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "true" (:verdict r)))
          (is (some? (:for_justification r))
              "trivially true has a for-justification")
          (is (nil? (:against_justification r))))))))

(deftest argue-nullary-or-returns-false
  (testing "(or) as closed_goal to argue → verdict false"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue {"closed_goal" "(or)"
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "false" (:verdict r)))
          (is (nil? (:for_justification r)))
          (is (some? (:against_justification r))
              "trivially false has an against-justification"))))))

;; ---- pre-ship gate: or-refusal --------------------------------------------

(deftest query-refuses-non-empty-or
  (testing "(or G1 G2) is explicitly refused — disjunction not supported"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-query {"goal"    "(or (dog Muffet) (cat Tweety))"
                                   "context" "CxWell"})]
          (is (:mcp-error r))
          (is (= "invalid_sentence" (:code r)))
          (is (re-find #"not supported" (:error r))))))))

(deftest argue-refuses-non-empty-or
  (testing "(or G1 G2) as closed_goal to argue is refused"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue {"closed_goal" "(or (dog Muffet) (cat Tweety))"
                                   "context"     "CxWell"})]
          (is (:mcp-error r))
          (is (= "invalid_sentence" (:code r))))))))

;; ---- pre-ship gate: and-normalization -------------------------------------

(deftest query-and-normalization
  (testing "(and G1 G2) is normalized to a conjunctive vector [G1 G2]"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [dog likes Muffet Bacon]
          (declare-fixture-predicates! fk dog likes)
          (verbs/verb-assert {"sentence" (pr-str (list dog Muffet))
                              "context"  "CxWell"})
          (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon))
                              "context"  "CxWell"})
          ;; query via (and ...) should produce the same results as a vector
          (let [r-and (verbs/verb-query {"goal"    (pr-str (list 'and (list dog '?x) (list likes '?x '?y)))
                                         "context" "CxWell"})
                r-vec (verbs/verb-query {"goal"    (pr-str [(list dog '?x) (list likes '?x '?y)])
                                         "context" "CxWell"})]
            (is (not (:mcp-error r-and)) (str "and-form error: " r-and))
            (is (not (:mcp-error r-vec)) (str "vec-form error: " r-vec))
            (is (= (count (:answers r-and)) (count (:answers r-vec)))
                "(and ...) and [...] produce the same number of answers")
            (when (seq (:answers r-and))
              (is (= (:bindings (first (:answers r-and)))
                     (:bindings (first (:answers r-vec))))
                  "bindings match between and-form and vector-form"))))))))

;; ---- pre-ship gate: argue refuses conjunction -----------------------------

(deftest argue-closed-conjunction-unknown
  (testing "conjunction of absent facts returns unknown"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue {"closed_goal" "(and (tmpNoSuchPred99 TmpFoo99) (tmpNoSuchPred98 TmpBar98))"
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "unknown" (:verdict r))))))))

(deftest argue-closed-conjunction-contradiction-and-unknown-is-false
  (testing "Belnap: contradiction ∧ unknown = false, not contradiction"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny dull Gold]
          (declare-fixture-predicates! fk shiny dull)
          ;; assert both shiny and dull (its negation) to get contradiction
          (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                              "context"  "CxWell"})
          (verbs/verb-assert {"sentence" (pr-str (list 'not (list shiny Gold)))
                              "context"  "CxWell"})
          ;; (and (shiny Gold) (tmpAbsent99 TmpWhiskerton99)) — one contradictory, one unknown
          ;; Belnap: (T,T) ∧ (F,F) = (T∧F, T∨F) = (F,T) = false
          (let [r (verbs/verb-argue
                   {"closed_goal" (pr-str (list 'and (list shiny Gold)
                                                '(tmpAbsent99 TmpWhiskerton99)))
                    "context"     "CxWell"})]
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (= "false" (:verdict r))
                "contradiction ∧ unknown should be false under Belnap")
            (is (nil? (:for_justification r))
                "false verdict must not claim positive proof")
            (is (some? (:against_justification r))
                "false verdict must have against proof")))))))

(deftest argue-closed-conjunction-true
  (testing "conjunction of believed facts returns true with correct envelope"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny big Gold]
          (declare-fixture-predicates! fk shiny big)
          (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                              "context"  "CxWell"})
          (verbs/verb-assert {"sentence" (pr-str (list big Gold))
                              "context"  "CxWell"})
          (let [r (verbs/verb-argue {"closed_goal" (pr-str (list 'and (list shiny Gold) (list big Gold)))
                                     "context"     "CxWell"})]
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (= "true" (:verdict r)))
            (is (some? (:for_justification r))
                "true verdict must have for proof")
            (is (nil? (:against_justification r))
                "true verdict must not have against proof")))))))

(deftest argue-closed-conjunction-unknown-envelope
  (testing "unknown ∧ unknown = unknown, both proof sides null"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue
                 {"closed_goal" "(and (tmpNoSuchPred97 TmpWhiskerton97) (tmpNoSuchPred96 TmpWhiskerton96))"
                  "context"     "CxWell"})]
          (is (= "unknown" (:verdict r)))
          (is (nil? (:for_justification r))
              "unknown verdict must have nil for proof")
          (is (nil? (:against_justification r))
              "unknown verdict must have nil against proof"))))))

(deftest argue-closed-conjunction-true-and-false-is-false
  (testing "true ∧ false = false, with only against proof"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [shiny Gold]
          (declare-fixture-predicates! fk shiny)
          ;; assert (shiny Gold) -> true
          (verbs/verb-assert {"sentence" (pr-str (list shiny Gold))
                              "context"  "CxWell"})
          ;; assert the negation to make tmp_false_pred99 explicitly false
          (declare-fixture-predicates! fk 'tmp_false_pred99)
          (verbs/verb-assert {"sentence" "(not (tmp_false_pred99 TmpWhiskerton99))"
                              "context"  "CxWell"})
          (let [r (verbs/verb-argue
                   {"closed_goal" (pr-str (list 'and (list shiny Gold)
                                                '(tmp_false_pred99 TmpWhiskerton99)))
                    "context"     "CxWell"})]
            (is (= "false" (:verdict r))
                "true ∧ false should be false")
            (is (nil? (:for_justification r))
                "false verdict must not claim positive proof")
            (is (some? (:against_justification r))
                "false verdict must have against proof")))))))

(deftest argue-empty-vector-matches-nullary-and
  (testing "[] behaves the same as (and) — trivially true with proof"
    (with-fork [fk]
      (with-verb-kb fk
        (let [r (verbs/verb-argue {"closed_goal" "[]"
                                   "context"     "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= "true" (:verdict r)))
          (is (some? (:for_justification r))
              "empty vector should have for proof like nullary (and)")
          (is (nil? (:against_justification r))))))))

;; ---- verb: matching ---------------------------------------------------------
;; Stored, believed facts matching a pattern — literal match, no inference.
;; `asserted` reports whether each row is a premise (asserted in its own
;; right) or derived (stored by a forward rule).

(defn- seed-matching-facts!
  "Two asserted `likes` facts and a forward rule that stores a third, derived
  one. Returns the derived sentence."
  [fk likes fond Muffet Rex Whiskerton Bacon]
  (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon)) "context" "CxWell"})
  (verbs/verb-assert {"sentence" (pr-str (list likes Rex Bacon)) "context" "CxWell"})
  (v/assert-rule fk [(list fond '?x '?y)] (list likes '?x '?y) 'CxWell
                 {:direction :forward})
  (verbs/verb-assert {"sentence" (pr-str (list fond Whiskerton Bacon)) "context" "CxWell"})
  (list likes Whiskerton Bacon))

(defn- rows-by-sentence [r]
  (into {} (map (juxt :sentence identity)) (:rows r)))

(deftest matching-returns-asserted-and-derived-with-flags
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [likes fond Muffet Rex Whiskerton Bacon]
        (declare-fixture-predicates! fk likes fond)
        (let [derived (seed-matching-facts! fk likes fond Muffet Rex Whiskerton Bacon)
              r       (verbs/verb-matching {"pattern" (pr-str (list likes '?x Bacon))
                                            "context" "CxWell"})
              by-s    (rows-by-sentence r)]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 3 (:count r)) (str "two asserted + one derived: " r))
          (is (= 3 (count (:rows r))))
          (is (false? (:truncated r)))
          (is (= #{(pr-str (list likes Muffet Bacon))
                   (pr-str (list likes Rex Bacon))
                   (pr-str derived)}
                 (set (keys by-s)))
              "sentences are EDN strings on the wire")
          (is (true? (:asserted (get by-s (pr-str (list likes Muffet Bacon))))))
          (is (true? (:asserted (get by-s (pr-str (list likes Rex Bacon))))))
          (is (false? (:asserted (get by-s (pr-str derived))))
              "the rule-derived stored fact is not a premise")
          (doseq [row (:rows r)]
            (is (integer? (:handle row)) "each row names its sentex handle")
            (is (= "CxWell" (:context row)) "context is an EDN string")
            (is (= (edn/read-string (:sentence row))
                   (:sentence (v/sentex fk (:handle row))))
                "the handle is the stored sentex the row reports")))))))

(deftest matching-asserted-only-drops-derived
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [likes fond Muffet Rex Whiskerton Bacon]
        (declare-fixture-predicates! fk likes fond)
        (let [derived (seed-matching-facts! fk likes fond Muffet Rex Whiskerton Bacon)
              r       (verbs/verb-matching {"pattern"       (pr-str (list likes '?x Bacon))
                                            "context"       "CxWell"
                                            "asserted_only" true})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 2 (:count r)))
          (is (every? true? (map :asserted (:rows r))))
          (is (not (contains? (rows-by-sentence r) (pr-str derived)))
              "asserted_only filters out the derived fact"))))))

(deftest matching-does-not-infer
  (testing "a backward rule's conclusion is not stored, so matching does not report it"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [likes adores Muffet Bacon]
          (declare-fixture-predicates! fk likes adores)
          (v/assert-rule fk [(list adores '?x '?y)] (list likes '?x '?y) 'CxWell
                         {:direction :backward})
          (verbs/verb-assert {"sentence" (pr-str (list adores Muffet Bacon))
                              "context"  "CxWell"})
          (is (seq (:answers (verbs/verb-query {"goal"    (pr-str (list likes '?x Bacon))
                                                "context" "CxWell"})))
              "query infers the conclusion")
          (let [r (verbs/verb-matching {"pattern" (pr-str (list likes '?x Bacon))
                                        "context" "CxWell"})]
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (= 0 (:count r)) "matching reports stored facts only")
            (is (= [] (:rows r)))))))))

(deftest matching-limit-truncates
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [likes fond Muffet Rex Whiskerton Bacon]
        (declare-fixture-predicates! fk likes fond)
        (seed-matching-facts! fk likes fond Muffet Rex Whiskerton Bacon)
        (let [pattern (pr-str (list likes '?x Bacon))
              r2      (verbs/verb-matching {"pattern" pattern "context" "CxWell" "limit" 2})
              r3      (verbs/verb-matching {"pattern" pattern "context" "CxWell" "limit" 3})]
          (is (not (:mcp-error r2)) (str "unexpected error: " r2))
          (is (= 2 (:count r2)))
          (is (= 2 (count (:rows r2))))
          (is (true? (:truncated r2)) "more matches exist beyond the limit")
          (is (= 3 (:count r3)))
          (is (false? (:truncated r3)) "a limit exactly met is not truncation"))))))

(deftest matching-truncation-is-judged-before-asserted-only-filters
  (testing "truncated reports matches beyond the examined limit, even when
            asserted_only leaves fewer rows than limit"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [likes fond Muffet Rex Whiskerton Pip Bacon]
          (declare-fixture-predicates! fk likes fond)
          ;; one premise, three forward-derived facts: whatever order the engine
          ;; walks them in, examining 2 candidates holds at most one premise
          (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon)) "context" "CxWell"})
          (v/assert-rule fk [(list fond '?x '?y)] (list likes '?x '?y) 'CxWell
                         {:direction :forward})
          (doseq [who [Rex Whiskerton Pip]]
            (verbs/verb-assert {"sentence" (pr-str (list fond who Bacon)) "context" "CxWell"}))
          (let [r (verbs/verb-matching {"pattern"       (pr-str (list likes '?x Bacon))
                                        "context"       "CxWell"
                                        "asserted_only" true
                                        "limit"         2})]
            (is (not (:mcp-error r)) (str "unexpected error: " r))
            (is (< (:count r) 2) "the filter leaves fewer rows than limit")
            (is (every? true? (map :asserted (:rows r))))
            (is (true? (:truncated r))
                "four stored matches, examining only two: the scan was cut short")))))))

(deftest matching-refuses-bad-limit-and-flag
  (with-fork [fk]
    (with-verb-kb fk
      (doseq [bad [0 -1 1001 "10" 2.5]]
        (let [r (verbs/verb-matching {"pattern" "(likes ?x Bacon)"
                                      "context" "CxWell"
                                      "limit"   bad})]
          (is (= "invalid_arguments" (:code r)) (str "limit " (pr-str bad) ": " r))
          (is (re-find #"limit" (str (:error r))))))
      (let [r (verbs/verb-matching {"pattern"       "(likes ?x Bacon)"
                                    "context"       "CxWell"
                                    "asserted_only" "yes"})]
        (is (= "invalid_arguments" (:code r)))
        (is (re-find #"asserted_only" (str (:error r))))))))

(deftest matching-refuses-conjunctive-patterns
  (with-fork [fk]
    (with-verb-kb fk
      (doseq [p ["[(likes ?x Bacon) (dog ?x)]"
                 "(and (likes ?x Bacon) (dog ?x))"
                 "(and (likes ?x Bacon))"
                 "(or (likes ?x Bacon) (dog ?x))"
                 "[]"]]
        (let [r (verbs/verb-matching {"pattern" p "context" "CxWell"})]
          (is (:mcp-error r) (str p " must be refused"))
          (is (= "invalid_sentence" (:code r)))
          (is (re-find #"single literal" (str (:error r))) (str p ": " r))))
      (doseq [p ["Muffet" "42" "((("]]
        (let [r (verbs/verb-matching {"pattern" p "context" "CxWell"})]
          (is (= "invalid_sentence" (:code r)) (str p ": " r)))))))

(deftest matching-context-handling-follows-query
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [likes Muffet Bacon]
        (declare-fixture-predicates! fk likes)
        (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon))
                            "context"  "CxWell"})
        (testing "an unknown but well-formed context answers empty, like query"
          (let [goal (pr-str (list likes '?x Bacon))
                q    (verbs/verb-query {"goal" goal "context" "CxNoSuchPlace99"})
                m    (verbs/verb-matching {"pattern" goal "context" "CxNoSuchPlace99"})]
            (is (= [] (:answers q)))
            (is (not (:mcp-error m)) (str "unexpected error: " m))
            (is (= {:rows [] :count 0 :truncated false} m))))
        (testing "a malformed context is refused with query's code"
          (let [q (verbs/verb-query {"goal" "(likes ?x Bacon)" "context" "Foo"})
                m (verbs/verb-matching {"pattern" "(likes ?x Bacon)" "context" "Foo"})]
            (is (= "invalid_context" (:code q)))
            (is (= (:code q) (:code m)))))))))

(deftest matching-reading-mode-contexts-follow-query
  (testing "CxEverything, CxInference and CxNothing: matching refuses or sees
            exactly what query does"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [likes Muffet Bacon]
          (declare-fixture-predicates! fk likes)
          (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon))
                              "context"  "CxWell"})
          (let [goal (pr-str (list likes '?x Bacon))]
            (doseq [[cx visible?] [["CxEverything" true]
                                   ["CxInference"  true]
                                   ["CxNothing"    false]]]
              (let [q   (verbs/verb-query {"goal" goal "context" cx})
                    m   (verbs/verb-matching {"pattern" goal "context" cx})
                    q-x (set (map #(get (:bindings %) "?x") (:answers q)))
                    m-x (set (map #(pr-str (second (edn/read-string (:sentence %))))
                                  (:rows m)))]
                (is (= (:code q) (:code m)) (str cx ": same refusal, or neither refuses"))
                (is (not (:mcp-error m)) (str cx ": unexpected error: " m))
                (is (= q-x m-x) (str cx ": matching sees what query sees"))
                (is (= (if visible? #{(pr-str Muffet)} #{}) m-x)
                    (str cx (if visible? " reads CxWell's fact" " reads nothing")))
                (is (every? #(= "CxWell" (:context %)) (:rows m))
                    (str cx ": a row names the context it is stored in"))))))))))

(deftest matching-sees-only-its-own-fork
  (tu/with-terms [likes Muffet Rex Bacon]
    (declare-fixture-predicates! tu/*kb* likes)
    (let [fk1 (v/fork tu/*kb*)
          fk2 (v/fork tu/*kb*)
          pat (pr-str (list likes '?x Bacon))]
      (with-verb-kb fk1
        (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon)) "context" "CxWell"}))
      (with-verb-kb fk2
        (verbs/verb-assert {"sentence" (pr-str (list likes Rex Bacon)) "context" "CxWell"}))
      (is (= [(pr-str (list likes Muffet Bacon))]
             (mapv :sentence (:rows (with-verb-kb fk1
                                      (verbs/verb-matching {"pattern" pat "context" "CxWell"})))))
          "fork 1 sees only its own fact")
      (is (= [(pr-str (list likes Rex Bacon))]
             (mapv :sentence (:rows (with-verb-kb fk2
                                      (verbs/verb-matching {"pattern" pat "context" "CxWell"})))))
          "fork 2 sees only its own fact")
      (is (= "unauthorized"
             (:code (verbs/verb-matching {"pattern" pat "context" "CxWell"})))
          "no bound fork KB, no read"))))

(deftest matching-is-dispatched-and-listed
  (is (contains? verbs/verbs "matching"))
  (is (= #{"assert" "unassert" "unassert_handle" "query" "argue" "matching"}
         (set (keys verbs/verbs))
         (set (map #(get % "name") verbs/schemas)))
      "the dispatch table and the tool list name the same six verbs")
  (let [s (some #(when (= "matching" (get % "name")) %) verbs/schemas)]
    (is (= ["pattern" "context"] (get-in s ["inputSchema" "required"])))
    (is (= #{"pattern" "context" "asserted_only" "limit"}
           (set (keys (get-in s ["inputSchema" "properties"])))))
    (is (re-find #"no inference" (get s "description"))))
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [likes Muffet Bacon]
        (declare-fixture-predicates! fk likes)
        (verbs/verb-assert {"sentence" (pr-str (list likes Muffet Bacon)) "context" "CxWell"})
        (let [r (verbs/dispatch "matching" {"pattern" (pr-str (list likes '?x Bacon))
                                            "context" "CxWell"})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 1 (:count r))))
        (is (= "invalid_arguments"
               (:code (verbs/dispatch "matching" {"pattern" "(likes ?x Bacon)"
                                                  "context" "CxWell"
                                                  "include_justification" true})))
            "matching fails closed on undeclared arguments")))))

;; ---- wire surface naming ----------------------------------------------------
;; The seat-owned wire surface is snake_case. "Seat-owned" means keys this
;; overlay names: verb result envelopes, error payloads, and tool schemas.
;; Justification :tree subtrees are the engine's own grammar and are exempt —
;; renaming them would misrepresent the engine's structures.

(defn- kebab-keys
  "Walk a structure and return every seat-owned map key containing a hyphen.
  Skips the contents of :tree (engine grammar) and the internal :mcp-error
  marker (stripped by the dispatcher before the wire)."
  [x]
  (cond
    (map? x)
    (mapcat (fn [[k v]]
              (let [kn (cond (keyword? k) (name k)
                             (string? k)  k
                             :else        (str k))]
                (concat (when (and (re-find #"-" kn)
                                   (not= k :mcp-error))
                          [kn])
                        (when-not (= k :tree)
                          (kebab-keys v)))))
            x)
    (sequential? x) (mapcat kebab-keys x)
    :else nil))

(deftest wire-surface-keys-are-snake-case
  (testing "the detector itself discriminates"
    (is (= ["bad-key"] (kebab-keys {:bad-key 1}))
        "helper must catch a kebab key")
    (is (= ["nested-bad"] (kebab-keys {:ok {:nested-bad 2}}))
        "helper must catch nested kebab keys")
    (is (empty? (kebab-keys {:tree {:engine-key 1}}))
        "engine grammar under :tree is exempt"))
  (testing "tool schemas carry no kebab keys"
    (is (empty? (kebab-keys verbs/schemas))))
  (testing "live results from all six verbs carry no kebab keys"
    (with-fork [fk]
      (with-verb-kb fk
        (tu/with-terms [wirePred WireWhiskerton]
          (declare-fixture-predicates! fk wirePred)
          (let [s        (pr-str (list wirePred WireWhiskerton))
                asserted (verbs/verb-assert {"sentence" s "context" "CxWell"})
                queried  (verbs/verb-query {"goal" s "context" "CxWell"
                                            "include_justification" true})
                argued   (verbs/verb-argue {"closed_goal" s "context" "CxWell"})
                matched  (verbs/verb-matching {"pattern" s "context" "CxWell"})
                unassert (verbs/verb-unassert {"sentence" s "context" "CxWell"})
                by-hand  (verbs/verb-unassert-handle
                          {"sentex_handle" (:handle asserted)})
                errored  (verbs/verb-assert {"sentence" "(((" "context" "CxWell"})]
            (doseq [[label r] [["assert" asserted] ["query" queried]
                               ["argue" argued] ["matching" matched]
                               ["unassert" unassert]
                               ["unassert_handle" by-hand] ["error" errored]]]
              (is (empty? (kebab-keys r))
                  (str label " result leaks kebab keys: "
                       (pr-str (kebab-keys r)))))))))))

(deftest tool-schemas-use-mcp-inputSchema-field
  (testing "each verb schema carries the MCP-standard inputSchema key, not input_schema"
    (doseq [s verbs/schemas]
      (is (contains? s "inputSchema")
          (str (get s "name") " schema is missing the MCP inputSchema key"))
      (is (not (contains? s "input_schema"))
          (str (get s "name")
               " schema still uses input_schema; MCP clients require inputSchema")))))

;; ---- unknown arguments fail closed ------------------------------------------
;; "additionalProperties": false in the published schemas is a claim to
;; clients; dispatch enforces it server-side. Silently ignoring an argument
;; lets a caller believe an option took effect when it did not.

(deftest unknown-arguments-fail-closed
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [failPred FailWhiskerton]
        (declare-fixture-predicates! fk failPred)
        (let [s (pr-str (list failPred FailWhiskerton))]
          (testing "an undeclared argument is rejected, not ignored"
            (let [r (verbs/dispatch "assert" {"sentence" s
                                              "context"  "CxWell"
                                              "strength" "monotonic"})]
              (is (:mcp-error r))
              (is (= "invalid_arguments" (:code r)))
              (is (str/includes? (:error r) "strength")
                  "the offending key must be named")))
          (testing "a caller cannot smuggle creator attribution into assert"
            (let [r (verbs/dispatch "assert" {"sentence" s
                                              "context" "CxWell"
                                              "creator" "someone-else"})]
              (is (:mcp-error r))
              (is (= "invalid_arguments" (:code r)))
              (is (str/includes? (:error r) "creator")
                  "creator is server-recorded, never a client argument")))
          (testing "a typo'd argument is rejected, not treated as absent"
            (let [r (verbs/dispatch "query" {"goal" s
                                             "context" "CxWell"
                                             "include_justifications" true})]
              (is (= "invalid_arguments" (:code r)))))
          (testing "declared arguments still dispatch"
            (let [r (verbs/dispatch "assert" {"sentence" s "context" "CxWell"})]
              (is (not (:mcp-error r)) (str "unexpected error: " r))
              (is (integer? (:handle r)))))
          (testing "unknown verb still reports not_found"
            (let [r (verbs/dispatch "promote" {"sentence" s})]
              (is (= "not_found" (:code r))))))))))

;; ---- justification provenance -----------------------------------------------

(deftest argue-tree-nodes-carry-server-recorded-provenance
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [glows Lantern]
        (declare-fixture-predicates! fk glows)
        (binding [v/*creator* "whiskerton"
                  v/*clock*   (constantly 0)]
          (verbs/verb-assert {"sentence" (pr-str (list glows Lantern))
                              "context"  "CxWell"}))
        (let [r    (verbs/verb-argue {"closed_goal" (pr-str (list glows Lantern))
                                      "context"     "CxWell"})
              ;; v0.30.0: argue's :for-why is why's graph, {:root h :nodes {h node}}
              tree (get-in r [:for_justification :tree])
              node ((:nodes tree) (:root tree))]
          (is (= "true" (:verdict r)))
          (is (integer? (:handle node)))
          (is (= {:creator "whiskerton" :asserted_at "1970-01-01T00:00:00Z"}
                 (:provenance node))
              "stored-claim node carries immutable server-recorded provenance")
          ;; wrapper contract unchanged for clients that ignore the new field
          (is (= "inference" (get-in r [:for_justification :kind])))
          (is (contains? node :believed?)))))))

(deftest provenance-is-first-writer-wins-and-null-safe
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [hums Reactor sparks Coil]
        (declare-fixture-predicates! fk hums sparks)
        ;; first writer's provenance is recorded; a re-assert by someone else keeps the original
        (binding [v/*creator* "whiskerton" v/*clock* (constantly 0)]
          (verbs/verb-assert {"sentence" (pr-str (list hums Reactor))
                              "context"  "CxWell"}))
        (binding [v/*creator* "muffet" v/*clock* (constantly 946684800000)]
          (verbs/verb-assert {"sentence" (pr-str (list hums Reactor))
                              "context"  "CxWell"}))
        (let [r    (verbs/verb-argue {"closed_goal" (pr-str (list hums Reactor))
                                      "context"     "CxWell"})
              ;; v0.30.0: argue's :for-why is why's graph, {:root h :nodes {h node}}
              tree (get-in r [:for_justification :tree])
              node ((:nodes tree) (:root tree))]
          (is (= "whiskerton" (get-in node [:provenance :creator]))
              "re-assert keeps the original creator record")
          (is (= "1970-01-01T00:00:00Z" (get-in node [:provenance :asserted_at]))
              "re-assert keeps the original asserted_at"))
        ;; no bound creator: the recorded provenance has nil creator, never the reader
        (binding [v/*clock* (constantly 0)]
          (verbs/verb-assert {"sentence" (pr-str (list sparks Coil))
                              "context"  "CxWell"}))
        (let [r    (verbs/verb-argue {"closed_goal" (pr-str (list sparks Coil))
                                      "context"     "CxWell"})
              ;; v0.30.0: argue's :for-why is why's graph, {:root h :nodes {h node}}
              tree (get-in r [:for_justification :tree])
              node ((:nodes tree) (:root tree))]
          (is (contains? node :provenance)
              "stored claim always carries the provenance key")
          (is (nil? (get-in node [:provenance :creator]))
              "absent creator renders null — never synthesized from the reader"))))))

(deftest query-proof-leaves-carry-provenance-for-stored-facts
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [purrs Muffet]
        (declare-fixture-predicates! fk purrs)
        (binding [v/*creator* "whiskerton" v/*clock* (constantly 0)]
          (verbs/verb-assert {"sentence" (pr-str (list purrs Muffet))
                              "context"  "CxWell"}))
        (let [r    (verbs/verb-query {"goal"                  (pr-str (list purrs Muffet))
                                      "context"               "CxWell"
                                      "include_justification" true})
              leaf (-> r :answers first :justification :tree first)]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= :leaf (:via leaf)) (str "expected a proof leaf, got " leaf))
          (is (integer? (:handle leaf))
              "ground stored-fact leaf binds to its sentex handle")
          (is (= {:creator "whiskerton" :asserted_at "1970-01-01T00:00:00Z"}
                 (:provenance leaf))
              "query reader surface exposes the same server-recorded provenance"))))))

(deftest variable-query-proof-leaves-carry-per-answer-provenance
  ;; Regression: `query (purrs ?x)` over two stored claims returned
  ;; two independently bound answers whose justification trees both read
  ;; {:goal (purrs ?var0) :via :leaf} — no handle, no provenance. Each
  ;; answer's proof must ground through that answer's own bindings, and the
  ;; two leaves must each resolve to their own distinct stored sentex.
  (with-fork [fk]
    (with-verb-kb fk
      (tu/with-terms [purrs Muffet Fido]
        (declare-fixture-predicates! fk purrs)
        (binding [v/*creator* "whiskerton" v/*clock* (constantly 0)]
          (verbs/verb-assert {"sentence" (pr-str (list purrs Muffet))
                              "context"  "CxWell"}))
        (binding [v/*creator* "brindlewick" v/*clock* (constantly 60)]
          (verbs/verb-assert {"sentence" (pr-str (list purrs Fido))
                              "context"  "CxWell"}))
        (let [r (verbs/verb-query {"goal"                  (pr-str (list purrs '?x))
                                   "context"               "CxWell"
                                   "include_justification" true})]
          (is (not (:mcp-error r)) (str "unexpected error: " r))
          (is (= 2 (count (:answers r))) (str "expected two answers: " r))
          (let [by-who (into {}
                             (map (fn [a]
                                    [(get (:bindings a) "?x")
                                     (-> a :justification :tree first)]))
                             (:answers r))
                m-leaf (get by-who (pr-str Muffet))
                f-leaf (get by-who (pr-str Fido))]
            (doseq [[who leaf expected] [[Muffet m-leaf "whiskerton"] [Fido f-leaf "brindlewick"]]]
              (is (= :leaf (:via leaf)) (str who " leaf: " leaf))
              (is (integer? (:handle leaf))
                  (str who " leaf binds to its own stored sentex"))
              (is (= expected (get-in leaf [:provenance :creator]))
                  (str who " provenance follows this answer's binding, never a guess")))
            (is (not= (:handle m-leaf) (:handle f-leaf))
                "the two answers resolve to two distinct stored claims")))))))
