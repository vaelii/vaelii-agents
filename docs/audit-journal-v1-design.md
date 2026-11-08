# Vaelii MCP audit journal v1

Status: A1 pure-schema implementation

## Purpose

Every MCP call must leave a durable, server-authored witness answering who
attempted which operation, against which fork, when, and what the server knows
about its outcome. The witness is an append-only journal outside the fork
lifecycle. Ordinary MCP sessions cannot inspect or mutate it.

The journal records all six v1 verbs (`assert`, `unassert`,
`unassert_handle`, `query`, `argue`, and `matching`) and every denial. The
first three may mutate a fork; `query`, `argue`, and `matching` are reads.

This design follows an established durable-journal discipline and a
separated audit plane: the audit record is its own append-only store,
outside the fork's own lifecycle, with no network audit service or
distributed transaction in v1.

## Frozen decisions

- The record lifecycle is `Attempt`, `Completion`, and optional `Resolution`.
- An `Attempt` is durable before dispatch. A `Completion` is durable before a
  normal response is returned.
- The fork mutation and audit journal do not share a transaction. If the effect
  may have happened but the outcome cannot be durably recorded, the caller gets
  `outcome_indeterminate`.
- Recovery appends a `Resolution`; it never rewrites history or manufactures a
  missing `Completion`.
- The local source of truth is append-only JSONL outside fork storage. Fork
  deletion cannot delete its witness.
- Audit unavailability fails closed. No work starts without a durable Attempt.
- V1 has no hash chain. Tamper evidence waits for a real verifier and key-custody
  design.
- Authenticated Vaelii content is preserved in full after normalization. The
  auditor never copies transport credentials, bearer tokens, or session
  secrets. If a caller deliberately places a secret inside a Vaelii sentence or
  context, it is Vaelii content and receives the same custody as the KB copy.
- Before authentication succeeds, attacker-controlled raw bodies are not stored;
  only bounded transport metadata, byte length, and a digest are retained.
- Query bindings and proof trees are summarized, not copied into the journal.
  This is a volume and schema-stability boundary, not a secrecy boundary.

## D1: record schema

### Common stored envelope

Every stored record has:

| Field | Type | Meaning |
| --- | --- | --- |
| `schema_version` | integer | Exactly `1` |
| `record_type` | enum | `attempt`, `completion`, or `resolution` |
| `record_id` | UUID string | Unique journal-record identity |
| `operation_id` | UUID string | Joins lifecycle records for one attempt |
| `sequence` | decimal string | Monotonic journal sequence |
| `recorded_at` | RFC 3339 UTC string | Server-authored event time |

A1's pure constructors do not invent `record_id`, `sequence`, or
`recorded_at`; the durable journal writer adds them at the durability
boundary.

### Attempt

An Attempt adds:

- `verb`: one of the six verb names (`matching` was added after the original
  five were frozen; the record shape is unchanged).
- `auth_status`: `authenticated`, `missing`, `invalid`, `binding_missing`, or
  `fork_unavailable`.
- `principal`, `session_id`, and `fork_id`: server-resolved strings when known,
  otherwise `null`.
- `correlation_id`: optional transport correlation identity. It is not an
  idempotency key.
- `input`: exactly one tagged input variant below.
- `input_digest`: `sha256:<lowercase hex>` over the canonical `input` bytes.

Authenticated input variants:

| Tag | Fields |
| --- | --- |
| `assert` | `sentence_edn`, `context_edn` |
| `unassert` | `sentence_edn`, `context_edn` |
| `unassert_handle` | `sentex_handle` as a decimal string |
| `query` | `goal_edn`, `context_edn`, `include_justification` boolean |
| `argue` | `closed_goal_edn`, `context_edn` |
| `matching` | `pattern_edn`, `context_edn`, `asserted_only` boolean, `limit` positive integer or null |

The `*_edn` fields contain normalized EDN values, not caller text.

