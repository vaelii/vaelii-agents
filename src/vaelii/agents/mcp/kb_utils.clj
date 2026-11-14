;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.kb-utils
  "Small helpers over `vaelii.core` that `vaelii.agents.mcp.overlay` needs and
  `vaelii.core` does not itself provide."
  (:require [vaelii.core :as v]))

(defn first-if-singleton
  "Returns the first element if coll has exactly one item, otherwise nil."
  [coll]
  (let [s (seq coll)]
    (when (and s (nil? (next s)))
      (first s))))

(defn sentex-matching
  "Returns the unique sentex matching sentence in context, or nil.
   Nil when zero or more than one match — ambiguity is not a match."
  [kb sentence context]
  (first-if-singleton (v/sentexes-matching kb sentence context)))

(defn except-sentex!
  "Except the stored sentex whose content is exactly sentence in context:
  assert (except (sentexHandle h)) in except-context, which the caller names.
  The except hides h from except-context and every context that sees it, and
  leaves h believed in the more general contexts — the starter itself is
  untouched.

  The lookup is v/handle-of: exact content, by storage, never a pattern. A
  ?var in sentence is part of the content (a rule names that rule, up to
  renaming its variables, and nothing else); it does not unify with a stored
  ground sentence. By content rather than handle, so the except survives an
  upgrade that renumbers handles; by storage rather than belief, so an
  except already hiding the target from context does not hide it from the
  lookup. Never throws on a missing target; a sentence or context
  v/handle-of refuses as malformed (a variable context, say) throws its
  :shape refusal. Every result carries the :sentence and :context it was
  asked, plus:
    {:status :not-found}                            nothing stored with that content in context
    {:status :already :handle h :except-handle e}   the except is stored and IN
    {:status :excepted :handle h :except-handle e}  the except was asserted
  Idempotent: a second call answers :already."
  [kb sentence context except-context]
  (let [asked  {:sentence sentence :context context}
        h      (v/handle-of kb sentence context)
        except (when h (list 'except (v/sentex-handle h)))
        prior  (when h (v/handle-of kb except except-context))]
    (cond
      (and prior (v/in? kb prior))
      (assoc asked :status :already :handle h :except-handle prior)

      h
      (assoc asked :status :excepted :handle h
             :except-handle (v/assert kb except except-context))

      :else
      (assoc asked :status :not-found))))
