;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.stub-test
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.agents.llm.protocol :as proto]
            [vaelii.agents.llm.stub :as stub]))

(deftest the-shorthands-expand-to-response-maps
  (let [p (stub/provider {:script ["plain answer"
                                   {:tool "kb_terms" :input {"q" "dog"} :prose "looking"}
                                   {:stop-reason "refusal" :content []}]})]
    (testing "a string is a text answer"
      (is (= "plain answer" (proto/text (proto/complete p {})))))
    (testing "a :tool entry is a tool-use turn, with its prose ahead of the call"
      (let [r (proto/complete p {})]
        (is (= "tool_use" (:stop-reason r)))
        (is (= "looking" (proto/text r)))
        (is (= [{:type :tool-use :id "stub_tool_1" :name "kb_terms" :input {"q" "dog"}}]
               (proto/tool-uses r)))))
    (testing "a full response map passes through"
      (is (proto/refused? (proto/complete p {}))))))

(deftest a-caller-expander-sees-each-entry-first
  (let [own (fn [entry _i] (when (contains? entry :shout)
                             (stub/text-response (str (:shout entry) "!"))))
        p   (stub/provider {:script [{:shout "hi"} "as written"] :expand own})]
    (is (= "hi!" (proto/text (proto/complete p {}))))
    (is (= "as written" (proto/text (proto/complete p {})))
        "an entry the expander answers nil to goes to the stub's own shorthands")))

(deftest past-the-script-every-turn-answers-the-default
  (testing "the default default is an empty end-of-turn answer"
    (let [p (stub/provider)]
      (is (= stub/empty-response (proto/complete p {})))))
  (testing "a :default replaces it"
    (let [p (stub/provider {:script ["one"] :default (stub/text-response "after")})]
      (is (= "one" (proto/text (proto/complete p {}))))
      (is (= "after" (proto/text (proto/complete p {}))))
      (is (= "after" (proto/text (proto/complete p {})))))))

(deftest a-stream-returns-what-complete-would-and-logs-the-request
  (let [script ["two words"]
        events (atom [])
        p      (stub/provider {:script script})
        r      (proto/stream p {:messages [{:role "user" :content "q"}]} #(swap! events conj %))]
    (is (= (proto/complete (stub/provider {:script script}) {}) r))
    (is (= ["two " "words"] (keep :text @events)))
    (is (= :done (:type (last @events))))
    (is (= "q" (stub/last-user-text p)))
    (is (= 1 (count (stub/requests p))))))
