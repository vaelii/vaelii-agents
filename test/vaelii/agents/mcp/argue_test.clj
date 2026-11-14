;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.argue-test
  "Regression coverage for `vaelii.agents.mcp.argue`'s four-valued verdicts,
  using Muffet the canonical dog."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.mcp.argue :as argue]
            [vaelii.agents.test-util :as tu]
            [vaelii.core :as v]))

(use-fixtures :each (tu/neutral-fresh tu/fresh))

(deftest argue-verdicts
  (let [kb tu/*kb*]
    ;; Muffet is a dog. Dogs are animals.
    (v/assert kb '(dog Muffet) 'CxUniverse)
    (v/assert kb '(genl dog animal) 'CxUniverse)

    ;; hungry is a unary predicate — no special semantics.
    ;; (hungry Muffet) is NOT asserted — this is the unknown case.

    (testing "asserted positive fact"
      (is (= :true (:verdict (argue/argue kb '(dog Muffet) 'CxUniverse)))))

    (testing "neither asserted nor denied"
      (is (= :unknown (:verdict (argue/argue kb '(hungry Muffet) 'CxUniverse)))))

    (testing "explicit negation asserted"
      (v/assert kb '(not (hungry Muffet)) 'CxUniverse)
      (is (= :false (:verdict (argue/argue kb '(hungry Muffet) 'CxUniverse)))))

    (testing "both sides asserted at :default"
      (v/assert kb '(hungry Muffet) 'CxUniverse)
      (is (= :contradiction (:verdict (argue/argue kb '(hungry Muffet) 'CxUniverse)))))

    (testing "true with rule expansion (backward): animal Muffet via genl, no opts"
      (is (= :true (:verdict (argue/argue kb '(animal Muffet) 'CxUniverse)))))

    (testing "unknown without rule expansion, true with"
      (v/assert-rule kb ['(dog ?x)] '(has_fur ?x) 'CxUniverse {:direction :backward})
      (is (= :unknown (:verdict (argue/argue kb '(has_fur Muffet) 'CxUniverse)))
          "no opts (ask, no rules)")
      (is (= :true (:verdict (argue/argue kb '(has_fur Muffet) 'CxUniverse {:max-depth 3})))
          "with {:max-depth 3} (rules fire)"))

    (testing "monotonic wins over default in contradiction"
      (let [kb2 (v/fork kb)]
        (v/assert kb2 '(not (hungry Muffet)) 'CxUniverse {:strength :default})
        (v/assert kb2 '(hungry Muffet) 'CxUniverse {:strength :monotonic})
        (is (= :true (:verdict (argue/argue kb2 '(hungry Muffet) 'CxUniverse))))))))
