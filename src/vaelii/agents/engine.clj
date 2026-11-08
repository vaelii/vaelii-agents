;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.engine
  "The `vaelii.host.*` vars this repo reads, and the one namespace in `vaelii.agents.*`
  that reaches for them.  `vaelii.host.*` is the engine's tooling tier, private and free
  to change, so a breaking change upstream is an edit here.  Each var is the engine's
  own under the name the callers use.

  The daemon's op tables (`vaelii.host.serve`): [[ops]], [[kbless-ops]], [[feed-ops]] and
  [[write-ops]].
  `vaelii.agents.tools` generates the model's tool schemas from the read subset of
  [[ops]] and dispatches tool calls back through it, so a model's reads are the daemon's
  reads with no second copy.

  `vaelii.agents.mcp.*` (the MCP server) reaches further than `vaelii.agents.tools` does:
  its write verbs (`assert`, `unassert`) have to reconstruct, ahead of a write, every
  predicate a sentence's assert would actually store — rule surfaces, `ist` nesting,
  `except`/`exceptWhen` peeling, `forall` desugaring, storage-context resolution — work
  `vaelii.core` does not expose a single call for. The vars below are that reach, one
  per engine fn the server calls outside `vaelii.core`:
  [[require-token!]], [[api-token]] and [[public-bind?]] (`vaelii.host.guard`);
  [[register-cache]] (`vaelii.impl.caches`); [[read-forms]] and [[load-entries!]]
  (`vaelii.impl.io.text`); [[context?]] and [[literals]] (`vaelii.impl.naming`);
  [[ist-parts]] and [[storage-context]] (`vaelii.impl.assert-entry`);
  [[soft-priority-surface?]], [[cardinality-surface?]], [[minimize-surface?]],
  [[split-exceptWhen]], [[inner-rule]] and [[expand-rule]] (`vaelii.impl.rules`);
  [[free-vars]], [[conjunction?]], [[peel-exception-strength]], [[forall?]],
  [[desugar-forall-literal]], [[defn-sentence?]], [[rule-functor]] and
  [[sentex-handle]] (`vaelii.impl.sentex`); [[substitute]] and [[unify]]
  (`vaelii.impl.resolution`). None of this is `vaelii.host.*` or `vaelii.impl.*`
  surface this repo would propose keeping stable on its own account — every var here
  is a candidate for a `vaelii.core` read a write-verb author would otherwise have to
  duplicate (see this repo's README, \"Reaching into the engine\", and docs/mcp.md,
  \"Engine reach\", which proposes the `vaelii.core` entry point)."
  (:require [vaelii.host.guard :as guard]
            [vaelii.host.serve :as serve]
            [vaelii.impl.assert-entry :as assert-entry]
            [vaelii.impl.caches :as caches]
            [vaelii.impl.io.text :as text]
            [vaelii.impl.naming :as naming]
            [vaelii.impl.resolution :as resolution]
            [vaelii.impl.rules :as rules]
            [vaelii.impl.sentex :as sentex]))

(def ops
  "The daemon's reachable operations, `{op-keyword (fn [kb args])}`: the allowlisted,
  EDN-typed map of `vaelii.core` calls the browser and the daemon reach a KB through."
  serve/ops)

(def kbless-ops
  "The op keywords in [[ops]] whose `vaelii.core` fn takes no KB, so the first parameter
  of the var is an argument the caller sends rather than the KB the daemon supplies."
  serve/kbless-ops)

(def write-ops
  "The op keywords in [[ops]] the daemon files as writes: every op that stores, removes or
  annotates a sentex a request names, and `:export`."
  serve/write-ops)

(def feed-ops
  "The daemon's change-feed operations, keyed by op keyword.  None of them is in [[ops]],
  so none of them is a model tool: a subscription holds daemon heap."
  serve/feed-ops)

;; ---- vaelii.host.guard: bearer-token admission for a non-loopback bind --------

(def require-token!
  "`vaelii.host.guard/require-token!` — throws `:unauthorized` when `host` is not
  loopback and `token` is nil.  `vaelii.agents.mcp.server` calls this on the browser
  bind before any fork store opens, so a public host with no token refuses at
  startup rather than after."
  guard/require-token!)

(def api-token
  "`vaelii.host.guard/api-token` — the bearer token `VAELII_API_TOKEN` names, or nil."
  guard/api-token)

(def public-bind?
  "`vaelii.host.guard/public-bind?` — true when `host` is not loopback, so a bind
  there is reachable off-box and needs a token."
  guard/public-bind?)

;; ---- vaelii.impl.caches: the bounded-state census -----------------------------

(def register-cache
  "`vaelii.impl.caches/register-cache` — registers one bounded-state entry with the
  daemon's cache census.  `vaelii.agents.mcp.server` registers the MCP request
  body-limit as a configuration entry, not a cache, so the census accounts for it."
  caches/register-cache)

;; ---- vaelii.impl.io.text: the text-KB file loader -----------------------------

