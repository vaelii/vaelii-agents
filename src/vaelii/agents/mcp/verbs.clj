;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.verbs
  "The six MCP verb tools — `assert`, `unassert`, `unassert-handle`, `query`,
  `argue`, `matching` — the write-and-read surface an MCP client reaches a fork KB through.

  Each verb is a map → map function: JSON-shaped input (string keys, EDN values
  carried as strings) → a Clojure result map the transport serializes to JSON.
  Errors are maps with `:mcp-error true`, never thrown — the transport layer
  decides how to encode them (a JSON-RPC error, an MCP error content block, etc.).

  **Fork injection.**  Every verb receives the fork KB through the dynamic var
  `*mcp-kb*`, which the MCP server binds per request.  The verbs never see the
  base KB directly; the fork's overlay isolates their writes.

  **Strength cap.**  An MCP client may assert at `:default` only — never
  `:monotonic`.  The cap is enforced here, before the call reaches `vaelii.core`,
  so a client cannot promote a belief past the fork's own defeat semantics.

  **EDN coercion.**  Sentence, goal, and context arguments arrive as JSON strings
  holding EDN s-expressions.  Parsed with `clojure.edn/read-string` (never
  `read-string`) — EDN has no reader-eval, so a client's output cannot execute
  code.

  **Wire format.**  Results are Clojure maps serialized to JSON by the transport.
  Handles are longs.  Bindings are maps of string keys to EDN-string values."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [vaelii.agents.engine :as eh]
            [vaelii.core :as v]))

;; ---- fork injection -------------------------------------------------------

(def ^:dynamic *mcp-kb*
  "The fork KB the MCP server binds per request.  Every verb reads it; assert and
  unassert write to its overlay.  `nil` outside an MCP request."
  nil)

(def ^:dynamic *check-write-permit!*
  "Fencing callback bound by the dispatcher. Called before every KB mutation
  (assert, retract) to verify the session's authority generation hasn't changed.
  Throws if the permit is stale — preventing a revoked/retired principal from
  committing a write that started before revocation but lands after.

  nil outside an MCP request (standalone verb calls skip fencing).
  The dispatcher binds this to a closure that checks session/permit-valid?
  against the permit acquired at admission time."
  nil)

(def ^:dynamic *authority-lock*
  "Authority lock bound by the dispatcher. Held around fence-check + mutation
  to prevent revocation from slipping between authorization and effect.
  C3: authorize before mutating — don't trust a rollback path under partial
  failure. nil outside an MCP request (standalone verb calls skip locking)."
  nil)

(defn- check-write-fence!
  "Check the write fence if one is bound. No-op when called outside
  MCP request context (standalone tests, REPL)."
  []
  (when-let [check *check-write-permit!*]
    (check)))

;; ---- depth budget ----------------------------------------------------------

(def ^:private default-depth
  "Server depth budget for query and argue — how many rule-expansion steps the
  engine takes.  Deep enough for most common-sense chains, shallow enough to
  bound cost."
  5)

;; ---- error helpers ---------------------------------------------------------

(defn- mcp-error
  "An MCP error map — the verb's way of saying 'no'.  Never thrown; the transport
  layer decides how to encode it."
  [code message]
  {:mcp-error true :code code :error message})

(defn- server-error
  "Wrap an unexpected throwable as a server_error."
  [^Throwable t]
  (mcp-error "server_error" (or (.getMessage t) (str (type t)))))

;; ---- EDN coercion ----------------------------------------------------------

(defn- parse-edn
  "Parse a string as EDN, returning [ok value] or [nil error-map]."
  [s label]
  (cond
    (not (string? s))
    [nil (mcp-error (str "invalid_" label)
                    (str label " must be a string holding an EDN expression, got " (pr-str (type s))))]

    (str/blank? s)
    [nil (mcp-error (str "invalid_" label)
                    (str label " must not be blank"))]

    :else
    (try [true (edn/read-string s)]
         (catch Exception e
           [nil (mcp-error (str "invalid_" label)
                           (str "could not parse " label " as EDN: " (.getMessage e)))]))))

(defn- parse-sentence [s]
  (let [[ok v] (parse-edn s "sentence")]
    (if ok
      (if (sequential? v)
        [true v]
        [nil (mcp-error "invalid_sentence"
                        (str "sentence must be a list (s-expression), got " (pr-str (type v))))])
      [nil v])))

