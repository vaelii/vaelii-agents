# shellcheck shell=bash
# scripts/badge-extras.sh — vaelii-agents domain badges, sourced by update-badges.sh
# between the loc and docstrings badges. Appends to the keys/msgs/links arrays it
# inherits; the count/g helpers are in scope.
#
# providers -> the backend kinds `vaelii.agents.llm.provider/provider` selects: one per
#              entry of its `backend-ns` table, plus the stub, which that table does not
#              list. The `{?` matches the first entry, which cljfmt keeps on the brace's
#              line.
providers_n=$(count g -oE "^\s*\{?\s*:[a-z]+\s+'vaelii\.agents\.llm\.[a-z]+" src/vaelii/agents/llm/provider.clj)
keys+=(providers); msgs+=("$((providers_n + 1))"); links+=("src/vaelii/agents/llm/provider.clj")