Known limitation (`matching`): the dispatcher builds the `matching` Attempt
from its own read of the arguments, recording a malformed `asserted_only` as
`false` and an invalid `limit` as `null`, so the durable Attempt does not
witness the bad caller input the verb then rejects (the rejected Completion
still records the refusal).

An attempt whose principal authentication has not succeeded (`missing` or
`invalid`) uses the `untrusted` variant:

- `raw_sha256`
- `byte_length`
- optional bounded `content_type`

It never contains the raw body. `content_type`, when present, is a string of at
most 256 characters. Pre-auth attempts must leave `principal`, `session_id`,
and `fork_id` null; an unauthenticated caller cannot assert server-resolved
identity into the audit record.

`binding_missing` and `fork_unavailable` happen after principal authentication.
They therefore retain the normalized typed input and known principal/session
identity while leaving the unresolved fork field `null` when necessary.

### Completion

A Completion contains exactly one outcome variant:

- `succeeded`: `effect` (`changed`, `unchanged`, or `none`) and a typed
  `result_summary`.
- `rejected`: stable `code` and bounded `message` for schema/input rejection.
- `denied`: stable `code` and bounded `message` for authorization or binding
  refusal.
- `failed`: stable `code` and bounded `message` only when the server knows no
  effect happened.
- `outcome_indeterminate`: `stage` plus bounded `observations` when an effect
  may have happened but cannot be proven either way.

Messages are capped at 4096 UTF-8 bytes. Codes, not prose, are the stable
contract. Every observations vector contains at most 32 nonblank strings; each
string is capped at 1024 UTF-8 bytes and the vector at 8192 aggregate UTF-8
bytes. The same bound applies to Resolution observations.

Successful result summaries are:

| Verb | Fields |
| --- | --- |
| `assert` | nullable `handle`, `already_there` boolean |
| `unassert` | `excepting_handles` decimal-string vector, `excepting_count` |
| `unassert_handle` | nullable `excepting_handle` decimal string |
| `query` | `answer_count`, `justifications_requested` boolean |
| `argue` | `verdict`, `for_proof_present`, `against_proof_present` |
| `matching` | `row_count`, `truncated` boolean |

Full query answers, matching rows, bindings, and proof bodies do not enter
the journal.
Created exception handles do: they are needed both for useful receipts and for
causal recovery evidence.

### Resolution

A Resolution contains exactly one variant:

- `effect_confirmed { evidence_ref }`
- `no_effect_confirmed { evidence_ref }`
- `indeterminate { observations[] }`

The two confirmed constructors accept only their outcome-specific evidence
classes frozen by the recovery contract:

- `effect_confirmed` accepts `operation_recorded_handles { handles[] }`: every
  unique positive handle in the nonempty vector carries this `operation_id`.
- `no_effect_confirmed` accepts
  `pre_execution_denial { completion_record_id }`, where the named durable
  Completion proves dispatch never began, or
  `exclusive_no_write_receipt { evidence_id }`, reserved for a future storage
  revision whose exclusivity rules can causally prove no write occurred.

A matching current postcondition, absence at inspection time, or a handle with
no recorded operation link is an observation, not causal proof. Those facts may construct only an
`indeterminate` Resolution.

## Canonical bytes

Canonical records use UTF-8 JSON with lexicographically sorted object keys, no
insignificant whitespace, no floating-point numbers, decimal identifiers
encoded as strings, and lowercase `sha256:` digests. Arrays preserve semantic
order. EDN maps and sets must first be normalized into a deterministic tagged
representation; values that cannot be normalized are rejected before Attempt
construction.

Golden vectors (`test-resources/audit_golden_vectors.json`) are the
cross-language contract: any future non-Clojure adapter must produce
byte-identical canonical forms and digests for them.

## D2: lifecycle and failure semantics

The live path is:

`receive -> resolve auth/binding -> durable Attempt -> dispatch -> durable Completion -> return`

