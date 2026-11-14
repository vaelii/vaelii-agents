;; The major version of the JDK evaluating this file, which is the one leiningen
;; launches the project JVM on: `:jvm-opts` below names a flag 21 refuses.
(def jdk-major
  (Integer/parseInt
   (second (re-find #"^(?:1\.)?(\d+)" (System/getProperty "java.specification.version")))))

(defproject com.vaelii/vaelii-agents "0.33.0"
  :description "Agent-facing code for vaelii: model-provider clients and the KB read
                tools a model calls, generated from the daemon's op table."
  :url "https://github.com/vaelii/vaelii-agents"
  :license {:name "Apache-2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"}
  ;; Generated into the POM. A Clojars page without it offers a reader no route
  ;; from the artifact back to the source it was built from.
  :scm {:name "git" :url "https://github.com/vaelii/vaelii-agents"}
  ;; Same as the engine's: credentials come from the environment (CLOJARS_USERNAME
  ;; plus a Clojars DEPLOY TOKEN as CLOJARS_PASSWORD), never from this file, and
  ;; `:sign-releases false` because the default is to attempt a GPG signature Clojars
  ;; does not require and fail without a key.
  ;;
  ;; This artifact deploys after the engine. It resolves against the published engine,
  ;; so publishing it first puts a coordinate on Clojars whose own dependency does not
  ;; resolve, and a Clojars release is immutable.
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org/"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  ;; A released vaelii resolves from Clojars.  A snapshot one does not, and comes from
  ;; `lein install` in ../vaelii instead (README, "Setup").  Code here reaches into
  ;; `vaelii.host.*` through `vaelii.agents.engine`, which vaelii is free to change; that
  ;; cost is this artifact's to carry.
  :dependencies [[org.clojure/clojure "1.12.6"]
                 [com.vaelii/vaelii "0.33.0"]
                 ;; Declared here rather than leaned on through the engine, so a change in
                 ;; the engine's dependency list cannot break this repo's load.  cheshire
                 ;; encodes and decodes every provider's wire JSON; trove is the logging
                 ;; facade `vaelii.agents.llm.provider` warns through.  Versions match the
                 ;; engine's.
                 [cheshire "6.2.0"]
                 [com.taoensso/trove "1.2.0"]
                 ;; The multi-host Ollama pool (`vaelii.agents.llm.pool`): a fixed multiset
                 ;; of host tokens on a channel.
                 [org.clojure/core.async "1.9.865"]
                 ;; `vaelii.agents.mcp.http-transport`'s Streamable HTTP listener.
                 ;; Versions match the engine's own ring/jetty pin.
                 [ring/ring-core "1.15.5"]
                 [ring/ring-jetty-adapter "1.15.5"]
                 ;; `vaelii.agents.mcp.transport`'s stdio JSON-RPC loop.
                 [org.clojure/data.json "2.5.1"]]
  :target-path "target/%s"
  ;; `^:llm` marks a test that can reach a model.  `VAELII_LLM_LIVE=1` is the separate
  ;; consent `vaelii.agents.test-util/live-llm?` reads before any of them dials out.
  ;; Every selector is `(fn [m & _] …)`: `lein test :llm some.ns` hands the selector the
  ;; trailing `some.ns` as a second argument, so a one-arg fn throws ArityException there.
  :test-selectors {:default (fn [m & _] (not (:llm m)))
                   :llm     (fn [m & _] (boolean (:llm m)))
                   :all     (fn [m & _] (not (:llm m)))}
  ;; Declaring `:jvm-opts` replaces leiningen's `^:displace` `:base` vector, which
  ;; drops the C1-only cap `:base` copies from a LEIN_JVM_OPTS naming "Tiered", and
  ;; restores `:base`'s `-XX:-OmitStackTraceInFastThrow` so a test failure names its
  ;; site. The two `--` flags silence the JDK 24+ warnings for JNA's native loads and
  ;; aircompressor's (nippy LZ4) `sun.misc.Unsafe` calls; the Unsafe flag exists from
  ;; 23, and 21 refuses to boot with it. Core's project.clj has the detail.
  :jvm-opts ~(cond-> ["-XX:-OmitStackTraceInFastThrow" "--enable-native-access=ALL-UNNAMED"]
               (<= 23 jdk-major) (conj "--sun-misc-unsafe-memory-access=allow"))
  ;; Same as the engine's: an unhinted interop call is a build-time failure rather than
  ;; a reflective lookup paid at runtime.
  :global-vars {*warn-on-reflection* true}
  ;; Static-analysis checks ported from vaelii-foreign:
  ;;   lein lint    — versions + kondo + shellcheck + cljfmt + reflect + prose
  ;;                  via scripts/lint.sh, one ✓/✗ line per check, runs all (not
  ;;                  fail-fast).  VERBOSE=1 dumps each check's full output.
  ;;   lein fix     — the only auto-repair: cljfmt rewrites in place.
  ;;   lein gate    — lint, then (only if green) the suite.
  ;; Needs clj-kondo on PATH; reflect shells out to lein's built-in `check` (AOT compile).
  :aliases {"lint-kondo"      ["shell" "clj-kondo" "--lint" "src" "test"]
            "lint-cljfmt"     ["cljfmt" "check"]
            "lint-reflect"    ["shell" "bash" "scripts/check-reflection.sh"]
            ;; the prose budget: metaphor and aphorism, held at zero hits
            "lint-prose"      ["shell" "python3" "scripts/check-prose.py"]
            "lint"            ["shell" "bash" "scripts/lint.sh"]
            "fix"             ["cljfmt" "fix"]
            "gate"            ["do" ["lint"] ["test"]]
            ;; cljfmt's built-in rules indent every `def…` and `with-…` form as a body,
            ;; so the test macros need no entry.
            "cljfmt"          ["with-profile" "+cljfmt" "cljfmt"]}
  ;; `shell` task: lets the lint aliases run scripts/*.sh from lein.
  :plugins [[lein-shell "0.5.0"]]
  ;; The lein launcher exports CLASSPATH (its own jar), so every nested `lein` under a
  ;; `lein shell` alias prints "You have $CLASSPATH set"; nil drops it from the child.
  :shell {:env {"CLASSPATH" nil}}
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[nrepl "1.7.0"]
                                  ;; the engine pulls Jetty; without a binding SLF4J
                                  ;; prints a "no providers" line on every boot
                                  [org.slf4j/slf4j-nop "2.0.18"]]}
             :cljfmt {:plugins [[dev.weavejester/lein-cljfmt "0.16.5"]]
                      :cljfmt {:indentation?                    true
                               :indent-line-comments?           true
                               :remove-surrounding-whitespace?  true
                               :remove-trailing-whitespace?     true
                               :insert-missing-whitespace?      true
                               :remove-consecutive-blank-lines? true
                               :sort-ns-references?             true}}
             ;; Reflection is flipped off here so the test tree doesn't spam `lein test`
             ;; (reflect lints src only, via `lein check`, which keeps the flag on).
             ;; The engine's own logging is held at `:error` under test.
             :test {:global-vars {*warn-on-reflection* false}
                    :injections
                    [(require 'vaelii.core)
                     ((resolve 'vaelii.core/set-log-level)
                      (keyword (or (System/getenv "VAELII_TEST_LOG_LEVEL") "error")))]}})
