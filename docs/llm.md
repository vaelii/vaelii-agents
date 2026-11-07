# Model providers and KB tools

**Covers:** the `Provider` protocol and its three backends, the stub, the vote pool, the
read tools a model calls, the environment variables, and the refusal `:type`s.
**Not here:** the engine's daemon and its op table
([operations.md](https://github.com/vaelii/vaelii/blob/main/docs/operations.md)); an
agent loop or a protocol server, which a caller writes over these namespaces.
**Assumes:** the engine's model ([README](https://github.com/vaelii/vaelii#the-model)).

## Tools

### `serve/ops` is already a tool registry

The engine daemon's op table, `vaelii.host.serve/ops` (read here as
`vaelii.agents.engine/ops`), is an allowlisted, EDN-typed map of `vaelii.core` calls: the
surface the engine's daemon and browser reach a KB through. `tools/schemas` derives the
model's tools from its **read subset** rather than transcribing them. Parameter names and
arities come from each `vaelii.core` var's own `:arglists`, and descriptions from its
docstring. Three sets are subtracted:

- `tools/write-ops` is the daemon's own write set (`serve/write-ops`) plus five ops the
  daemon files elsewhere. `:forward-chain` stores what the rules derive, `:abduce` stores
  its hypotheses in a scratch context and `:abduce-discard` drops that context. `:preview`
  stores nothing, but holds the process's single writer and advances the handle counter,
  and `:clear-caches` mutates the process's measurement state.
- `tools/host-path-ops` holds `:kb-diff`, whose second side is a directory on the daemon's
  **host** rather than a value on the wire. Exposing it would hand a prompt-injected model
  a path-recon primitive.
- `tools/unbounded-ops` holds seven reads with no bound a call can carry. `:handles` and
  `:terms` sort every handle or term in the KB into one vector before the printer's bound
  applies, and `:explain-levels` realizes every level in full under no clock.
  `:kb-quality`, `:all-specified-violations`, `:all-functional-at-instant-violations` and
  `:exposed-clashes` audit the whole KB. `kb_sentex_count`, `kb_term_count`,
  `kb_find_terms`, `kb_escalate` and `kb_kb_integrity`, the last two held to the daemon's
  clock ceiling, answer the narrower questions.

An op whose name resolves to no `vaelii.core` var, or to a variadic one, has no single
signature to read and is not a tool either. The model sees every other entry as a tool. A
read added to `serve/ops` becomes a tool with no edit here, and a signature change is
picked up on the next build.

Names are munged to the provider's identifier grammar: `:find-sentexes` becomes
`kb_find_sentexes`, and `:ask?` becomes `kb_ask_p` (so it stays distinct from `kb_ask`).
JSON carries no symbols, so a sentence, context or term argument is a **string holding an
EDN s-expression** (`"(dog ?x)"`), read back with `clojure.edn/read-string` and never
`read-string`. EDN has no reader-eval, so model output cannot evaluate code.

An op with several different shapes (`why-not` takes a handle *or* a sentence and a
context) declares all their parameters, requires only the ones every shape needs, and
dispatches on the longest signature the model's input satisfies. Only an op whose every
parameter is required is marked `strict`, because a strict schema requires every
declared property.

**An argument the chosen shape does not take is refused rather than dropped**, which is
`opts/check!`'s rule one level out. `query` takes `(goal)`, `(goal, context)` and `(goal,
context, opts)`, so a call giving goal and `opts` and no context satisfies the first.
Passing it on would run the read facts-only with the depth discarded, and the answer would
read as a goal no rule can reach. The refusal names the form the input selected and the
shapes the op has, so the next call supplies the missing argument.

**Writes are excluded structurally.** `tools/write-ops` starts from the daemon's write
set, so a write the daemon files there is excluded with no edit here, and the suite fails
when any op of that set is a tool. A new op that changes state outside that set needs a
line in `tools/write-ops`. An op for a `!` fn is excluded a second way: the daemon names it
without its `!` (`:retract` for `retract!`), so its name resolves to no `vaelii.core` var.
There is no write tool, and `tools/op-of` does not resolve a write's name at all.

**A result is cut in the writer that prints it.** `tools/call` prints a read's answer into
a writer that stops at `:max-result-chars` (4,000). The printer walks only the members it
prints: the projection from records to plain maps replaces every map, vector, set and seq
with a lazy one that prints as `pr` prints the original, `cycle`, `iterate` and `repeat`
included. A lazy answer is realized only as far as the prefix the model is shown, so
`kb_sentexes_in_context` over an imported ontology realizes a few dozen of its hundred
thousand records. An answer the engine builds eagerly (`kb_types`, `kb_contexts`) is
built whole before the printer starts, and only its printing is cut. Each write appends
only the room left, since one `print-method` call can hand over a whole symbol name.

**A count the engine realizes whole is held to `tools/max-limit` (100).**
`kb_qualitative_scenarios`' `limit` and the `:limit` of `kb_find_terms` and `kb_describe`
each decide how much the engine builds before the printer's bound applies. A larger count
is refused `:over-ceiling`, and a `kb_find_terms` call naming none is given 100, since an
empty search string otherwise answers the whole vocabulary. A `tools/call` input that is
not a JSON object is refused, and a missing one reads as no arguments.

**A read's bounds are the daemon's.** `:max-depth` and `:max-ms` are held under the
ceilings the engine daemon applies *in the op table* rather than at its HTTP route, and a
read the clock holds runs under the ceiling when the call names no `:max-ms`. A tool call
naming a bound past one is refused `:over-ceiling` and comes back as the `{:ok false
:error …}` a `tool_result` carries. A model reading a KB through a prompt it did not write
is the caller most likely to name an expensive depth, and a ceiling held only at the HTTP
route would not apply to it.

**The engine on the classpath decides which reads the clock holds.** The tools read that
engine's ceiling tables. Under the 0.32.0 engine release, `kb_escalate` accepts a
`:max-ms` over the ceiling, and `kb_escalate`, `kb_lookup`, `kb_query`, `kb_query_p`,
`kb_query_status` and `kb_argue` run under no clock when the call names no `:max-ms`;
the last five take no `:max-ms` there. A `kb_why_not`, `kb_search_tree`,
`kb_compare_tacticians`, `kb_ask_within` or `kb_prove_within` call that sends no `opts`
runs under no clock under that release too. The engine's `develop` branch holds all
eleven to the ceiling. CI runs this repo's tests against `develop`, and `kb_escalate`'s
ceiling test fails against the 0.32.0 jar.

### Where this repo reaches into the engine

`vaelii.agents.engine` reads four vars of `vaelii.host.serve`. The tools read `ops`,
`kbless-ops` and `write-ops`; the suite reads `feed-ops`, to check that no change-feed op is
a tool.
`vaelii.host.*` is private to the engine, which may change it in any release.
`vaelii.agents.engine` holds every such reach, so an engine change that moves one is
repaired in that one namespace. A new namespace here that needs an engine var outside
`vaelii.core` reads it through `vaelii.agents.engine` too.

## Providers

A provider implements `vaelii.agents.llm.protocol/Provider`: `complete` and `stream`, over
a request map and a response map that name no vendor field, so a caller's loop is written
once and runs against every backend. The protocol's ns docstring lists every key of both
maps, and a key any path sends belongs on that list. `vaelii.agents.surface-test` freezes
the protocol's method set and arglists, since an out-of-tree backend implements it.

### Choosing one

`vaelii.agents.llm.provider` selects a backend the way the engine selects a contradiction
solver. A keyword names a backend, the backend is **lazily resolved** so choosing one is
what loads it, and an unreachable backend falls back to the stub rather than throwing.

```clojure
(provider/provider)             ; the configured backend, or the stub
(provider/provider :ollama)     ; demand one; still falls back
(provider/available? :ollama)   ; ask first if you must know
(provider/active-kind)          ; what (provider) will hand back right now
(provider/warm)                 ; the configured backend's warm-up, nil when it has none
```

The kinds are `:stub`, `:ollama` and `:anthropic`, selected with `VAELII_LLM_PROVIDER` or
`-Dvaelii.llm.provider`, default `:stub`. Resolution is lazy because
`anthropic/available?` may shell out to the `ant` CLI and `ollama/available?` opens a
socket, and requiring a namespace does neither.

A backend that probes available and then throws from its constructor is logged at `:warn`
before the stub takes over. Without the log line, a caller could not tell a fallback from a
model that answered nothing. The log line names the kind and never the `opts`, which may carry a
credential. The stub a fallback builds takes its options from `opts`'s `:stub`, so a
caller decides what the fallback answers; a backend never sees `:stub`.

`provider/generation-provider` is `provider` for a generation turn, one that writes new
content. It builds a backend's `generation-provider` constructor where the backend has one
(Ollama does) and its ordinary one where it does not. `provider/available?` hands the
Ollama probe only `:host` from `opts`, so every selection path probes on the probe's own
two-second deadline whatever `:timeout-ms` the turn allows. It hands the Messages API check
only `:credential`, so an explicit credential selects that backend when the environment
holds none. Every Ollama call normalizes `:host` the same way, so a bare `127.0.0.1:11434`
reaches the probes and the turns alike.

### The stub is the default

`vaelii.agents.llm.stub/provider` is the deterministic default. `lein test` runs every
caller against it, with no API key, no socket and **no model call**. A deployment with no
credential gets a provider that answers nothing instead of an exception.

A test drives a loop through `:script`, the turns to hand back, one per call. Each is a
full response map or a shorthand, listed in the ns docstring:

```clojure
(stub/provider {:script [{:tool "kb_types_of" :input {"x" "Muffet"}}
                         "plain prose"]})
