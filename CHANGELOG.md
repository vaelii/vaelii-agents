# Changelog

Notable changes to `vaelii-agents`, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

A breaking change here is one to a public var's arglists or return shape, to the
`Provider` protocol's method set (`vaelii.agents.surface-test` freezes it), to an
environment variable's name or unset default, or to the set of tools a model is offered.

## 0.33.0 — 2026-11-10 — "an MCP server a client writes a fork KB through, and every clocked read tool held to the daemon's ceiling"

Requires core 0.33.0. **2 entries** — 1 Added, 1 Fixed.

### Added

- **An MCP server** (`vaelii.agents.mcp.*`): six verb tools (`assert`, `unassert`,
  `unassert_handle`, `query`, `argue`, `matching`) a client writes a fork KB through, over
  stdio or Streamable HTTP, with a durable per-call audit journal and a session/fork
  authority registry that fences a write against mid-request revocation.
  ([docs/mcp.md](docs/mcp.md))

### Fixed

- **The eleven read tools that ran under no clock under core 0.32.0 run under the
  daemon's clock ceiling.** Core 0.33.0, which this release requires, holds `kb_escalate`,
  `kb_lookup`, `kb_query`, `kb_query_p`, `kb_query_status` and `kb_argue` to the ceiling
  when a call names no `:max-ms`, and `kb_why_not`, `kb_search_tree`,
  `kb_compare_tacticians`, `kb_ask_within` and `kb_prove_within` when a call sends no
  `opts`. This resolves 0.32.0's known limit. ([docs/llm.md § Tools](docs/llm.md#tools))

## 0.32.0 — 2026-11-08 — "model-provider clients and generated KB read tools, outside the engine"

### Added

- **The `Provider` protocol and three backends**: the Messages API, Ollama and a scripted
  offline stub, selected by `VAELII_LLM_PROVIDER` with the stub as the fallback.
  ([docs/llm.md § Providers](docs/llm.md#providers))
- **A provider pool and a majority yes/no vote** across Ollama hosts
  (`VAELII_OLLAMA_HOSTS`). ([docs/llm.md § The vote pool](docs/llm.md#the-vote-pool))
- **KB read tools for a model**, generated from the engine daemon's read ops; no tool
  writes. ([docs/llm.md § Tools](docs/llm.md#tools))

### Known limits

- **Under the 0.32.0 engine release, eleven read tools can run under no clock.**
  `kb_escalate`, `kb_lookup`, `kb_query`, `kb_query_p`, `kb_query_status` and `kb_argue`
  are held to the engine's clock ceiling by its `develop` branch, and so are five more
  tools called without `opts`. Run against an engine built from `develop` for those
  bounds. ([docs/llm.md § Tools](docs/llm.md#tools))
