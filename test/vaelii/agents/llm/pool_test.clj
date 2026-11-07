;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.pool-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [are deftest is testing]]
            [vaelii.agents.llm.pool :as llm]
            [vaelii.agents.llm.protocol :as proto]))

(deftest a-leading-yes-or-no-is-a-vote-and-nothing-else-is
  (are [answer vote] (= vote (llm/read-yes-no answer))
    "yes"                                      :yes
    "no"                                       :no
    "Yes."                                     :yes
    "  YES\n"                                  :yes
    "No, a penguin is not a mammal."           :no
    "**yes**"                                  :yes
    "It depends on context, but broadly yes"   nil
    "I cannot determine this."                 nil
    ""                                         nil
    nil                                        nil
    "nobody knows"                             nil
    "yesterday it was true"                    nil))

(defn- scripted [& answers]
  (llm/stub-pool {:script (vec answers)}))

(deftest the-verdict-compares-the-counted-votes
  (are [answers verdict] (= verdict (:verdict (llm/vote (apply scripted answers) "q")))
    ["yes" "yes" "no"]                 :yes
    ["no" "no" "yes"]                  :no
    ["yes" "yes" "yes"]                :yes
    ["yes" "no" "maybe"]               :abstain
    ["hmm" "unclear" "it depends"]     :abstain
    ["hmm" "yes" "unclear"]            :yes))

(deftest turns-that-produced-no-opinion-are-not-votes
  (let [r (llm/vote (scripted "I cannot answer" "I cannot answer" "I cannot answer") "q")]
    (is (= :abstain (:verdict r)))
    (is (empty? (:votes r))))
  (testing "an unscripted stub answers empty text, which is not a vote either"
    (is (= :abstain (:verdict (llm/vote (llm/stub-pool) "q"))))))

(deftest a-vote-reports-what-it-spent
  (let [r (llm/vote (scripted "yes" "yes" "yes") "q")]
    (is (= 3 (:calls r)))
    (is (= {:yes 3} (:tally r)))
    (is (zero? (:failed r))))
  (is (= 1 (:calls (llm/vote (scripted "yes") "q" {:votes 1})))))

(deftest host-specs-read-as-written
  (is (= [{:host "gpu1" :tokens 4} {:host "localhost" :tokens 2}]
         (llm/parse-hosts "gpu1*4,localhost")))
  (is (= [{:host "a" :tokens 2}] (llm/parse-hosts " a ")))
  (testing "a port is part of the host, not a token count"
    (is (= [{:host "gpu1:11434" :tokens 2} {:host "http://gpu2:8080" :tokens 3}]
           (llm/parse-hosts "gpu1:11434,http://gpu2:8080*3"))))
  (testing "a blank or nil spec is no hosts"
    (is (= [] (llm/parse-hosts "")))
    (is (= [] (llm/parse-hosts nil))))
  (testing "an entry that is not host[*n] is refused, not read as a default"
    (doseq [spec ["gpu1*0" "gpu1*x" "*4" "gpu1*4*2" "gpu1*99999999999999999999"]]
      (is (= :llm-bad-hosts
             (try (llm/parse-hosts spec) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
          spec))))

(deftest a-pool-over-no-hosts-is-refused
  (is (= :llm-bad-hosts
         (try (llm/pool []) nil
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))

(deftest a-pool-holds-one-provider-per-token
  ;; Building an Ollama provider opens no socket, so this reaches no host.
  (let [p (llm/pool [{:host "a" :tokens 3} {:host "b"}])]
    (is (= 5 (:capacity p)))
    (is (zero? (llm/outstanding p)))))

(deftest a-provider-comes-back-even-when-the-call-throws
  (let [p (llm/stub-pool {:script ["yes" "yes" "yes"]})]
    (is (zero? (llm/outstanding p)))
    (is (thrown? Exception (llm/with-provider p (fn [_] (throw (ex-info "boom" {}))))))
    (is (zero? (llm/outstanding p)) "the token is back")
    (is (= :yes (:verdict (llm/vote p "q" {:votes 1})))
        "and the pool still works afterwards")
    (llm/vote p "q" {:votes 2})
    (is (zero? (llm/outstanding p)) "and is back after a vote")))

(deftest a-call-that-throws-is-counted-as-failed-and-not-as-a-vote
  (let [ch (async/chan 1)
        p  {:ch ch :capacity 1 :out (atom 0)}]
    (async/>!! ch (reify proto/Provider
                    (complete [_ _] (throw (java.net.ConnectException. "refused")))
                    (stream [_ _ _] (throw (java.net.ConnectException. "refused")))))
    (let [r (llm/vote p "q")]
      (is (= :abstain (:verdict r)))
      (is (= [] (:votes r)))
      (is (= 3 (:failed r)))
      (is (zero? (llm/outstanding p)) "every token is back"))
    (is (nil? (llm/ask-yes-no p "q")))))

(deftest a-pool-with-no-free-provider-answers-no-vote
  (with-redefs [llm/acquire-timeout-ms 20]
    (let [empty-pool {:ch (async/chan 1) :out (atom 0)}]
      (is (= {:status :no-host} (llm/with-provider empty-pool (fn [_] :called))))
      (is (nil? (llm/ask-yes-no empty-pool "q"))))))