| Condition | Durable record / response |
| --- | --- |
| Attempt cannot persist | no work; native MCP `audit_unavailable` |
| Auth, binding, or validation refusal | Attempt + denied/rejected Completion |
| Successful read or mutation | Attempt + succeeded Completion, then normal response |
| Dispatch fails and no effect is known | Attempt + failed Completion |
| Dispatch began and effect is unknown | Attempt + indeterminate Completion; `outcome_indeterminate` |
| Completion fails after known no effect | bounded retry, then `audit_unavailable` |
| Completion fails after changed or possibly changed effect | bounded retry, then `outcome_indeterminate` |
| Process dies after Attempt | unmatched Attempt; recovery may append Resolution |

The initial Completion append gets two retries within a one-second total
budget. These values are operational defaults and may be tuned without changing
the outcome semantics.

Two invariants hold:

1. No verb work executes without a durable Attempt.
2. No ordinary success or ordinary native error returns without a durable
   Completion.

`unassert` currently creates exceptions sequentially. A later failure may
therefore follow earlier mutations. A future refactor should record the
operation id on every created exception handle, preserve known handles as observations, and
map any failure after dispatch begins to `outcome_indeterminate` unless
causal evidence proves no effect. It must never report that path as a
known-no-effect `failed` outcome.

The audit envelope observes calls admitted through the transport contract.
Current direct-function quirks (extra keys, broad sequential values, truthy
booleans, and inconsistent handle validation) are implementation gaps, not an
alternative audit schema.

## D3: custody and operations

- `VAELII_AUDIT_DIR` is an absolute, host-local directory outside fork storage,
  temporary storage, and `/tmp`; directory mode is `0700`, segment mode `0600`.
- One writer owns an exclusive lock. Append, flush, and `sync_data` define the
  commit point before projection or response.
- Segments rotate for operability but are never automatically deleted in v1.
  Retention requires a separately frozen deletion policy.
- Startup validates every record and lifecycle join. Corruption in an earlier
  record or segment fails closed.
- An incomplete final line is not silently discarded. Host-local tooling may
  seal or quarantine it with an append-only operator receipt; startup never
  guesses or auto-repairs.
- Disk-full, lock loss, sync failure, and corruption are health failures visible
  to host-local operators and fail closed for ordinary operations.
- Host-local tooling supports health, lookup, unmatched-attempt scan, append
  Resolution, export, recovery, and backup/restore. There is no network admin
  plane in v1.
- Operator records include the host-resolved operator principal and invocation
  identity.
- Backup and restore cover the KB and audit journal in one workflow while
  preserving them as separate lifecycle objects. Restoring or deleting a fork
  never erases its witness.

## Implementation ladder

1. **A1 — pure schema:** constructors, validators, canonicalization, digests,
   and golden vectors; no I/O.
2. **A2 — journal:** append, replay, lookup, unmatched scan, corruption policy,
   locking, rotation, and fault injection.
3. **A3 — middleware:** one envelope around all verbs and host-local admin work;
   shared parsed values and recording the operation id on each written handle.
4. **A4 — vertical slice:** `query` plus `assert`, including denial and crash
   tests.
5. **A5 — all verbs:** add `unassert`, `unassert_handle`, and `argue`; exercise
   partial-mutation semantics.
6. **A6 — recovery:** Resolution tooling that checks evidence before it writes.
7. **A7 — operator surface:** health, lookup, export, and custody receipts.
8. **A8 — operations:** backup/restore, rotation, disk-pressure behavior, and
   deployment documentation.
9. **A9 — acceptance checks:** threat/acceptance matrix and full fault-injection suite.

A3 starts only after independent review finds A1 and A2 agree on exact bytes and
semantics.

## Explicitly deferred

- client replay-key idempotency and exact response replay
- caller-visible `audit_id` or correlation metadata if MCP cannot carry it
  uniformly across native successes and errors
- ingestion into a downstream audit pipeline
- signed tamper evidence and external verifier/key custody
- network-accessible administration
- automatic retention or deletion
