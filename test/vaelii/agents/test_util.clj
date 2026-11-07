;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.test-util
  "Test scaffolding for `vaelii.agents.*`: the live-model consent check (docs/llm.md
  § The stub is the default), the KB fixtures, and gensym'd temporary terms.

  The `:each` fixture retracts every premise a test added and checks that the stored
  sentexes and premise marks are back to their baseline:

    (use-fixtures :each (tu/neutral-fresh tu/fresh))

  Every KB here is on the in-memory backend, on a space of this namespace's own."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [vaelii.agents.llm.ollama :as ollama]
            [vaelii.core :as v]))

(defn live-llm?
  "True when `VAELII_LLM_LIVE` is `1`, `true` or `yes`: the consent to call a real model."
  []
  (contains? #{"1" "true" "yes"}
             (some-> (System/getenv "VAELII_LLM_LIVE") str/trim str/lower-case)))

(defn live-model
  "The model a live test runs against, or nil with a printed skip reason.  It checks, in
  order, consent (before any probe), reachability, and whether the host has the model.

  `what` names the tier in the skip line. `opts`: `:model` (default
  `ollama/configured-model`) and `:env`, the variable that names it — omitted for a model
  a test fixes in its source, where there is no variable to point anybody at."
  ([what] (live-model what {}))
  ([what {:keys [model env] :or {env "VAELII_OLLAMA_MODEL"}}]
   (let [model (or model (ollama/configured-model))]
     (cond
       (not (live-llm?))
       (do (println (str "  [skip] " what ": set VAELII_LLM_LIVE=1 to opt in")) nil)

       (not (ollama/available? {:timeout-ms 2000}))
       (do (println (str "  [skip] " what ": no server at " (ollama/base-url)
                         " — set VAELII_OLLAMA_HOST to point at one"))
           nil)

       (nil? (ollama/capabilities model))
       (do (println (str "  [skip] " what ": " (ollama/base-url) " has no model " model
                         (when env (str " — set " env))))
           nil)

       :else model))))

(defn fresh
  "An empty, cleared KB on this suite's scratch space."
  []
  (doto (v/open-kb {:backend :memory :space [::scratch] :recover? false}) (v/clear!)))

(defn- alnum [s] (str/replace (str s) #"[^A-Za-z0-9]" ""))

(defn- snake [s] (str/lower-case (str/replace (str s) #"[^A-Za-z0-9]+" "_")))

(defn- cap [s] (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s))

(defn fresh-term
  "A gensym'd temporary term of `role`, named after `base` so a failure reads `tmpdog17`
  rather than an anonymous `tmp_t17`. The name satisfies the engine's naming rule for its
  role. A `:type` temp keeps the base's own spelling: a base carrying an underscore becomes
  snake_case, which commits it to arity 1."
  [role base]
  (case role
    :type       (let [n (snake base)]
                  (if (str/includes? n "_")
                    (gensym (str "tmp_" n "_"))
                    (gensym (str "tmp" n))))
    :predicate  (gensym (str "tmp" (str/lower-case (alnum base))))
    :individual (gensym (str "Tmp" (cap (alnum base))))
    :context    (symbol (str "Cx" (gensym (str "Tmp" (cap (alnum (str/replace (str base) #"^Cx" "")))))))))

(defn term-role
  "The role a symbol's spelling names under the engine's naming rule."
  [sym]
  (let [n (name sym)]
    (cond
      (re-matches #"Cx[A-Z][A-Za-z0-9]*" n) :context
      (re-matches #"[A-Z][A-Za-z0-9]*" n)   :individual
      (re-matches #"[a-z][a-z0-9_]*" n)     :type
      (re-matches #"[a-z][a-zA-Z0-9]*" n)   :predicate
      :else (throw (ex-info (str "no naming role for " sym) {:symbol sym})))))

(defmacro with-terms
  "Bind each symbol to a gensym'd temporary term whose role its own spelling names:

    (with-terms [dog Muffet CxStory]
      (v/assert kb (list dog Muffet) CxStory))"
  [syms & body]
  (assert (and (vector? syms) (every? simple-symbol? syms))
          "with-terms takes a vector of plain symbols")
  `(let [~@(mapcat (fn [s] [s `(fresh-term ~(term-role s) '~s)]) syms)]
     ~@body))

(defn- sentex-ids [kb] (set (v/handles kb)))

(defn- premise-ids [kb] (into #{} (filter #(v/premise? kb %)) (v/handles kb)))

(defn- baseline [kb] {:sentexes (sentex-ids kb) :premises (premise-ids kb)})

(defn- retract-added!
  "Retract every premise added since `before`, to a fixpoint: retraction is
  dependency-directed, so the derived consequences go with it."
  [kb before]
  (loop [guard 0]
    (let [added (remove (:sentexes before) (premise-ids kb))]
      (when (and (seq added) (< guard 50))
        (doseq [h added] (try (v/retract! kb h) (catch Throwable _)))
        (recur (inc guard))))))

(defn- assert-neutral!
  "Retract the test's additions and check the KB against `before`, both directions."
  [kb before]
  (retract-added! kb before)
  (let [now  (sentex-ids kb)
        leak (set/difference now (:sentexes before))
        lost (set/difference (:sentexes before) now)]
    (is (and (empty? leak) (empty? lost))
        (str "KB not restored after teardown — leaked " (count leak) " sentex(es) "
             (pr-str (mapv #(v/sentence-of (v/sentex kb %)) (take 8 leak)))
             "; lost " (count lost) " " (pr-str (vec (take 8 lost)))))
    (is (= (:premises before) (set/intersection (premise-ids kb) now))
        "premise marks not restored after teardown")))

(def ^:dynamic *kb*
  "The KB under test, bound by [[neutral-fresh]] and read by [[deftest-kb]]."
  nil)

(defmacro deftest-kb
  "A `deftest` whose body has `kb` bound to the KB under test."
  [name & body]
  `(clojure.test/deftest ~name (let [~'kb *kb*] ~@body)))

(defn neutral-fresh
  "An `:each` fixture that builds a KB with `build-fn`, binds it, and checks
  net-neutrality per test."
  [build-fn]
  (fn [f]
    (let [kb (build-fn)]
      (binding [*kb* kb]
        (let [before (baseline kb)]
          (try (f) (finally (assert-neutral! kb before))))))))