```

Past the script, every turn answers `:default` (an empty end-of-turn response unless
given). A caller with answer shapes of its own passes `:expand`, a function the stub calls
on each map entry before its own shorthands. `stub/requests` and `stub/last-user-text`
read back what the loop sent.

A test that can reach a real model is held out of every other run by **two independent
checks**:

| | what it decides | how to move it |
|---|---|---|
| **`^:llm`** | which tests *run*: neither `:default` nor `:all` selects it | `lein test :llm` runs exactly those and nothing else |
| **`tu/live-llm?`** | whether a call may be *made* | `VAELII_LLM_LIVE=1` |

`lein test :llm` without the variable prints a skip per test instead of dialling out. A
selector is a request and consent is separate, and a reachable Ollama on the machine is
not consent. A test whose assertions depend on what a model said is neither hermetic nor
reproducible, so `lein test` and `lein test :all` never reach one. `tu/live-model` checks
consent, then reachability, then whether the host has the model, and prints the skip
reason for the first that fails. This repo's own suite holds no `^:llm` test.

### The Messages API backend

`vaelii.agents.llm.anthropic/provider` speaks the Messages API over JDK `java.net.http` and
`cheshire`, with an explicit connection handle and no global state.

- The default model is `claude-opus-5-5`. Its effort defaults to `medium`, one level below
  `claude-opus-5`'s, so a caller wanting deeper reasoning sends `:effort`.
- `temperature`, `top_p` and `top_k` are rejected by this model family and are never
  sent; a caller steers in the prompt. `tool_choice` is never sent either: the model
  refuses a forced tool.
- Thinking is always on and takes no token budget; the model refuses a disabled or
  budgeted `thinking`. Depth is `output_config.effort`, and `:thinking-display
  "summarized"` opts into readable reasoning (the default omits the text).
- `:format` is structured outputs: the JSON schema goes out as `output_config.format`.
  Ollama takes the same schema under its own `format` key, so one neutral request
  constrains decoding on both backends.
- A **refusal is HTTP 200** with `stop_reason: "refusal"` and empty or partial content.
  Nothing fabricates a text block, and a caller branches on `:stop-reason` first.
- `fallbacks: "default"` is sent by default, with its beta header, so a policy decline is
  re-served by a substitute model instead of returned as a dead turn. `{:fallbacks nil}`
  in the request drops both, for an organization that has not enabled that beta.
- `stream` parses the SSE frames and reassembles whole blocks, so it returns the same
  response map `complete` would.

Assistant content is echoed back through `:raw`, the block's original JSON. The API
rejects an edited thinking block, and a streamed thinking block's signature arrives as a
delta of its own, so the reassembled block carries it back. A vendor block type this
namespace does not model round-trips the same way, and a built block of a type the
encoder has no shape for is refused (`:llm-encode`).

### The local backend

`vaelii.agents.llm.ollama/provider` speaks Ollama's chat API over the same two libraries.
The transport differs:

- **No credential.** `available?` is a reachability probe, so this backend can be tested
  end to end against a real model.
- **The context length is the caller's to set**, per request, as `options.num_ctx`.
  Nothing here truncates; the caller sizes the prompt first.
- **`format` carries a JSON schema** the sampler is constrained to, so a model with no
  `tools` capability answers in an exact shape.
- **`temperature` defaults to 0**, so an answer is reproducible.
- **Streaming is newline-delimited JSON**, not SSE: one object per chunk carrying the
  delta, the last carrying `done: true` and the run's counts. `stream` reassembles it into
  the same response map `complete` returns.
- **`:usage` reports what the host measured**: `prompt_eval_count`, `eval_count` and the
  durations, on every response.
- **`keep_alive` is always sent** (30 minutes by default). Model load is most of a cold
  local turn's latency, and a host left to its own 5-minute default evicts the model
  between one question and the next.
- **A failure inside a 200**, an `error` field in the body or in a streamed chunk, is an
  `:llm-api-error`, as a failing status is.
- **Tool schemas are re-shaped** from the protocol's `{name, description, input_schema}` to
  Ollama's `{type: function, function: {name, description, parameters}}`, and `strict` is
  dropped. A tool call with no `id` gets one from its position in the turn, counted across
  a streamed turn's chunks.
- **A host is read as Ollama reads `OLLAMA_HOST`** (`ollama/normalize-host`). A host with
  no scheme gets `http://`, and `:11434` when it names no port; a host with a scheme keeps
  that scheme's port.

