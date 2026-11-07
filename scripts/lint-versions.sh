#!/usr/bin/env bash
# scripts/lint-versions.sh — the version strings this tree states more than once
# must agree, and the one it publishes must be publishable.
#
# This repo names three coordinates and two of them are cut together:
#
#   1. The engine version.  On a cut of this artifact, `defproject com.vaelii/vaelii-agents
#      "V"` and the `[com.vaelii/vaelii "V"]` dependency name one version — the
#      artifact resolves against the engine it was cut with, and the release carve
#      strips the snapshot suffix tree-wide.  Between cuts the dependency names the
#      engine's newest release instead, as `develop` does: this artifact may sit a
#      release out while the engine moves a patch past it, and an engine snapshot
#      matching this artifact's own number is then built by no tree
#      (`bump-dev-tree.sh` sets the engine version after every release).  The
#      engine names no coordinate of this artifact, so this dependency is the only link
#      between the two versions; `check-versions-agree.sh` in the release tooling holds the
#      artifact at or behind the engine, and on a cut of this artifact (`--strict-equal`)
#      holds both the version and this dependency to the engine's.  A version
#      nothing reads drifts a whole release unnoticed, which is why this check reads it.
#
#   3. The README's install coordinate is the same string again, so it is held
#      to the same equality rather than to a weaker rule.  Do not
#      treat it as lagging — "it should name the last *release*" — and that is
#      the wrong model: `build-release-tree.sh` strips `-SNAPSHOT` **tree-wide**
#      and guards that none survives, so a dev README saying `0.5.0`
#      carves into a release README saying `0.5.0`, which is exactly right and
#      needs nobody to remember it.  A README naming a bare previous release
#      is the one that carves wrong: it survives the strip untouched and ships a
#      0.5.0 tree advertising 0.4.0.  So the dev tree says snapshot everywhere,
#      the cut says the release everywhere, and this check is what keeps the
#      three spellings one string.
#
# Exit 0 when both hold; prints each disagreement and the fix, and exits 1.
set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROJECT=${PROJECT_FILE:-project.clj}
README=${README_FILE:-README.md}

FAILS=0

# agrees <own> <other> — is `other` a legal spelling beside this tree's `own`?
#
# Two shapes are legal, because three trees carry this file and they are not the
# same tree.  The cut tree is uniform: the carve has stripped every suffix and
# everything is `X.Y.Z`.  **`develop` and the dev tree between cuts are
# deliberately not uniform.** `bump-develop.sh` rewrites line 1 only, leaving the
# engine coordinate at the version that shipped, and `bump-dev-tree.sh` sets the
# dev tree's engine dependency to the engine's newest release.  The reason matters: a
# contributor cloning develop has to be able to build it, and an engine snapshot
# is on nobody's Clojars.  A check that demanded equality there would turn the
# branch every pull request targets red, which is a worse failure than the drift
# it is looking for.
#
# So: equal, or this tree is a snapshot and the other names a plain release.  That
# second arm is what a released coordinate looks like and nothing else — the drift
# this check exists for left a *snapshot* behind (`0.4.0` beside a dependency
# reading `0.3.0`), so it is still caught, and so is a dependency left at
# an older snapshot in any tree.
agrees() {
  local own="$1" other="$2"
  [[ "$own" == "$other" ]] && return 0
  [[ "$own" == *-SNAPSHOT && "$other" =~ ^[0-9]+(\.[0-9]+)*$ ]] && return 0
  return 1
}

err() { echo "  FAIL: $*" >&2; FAILS=$((FAILS + 1)); }

# read_version <regex-with-one-capture> <file> — first match, or empty.  An
# unreadable coordinate is reported rather than passed over: a check that goes
# quiet when its own parse breaks is one that has stopped holding.
read_version() {
  sed -n "s/$1/\1/p" "$2" 2>/dev/null | head -1
}

# ---- 1: the engine version tracks defproject ----
own=$(read_version '^(defproject com\.vaelii\/vaelii-agents "\([^"]*\)".*' "$PROJECT")
engine=$(read_version '.*\[com\.vaelii\/vaelii "\([^"]*\)"\].*' "$PROJECT")

if [[ -z "$own" ]]; then
  err "cannot read defproject's version from $PROJECT"
elif [[ -z "$engine" ]]; then
  err "cannot read the com.vaelii/vaelii dependency version from $PROJECT"
elif ! agrees "$own" "$engine"; then
  err "engine version drift: defproject is $own, the dependency names vaelii $engine"
  echo "        → set the vaelii dependency to \"$own\" (they are cut together)," >&2
  echo "          or to the release this snapshot follows (that is develop's shape)" >&2
fi

# ---- 2: every README install coordinate is the same version ----
# Both spellings, not the first: the Leiningen vector and the deps.edn map are two
# lines a reader copies, and fixing one is how the other goes stale.
# `mapfile` is bash 4; macOS ships 3.2 as /bin/bash, so read the matches in a loop.
found=0
while IFS= read -r r; do
  found=1
  agrees "$own" "$r" && continue
  err "$README advertises $r, defproject is $own"
  echo "        → make it \"$own\"; the carve strips -SNAPSHOT tree-wide" >&2
done < <(sed -n 's/.*com\.vaelii\/vaelii-agents "\([^"]*\)".*/\1/p;
                 s/.*com\.vaelii\/vaelii-agents {:mvn\/version "\([^"]*\)"}.*/\1/p' \
           "$README" 2>/dev/null)

(( found == 1 )) || err "cannot read an install coordinate from $README"

if (( FAILS > 0 )); then
  echo "lint-versions: $FAILS disagreement(s)" >&2
  exit 1
fi
echo "lint-versions: OK ($own in project.clj, the vaelii dependency and $README)"
