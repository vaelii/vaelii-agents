;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.tools-test
  "The model's read tools (`vaelii.agents.tools`): generated from the daemon's op table,
  reads only, and bounded in what a call prints."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.engine :as engine]
            [vaelii.agents.test-util :as tu]
            [vaelii.agents.tools :as tools]
            [vaelii.core :as v]))

(use-fixtures :each (tu/neutral-fresh tu/fresh))

(deftest the-writer-holding-ops-are-filed-with-the-writes
  ;; the subset test below is relative, and neither name ends in `!`, so only the
  ;; `write-ops` list keeps these two out (docs/llm.md § Tools)
  (is (contains? tools/write-ops :preview))
  (is (contains? tools/write-ops :clear-caches)))

(deftest no-op-the-daemon-files-as-a-write-is-a-tool
  (is (seq engine/write-ops))
  (is (empty? (filter engine/write-ops (tools/read-ops))))
  (is (nil? (tools/op-of "kb_try_assert"))))

(deftest no-op-for-a-bang-fn-is-a-tool
  ;; the daemon names the op for `retract!` `:retract`, which resolves to no
  ;; `vaelii.core` var, so `read-ops` drops it whatever `write-ops` holds
  (let [bang (filter #(ns-resolve 'vaelii.core (symbol (str (name %) "!"))) (keys engine/ops))]
    (is (seq bang))
    (is (empty? (filter (set (tools/read-ops)) bang)))))

(deftest read-ops-are-the-read-subset-of-the-daemon-op-table
  (let [reads (set (tools/read-ops))]
    (is (seq reads))
    (is (every? (set (keys engine/ops)) reads)
        "every exposed tool names an op the daemon already serves")
    (is (empty? (filter tools/write-ops reads))
        "no declared write is exposed")
    (testing "the writes are named and reachable through serve, just not as tools"
      (doseq [w tools/write-ops]
        (is (contains? engine/ops w) (str w " is declared a write but serve does not serve it"))
        (is (not (contains? reads w)))))
    (testing "a host-path read is served for a human caller but kept out of the model's tools"
      (is (seq tools/host-path-ops) "the host-path exclusion set is not empty")
      (is (contains? tools/host-path-ops :kb-diff)
          "kb-diff's second side is a path on the daemon's host")
      (doseq [h tools/host-path-ops]
        (is (contains? engine/ops h)
            (str h " is excluded as a host-path read but serve does not serve it"))
        (is (not (contains? reads h))
            (str h " names a host path and must not become a model tool"))))
    (testing "a read with no bound a call can carry is served, just not as a tool"
      (doseq [u tools/unbounded-ops]
        (is (contains? engine/ops u) (str u " is excluded as unbounded but serve does not serve it"))
        (is (not (contains? reads u)))))))

(deftest no-write-is-reachable-as-a-tool
  (testing "the write ops have no tool name that dispatches"
    (doseq [w tools/write-ops]
      (is (nil? (tools/op-of (tools/tool-name w)))
          (str (tools/tool-name w) " must not resolve to an op"))))
  (testing "and a call naming a write is refused and stores nothing"
    (let [kb (tu/fresh)
          n  (v/sentex-count kb)]
      (doseq [tname ["kb_assert" "kb_try_assert"]]
        (is (= {:ok false :error (str "unknown tool: " tname)}
               (tools/call kb tname {"sentence" "(dog Muffet)" "context" "CxUniverse"}))))
      (is (= n (v/sentex-count kb))))))

(deftest schemas-are-well-formed-and-stable
  (let [ss (tools/schemas)]
    (is (= (count (tools/read-ops)) (count ss)))
    (doseq [s ss]
      (is (re-matches #"[a-zA-Z0-9_-]{1,128}" (get s "name"))
          (str "tool name is not a legal identifier: " (get s "name")))
      (is (not (str/blank? (get s "description"))))
      (let [schema (get s "input_schema")]
        (is (= "object" (get schema "type")))
        (is (false? (get schema "additionalProperties")))
        (is (every? (set (keys (get schema "properties"))) (get schema "required"))
            (str (get s "name") ": a required parameter is missing from properties")))
      (when (get s "strict")
        (is (= (set (keys (get (get s "input_schema") "properties")))
               (set (get (get s "input_schema") "required")))
            (str (get s "name") ": strict claimed with an optional parameter"))))
    (is (= ss (tools/schemas)) "generation is deterministic"))
  (testing "narrowing"
    (is (= ["kb_sentexes_matching"] (mapv #(get % "name") (tools/schemas {:only #{:sentexes-matching}}))))
    (is (not (contains? (set (mapv #(get % "name") (tools/schemas {:exclude #{:sentexes-matching}})))
                        "kb_sentexes_matching")))))

(deftest tool-names-round-trip
  (doseq [op (tools/read-ops)]
    (is (= op (tools/op-of (tools/tool-name op))) (str "round trip failed for " op)))
  (testing "the ? ops stay distinct from their plain twins"
    (is (not= (tools/tool-name :ask) (tools/tool-name :ask?)))))

(tu/deftest-kb tool-calls-reach-the-kb
  (tu/with-terms [dog animal Muffet]
    (v/assert kb (list 'genl dog animal) 'CxUniverse)
    (v/assert kb (list dog Muffet) 'CxUniverse)
    (testing "a query returns the stored sentence"
      (let [{:keys [ok result]} (tools/call kb "kb_sentexes_matching" {"sentence" (pr-str (list dog '?x))})]
        (is ok)
        (is (str/includes? result (str Muffet)))))
    (testing "an integer parameter arrives as an integer"
      (let [h (v/handle-of kb (list dog Muffet) 'CxUniverse)
            {:keys [ok result]} (tools/call kb "kb_in_p" {"handle" h})]
        (is ok)
        (is (= "true" result))))
    (testing "escalate climbs from an integer floor under the daemon's clock ceiling"
      (let [args {"goal" (pr-str (list dog '?x)) "context" "CxUniverse" "floor" 2}
            {:keys [ok result]} (tools/call kb "kb_escalate" args)
            over (tools/call kb "kb_escalate" (assoc args "opts" "{:max-ms 999999999}"))]
        (is ok)
        (is (str/includes? result (str Muffet)))
        (is (false? (:ok over)))
        (is (str/includes? (:error over) "ceiling"))))
    (testing "a taxonomic read"
      (let [{:keys [ok result]} (tools/call kb "kb_isa_p" {"x" (str Muffet) "t" (str animal)})]
        (is ok)
        (is (= "true" result))))
    (testing "a failing call is reported, not thrown"
      (let [{:keys [ok error]} (tools/call kb "kb_sentexes_matching" {"sentence" "(unbalanced"})]
        (is (false? ok))
        (is (some? error))))
    (testing "an argument the chosen shape cannot read is refused, never dropped"
      ;; goal-plus-opts satisfies only `query`'s `(goal)` shape (docs/llm.md § Tools)
      (let [{:keys [ok error]} (tools/call kb "kb_query" {"goal" (pr-str (list animal '?x))
                                                          "opts" "{:max-depth 3}"})]
        (is (false? ok))
        (is (str/includes? error "opts"))
        (is (str/includes? error "(goal)")))
      (testing "and the same call with the argument in between is run"
        (let [{:keys [ok]} (tools/call kb "kb_query" {"goal" (pr-str (list animal '?x))
                                                      "context" "?ctx"
                                                      "opts" "{:max-depth 3}"})]
          (is ok)))
      (testing "the two disjoint shapes of why-not refuse a mixture rather than picking"
        (let [{:keys [ok error]} (tools/call kb "kb_why_not"
                                             {"handle" 1
                                              "sentence" (pr-str (list dog Muffet))
                                              "context" "CxUniverse"})]
          (is (false? ok))
          (is (str/includes? error "handle")))))
    (testing "results are bounded"
      (let [{:keys [result]} (tools/call kb "kb_find_sentexes" {"term" (str Muffet)}
                                         {:max-result-chars 20})]
        (is (str/includes? result "truncated"))
        (is (= 20 (count (first (str/split-lines result)))) "cut at the bound exactly")))
    (testing "and the bound is on the printing, so a broad read realizes only what it shows
              — the op below never ends, and a reader that printed it whole would not either"
      (let [realized (atom 0)
            endless  (map (fn [i] (swap! realized inc)
                            {:id i :sentence (list 'dog 'Muffet) :context 'CxEndless})
                          (range))]
        (with-redefs [engine/ops (assoc engine/ops :sentexes-in-context (fn [_ _] endless))]
          (let [{:keys [ok result]} (tools/call kb "kb_sentexes_in_context" {"context" "CxEndless"}
                                                {:max-result-chars 300})]
            (is ok)
            (is (str/includes? result "truncated at 300"))
            (is (str/starts-with? result "({:id 0"))
            (is (< @realized 200) (str @realized " records realized to show 300 characters"))))))))

(tu/deftest-kb a-stream-stays-lazy-wherever-it-sits-in-the-answer
  ;; a stream below the top level, and `cycle`, `iterate` and `repeat`, which are not
  ;; `LazySeq`, are each realized whole by a walk that projects before the writer runs
  (let [shapes {"a bare stream"      identity
                "a stream in a map"  (fn [s] {:rows s})
                "a stream in a list" (fn [s] (list :head s))
                "a stream behind a cons" (fn [s] (cons :head s))
                "a stream in a vector"   (fn [s] [:head s])}]
    (doseq [[label wrap] shapes]
      (let [realized (atom 0)
            endless  (map (fn [i] (swap! realized inc) {:id i}) (range))]
        (with-redefs [engine/ops (assoc engine/ops :sentexes-in-context
                                        (fn [_ _] (wrap endless)))]
          (let [{:keys [ok result]} (tools/call kb "kb_sentexes_in_context" {"context" "CxEndless"}
                                                {:max-result-chars 300})]
            (is ok label)
            (is (str/includes? result "truncated at 300") label)
            (is (< @realized 500) (str label ": " @realized " elements realized for 300 characters")))))))
  (testing "and a seq that is not a LazySeq is bounded the same way"
    ;; each of these hangs — or dies of memory — under a walk that realizes to project
    (doseq [[label answer] {"cycle"        (cycle [1 2])
                            "iterate"      (iterate inc 0)
                            "repeat"       (repeat :x)
                            "a nested cycle" {:rows (cycle [1 2])}}]
      (with-redefs [engine/ops (assoc engine/ops :sentexes-in-context (fn [_ _] answer))]
        (let [{:keys [ok result]} (tools/call kb "kb_sentexes_in_context" {"context" "CxEndless"}
                                              {:max-result-chars 300})]
          (is ok label)
          (is (str/includes? result "truncated at 300") label))))))

(deftest one-write-cannot-carry-the-result-past-its-bound
  ;; one `print-method` call hands over a whole symbol name.  Read off the writer, since
  ;; `render` cuts an oversized buffer back down and reads the same either way.
  (let [bounded  @#'tools/bounded-pr-str
        long-sym (symbol (apply str (repeat 400000 \x)))]
    (doseq [[label x] {"a symbol printed in one write" [long-sym]
                       "a string printed character by character" [(apply str (repeat 400000 \y))]
                       "an unbounded stream"           (range 100000)}]
      (let [[s cut?] (bounded x 300)]
        (is cut? label)
        (is (= 300 (count s))
            (str label ": the writer kept " (count s) " characters to bound the result at 300"))))
    (testing "and a printing that fits is not cut at all"
      (let [[s cut?] (bounded [:a :b] 300)]
        (is (not cut?))
        (is (= "[:a :b]" s))))))

(tu/deftest-kb a-failure-with-no-message-is-still-named
  ;; `coerce` reads a model's argument as EDN, a deeply nested form overflows the
  ;; reader's stack, and a `StackOverflowError` carries no message
  (doseq [[label thrown expected]
          [["an Error with no message" (StackOverflowError.) "java.lang.StackOverflowError"]
           ["an Exception with no message" (RuntimeException.) "java.lang.RuntimeException"]
           ["and one that has a message keeps it" (RuntimeException. "no such term") "no such term"]]]
    (testing label
      (let [{:keys [ok error]}
            (with-redefs [engine/ops (assoc engine/ops :term-count (fn [_ _] (throw thrown)))]
              (tools/call kb "kb_term_count" {}))]
        (is (false? ok))
        (is (= expected error))
        (is (not (str/blank? error)) "an empty error is not a diagnosis")))))

(defrecord ^:private Row [id sentence])

(deftest a-collection-prints-as-pr-prints-it-and-a-record-as-its-map
  (let [shown #(#'tools/render (#'tools/wire-safe %) 100000)]
    (doseq [x [{:a [1 "two" #{3}] :b (sorted-map 1 [2] 3 '(4)) :c '(x y)}
               [{} [] #{} ()]
               (sorted-set 3 1 2)]]
      (is (= (pr-str x) (shown x))))
    (is (= "[{:id 7, :sentence (dog Muffet)}]" (shown [(->Row 7 '(dog Muffet))])))))

(tu/deftest-kb a-broad-collection-is-walked-only-as-far-as-it-prints
  (let [n 100000]
    (doseq [[label answer] {"a vector" (vec (range n))
                            "a set"    (set (range n))
                            "a map"    (zipmap (range n) (range n))}]
      (let [walked (atom 0)
            ws     @#'tools/wire-safe
            {:keys [ok result]}
            (with-redefs-fn {#'tools/wire-safe (fn [x] (swap! walked inc) (ws x))
                             #'engine/ops (assoc engine/ops :sentexes-in-context
                                                 (fn [_ _] answer))}
              #(tools/call kb "kb_sentexes_in_context" {"context" "CxEndless"}
                           {:max-result-chars 300}))]
        (is ok label)
        (is (str/includes? result "truncated at 300") label)
        (is (< @walked 1000) (str label ": " @walked " members walked for 300 characters"))))))

(tu/deftest-kb input-that-is-not-an-object-is-refused-not-thrown
  (doseq [input ["(dog Muffet)" 5 ["a"]]]
    (let [{:keys [ok error]} (tools/call kb "kb_term_count" input)]
      (is (false? ok) (pr-str input))
      (is (str/includes? error "JSON object") (pr-str input))))
  (is (:ok (tools/call kb "kb_term_count" nil)) "no input reads as no arguments"))

(tu/deftest-kb a-count-over-max-limit-is-refused-and-find-terms-is-given-one
  (let [over (inc tools/max-limit)
        seen (atom nil)
        sent (fn [op tname input]
               (reset! seen nil)
               (with-redefs [engine/ops (assoc engine/ops op (fn [_ args] (reset! seen args) []))]
                 (assoc (tools/call kb tname input) :args @seen)))]
    (testing "a count over the bound is refused before the op runs"
      (doseq [[op tname input]
              [[:qualitative-scenarios "kb_qualitative_scenarios"
                {"calculus" ":allen" "context" "CxUniverse" "limit" over}]
               [:find-terms "kb_find_terms" {"q" "dog" "opts" (pr-str {:limit over})}]
               [:describe "kb_describe" {"term" "Muffet" "context" "?ctx"
                                         "opts" (pr-str {:limit over})}]]]
        (let [{:keys [ok error args]} (sent op tname input)]
          (is (false? ok) tname)
          (is (str/includes? error "[:over-ceiling]") tname)
          (is (nil? args) (str tname " ran")))))
    (testing "find-terms naming no count is given the bound, and one under it keeps its own"
      (doseq [[opts expected] [[nil {:limit tools/max-limit}]
                               ["nil" {:limit tools/max-limit}]
                               ["{:match :substring}" {:match :substring :limit tools/max-limit}]
                               ["{:limit 5}" {:limit 5}]]]
        (is (= ['dog expected]
               (:args (sent :find-terms "kb_find_terms"
                            (cond-> {"q" "dog"} opts (assoc "opts" opts)))))
            (pr-str opts))))
    (testing "and describe, whose engine default is bounded, is given nothing"
      (is (= ['Muffet] (:args (sent :describe "kb_describe" {"term" "Muffet"})))))))