```clojure
(ollama/available?)                    ; is a host reachable?
(ollama/capabilities "phi4:14b")       ; => #{:completion}
(ollama/supports-tools? "phi4:14b")    ; => false
(ollama/context-length "phi4:14b")     ; => 16384
(ollama/warm)                          ; load the generation model and hold it resident
(ollama/generation-provider)           ; a provider on the generation model
```

`warm` sends a prefill of a realistic size at the configured context length and generates
one token. It is bare, not `warm!`, because it destroys nothing.

A model without `:tools` cannot tool-call: its chat template has no tools section, and the
tool schemas sent to it spend context length on output it never emits. `supports-tools?`
answers false for an unreachable host too, since a wrong yes spends that context length.

`OLLAMA_HOST` has two readers: `ollama serve` reads it as the address to **bind**, so a
machine running its own Ollama commonly has it set to `0.0.0.0:11434`. `ollama/base-url`
skips a wildcard, which is no address to connect to. The variables and their defaults are
§ Configuration.

### What both HTTP backends share

`vaelii.agents.llm.http` holds the part of a transport that is not a wire format, so the
deadline policy lives in one place. Each backend passes an **endpoint** descriptor,
`{:label :slug}`: how the far end is named in a message and in a thread name.

**The failing body is data, never the message.** A response can be megabytes, and what
answered is often not the API: a proxy in front of it answers HTML. Every refusal that
carries body text bounds its message to `http/excerpt`'s opening 200 characters and hands
the whole text over in the ex-data: `:excerpt` for a 200 that would not parse
(`:llm-bad-response`), `:body` for a non-200 (`:llm-api-error`). An unbounded body would
reach every log line the failure reaches.

