# Changelog

Notable changes to `vaelii-agents`, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html).

A breaking change here is one to a public var's arglists or return shape, to the
`Provider` protocol's method set (`vaelii.agents.surface-test` freezes it), to an
environment variable's name or unset default, or to the set of tools a model is offered.

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
