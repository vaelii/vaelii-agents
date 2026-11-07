#!/usr/bin/env bash
# scripts/check-reflection.sh — ratchet against runtime reflection in main.
#
# clj-kondo is a static analyzer; it never invokes the Clojure compiler, so it
# cannot see reflection or primitive auto-boxing — those are emitted by the
# compiler under `*warn-on-reflection*` (true at the top level of project.clj),
# and only when a namespace actually compiles. `lein lint` never compiles, and
# `lein test` flips the flag off in the :test profile, so neither catches them.
# This wraps `lein check` (which AOT-compiles every main namespace under the
# top-level flag) and fails on any reflection / auto-boxing / primitive-recur
# warning that comes from OUR code.
#
# Every namespace this artifact requires is loaded under the same global flag,
# so reflection from a dependency leaks into the output too — and the biggest
# dependency here is vaelii core, whose whole `impl` tree loads to satisfy one
# `require`. ALLOW below scopes the check to the source this repo can actually
# fix: core's reflection is core's to answer for, not something code out
# here can repair or should be blocked by. Add a third-party dep to ALLOW only
# after confirming its newest release still reflects.
#
# Exit 0 when clean; prints each offending warning and exits 1 otherwise.
# Set REFLECTION_LOG=<file> to lint a pre-captured `lein check` log instead of
# recompiling (used by the script's own self-test).
set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Compiler warnings we treat as failures.
PATTERNS='Reflection warning|Auto-boxing|recur arg for primitive'

# Sources this repo does not own. `vaelii/(core|impl)/` is the engine: it has no
# reflection stage in its own lint, so warnings do surface from it, and they are
# reported there rather than failing this repo's reflection check. The lein `run -m` wrapper
# (form-init*.clj) is generated, not compiled by `check`, but stays here so a
# stray `lein run` log lints clean too.
ALLOW='form-init[0-9]*\.clj|vaelii/core\.clj|vaelii/core/|vaelii/impl/|vaelii/host/|vaelii/browser/|vaelii/serve\.clj'

if [[ -n "${REFLECTION_LOG:-}" ]]; then
  log="$REFLECTION_LOG"
else
  log="$(mktemp -t vaelii-reflection.XXXXXX)"
  trap 'rm -f "$log"' EXIT
  echo "check-reflection: compiling main via lein check ..." >&2
  # `check` exits 0 on reflection (warnings are just stderr), so we grep, not $?.
  lein check >"$log" 2>&1 || { echo "check-reflection: lein check failed to compile" >&2; cat "$log" >&2; exit 1; }
fi

offenders="$(grep -E "$PATTERNS" "$log" | grep -vE "$ALLOW" || true)"

if [[ -n "$offenders" ]]; then
  echo "check-reflection: reflection / boxing in main (fix or, for a dep at its newest reflecting release, allow-list):" >&2
  printf '  %s\n' "$offenders" >&2
  echo "check-reflection: $(printf '%s\n' "$offenders" | grep -c .) warning(s)" >&2
  exit 1
fi

echo "check-reflection: OK (no reflection / boxing in main)"
