# The MCP server

**Covers:** `vaelii.agents.mcp.*` — a Model Context Protocol server over a Vaelii KB:
sessions, forks, the six verb tools, the two transports, and the audit journal.
**Not here:** the model-provider clients and the generated KB read tools
([llm.md](llm.md)), which a model-calling agent uses instead of, or beside, this server.
**Assumes:** the engine's model ([README](https://github.com/vaelii/vaelii#the-model)).

## What it is

An [MCP](https://modelcontextprotocol.io) server that gives a client — an editor, an
agent runtime, anything that speaks MCP — write access to a Vaelii knowledge base through
six tools, each a verb an authenticated principal calls against its own fork. Unlike
`vaelii.agents.tools` (generated read-only tools dispatched through the engine's op
table), this server's verbs write: `assert` and `unassert` change belief state, under a
durable audit journal and a security-authority registry that can revoke a principal
mid-session and have that revocation fence a write already in flight.

| namespace | what it holds |
|-----------|---------------|
| `vaelii.agents.mcp.verbs` | the six verb tools (`assert`, `unassert`, `unassert_handle`, `query`, `argue`, `matching`), their MCP tool schemas, and the fail-closed dispatch table |
| `vaelii.agents.mcp.dispatcher` | transport-neutral JSON-RPC dispatch, the audit-sink protocol and its durable `JournalAuditSink`, and effect fencing |
| `vaelii.agents.mcp.session` | the session/fork/authority registry: one atom, commit-before-ack persistence, lease expiry |
| `vaelii.agents.mcp.audit` | pure v1 audit-record construction, validation, normalization and hashing — no I/O ([audit-journal-v1-design.md](audit-journal-v1-design.md)) |
| `vaelii.agents.mcp.config` | the EDN server-config reader: bearer-token fingerprints to principal/fork identity |
| `vaelii.agents.mcp.transport` | the stdio JSON-RPC 2.0 loop |
| `vaelii.agents.mcp.http-transport` | the Streamable HTTP transport (`/mcp`) and a raw-operation route (`/op`) |
| `vaelii.agents.mcp.overlay` | an optional directory of extra `.clj`/`excepts.edn` knowledge loaded into the base KB at server start |
| `vaelii.agents.mcp.server` | the production `-main` entry point: startup ordering, custody checks, and wiring |
| `vaelii.agents.mcp.argue` | a standalone four-valued (`:true`/`:false`/`:unknown`/`:contradiction`) epistemic query over ground assertions, independent of the verb of the same name, which calls `vaelii.core/argue` directly |
| `vaelii.agents.mcp.kb-utils` | small `vaelii.core` helpers the overlay loader needs |

## Running it

```sh
clojure -M -m vaelii.agents.mcp.server --config config.edn --port 3000
```

A minimal `config.edn`:

```clojure
{:state-dir "state"
 :forks-dir "forks"
 :principals
 {"alice" {:token-sha256 "<sha-256 hex of alice's bearer token>"
           :fork-id      "alice"
           :lifecycle    :durable}}}
```

`:state-dir` is always required (durable authority — revocations and fork metadata —
persists there even for an all-leased config); `:forks-dir` is required once any
principal's `:lifecycle` is `:durable`. `VAELII_AUDIT_DIR` (an absolute, symlink-free path
outside `/tmp` and disjoint from `:forks-dir`/`:state-dir`) must name the audit journal's
directory; production refuses to start without it.

Two or more principals may name the same `:fork-id`: they then share one forked KB,
each write server-recorded with its own acting principal
([mcp-shared-fork-proof.md](mcp-shared-fork-proof.md)). The first principal to name a
fork fixes its `:lifecycle`/`:lease-ttl-ms`; a later principal naming the same fork with a
different shape is refused at config-read time.

## The six verbs

| tool | reads or writes | what it does |
|------|------------------|--------------|
| `assert` | write | store a sentence in a context, capped at `:default` strength |
| `unassert` | write | except every visible sentex matching a sentence from a context |
| `unassert_handle` | write | except one sentex by its handle |
| `query` | read | answer a goal (a sentence, or a vector for a conjunctive query), optionally with its inference justification |
| `argue` | read | the four-valued epistemic verdict for a ground (closed) goal |
| `matching` | read | stored, believed sentexes matching a single literal pattern — no inference, no subtype expansion |

Every verb's sentence/goal/context/pattern argument is a JSON string holding an **EDN**
s-expression (`"(dog ?x)"`), read with `clojure.edn/read-string` — never `read-string` —
so a client's output cannot execute code. A write verb additionally re-derives, ahead of
the write, every predicate the sentence would actually store under (rule surfaces, `ist`
nesting, `exceptWhen` peeling, `forall` desugaring) and refuses an undeclared one before
anything is stored; see the "Reaching into the engine" note below.

A result's wire shape is a JSON object serialized from the verb's result map; an error is
`{"isError": true, "content": [{"type": "text", "text": "{:code ... :error ...}"}]}`.
Every stored-claim node in a justification tree carries a server-recorded `:provenance`
(`{:creator :asserted_at}`), read from the fork, never trusted from the caller.