**The body read carries its own deadline.** A request's `.timeout` bounds the response
*arriving*, and ends when the headers do. A streamed turn is almost entirely what comes
after that: the lines are pulled off the socket once `send` has returned. A non-streamed
turn and the Ollama probes read their body through `http/send-for-text`, which reads it
the same way under the time left of the call's `:timeout-ms`. A model evicted under memory
pressure goes quiet in exactly that phase. `http/under-read-deadline` runs a daemon watchdog that closes
the body once the turn's remaining budget passes, which makes a blocked read fail, and
rewrites the failure that follows as `:llm-timeout` with the original as its cause. It
catches `Throwable`, not `Exception`: the read parses bytes a far end chose, so a hostile
body can overflow the stack or exhaust the heap, and an `Error` escaping the rewrite would
reach a caller's `:llm-timeout` handler as a parser bug instead. With the deadline unspent
the original propagates unchanged, and the watchdog is cancelled either way. The watchdog
is a daemon thread, so a stalled read never keeps a JVM from exiting.

**The send's own failures are typed.** Every turn sends through `http/send-request`. No
response inside the request's `.timeout` is `:llm-timeout` carrying `:timeout-ms`, and a
connect timeout or any other `IOException` (refused, reset, unresolved) is
`:llm-unreachable`. Each carries the original as its cause.