(def read-forms
  "`vaelii.impl.io.text/read-forms` — every top-level EDN form in a `.clj` knowledge
  file, or throws `:unreadable` with the line it opens on.  The reader
  `vaelii.agents.mcp.overlay` uses for each overlay file, same as a starter's own."
  text/read-forms)

(def load-entries!
  "`vaelii.impl.io.text/load-entries!` — asserts `[form context]` entries in one
  order-insensitive pass, retrying a form refused because what it rests on is
  further down; the same loader `vaelii.core/load-text!` and the starter use.
  `vaelii.agents.mcp.overlay` loads every overlay file's forms through it."
  text/load-entries!)

;; ---- vaelii.impl.naming: context and literal-position vocabulary -------------

(def context?
  "`vaelii.impl.naming/context?` — true when a symbol names a context by vaelii's
  convention: a `Cx…` CapitalCamelCase symbol, or a reified context constant in the
  `cx/` namespace."
  naming/context?)

(def literals
  "`vaelii.impl.naming/literals` — the literal positions a rule-shaped sentence
  reads from, in the order a rule evaluates them.  `vaelii.agents.mcp.verbs` walks
  these to find every predicate an `assert` would store, ahead of the write."
  naming/literals)

;; ---- vaelii.impl.assert-entry: where a sentence actually stores ---------------

(def ist-parts
  "`vaelii.impl.assert-entry/ist-parts` — `[inner-context inner-sentence]` when a
  sentence is wrapped in `(ist context …)`, else nil."
  assert-entry/ist-parts)

(def storage-context
  "`vaelii.impl.assert-entry/storage-context` — the context a rule's consequent
  (named by its functor) actually stores to, which the request's own context may
  not be: a decontextualized predicate's rule can store elsewhere."
  assert-entry/storage-context)

;; ---- vaelii.impl.rules: rule-surface and rule-expansion vocabulary ------------

(def soft-priority-surface?
  "`vaelii.impl.rules/soft-priority-surface?` — true when a sentence is a
  soft-priority wrapper, whose third element is the sentence it actually governs."
  rules/soft-priority-surface?)

(def cardinality-surface?
  "`vaelii.impl.rules/cardinality-surface?` — true when a sentence is a cardinality
  wrapper, whose fourth element is the sentence it actually governs."
  rules/cardinality-surface?)

(def minimize-surface?
  "`vaelii.impl.rules/minimize-surface?` — true when a sentence is a minimize
  wrapper, whose fourth element is the sentence it actually governs."
  rules/minimize-surface?)

(def split-exceptWhen
  "`vaelii.impl.rules/split-exceptWhen` — `[exceptions inner]`: a sentence's
  `exceptWhen` clauses peeled from the sentence they qualify."
  rules/split-exceptWhen)

(def inner-rule
  "`vaelii.impl.rules/inner-rule` — an expanded rule with its wrapping (priority,
  cardinality, minimize) peeled back to the plain `(implies …)` or fact it governs."
  rules/inner-rule)

(def expand-rule
  "`vaelii.impl.rules/expand-rule` — every rule a sentence's `exceptWhen`-stripped
  form expands to, before `inner-rule` peels each one's wrapping."
  rules/expand-rule)

;; ---- vaelii.impl.sentex: sentence-shape vocabulary ----------------------------

(def free-vars
  "`vaelii.impl.sentex/free-vars` — the free (unbound) variables in a sentence or
  goal."
  sentex/free-vars)

(def conjunction?
  "`vaelii.impl.sentex/conjunction?` — true when a sentence is an `(and …)` form."
  sentex/conjunction?)

(def peel-exception-strength
  "`vaelii.impl.sentex/peel-exception-strength` — `[sentence strength]`: a
  sentence with its leading exception-strength marker, if any, removed."
  sentex/peel-exception-strength)

(def forall?
  "`vaelii.impl.sentex/forall?` — true when a literal is a `(forall …)` form."
  sentex/forall?)

(def desugar-forall-literal
  "`vaelii.impl.sentex/desugar-forall-literal` — a `(forall …)` literal rewritten
  to the plain sentence it quantifies over."
  sentex/desugar-forall-literal)

(def defn-sentence?
  "`vaelii.impl.sentex/defn-sentence?` — true when a literal is a reifiable-function
  definition, whose condition is executable rather than ordinary metadata."
  sentex/defn-sentence?)

(def rule-functor
  "`vaelii.impl.sentex/rule-functor` — the symbol (`implies`) a rule's normalized
  form is headed by."
  sentex/rule-functor)

(def sentex-handle
  "`vaelii.impl.sentex/sentex-handle` — the `(sentexHandle h)` term naming a stored
  sentex by its integer handle, the argument an `except` takes to withdraw it."
  sentex/sentex-handle)

;; ---- vaelii.impl.resolution: unification over a goal's bindings --------------

(def substitute
  "`vaelii.impl.resolution/substitute` — a sentence with a binding map's values
  substituted for its variables."
  resolution/substitute)

(def unify
  "`vaelii.impl.resolution/unify` — bindings that unify two sentences, extending a
  given binding map, or nil when they cannot unify under it."
  resolution/unify)