## Transports

**Stdio** (`vaelii.agents.mcp.transport/start-stdio!`): newline-delimited JSON-RPC on
stdin/stdout, authentication by `VAELII_TOKEN` or the `initialize` call's `_meta.token`.

**Streamable HTTP** (`vaelii.agents.mcp.http-transport`): `POST`/`DELETE`/`GET` on `/mcp`
(MCP Streamable HTTP), bearer-token authentication against `config.edn`, an `Origin`
allowlist, and a bounded request body. `POST /op` is a narrower raw-operation route for a
client that only needs to add facts: `{:op :edit :args [{:add [[sentence context] ...]}]}`
goes through the same `assert` verb, on an ephemeral session, so audit, write-fencing and
provenance recording apply exactly as on `/mcp`.

## Audit and authority

Every call — successful, refused, or denied before authentication — leaves a durable
`Attempt` and `Completion` record in an append-only, fsync'd, process-locked JSONL
journal (`vaelii.agents.mcp.dispatcher/JournalAuditSink`); see
[audit-journal-v1-design.md](audit-journal-v1-design.md) for the full schema. Audit
unavailability fails closed: no verb runs without a durable `Attempt` first.

The session registry (`vaelii.agents.mcp.session`) holds one atom for sessions, revoked
principals, fork handles and metadata, and a monotonic generation counter. A write
acquires a generation permit at admission and checks it again at the mutation boundary,
under the same authority lock a revocation takes — so a principal revoked mid-request
either fences the write before it commits, or loses the race and its write commits
with a receipt that states exactly what happened, never a response that claims
both outcomes at once.

## Engine reach

`vaelii.agents.mcp.*` calls 26 engine functions outside `vaelii.core`: 23 in
`vaelii.impl.*` across seven namespaces, and three in `vaelii.host.guard`. None is
required directly from `vaelii.agents.mcp.*`. Every call goes through one re-exporting
var in `vaelii.agents.engine`, so an engine change is repaired in one namespace (README,
"Reaching into the engine"). By owning namespace, with the caller:

| engine namespace | vars | called from, for |
|------------------|------|------------------|
| `vaelii.host.guard` | `require-token!`, `api-token`, `public-bind?` | `server`: the browser-bind token check, run before any fork store opens |
| `vaelii.impl.caches` | `register-cache` | `server`: registers the MCP request body limit with the daemon's bounded-state census |
| `vaelii.impl.io.text` | `read-forms`, `load-entries!` | `overlay`: the text-KB reader and loader, reused for overlay files |
| `vaelii.impl.naming` | `context?`, `literals` | `overlay` and `verbs`: context-argument checks; the predicate walk |
| `vaelii.impl.assert-entry` | `ist-parts`, `storage-context` | `verbs`: the predicate walk |
| `vaelii.impl.rules` | `soft-priority-surface?`, `cardinality-surface?`, `minimize-surface?`, `split-exceptWhen`, `inner-rule`, `expand-rule` | `verbs`: the predicate walk |
| `vaelii.impl.sentex` | `conjunction?`, `peel-exception-strength`, `forall?`, `desugar-forall-literal`, `defn-sentence?`, `rule-functor` | `verbs`: the predicate walk |
| `vaelii.impl.sentex` | `free-vars`, `sentex-handle` | `verbs`: ground-goal checks in `query`/`argue`; the `except` term `unassert` writes |
| `vaelii.impl.resolution` | `substitute`, `unify` | `verbs`: grounding a conjunctive answer's justification tree |

**The predicate walk.** Before `assert` stores a sentence, `verbs/asserted-predicate-uses`
lists every predicate the sentence would store under, and the context each one stores
in. It handles rule surfaces, `ist` nesting, `except`/`exceptWhen` peeling, `forall`
desugaring, and a decontextualized predicate's storage context. An undeclared predicate
is refused before anything is stored. 15 of the 26 vars exist only for this walk, and
the walk duplicates decisions the engine's own assert path already makes.

**Proposed `vaelii.core` entry point.**

```clojure
(vaelii.core/predicate-uses kb sentence context)
;; => a distinct seq of [predicate storage-context] pairs: every predicate
;;    (assert kb sentence context) would store under, and where.
;;    Reads only; stores nothing.
```

The engine's assert path computes these pairs itself, so this entry point would answer
from that code rather than from a copy. With it, `asserted-predicate-uses` becomes one
call, and the 15 walk vars leave `vaelii.agents.engine`. The other 11 are narrower:
`sentex-handle` already has a `vaelii.core` counterpart (`kb-utils` calls
`vaelii.core/sentex-handle`) and can move to it, and `free-vars`, `substitute` and `unify`
are general term operations a `vaelii.core` read could expose. The `guard`, `caches` and
`io.text` reaches serve a server process, not a verb, and can stay component-local.