**The connect deadline is not the turn's.** `http/connect-timeout-ms` is a fixed five
seconds and every client is built on it, whatever `:timeout-ms` a caller allowed. A turn
may run for minutes (a cold 14B load, a high-effort answer), while a connection completes
in well under a second or not at all. Handed the turn's budget, `connectTimeout` would make
a host that never answers the SYN cost five minutes on Ollama and ten on the Messages API.
A probe that fails faster still can: the request's own `.timeout` bounds the exchange,
connection included, so `ollama/version` keeps its two-second timeout.

**One client for the probes.** A JDK `HttpClient` owns a connection pool and a selector
thread. `ollama/probe-client` is one held client that `version`, `show` and `warm` all
send on, and so `available?` and `capabilities` above them. A caller may probe on every
request it serves, and a client per probe would be a connection pool per request. The host
rides on the request URI and the deadline on the request. A provider keeps its **own**
client, since a turn is long-lived and streams.

**There is no retry and no backoff.** A 429 or a 529 surfaces as `:llm-api-error` with the
status in its ex-data, like any other non-200, and the turn ends there. Nothing here
sleeps, re-sends, or reads a `retry-after` header; a caller that wants to try again calls
again.

### Credentials

`anthropic/credentials` resolves the way the official SDKs do: `ANTHROPIC_API_KEY`, then
`ANTHROPIC_AUTH_TOKEN`, then an `ant auth login` profile via `ant auth print-credentials
--access-token`. **An unset `ANTHROPIC_API_KEY` does not mean there is no credential**: an
active OAuth profile is enough, and it authenticates with `Authorization: Bearer` plus the
OAuth beta header rather than `x-api-key`.

`available?` calls `credentials` on a request thread, so the `ant` CLI is killed after
five seconds, which answers "no credential" as an absent CLI does. An interrupt during that
wait is put back on the thread.

The value is a secret: it is returned for the caller to hand straight to a header, and is
never logged, printed, or put in an exception message. Nothing here reads or writes a
credential file. An environment value is trimmed, since a `.env` file ending in CRLF and a
`$(cat key)` that keeps its newline both leave a stray character. A control character
mid-string survives the trim, and the JDK quotes a header value it rejects verbatim in its
own exception; `request-builder` replaces that exception with an `:llm-bad-credential` that
names the header and never the value.

### The vote pool

`vaelii.agents.llm.pool` answers a yes/no question by majority: `(pool/vote p question)`
asks `:votes` times (default 3) and answers `:yes`, `:no` or `:abstain`, with the votes,
their tally and the call count.

- A turn that produced no opinion is **not a vote**: a refusal, a truncation, or prose
  whose first word is not `yes` or `no`. Counting it would let a model that declined three
  times read as a unanimous no. `read-yes-no` takes `Yes.` and `no — because …`, and not a
  `yes` buried in a paragraph.
- The verdict is `:yes` when more counted votes say yes than no, `:no` for the reverse, and
  `:abstain` on a tie, zero counted votes included. One yes among two non-answers is
  `:yes`.
- **`:abstain` is an outcome**, not a no. A caller that records a verdict on `:abstain`
  records one nobody reached.

The pool is a fixed multiset of providers on a core.async channel, one per host token.
`with-provider` takes one for the length of a call and **offers** it back on every exit,
since a blocking put from an interrupted thread parks forever and drops the token. A pool
leaking tokens one at a time shows only as calls waiting longer, so `outstanding` counts
checked-out providers on the pool's own counter, and a nonzero count on an idle pool is a
leak. A call waits `acquire-timeout-ms` (five minutes, a cold load and the queue behind it)
for a provider, then answers no vote. A call that throws (an unreachable host, an
`:llm-api-error`) answers no vote either, and `vote` counts both kinds of failed turn
under `:failed`, so a caller can tell a pool that could not ask from a model that would not
answer.

