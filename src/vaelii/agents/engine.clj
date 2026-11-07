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
  reads with no second copy."
  (:require [vaelii.host.serve :as serve]))

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
