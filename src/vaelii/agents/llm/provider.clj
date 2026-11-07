;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.provider
  "Which backend a turn runs against: `:stub` (the default), `:ollama` or `:anthropic`,
  resolved lazily, with an unreachable backend falling back to the stub.  Every fn here
  that can build a stub reads that stub's own opts (`vaelii.agents.llm.stub/provider`)
  from `opts`'s `:stub`, and hands a backend `opts` without it.  See docs/llm.md
  § Choosing one."
  (:require [clojure.string :as str]
            [taoensso.trove :as trove]
            [vaelii.agents.llm.stub :as stub]))

(def ^:private backend-ns
  "The namespace each non-stub kind resolves its constructors out of."
  {:ollama    'vaelii.agents.llm.ollama
   :anthropic 'vaelii.agents.llm.anthropic})

(defn configured
  "The backend named by `VAELII_LLM_PROVIDER` / `-Dvaelii.llm.provider`, or nil."
  []
  (some-> (or (System/getProperty "vaelii.llm.provider")
              (System/getenv "VAELII_LLM_PROVIDER"))
          str/trim str/lower-case not-empty keyword))

(defn- resolve-fn
  "A backend's var, or nil when the namespace does not load in this build."
  [kind sym]
  (when-let [ns-sym (backend-ns kind)]
    (try (requiring-resolve (symbol (name ns-sym) (name sym)))
         (catch Throwable _ nil))))

(def ^:private probe-keys
  "The `opts` keys each backend's own `available?` is handed; no other key reaches it."
  {:ollama    [:host]
   :anthropic [:credential]})

(defn available?
  "Can this backend serve a turn now?  `:stub` always can; `:ollama` probes the host
  named by `opts`'s `:host` alone, so the probe keeps its own short deadline whatever
  `:timeout-ms` a turn allows; `:anthropic` looks for `opts`'s `:credential`, then the
  environment's.  Never throws: an unreachable or unloadable backend answers false."
  ([kind] (available? kind {}))
  ([kind opts]
   (case kind
     :stub true
     (boolean (when-let [f (resolve-fn kind 'available?)]
                (try (f (select-keys opts (probe-keys kind)))
                     (catch Throwable _ false)))))))

(defn- call-builder
  "Call one of a backend's constructors, or nil when its namespace does not load
  (silently) or the constructor throws (logged at `:warn`).  `opts` never reaches the
  log line: it may carry a credential."
  [kind sym opts]
  (when-let [f (resolve-fn kind sym)]
    (try (f (dissoc opts :stub))
         (catch Throwable t
           (trove/log! {:level :warn :id ::provider-build-failed :error t
                        :msg "backend probed available but would not build; using the stub"
                        :data {:kind kind}})
           nil))))

(defn build
  "Build the named backend with `opts`, without probing it, or nil when it cannot be
  built.  A constructor that throws is logged."
  ([kind] (build kind {}))
  ([kind opts]
   (if (= :stub kind)
     (stub/provider (:stub opts {}))
     (call-builder kind 'provider opts))))

(defn provider
  "The `Provider` to run a turn against: `kind` (default the configured one, else
  `:stub`) when it is available and builds, else a stub built from `:stub`.  Never throws; a
  caller that must know which it got asks `available?` first."
  ([] (provider (or (configured) :stub) {}))
  ([kind] (provider kind {}))
  ([kind opts]
   (or (when (available? kind opts) (build kind opts))
       (stub/provider (:stub opts {})))))

(defn active-kind
  "The kind `(provider)` would hand back now."
  ([] (active-kind {}))
  ([opts]
   (let [k (or (configured) :stub)]
     (if (and (available? k opts) (build k opts)) k :stub))))

(defn generation-provider
  "`provider` for a generation turn, one that writes new content: a
  backend with a `generation-provider` constructor is built through it, any other
  through `provider`'s."
  ([] (generation-provider (or (configured) :stub) {}))
  ([kind] (generation-provider kind {}))
  ([kind opts]
   (or (when (available? kind opts)
         (if (resolve-fn kind 'generation-provider)
           (call-builder kind 'generation-provider opts)
           (build kind opts)))
       (stub/provider (:stub opts {})))))

(defn warm
  "Run the backend's own `warm` and return what it reports, or nil when the backend has
  none (the stub, Anthropic) or it throws.  See docs/llm.md § The local backend."
  ([] (warm (or (configured) :stub)))
  ([kind]
   (when-let [f (resolve-fn kind 'warm)]
     (try (f) (catch Throwable _ nil)))))
