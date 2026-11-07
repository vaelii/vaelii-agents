;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.tools
  "The model's read tools, generated from the read subset of the engine daemon's op
  table (`vaelii.agents.engine/ops`) and dispatched back through it.  No tool
  writes.  See docs/llm.md § Tools."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [vaelii.agents.engine :as eh]))

(def write-ops
  "Every op in `eh/ops` that changes the KB or the process, which `read-ops` subtracts: the
  daemon's own write set (`eh/write-ops`) and five ops the daemon does not file there.
  `:forward-chain` stores what the rules derive, `:abduce` stores its hypotheses in a
  scratch context and `:abduce-discard` drops that context, `:preview` holds the single
  writer and advances the handle counter, and `:clear-caches` resets the process's
  measurement state."
  (into eh/write-ops #{:forward-chain :abduce :abduce-discard :preview :clear-caches}))

(def host-path-ops
  "Reads whose argument names a path on the daemon's host, which `read-ops` subtracts:
  `:kb-diff`'s second side is a directory the daemon loads."
  #{:kb-diff})

(def unbounded-ops
  "Reads with no bound a tool call can carry, which `read-ops` subtracts.  `:handles` and
  `:terms` sort every handle or term in the KB into one vector before the printer's bound
  applies, `:explain-levels` realizes every level in full under no clock, and
  `:kb-quality`, `:all-specified-violations`, `:all-functional-at-instant-violations` and
  `:exposed-clashes` audit the whole KB.  `:sentex-count`, `:term-count`, `:find-terms`,
  `:escalate` and the clock-bounded `:kb-integrity` answer the narrower questions."
  #{:handles :terms :kb-quality :explain-levels :all-specified-violations
    :all-functional-at-instant-violations :exposed-clashes})

(defn- core-var
  "The `vaelii.core` var an op keyword names, or nil."
  [op]
  (ns-resolve 'vaelii.core (symbol (name op))))

(defn- arglists [v] (:arglists (meta v)))

(defn- variadic? [v]
  (boolean (some #(some #{'&} %) (arglists v))))

(defn read-ops
  "The op keywords the model may call, sorted: `eh/ops` minus `write-ops`,
  `host-path-ops` and `unbounded-ops`, keeping each op whose name resolves to a
  non-variadic `vaelii.core` var.  The daemon names the op for a `!` fn without its `!`
  (`:retract` for `retract!`), so such an op resolves to no var and is dropped."
  []
  (->> (set/difference (set (keys eh/ops)) write-ops host-path-ops unbounded-ops)
       (filter (fn [op] (some-> (core-var op) variadic? not)))
       sort
       vec))

(defn tool-name
  "The tool name for an op keyword — `:find-sentexes` -> `kb_find_sentexes`,
  `:ask?` -> `kb_ask_p`."
  [op]
  (str "kb_" (-> (name op) (str/replace "-" "_") (str/replace "?" "_p"))))

(def ^:private by-tool-name
  "Every exposed read keyed by the tool name it answers to, computed once: `eh/ops` and
  the vars' arglists are fixed at load."
  (delay (into {} (map (juxt tool-name identity)) (read-ops))))

(defn op-of
  "The op keyword a tool name came from, or nil if it names no exposed read."
  [tname]
  (get @by-tool-name tname))

(def ^:private integer-params
  ;; `limit`: `qualitative-scenarios`' bound, which `check-limit!` refuses as a string
  '#{handle jid pos level floor limit})

(def max-limit
  "The largest count a tool call may ask of `qualitative-scenarios`' `limit` or of
  `find-terms`' and `describe`'s `:limit`: the engine realizes each answer whole before
  the printer's bound applies.  A larger count is refused `:over-ceiling`, and a
  `find-terms` call naming none is given this one."
  100)

(def ^:private limit-slots
  "Per op, the parameter holding its count, and whether a call naming none gets
  `max-limit`."
  '{:qualitative-scenarios {:param limit}
    :find-terms            {:param opts :fill? true}
    :describe              {:param opts}})

(def ^:private array-params
  '#{terms})

(def ^:private param-doc
  "Per-parameter descriptions, keyed by the `vaelii.core` parameter name; an unlisted
  one gets the generic EDN-form description."
  '{sentence "A sentence as an EDN s-expression: \"(dog Muffet)\", \"(parentOf ?x Tom)\"."
    goal     "A goal formula, or a vector of them for a conjunctive query: \"[(parentOf ?x ?y) (dog ?y)]\"."
    context  "A context name: \"CxWell\". Use \"?ctx\" to mean any context."
    ctx      "A context name: \"CxWell\"."
    term     "A single term (predicate, individual, type, or context name): \"Muffet\"."
    terms    "Terms to intersect on, as EDN strings: [\"Muffet\", \"dog\"]."
    handle   "A sentex handle — the integer id a stored sentex is referenced by."
    arg-pos  "Which slot the audit reads: \":second\" for predAllSpecified (the default), \":first\" for the predSpecifiedAll twin. Only those two values."
    jid      "A justification id."
    x        "An individual: \"Muffet\"."
    t        "A type: \"dog\"."
    sub      "The more specific type: \"dog\"."
    super    "The more general type: \"animal\"."
    pred     "A predicate name: \"parentOf\"."
    functor  "A predicate or type name — the head of a sentence."
    kind     "A predicate property: \":transitive\", \":symmetric\", \":reflexive\", \":functional\"."
    pos      "A 1-based argument position."
    level    "A retrieval level, 0 (raw index) to 7 (full backchaining)."
    floor    "The lowest level to start climbing from (default 2)."
    q        "A search string matched against term names."
    limit    "How many to return, at most, up to 100. Required — the enumeration is exponential in the node count."
    calculus (str "Which qualitative calculus to read: \":rcc8\" (regions), \":cardinal\" "
                  "(compass directions), \":relative\" (left-of / right-of), "
                  "\":distance\", \":allen\" (intervals), \":point\" (instants). Only "
                  "those six; any other name is refused.")
    budget   (str "An anytime budget, as an EDN map: \"{:max-ms 2000}\", "
                  "\"{:max-results 20}\", \"{:max-cost :compute}\". `:max-ms` and "
                  "`:max-results` bound how much of the lazy answer stream is realized; "
                  "`:max-cost` drops every prover above a tier (:lookup < :compute < …) "
                  "before the stream is built. The answer carries `:status` "
                  "(:complete / :timeout / :capped) and, when it is not complete, a "
                  "`:resume` to continue the same search under a fresh budget.")
    a        "One term of the pair being compared: \"dog\", \"Muffet\"."
    b        "The other term of the pair: \"animal\", \"Ann\"."
    m        "A disjoint metatype name — the type whose members are pairwise disjoint."
    batch    "An edit batch, as an EDN map: \"{:add [[(dog Muffet) CxWell]] :remove [4211]}\"."
    sx       "A stored sentex, as the EDN map a read answered with."
    opts     (str "Options map, as an EDN string. For kb_query and kb_query_p this is "
                  "where the rule-expansion depth goes: \"{:max-depth 3}\". With no "
                  "depth the read expands no rule at all and answers only from stored "
                  "facts and cached closures — which is a real answer, not an error, so "
                  "pass a depth when the conclusion you want follows from rules. "
                  "Send context alongside it: the argument shapes nest, so opts with no "
                  "context names the structure that has neither and is refused. "
                  "Elsewhere, per-op options such as \"{:limit 20}\".")})

(defn- param-schema [p]
  (let [desc (or (param-doc p)
                 (str "The `" p "` argument, as an EDN string."))]
    (cond
      (integer-params p) {"type" "integer" "description" desc}
      (array-params p)   {"type" "array" "items" {"type" "string"} "description" desc}
      :else              {"type" "string" "description" desc})))

(defn- summary
  "The first paragraph of a var's docstring, whitespace-collapsed."
  [v]
  (let [d (or (:doc (meta v)) "")]
    (-> (first (str/split d #"\n\s*\n"))
        (str/replace #"\s+" " ")
        str/trim)))

(defn- signatures
  "The op's parameter lists, shortest first, minus the leading `kb` — except for an
  `eh/kbless-ops` op, whose fn takes no KB and whose first parameter the caller sends."
  [op v]
  (let [drop-kb (if (eh/kbless-ops op) identity rest)]
    (vec (sort-by count (map #(vec (drop-kb %)) (arglists v))))))

(defn- op-params
  "`[all required]` for an op: every parameter any signature takes (first-seen order),
  and the ones **every** signature takes."
  [op v]
  (let [sigs (signatures op v)
        all  (vec (distinct (apply concat sigs)))]
    [all (filterv (fn [p] (every? #(some #{p} %) sigs)) all)]))

(defn schema
  "The tool schema for one op keyword."
  [op]
  (let [v (core-var op)
        sigs (signatures op v)
        [all required] (op-params op v)
        doc (summary v)
        doc (if (str/blank? doc) (str "Read `" (name op) "` from the knowledge base.") doc)
        doc (if (next sigs)
              (str doc " Argument sets: "
                   (str/join " | " (map #(str "(" (str/join ", " %) ")") sigs)) ".")
              doc)]
    (cond-> {"name" (tool-name op)
             "description" doc
             "input_schema" {"type" "object"
                             "properties" (into {} (map (fn [p] [(name p) (param-schema p)])) all)
                             "required" (mapv name required)
                             "additionalProperties" false}}
      ;; strict only when every property is required, as a strict schema demands
      (= (count all) (count required)) (assoc "strict" true))))

(defn schemas
  "Every exposed read as a tool schema, in `read-ops` order.  `opts`:

    :only     a set of op keywords to keep (default: all reads)
    :exclude  a set of op keywords to drop"
  ([] (schemas {}))
  ([{:keys [only exclude]}]
   (->> (read-ops)
        (filter (fn [op] (and (or (nil? only) (contains? only op))
                              (not (contains? (or exclude #{}) op)))))
        (mapv schema))))

(deftype ^:private Printed [^String open ^String close entries xs])

(defmethod print-method Printed [^Printed p ^java.io.Writer w]
  (.write w ^String (.-open p))
  (loop [xs (seq (.-xs p)) sep ""]
    (when xs
      (.write w ^String sep)
      (let [x (first xs)]
        (if (.-entries p)
          (do (print-method (key x) w) (.write w " ") (print-method (val x) w))
          (print-method x w)))
      (recur (next xs) (if (.-entries p) ", " " "))))
  (.write w ^String (.-close p)))

(defn- wire-safe
  "`x` with every map, vector, set and `seq?` replaced by a lazy one that prints as `pr`
  prints the original, a record as a plain map, so the bounded printer realizes and walks
  only the members it prints.  `seq?` rather than a `LazySeq` test, which `cycle`,
  `iterate` and `repeat` fail."
  [x]
  (cond
    (seq? x)    (map wire-safe x)
    (map? x)    (Printed. "{" "}" true
                          (map (fn [e] (clojure.lang.MapEntry/create (wire-safe (key e))
                                                                     (wire-safe (val e))))
                               x))
    (vector? x) (Printed. "[" "]" false (map wire-safe x))
    (set? x)    (Printed. "#{" "}" false (map wire-safe x))
    :else       x))

(defn- bounded-pr-str
  "`pr-str` of `x` into a writer that stops at `limit` characters: `[s cut?]`, where `s`
  holds exactly `limit` characters when `cut?` and all of the printing otherwise.  Each
  write appends only the room left, since one `print-method` call can hand over a whole
  string; the stop is a throw from inside `pr`, caught here."
  [x limit]
  (let [sb    (StringBuilder.)
        lim   (long limit)
        stop  (ex-info "over the result bound" {::over true})
        room  (fn [] (- lim (.length sb)))
        put-s (fn [^CharSequence s ^long off ^long len]
                (let [r (long (room))]
                  (if (<= len r)
                    (.append sb s (int off) (int (+ off len)))
                    (do (when (pos? r) (.append sb s (int off) (int (+ off r))))
                        (throw stop)))))
        put-a (fn [^chars c ^long off ^long len]
                (let [r (long (room))]
                  (if (<= len r)
                    (.append sb c (int off) (int len))
                    (do (when (pos? r) (.append sb c (int off) (int r)))
                        (throw stop)))))
        sink  (proxy [java.io.Writer] []
                (write
                  ([c] (if (string? c)
                         (put-s c 0 (count c))
                         (if (pos? (long (room)))
                           (.append sb (char c))
                           (throw stop))))
                  ([c off len]
                   (if (string? c)
                     (put-s c off len)
                     (put-a c off len))))
                (flush [])
                (close []))]
    (try
      (binding [*out* sink] (pr x))
      [(str sb) false]
      (catch clojure.lang.ExceptionInfo e
        (if (identical? e stop)
          [(str sb) true]
          (throw e))))))

(defn- coerce
  "JSON value -> the value `vaelii.core` wants.  A string argument is an EDN form
  unless the parameter is declared integer; an array is read element-wise."
  [p v]
  (cond
    (integer-params p) (long v)
    (array-params p)   (mapv #(if (string? %) (edn/read-string %) %) v)
    (string? v)        (edn/read-string v)
    :else              v))

(defn- render
  "`x` as the string a `tool_result` carries: its printing cut at `limit` characters,
  with a truncation marker when there was more."
  [x limit]
  (let [[s cut?] (bounded-pr-str x limit)]
    (if cut?
      (str s "\n… [truncated at " limit " characters]")
      s)))

(defn- held-to-max-limit
  "`args`, an `op` call's coerced arguments under signature `sig`, with its count held to
  `max-limit`: a larger count throws `:over-ceiling`, and a `:fill?` op naming none gets
  `max-limit`.  A `sig` without the option map gains one at its end, the position
  `find-terms` takes it at."
  [op sig args]
  (if-let [{:keys [param fill?]} (limit-slots op)]
    (let [i (.indexOf ^java.util.List sig param)
          v (when (<= 0 i) (nth args i))
          n (if (= 'limit param) v (when (map? v) (:limit v)))]
      (when (and (integer? n) (< (long max-limit) (long n)))
        (throw (ex-info (str (name op) " realizes its whole answer before the result is cut,"
                             " so a tool call asks for at most " max-limit ", not " n)
                        {:type :over-ceiling})))
      (cond
        (or (not fill?) (some? n)) args
        (neg? i)                   (conj args {:limit max-limit})
        (nil? v)                   (assoc args i {:limit max-limit})
        (map? v)                   (assoc-in args [i :limit] max-limit)
        :else                      args))
    args))

(defn call
  "Run one tool call against `kb` and return `{:ok true :result \"…\"}` or
  `{:ok false :error \"…\"}` — the string a `tool_result` block carries back.

  `input` is the provider's JSON object (string keys); nil reads as `{}`, and any other
  non-map is refused.  The longest signature the input fully satisfies decides the call;
  an input key that signature does not take is refused, naming the shapes the op has.
  A count over `max-limit` is refused.  Never throws: a bad argument, an unknown tool or
  a refusal from the KB comes back as `{:ok false :error \"…\"}`.

  `opts`: `:max-result-chars` (default 4000) bounds the printed result."
  ([kb tname input] (call kb tname input {}))
  ([kb tname input {:keys [max-result-chars] :or {max-result-chars 4000}}]
   (if-let [op (op-of tname)]
     (if-not (or (nil? input) (map? input))
       {:ok false :error (str tname " takes a JSON object of named arguments")}
       (let [input  (or input {})
             sigs   (signatures op (core-var op))
             sig    (last (filter (fn [s] (every? #(contains? input (name %)) s)) sigs))
             shapes (str/join " or " (map #(str "(" (str/join ", " %) ")") sigs))
             unread (when sig (sort (remove (set (map name sig)) (keys input))))]
         (cond
           (nil? sig)
           {:ok false
            :error (str "missing arguments — " tname " takes " shapes)}

           (seq unread)
           {:ok false
            :error (str tname " cannot read " (str/join ", " unread)
                        " beside the arguments given: they select the shape ("
                        (str/join ", " (map name sig)) "), and " tname " takes "
                        shapes ".  A shape is chosen by what is supplied, so give"
                        " every argument of the one you want.")}

           :else
           (try
             (let [args (held-to-max-limit
                         op sig (mapv (fn [p] (coerce p (get input (name p)))) sig))]
               {:ok true
                :result (render (wire-safe ((eh/ops op) kb args)) max-result-chars)})
             ;; `Throwable`: `coerce` reads model EDN, which can overflow the stack.  The
             ;; class name when there is no message
             (catch Throwable e
               {:ok false
                :error (str (or (ex-message e) (.getName (class e)))
                            (when-let [t (:type (ex-data e))] (str " [" t "]")))})))))
     {:ok false :error (str "unknown tool: " tname)})))
