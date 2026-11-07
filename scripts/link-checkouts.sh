#!/usr/bin/env bash
# scripts/link-checkouts.sh [-f] — create this artifact's dev-local
# checkouts/vaelii symlink so Leiningen resolves vaelii core from live source
# instead of the snapshot jar `lein install` publishes.
#
# Code here reaches into `vaelii.host.*`, which core is free to change, so
# working against live source is how a break shows up here at the moment it
# lands rather than at the next install.
#
# The sibling is `../vaelii`, which is what `git clone` of the engine produces
# and so what anyone cloning this project has.
#
# checkouts/ is gitignored, so this link is not committed — rerun after a
# fresh clone. Idempotent (ln -snf). By default the link is skipped with a
# WARN when the target does not exist; pass -f / --force to link anyway (a
# dangling link is harmless and resolves once the core repo is cloned).
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

force=0
while [[ "${1:-}" == -* ]]; do
  case "$1" in
    -f|--force) force=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

mkdir -p checkouts
# Leiningen matches a checkout by the project.clj it finds, not by the directory
# name, so the link is named for the artifact and points at the repo.
target=../../vaelii
if [[ $force -eq 0 && ! -e "checkouts/$target" ]]; then
  echo "WARN: skipping checkouts/vaelii -> $target (target missing; -f to link anyway)" >&2
  exit 0
fi
ln -snf "$target" checkouts/vaelii
echo "linked checkouts/vaelii -> $target"