(defn- and-form?
  "Is `v` the form `(and ...)` — a conjunction written as an s-expression?
  Must be a list (sequential but not vector) to avoid misparsing vector goals
  whose first element happens to be the symbol `and`."
  [v]
  (and (sequential? v) (not (vector? v)) (= 'and (first v))))

(defn- or-form?
  "Is `v` the form `(or ...)` — a disjunction written as an s-expression?
  Must be a list (sequential but not vector) — same guard as and-form?."
  [v]
  (and (sequential? v) (not (vector? v)) (= 'or (first v))))

(defn- normalize-goal
  "Normalize a parsed goal form per the verb contract.

  - `(and G1 G2 ...)` → `[G1 G2 ...]` (vector conjunction)
  - `(and)` → `:nullary-true` (identity element — trivially satisfiable)
  - `(or)` → `:nullary-false` (identity element — trivially unsatisfiable)
  - `(or G1 G2 ...)` → error (disjunction not yet supported)
  - vector `[G1 G2 ...]` → pass through
  - bare sentence `(pred ...)` → pass through"
  [v]
  (cond
    (and-form? v)
    (let [conjuncts (rest v)]
      (if (empty? conjuncts)
        [true :nullary-true]
        [true (vec conjuncts)]))

    (or-form? v)
    (let [disjuncts (rest v)]
      (if (empty? disjuncts)
        [true :nullary-false]
        [nil (mcp-error "invalid_sentence"
                        "non-empty (or ...) is not supported; use separate queries")]))

    ;; vector of goals — already a conjunction
    (vector? v)
    [true v]

    ;; bare sentence (list)
    (sequential? v)
    [true v]

    :else
    [nil (mcp-error "invalid_sentence"
                    (str "goal must be a list or vector, got " (pr-str (type v))))]))

(defn- parse-goal [s]
  (let [[ok v] (parse-edn s "goal")]
    (if ok
      (normalize-goal v)
      [nil v])))

(defn- parse-context
  "Parse and validate a context against vaelii's naming convention.  A valid
  context is a `Cx…` CapitalCamelCase symbol or a reified context constant in the
  `cx/` namespace — decided by `eh/context?`."
  [s]
  (let [[ok v] (parse-edn s "context")]
    (if ok
      (cond
        (not (symbol? v))
        [nil (mcp-error "invalid_context"
                        (str "context must be a symbol, got " (pr-str (type v))))]

        (not (eh/context? v))
        [nil (mcp-error "invalid_context"
                        (str "not a valid context name: " (pr-str v)
                             ". contexts must be Cx… (e.g. CxWell) or cx/… namespace"))]

        :else [true v])
      [nil v])))

;; ---- wire format helpers ---------------------------------------------------

(defn- wire-safe
  "Project records to plain maps and realize lazy seqs, so a result serializes
  cleanly to JSON."
  [x]
  (walk/postwalk (fn [y] (if (record? y) (into {} y) y)) x))

(defn- bindings->wire
  "Convert a binding map {?x val, ...} to {\"?x\" \"val-edn\", ...} for JSON."
  [bindings]
  (into {} (map (fn [[k v]] [(pr-str k) (pr-str v)])) bindings))

(defn- provenance->wire
  "Render one stored claim's provenance record to the wire: server-derived
  from the immutable per-handle map `assert` recorded, never accepted from
  callers. A missing record (legacy claim with no trustworthy record) renders
  nil — nothing is ever synthesized from read time. `:created` is epoch
  millis; the wire carries it as ISO-8601 UTC under `asserted_at` (the time
  the claim entered its origin store)."
  [p]
  (when p
    {:creator     (some-> (:creator p) str)
     :asserted_at (some-> (:created p)
                          long
                          java.time.Instant/ofEpochMilli
                          str)}))

(defn- resolve-leaf-handle
  "The stored sentex handle for a ground inference-proof leaf, or nil. A
  proof leaf records only the literal the search handed to its leaf solver —
  which prover answered is deliberately not recorded — so only a ground goal
  matching exactly one believed stored sentex in the queried context binds.
  Anything else (free variables, no match, ambiguity) stays unresolved and
  the node stays bare: provenance is attached only where the stored claim is
  unambiguous, never guessed."
  [kb context goal]
  (when (and kb context goal (empty? (eh/free-vars goal)))
    (let [matches (v/sentexes-matching kb goal context)]
      (when (= 1 (count matches))
        (:id (first matches))))))

(defn- attach-node-provenance
  "Walk a wire justification tree attaching provenance to every stored-claim
  node:

  - JTMS nodes (and anything else carrying an integer `:handle`) read their
    own handle;
  - inference **rule** nodes carry the rewriting rule's stored handle under
    `:rule`;
  - inference **leaves** carry no handle by construction, so ground leaves
    are bound to their unique believed stored sentex in the queried context
    (see [[resolve-leaf-handle]]) and gain both `:handle` and `:provenance`.

  Structural/derived/unresolvable nodes carry no `:provenance` key at all:
  absence is the explicit signal, never inherited attribution from the query
  author. Stored claims whose provenance record is missing carry
  `:provenance nil`, distinguishing \"claim without a trustworthy record\"
  from \"not a stored claim\"."
  [kb context form]
  (walk/postwalk
   (fn [node]
     (if-not (map? node)
       node
       (cond
         (integer? (:handle node))
         (assoc node :provenance (provenance->wire (v/provenance kb (:handle node))))

         (and (= :rule (:via node)) (integer? (:rule node)))
         (assoc node :provenance (provenance->wire (v/provenance kb (:rule node))))

         (and (= :leaf (:via node)) (contains? node :goal))
         (if-let [handle (resolve-leaf-handle kb context (:goal node))]
           (assoc node
                  :handle handle
                  :provenance (provenance->wire (v/provenance kb handle)))
           node)

         :else node)))
   form))

(defn- ground-proof-for-answer
  "Ground one answer's proof tree through that answer's own bindings.

  The engine projects each answer's bindings onto the query's own variables
  and discards the search's scratch bindings (see `eh/substitute`'s caller,
  `vaelii.impl.resolution/project-answer`), so the proof tree can still spell
  its goals with renamed variables (`?var0`) the answer map never mentions.
  Recover the renaming per answer: unify each root node's `:goal` against the
  corresponding answer-grounded query conjunct, then substitute the derived
  bindings through the whole tree. A root that does not unify contributes
  nothing, and untouched nodes stay bare — grounding is derived from this
  answer's own derivation, never guessed across answers.

  `bindings` is the answer's raw binding map (query-surface variables);
  `conjuncts` the parsed query conjuncts in engine order."
  [proof conjuncts bindings]
  (let [grounded (map #(eh/substitute % bindings) conjuncts)
        roots    (cond (map? proof) [proof]
                       (sequential? proof) proof
                       :else nil)
        derived  (when roots
                   (reduce (fn [b [node gc]]
                             (or (when (map? node)
                                   (eh/unify (:goal node) gc b))
                                 b))
                           {}
                           (map vector roots grounded)))]
    (if (seq derived)
      (walk/postwalk
       (fn [n]
         (if (and (map? n) (contains? n :goal))
           (update n :goal #(eh/substitute % derived))
           n))
       proof)
      proof)))

(defn- justification->wire
  "Convert a proof/justification tree to the contract wire shape.
  InferenceJustification: {kind: \"inference\", depth_used: int, tree: [...]}
  JTMSJustification: {kind: \"jtms\", depth_used: 0, tree: {...}}
  Every stored-claim node additionally carries server-recorded `:provenance`
  ({creator, asserted_at}) when an MCP KB is bound — see
  [[attach-node-provenance]]. Pass the queried `context` so ground inference
  leaves (which carry no handle by construction) can bind to their stored
  sentex; the no-context arity enriches handle-bearing nodes only."
  ([j] (justification->wire j nil))
  ([j context]
   (when j
     (let [safe    (wire-safe j)
           wrapped (if (:kind safe)
                     ;; Already wrapped (nullary identities, enrichment fallback) —
                     ;; normalize: keyword→string for :kind, ensure :depth_used present
                     (-> safe
                         (update :kind #(if (keyword? %) (name %) (str %)))
                         (update :depth_used #(or % 0)))
                     ;; Raw vaelii justification — wrap according to type
                     (if (:support safe)
                       ;; JTMS justification (has :support, :sentence, :believed?, etc.)
                       {:kind "jtms" :depth_used 0 :tree safe}
                       ;; Inference proof (from query with :proof? true)
                       {:kind "inference" :depth_used (or (:depth safe) 0) :tree safe}))]
       (if-let [kb *mcp-kb*]
         (attach-node-provenance kb context wrapped)
         wrapped)))))

(defn- defeat->wire
  "The wire justification for a goal placed nogoods defeat: `argue`'s
  `:defeated-by` reports (`conflicts`' `:defeat` shape) as
  {kind: \"defeat\", depth_used: 0, tree: [report ...]}, through
  [[justification->wire]], so the reports are wire-safe and each stored side
  (`:loser`, `:winners`, `:sides`) carries server-recorded `:provenance`.
  nil when nothing defeats the goal."
  [reports context]
  (when (seq reports)
    (justification->wire {:kind "defeat" :depth_used 0 :tree (vec reports)} context)))

;; ---- assert atomicity -------------------------------------------------------

(defonce ^{:private true :doc "Serializes check-then-assert to prevent concurrent identical asserts from
  both returning already_there=false with distinct handles.  Per-verb-layer
  lock; does not reach into vaelii.core's internal concurrency."}
  assert-lock
  (Object.))

(defn- asserted-predicate-uses
  "Predicate applications, with the context in which assert would store them.
  Use the engine's literal reader: ordinary arguments (including NATs and quoted
  sentences) are data, not additional predicate positions."
  [kb sentence context]
  (cond
    (eh/soft-priority-surface? sentence)
    (asserted-predicate-uses kb (nth sentence 2) context)

    (or (eh/cardinality-surface? sentence) (eh/minimize-surface? sentence))
    (asserted-predicate-uses kb (nth sentence 3) context)

    :else
    (if-let [[inner-context inner] (eh/ist-parts sentence)]
      (asserted-predicate-uses kb inner inner-context)
      (if (eh/conjunction? sentence)
        (mapcat #(asserted-predicate-uses kb % context) (rest sentence))
        (let [[sentence _] (eh/peel-exception-strength sentence)
              [exceptions inner] (eh/split-exceptWhen sentence)
              literal-uses
              (fn literal-uses [[_ literal]]
                (cond
                  (eh/forall? literal)
                  (mapcat literal-uses (eh/literals (eh/desugar-forall-literal literal)))

                  ;; An or in a query position (e.g. a definition's condition).
                  (= 'or (first literal))
                  (mapcat #(mapcat literal-uses (eh/literals %)) (rest literal))

                  (eh/ist-parts literal)
                  (asserted-predicate-uses kb literal context)

                  (eh/defn-sentence? literal)
                  (cons [(first literal) context]
                        ;; The condition is executable, unlike ordinary metadata
                        ;; arguments. A variable consequent adds no predicate use.
                        (asserted-predicate-uses
                         kb (list 'implies (nth literal 2) '(?p ?x)) context))

                  :else [[(first literal) context]]))]
          (concat
           (mapcat #(mapcat literal-uses (eh/literals %)) exceptions)
           (mapcat (fn [expanded]
                     (let [normalized (eh/inner-rule expanded)
                           effective-context
                           (if (= eh/rule-functor (first normalized))
                             context
                             (eh/storage-context kb (first normalized) context))]
                       ;; Re-enter after peeling wrappers so wrapped ist/and facts
                       ;; retain their own context rather than the request's.
                       (if (or (eh/ist-parts normalized)
                               (eh/conjunction? normalized))
                         (asserted-predicate-uses kb normalized context)
                         (map (fn [[pred cx]]
                                [pred (if (= cx context) effective-context cx)])
                              (mapcat literal-uses (eh/literals normalized))))))
                   (eh/expand-rule inner))))))))

(defn- check-declared-predicates!
  [kb sentence context]
  (doseq [[pred effective-context] (distinct (asserted-predicate-uses kb sentence context))]
    (when-not (v/ask? kb (list 'predicate pred) effective-context)
      (throw (ex-info (str "undeclared predicate " pred " in " effective-context)
                      {:type :shape :predicate pred :context effective-context})))))

;; ---- verb: assert ----------------------------------------------------------

(defn verb-assert
  "Assert a sentence in a context.  Strength is capped at `:default`.

  Input:  {\"sentence\" \"(dog Muffet)\" \"context\" \"CxWell\"}
  Output: {:handle 42 :already_there false}"
  [input]
  (try
    (let [[sok sentence] (parse-sentence (get input "sentence"))
          [cok context]  (parse-context (get input "context"))]
      (cond
        (not sok) sentence   ; the error map
        (not cok) context
        :else
        (let [kb *mcp-kb*]
          (when-not kb
            (throw (ex-info "no MCP KB bound" {:type :unauthorized})))
          ;; C3: atomic authorization — hold authority-lock around fence + mutation
          ;; so revocation cannot slip between authorization check and effect.
          ;; No post-mutation rollback needed when authorization is atomic.
          (let [do-write (fn []
                           (locking assert-lock
                             (check-declared-predicates! kb sentence context)
                             (let [existing-handle (v/handle-of kb sentence context)
                                   already?       (and existing-handle (v/in? kb existing-handle))]
                               (if already?
                                 {:handle existing-handle :already_there true}
                                 (do
                                   (check-write-fence!)
                                   (let [h (v/assert kb sentence context {:strength :default})
                                         handle (if (vector? h) (first h) h)]
                                     {:handle handle :already_there false}))))))]
            (if-let [lock *authority-lock*]
              (locking lock (do-write))
              (do-write))))))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          ;; Effect uncertainty must cross this boundary intact — the
          ;; dispatcher owns the outcome_indeterminate completion and response.
          :outcome-indeterminate (throw e)
          :unknown-option   (mcp-error "strength_cap" (.getMessage e))
          :shape            (mcp-error "invalid_sentence" (.getMessage e))
          :unrecovered-kb   (mcp-error "server_error" (.getMessage e))
          :unauthorized     (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- verb: unassert --------------------------------------------------------

(defn verb-unassert
  "Belief-state postcondition: ensure sentence is not believed from context.
  Finds all visible handles and excepts each.

  Input:  {\"sentence\" \"(dog Muffet)\" \"context\" \"CxWell\"}
  Output: {:excepting_handles [101 102]}"
  [input]
  (try
    (let [[sok sentence] (parse-sentence (get input "sentence"))
          [cok context]  (parse-context (get input "context"))]
      (cond
        (not sok) sentence
        (not cok) context
        :else
        (let [kb      *mcp-kb*
              _       (when-not kb (throw (ex-info "no MCP KB bound" {:type :unauthorized})))
              matches (v/sentexes-matching kb sentence context)
              handles (vec (keep :id matches))]
          (if (empty? handles)
            ;; Vacuous — already not believed
            {:excepting_handles []}
            ;; C3: atomic authorization — fence + mutation under authority-lock
            ;; P1#2: track effect certainty — if any assertion succeeds before
            ;; a later one fails, the outcome is indeterminate, not rejected.
            (let [do-write (fn []
                             (check-write-fence!)
                             (let [committed (atom 0)
                                   excepting (try
                                               (mapv (fn [h]
                                                       (let [r (v/assert kb
                                                                         (list 'except (eh/sentex-handle h))
                                                                         context
                                                                         {:strength :default})]
                                                         (swap! committed inc)
                                                         r))
                                                     handles)
                                               (catch Throwable t
                                                 (if (pos? @committed)
                                                   (throw (ex-info "partial mutation in multi-handle unassert"
                                                                   {:type :outcome-indeterminate
                                                                    :committed @committed
                                                                    :total (count handles)}
                                                                   t))
                                                   (throw t))))]
                               {:excepting_handles excepting}))]
              (if-let [lock *authority-lock*]
                (locking lock (do-write))
                (do-write)))))))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          ;; Effect uncertainty must cross this boundary intact — the
          ;; dispatcher owns the outcome_indeterminate completion and response.
          :outcome-indeterminate (throw e)
          :shape          (mcp-error "invalid_sentence" (.getMessage e))
          :unauthorized   (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- verb: unassert-handle -------------------------------------------------

(defn verb-unassert-handle
  "Identity withdrawal of one specific handle.

  Input:  {\"sentex_handle\" 42}
  Output: {:excepting_handle 101} or {:excepting_handle nil}"
  [input]
  (try
    (let [h (get input "sentex_handle")]
      (when-not (integer? h)
        (throw (ex-info (str "sentex_handle must be an integer, got " (pr-str h))
                        {:type :shape})))
      (let [kb *mcp-kb*
            _  (when-not kb (throw (ex-info "no MCP KB bound" {:type :unauthorized})))
            sx (v/sentex kb h)]
        (if-not sx
          ;; Unknown handle
          (mcp-error "not_found" (str "no sentex at handle " h))
          ;; Check if already not visible — JTMS out, or already excepted.
          ;; `in?` alone is not enough: an excepted fact is still IN in the JTMS
          ;; but invisible to reads.  Check visibility via sentexes-matching.
          (let [ctx     (or (:context sx) 'CxUniverse)
                visible (and (v/in? kb h)
                             (seq (filter #(= h (:id %))
                                          (v/sentexes-matching kb (v/sentence-of sx) ctx))))]
            (if-not visible
              ;; Already withdrawn (excepted, defeated, or absent)
              {:excepting_handle nil}
              ;; C3: atomic authorization — fence + mutation under authority-lock
              (let [do-write (fn []
                               (check-write-fence!)
                               (let [except-handle (v/assert kb
                                                             (list 'except (eh/sentex-handle h))
                                                             ctx
                                                             {:strength :default})]
                                 ;; C3: post-mutation fence
                                 (check-write-fence!)
                                 {:excepting_handle except-handle}))]
                (if-let [lock *authority-lock*]
                  (locking lock (do-write))
                  (do-write))))))))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          ;; Effect uncertainty must cross this boundary intact — the
          ;; dispatcher owns the outcome_indeterminate completion and response.
          :outcome-indeterminate (throw e)
          :bad-handle       (mcp-error "not_found" (.getMessage e))
          :shape            (mcp-error "invalid_sentence" (.getMessage e))
          :unauthorized     (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- verb: query -----------------------------------------------------------

(defn verb-query
  "Answer a goal in a context.

  Input:  {\"goal\" \"(dog ?x)\" \"context\" \"CxWell\" \"include_justification\" true}
  Output: {:answers [{:bindings {\"?x\" \"Muffet\"} :justification nil}]}"
  [input]
  (try
    (let [[gok goal]    (parse-goal (get input "goal"))
          [cok context] (parse-context (get input "context"))
          proof?        (boolean (get input "include_justification"))]
      (cond
        (not gok) goal
        (not cok) context
        :else
        (let [kb *mcp-kb*
              _  (when-not kb (throw (ex-info "no MCP KB bound" {:type :unauthorized})))]
          (cond
            ;; Nullary identity: (and) → true — one answer, empty bindings
            (= goal :nullary-true)
            {:answers [{:bindings {}
                        :justification (when proof?
                                         {:kind "inference" :depth_used 0
                                          :tree [{:goal "(and)" :via "nullary-identity"
                                                  :because []}]})}]}

            ;; Nullary identity: (or) → false — no answers
            (= goal :nullary-false)
            {:answers []}

            :else
            (let [opts (cond-> {:max-depth default-depth}
                         proof? (assoc :proof? true))
                  results (v/query kb goal context opts)]
              {:answers
               (vec
                (if proof?
                  ;; results are [{:bindings {...} :proof {...}} ...]
                  (let [conjuncts (if (vector? goal) goal [goal])]
                    (map (fn [r]
                           {:bindings      (bindings->wire (:bindings r))
                            :justification (-> (:proof r)
                                               (ground-proof-for-answer
                                                conjuncts (:bindings r))
                                               (justification->wire context))})
                         results))
                  ;; results are [{?x val ...} ...]
                  (map (fn [bindings]
                         {:bindings      (bindings->wire bindings)
                          :justification nil})
                       results)))})))))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          ;; Effect uncertainty must cross this boundary intact — the
          ;; dispatcher owns the outcome_indeterminate completion and response.
          :outcome-indeterminate (throw e)
          :shape        (mcp-error "invalid_sentence" (.getMessage e))
          :unauthorized (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- verb: argue -----------------------------------------------------------

(defn verb-argue
  "Four-valued epistemic status of a closed (ground) goal.

  Input:  {\"closed_goal\" \"(dog Muffet)\" \"context\" \"CxWell\"}
  Output: {:verdict \"true\" :for_justification {...} :against_justification nil}

  A \"false\" verdict has one of two grounds, told apart by
  `against_justification`'s `:kind`: a provable denial `(not G)` is
  `\"inference\"`, its tree the denial's justification; a stored G that a
  placed nogood defeats (`argue`'s `:defeated-by`, e.g. a `:default` filler
  of a `functional` predicate beside a `:monotonic` one) is `\"defeat\"`, its
  tree the engine's defeat reports — each with `:loser`, `:winners`
  (`{:handle :sentence :context :defeat-class :rules}`, stored claims
  carrying `:provenance`) and `:placed-in`, the contexts the nogood is placed
  in. A stated denial wins the side when both are there. A conjunction's
  against side is derived, so it stays `\"inference\"`; a defeated conjunct's
  entry in its tree is that conjunct's `\"defeat\"` justification."
  [input]
  (try
    (let [[gok goal]    (parse-edn (get input "closed_goal") "sentence")
          [cok context] (parse-context (get input "context"))]
      (cond
        (not gok) goal
        (not cok) context
        :else
        ;; Auth check before any work — fork is the security boundary
        (let [kb *mcp-kb*
              _  (when-not kb (throw (ex-info "no MCP KB bound" {:type :unauthorized})))
              ;; Normalize connective forms before validation
              [nok normalized] (normalize-goal goal)]
          (cond
            (not nok) normalized  ; error from normalize-goal (e.g. non-empty or)

            ;; Nullary identity: (and) → true — trivially true
            (= normalized :nullary-true)
            {:verdict               "true"
             :for_justification     {:kind "inference" :depth_used 0
                                     :tree [{:goal "(and)" :via "nullary-identity"
                                             :because []}]}
             :against_justification nil}

            ;; Nullary identity: (or) → false — trivially false
            (= normalized :nullary-false)
            {:verdict               "false"
             :for_justification     nil
             :against_justification {:kind "inference" :depth_used 0
                                     :tree [{:goal "(or)" :via "nullary-identity"
                                             :because []}]}}

            ;; (and G1 G2 ...) was normalized to a vector — argue each conjunct.
            ;; All must be true for the conjunction to be true; any false makes it false;
            ;; any unknown (with none false) makes it unknown.
            ;; Empty vector [] — same as nullary (and): trivially true
            (and (vector? normalized) (empty? normalized))
            {:verdict               "true"
             :for_justification     {:kind "inference" :depth_used 0
                                     :tree [{:goal "(and)" :via "nullary-identity"
                                             :because []}]}
             :against_justification nil}

            (vector? normalized)
            (let [conjunct-results (mapv (fn [g]
                                           (let [fv (eh/free-vars g)]
                                             (if (seq fv)
                                               {:verdict :error :goal g :fv fv}
                                               (v/argue kb g context {:max-depth default-depth}))))
                                         normalized)
                  fv-errors (filter #(= :error (:verdict %)) conjunct-results)]
              (if (seq fv-errors)
                (mcp-error "invalid_sentence"
                           (str "closed_goal conjuncts must be ground, but found free variables in: "
                                (pr-str (mapv :goal fv-errors))))
                ;; Belnap four-valued conjunction: component-wise AND.
                ;; Each verdict → [support-for, support-against]:
                ;;   true=[T,F], false=[F,T], unknown=[F,F], contradiction=[T,T]
                ;; Conjunction: [AND-of-fors, OR-of-againsts]
                (let [to-pair   (fn [v] (case (name v)
                                          "true"          [true false]
                                          "false"         [false true]
                                          "unknown"       [false false]
                                          "contradiction" [true true]))
                      pairs     (mapv #(to-pair (:verdict %)) conjunct-results)
                      conj-for  (every? first pairs)
                      conj-agt  (boolean (some second pairs))
                      verdict   (cond
                                  (and conj-for (not conj-agt))  "true"
                                  (and (not conj-for) conj-agt)  "false"
                                  (and conj-for conj-agt)        "contradiction"
                                  :else                          "unknown")
                      for-trees  (keep #(or (:for-why %) (:for %)) conjunct-results)
                      ;; A conjunct a placed nogood defeats has no (not G) to
                      ;; show; its entry is the defeat, kind "defeat", while
                      ;; the conjunction's own against side, derived, stays
                      ;; kind "inference".
                      agt-trees  (keep #(or (:against-why %) (:against %)
                                            (defeat->wire (:defeated-by %) context))
                                       conjunct-results)]
                  ;; Envelope invariant: proof sides must agree with verdict.
                  ;; true → for non-null (all conjuncts), against null
                  ;; false → for null, against non-null
                  ;; contradiction → both non-null
                  ;; unknown → both null
                  {:verdict verdict
                   :for_justification (when (and conj-for (seq for-trees))
                                        (justification->wire
                                         {:kind "inference" :depth_used default-depth
                                          :tree (vec for-trees)}
                                         context))
                   :against_justification (when (and conj-agt (seq agt-trees))
                                            (justification->wire
                                             {:kind "inference" :depth_used default-depth
                                              :tree (vec agt-trees)}
                                             context))})))

            ;; Normal sentence — validate: must be a list
            (not (sequential? normalized))
            (mcp-error "invalid_sentence"
                       (str "closed_goal must be a list (s-expression), got " (pr-str (type normalized))))

            :else
            ;; Validate: must be ground (no free variables)
            (let [fv (eh/free-vars normalized)]
              (if (seq fv)
                (mcp-error "invalid_sentence"
                           (str "closed_goal must be ground (no free variables), but found: "
                                (pr-str (vec fv))))
                (let [r       (v/argue kb normalized context {:max-depth default-depth})
                      verdict (name (:verdict r))]
                  ;; Envelope invariant, as for a conjunction: false → for null,
                  ;; against non-null.  A stored goal a placed nogood defeats
                  ;; answers :false with no (not G) provable, so the against
                  ;; side is then the defeat (kind "defeat"); a stated denial
                  ;; keeps its own justification (kind "inference").
                  {:verdict              verdict
                   :for_justification    (justification->wire (:for-why r) context)
                   :against_justification (or (justification->wire (:against-why r) context)
                                              (defeat->wire (:defeated-by r) context))})))))))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          ;; Effect uncertainty must cross this boundary intact — the
          ;; dispatcher owns the outcome_indeterminate completion and response.
          :outcome-indeterminate (throw e)
          :shape        (mcp-error "invalid_sentence" (.getMessage e))
          :unauthorized (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- verb: matching --------------------------------------------------------

(def matching-default-limit
  "Rows `matching` examines when the caller names no `limit`."
  100)

(def matching-max-limit
  "The largest `limit` `matching` accepts."
  1000)

(defn- parse-pattern
  "Parse a `matching` pattern: one literal s-expression. Conjunctions — a
  vector, `(and …)`, `(or …)` — are refused here rather than handed to the
  engine, so the refusal names the verb's own contract."
  [s]
  (let [[ok v] (parse-edn s "pattern")]
    (cond
      (not ok)
      [nil (assoc v :code "invalid_sentence")]

      (or (vector? v) (and-form? v) (or-form? v))
      [nil (mcp-error "invalid_sentence"
                      (str "pattern must be a single literal, not a conjunction or "
                           "disjunction: " (pr-str v) ". Use query for conjunctive goals."))]

      (not (sequential? v))
      [nil (mcp-error "invalid_sentence"
                      (str "pattern must be a single literal (an s-expression), got "
                           (pr-str (type v))))]

      :else [true v])))

(defn- parse-matching-options
  "Validate `asserted_only` and `limit`, returning [ok {:asserted-only? :limit}]
  or [nil error-map]. Out-of-range or mistyped values are refused, never
  clamped or coerced: a caller must not believe an option took effect when it
  did not."
  [input]
  (let [ao    (get input "asserted_only" false)
        limit (get input "limit" matching-default-limit)]
    (cond
      (not (boolean? ao))
      [nil (mcp-error "invalid_arguments"
                      (str "asserted_only must be a boolean, got " (pr-str ao)))]

      (not (and (integer? limit) (<= 1 limit matching-max-limit)))
      [nil (mcp-error "invalid_arguments"
                      (str "limit must be an integer from 1 to " matching-max-limit
                           ", got " (pr-str limit)))]

      :else [true {:asserted-only? ao :limit (long limit)}])))

(defn verb-matching
  "Stored, believed sentexes matching a single-literal pattern in a context —
  a literal match against what is stored (asserted or rule-derived), with no
  inference and no subtype expansion. A read: no write fence, no mutation;
  it sees exactly what the bound fork sees, like `query`.

  `limit` bounds the stored matches examined: the engine's lazy seq is cut
  with `take` before it is realized, so an open pattern cannot walk the whole
  store. `truncated` is true when more matches existed beyond the limit.
  `asserted_only` then keeps only premises, so with it a truncated answer can
  hold fewer than `limit` rows.

  Input:  {\"pattern\" \"(likes ?x Bacon)\" \"context\" \"CxWell\"
           \"asserted_only\" false \"limit\" 100}
  Output: {:rows [{:sentence \"(likes Muffet Bacon)\" :context \"CxWell\"
                   :handle 42 :asserted true :provenance {...}}]
           :count 1 :truncated false}"
  [input]
  (try
    (let [[pok pattern] (parse-pattern (get input "pattern"))
          [cok context] (parse-context (get input "context"))
          [ook opts]    (parse-matching-options input)]
      (cond
        (not pok) pattern
        (not cok) context
        (not ook) opts
        :else
        (let [kb *mcp-kb*
              _  (when-not kb (throw (ex-info "no MCP KB bound" {:type :unauthorized})))
              {:keys [asserted-only? limit]} opts
              ;; one past the limit: realizing it tells truncation from an exact fit
              candidates (vec (take (inc limit) (v/sentexes-matching kb pattern context)))
              truncated (> (count candidates) limit)
              rows      (into []
                              (comp (map (fn [sx]
                                           (let [h (:id sx)]
                                             {:sentence   (pr-str (:sentence sx))
                                              :context    (pr-str (:context sx))
                                              :handle     h
                                              :asserted   (boolean (v/premise? kb h))
                                              :provenance (provenance->wire (v/provenance kb h))})))
                                    (filter #(or (not asserted-only?) (:asserted %))))
                              (take limit candidates))]
          {:rows rows :count (count rows) :truncated truncated})))
    (catch clojure.lang.ExceptionInfo e
      (let [t (:type (ex-data e))]
        (case t
          :outcome-indeterminate (throw e)
          :shape        (mcp-error "invalid_sentence" (.getMessage e))
          :unauthorized (mcp-error "unauthorized" (.getMessage e))
          (mcp-error "server_error" (.getMessage e)))))
    (catch Throwable t
      (server-error t))))

;; ---- dispatch table --------------------------------------------------------

(def verbs
  "The six MCP verbs, keyed by their wire name."
  {"assert"          verb-assert
   "unassert"        verb-unassert
   "unassert_handle" verb-unassert-handle
   "query"           verb-query
   "argue"           verb-argue
   "matching"        verb-matching})

(def read-verbs
  "Wire names of the verbs that never mutate the fork. The dispatcher records
  their successful completions with effect \"none\"."
  #{"query" "argue" "matching"})

;; ---- tool schemas (for MCP tool listing) -----------------------------------

(def schemas
  "The six verb schemas in MCP tool-list format."
  [{"name"         "assert"
    "description"  (str "Assert a sentence in a context. The sentence is stored as a "
                        "belief in the fork KB at :default strength (never monotonic). "
                        "Idempotent: re-asserting returns the existing handle with "
                        "already_there: true.")
    "inputSchema" {"type"       "object"
                   "properties" {"sentence" {"type"        "string"
                                             "description" "A sentence as an EDN s-expression: \"(dog Muffet)\", \"(parentOf Tom Bob)\"."}
                                 "context"  {"type"        "string"
                                             "description" "A context name as EDN: \"CxWell\"."}}
                   "required"   ["sentence" "context"]
                   "additionalProperties" false}}

   {"name"         "unassert"
    "description"  (str "Ensure a sentence is not believed from a context. Finds all "
                        "visible sentexes matching the sentence and excepts each. "
                        "Idempotent: if already not believed, returns an empty array.")
    "inputSchema" {"type"       "object"
                   "properties" {"sentence" {"type"        "string"
                                             "description" "The sentence to unassert, as EDN: \"(dog Muffet)\"."}
                                 "context"  {"type"        "string"
                                             "description" "The context to unassert from, as EDN: \"CxWell\"."}}
                   "required"   ["sentence" "context"]
                   "additionalProperties" false}}

   {"name"         "unassert_handle"
    "description"  (str "Withdraw one specific sentex by its handle. Asserts an "
                        "except against the handle. Returns null if already withdrawn "
                        "or not believed. Errors with not_found for unknown handles.")
    "inputSchema" {"type"       "object"
                   "properties" {"sentex_handle" {"type"        "integer"
                                                  "description" "The sentex handle (integer id) to withdraw."}}
                   "required"   ["sentex_handle"]
                   "additionalProperties" false}}

   {"name"         "query"
    "description"  (str "Answer a goal in a context. The goal is an EDN s-expression "
                        "(a single sentence or a vector for conjunctive query). "
                        "Returns binding maps for each answer. Ground success: "
                        "[{bindings: {}}]. No match: [].")
    "inputSchema" {"type"       "object"
                   "properties" {"goal"                   {"type"        "string"
                                                           "description" (str "A goal sentence as EDN: \"(dog ?x)\", "
                                                                              "or a vector for conjunctive query: "
                                                                              "\"[(parentOf ?x ?y) (dog ?y)]\".")}
                                 "context"                {"type"        "string"
                                                           "description" "The context to query in, as EDN: \"CxWell\"."}
                                 "include_justification"  {"type"        "boolean"
                                                           "description" "If true, include the inference justification tree for each answer."}}
                   "required"   ["goal" "context"]
                   "additionalProperties" false}}

   {"name"         "argue"
    "description"  (str "Four-valued epistemic status of a ground (closed) assertion. "
                        "Queries both the sentence and its explicit negation. "
                        "Verdicts: \"true\", \"false\", \"unknown\", \"contradiction\".")
    "inputSchema" {"type"       "object"
                   "properties" {"closed_goal" {"type"        "string"
                                                "description" (str "A ground sentence (no free variables) as EDN: "
                                                                   "\"(dog Muffet)\". Must be closed — variables are refused.")}
                                 "context"     {"type"        "string"
                                                "description" "The context to argue in, as EDN: \"CxWell\"."}}
                   "required"   ["closed_goal" "context"]
                   "additionalProperties" false}}

   {"name"         "matching"
    "description"  (str "Stored, believed facts matching a pattern, with no inference. "
                        "asserted_only filters out derived ones.")
    "inputSchema" {"type"       "object"
                   "properties" {"pattern"       {"type"        "string"
                                                  "description" (str "A single literal as EDN, variables allowed: "
                                                                     "\"(likes ?x Bacon)\". Conjunctions are refused.")}
                                 "context"       {"type"        "string"
                                                  "description" "The context to match in, as EDN: \"CxWell\"."}
                                 "asserted_only" {"type"        "boolean"
                                                  "description" "If true, keep only facts asserted in their own right, not derived. Default false."}
                                 "limit"         {"type"        "integer"
                                                  "minimum"     1
                                                  "maximum"     matching-max-limit
                                                  "description" (str "How many stored matches to examine (default "
                                                                     matching-default-limit ", max " matching-max-limit
                                                                     "). truncated is true when more exist.")}}
                   "required"   ["pattern" "context"]
                   "additionalProperties" false}}])

;; ---- dispatch ---------------------------------------------------------------

(def ^:private allowed-keys
  "Wire argument keys each verb accepts, derived from the published schemas so
  enforcement cannot drift from the contract (\"additionalProperties\" false is
  a claim to clients; this makes the server keep it)."
  (into {}
        (map (fn [s] [(get s "name")
                      (set (keys (get-in s ["inputSchema" "properties"])))]))
        schemas))

(defn dispatch
  "Dispatch a verb call.  `verb-name` is the wire name (a string), `input` is the
  JSON object (string keys).  Returns the result map or an error map — never throws.

  Fail-closed on unknown arguments: any input key outside the verb's published
  schema is rejected before the verb runs.  Silently ignoring an argument would
  let a caller believe an option took effect when it did not."
  [verb-name input]
  (if-let [f (get verbs verb-name)]
    (let [allowed (get allowed-keys verb-name #{})
          unknown (remove allowed (keys input))]
      (if (seq unknown)
        (mcp-error "invalid_arguments"
                   (str "unknown argument(s) for " verb-name ": "
                        (str/join ", " (map pr-str unknown))))
        (f input)))
    (mcp-error "not_found" (str "unknown verb: " verb-name))))