`VAELII_OLLAMA_HOSTS` is `host[*n],…`, `n` defaulting to 2, and each host is read as
`ollama/normalize-host` reads one, so `gpu1:8080*4` is four calls at once on port 8080.
Unset, the pool is the one configured host. Blank, it is no hosts, and `pool/pool` refuses
an empty host list (`:llm-bad-hosts`) rather than build a pool every call would wait out.
`pool/stub-pool` is a pool of one stub provider, for tests.

## Configuration

The model stack reads its own switches with `System/getenv` and `System/getProperty`, each
at the call that uses it; the engine reads none of them.

| Switch | Read at | Legal values | Default | What it decides |
|---|---|---|---|---|
| `VAELII_LLM_PROVIDER` | `provider/configured` | `stub` `ollama` `anthropic` | unset (the stub) | Which backend `provider/provider` builds. |
| `vaelii.llm.provider` | `provider/configured` | as above | unset | The same choice, read **first**. |
| `VAELII_OLLAMA_HOST` | `ollama/base-url` | a base URL, or a host read as Ollama reads one | `http://localhost:11434` | Where the Ollama backend connects. The default is Ollama's own. |
| `OLLAMA_HOST` | `ollama/base-url` | a base URL; a bind address (`0.0.0.0`, `::`, `*`) is ignored | unset | Ollama's own variable, read after `VAELII_OLLAMA_HOST`. |
| `VAELII_OLLAMA_MODEL` | `ollama/configured-model` | a model name | `phi4:14b` | The model a turn runs. |
| `VAELII_OLLAMA_GENERATION_MODEL` | `ollama/configured-generation-model` | a model name | `qwen3-coder:30b` | The model a generation turn runs. |
| `VAELII_OLLAMA_NUM_CTX` | `ollama/configured-num-ctx` | a whole number of tokens | `8192` | The context length a request asks for. An unparseable value yields the default. |
| `VAELII_OLLAMA_KEEP_ALIVE` | `ollama/configured-keep-alive` | an Ollama duration (`30m`, `0`) | `30m` | How long the host is asked to hold the model resident after a turn. |
| `VAELII_OLLAMA_HOSTS` | `pool/configured-hosts` | `host[*n],…` | unset (the one host above) | The hosts a vote pool spreads its calls over, `n` calls at once per host. |
| `ANTHROPIC_API_KEY` | `anthropic/credentials` | an API key | unset | The credential sent as `x-api-key`, tried first. |
| `ANTHROPIC_AUTH_TOKEN` | `anthropic/credentials` | a bearer token | unset | The credential sent as `Authorization: Bearer`, tried when there is no key. |
| `ANTHROPIC_BASE_URL` | `anthropic/provider` | a base URL | `https://api.anthropic.com` | The host that backend calls. |
| `VAELII_LLM_LIVE` | `test-util/live-llm?` (test tree) | `1` `true` `yes` | unset | The consent to call a real model from a test. The `^:llm` mark is the separate half, and both are needed. |

`vaelii.agents.surface-test` holds the unset reading of each `provider` and `ollama` row
against the Default column.

## What a refusal's `:type` means

| `:type` | what happened |
|---|---|
| `:llm-api-error` | a provider answered an error status, or put an error in a 200 body or a stream chunk |
| `:llm-bad-credential` | a credential holds a character an HTTP header cannot carry; the value is never quoted back |
| `:llm-bad-hosts` | a pool's host list is empty, or an entry of it is not `host[*n]` |
| `:llm-bad-response` | a provider answered with a body that is not JSON |
| `:llm-encode` | a content block whose `:type` the request encoder does not write |
| `:llm-no-credential` | no provider credential is set |
| `:llm-timeout` | a provider stopped answering inside the turn's `:timeout-ms` budget |
| `:llm-unreachable` | a provider's host refused or dropped the connection, did not accept one inside `http/connect-timeout-ms`, or did not resolve |

A tool call refused by the engine (`:over-ceiling`, a naming or arity refusal) comes back
inside the tool result as `{:ok false :error …}`, with the engine's `:type` in the
message, rather than as a thrown exception.
