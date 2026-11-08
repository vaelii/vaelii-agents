;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.overlay
  "An overlay knowledge loader: a directory's `excepts.edn` and `*.clj` files
  into the base KB at server start, each file into the context `contexts.edn`
  names for it. Kept out of `vaelii.agents.mcp.server`, which calls
  `load-overlay!` once, so the server file stays a near copy of the shape a
  non-overlay deployment would use."
  (:require [clojure.edn :as edn]
            [vaelii.agents.engine :as eh]
            [vaelii.agents.mcp.kb-utils :as utils]
            [vaelii.core :as v]))

(def default-context
  "The context an overlay .clj file loads into when contexts.edn names none for
  it: the overlay's own context by default. A file this map does not name
  loads here unless contexts.edn says otherwise."
  'CxOverlay)

(def except-context
  "The context excepts.edn's entries are excepted from: CxWell, which every
  promotable overlay file loads into (directly or by contexts.edn's naming it)
  and which default-context sees, so an except made here binds CxWell and
  everything that sees it, default-context included. Independent of
  default-context: a deployment can route its own files anywhere and still
  except from CxWell."
  'CxWell)

(defn- log!
  "One overlay line on stderr."
  [& parts]
  (binding [*out* *err*]
    (println (apply str "overlay: " parts))))

(defn- read-excepts
  "The entries of excepts.edn: its one top-level form when that is
  sequential, else [] and a log line — a scalar, a map, a string, a second
  top-level form or unreadable EDN applies nothing rather than throwing or
  applying part of the file."
  [^java.io.File ef]
  (try
    (let [r       (java.io.PushbackReader. (java.io.StringReader. (slurp ef)))
          eof     (Object.)
          entries (edn/read {:eof eof} r)]
      (cond
        (identical? entries eof)
        (do (log! "skipped excepts.edn (empty)") [])

        (not (identical? eof (edn/read {:eof eof} r)))
        (do (log! "skipped excepts.edn (more than one top-level form)") [])

        (not (sequential? entries))
        (do (log! "skipped excepts.edn (not a vector of [sentence context] pairs)") [])

        :else entries))
    (catch Exception e
      (log! "skipped excepts.edn (" (.getMessage e) ")")
      [])))

(defn- apply-except!
  "Except one excepts.edn entry from CxWell and log the result: a line per
  entry, failures marked WARN so a missing target does not read as applied."
  [kb entry]
  (let [r (if-not (and (vector? entry) (= 2 (count entry)))
            {:status :error :entry entry :message "not a [sentence context] pair"}
            (try (let [[sentence context] entry]
                   (utils/except-sentex! kb sentence context except-context))
                 (catch Exception e
                   {:status :error :entry entry :message (.getMessage e)})))
        shown (pr-str (dissoc r :sentence :context))]
    (if (#{:excepted :already} (:status r))
      (log! "except " (pr-str entry) " -> " shown)
      (log! "WARN except " (pr-str entry) " not applied -> " shown))
    r))

(defn- read-contexts
  "[contexts nil] for contexts.edn — its one top-level form, a map from .clj file
  name to context symbol — or [nil unreadable] when it is anything else: an
  unreadable or empty file, a second top-level form, a non-map, a key that is
  not a string or a value that is not a context name. A bad contexts.edn is
  reported like an unreadable .clj file and routes no file, so every file
  loads into default-context rather than into half of a map."
  [^java.io.File cf]
  (let [bad (fn [message] [nil {:file (.getName cf) :line nil :message message}])]
    (try
      (let [r   (java.io.PushbackReader. (java.io.StringReader. (slurp cf)))
            eof (Object.)
            m   (edn/read {:eof eof} r)]
        (cond
          (identical? m eof) (bad "empty")
          (not (identical? eof (edn/read {:eof eof} r))) (bad "more than one top-level form")
          (not (map? m)) (bad "not a map of file name to context")
          :else
          (if-let [[k c] (first (remove (fn [[k c]] (and (string? k) (symbol? c) (eh/context? c))) m))]
            (bad (str "entry " (pr-str k) " " (pr-str c) " is not a file name and a context"))
            [m nil])))
      (catch Exception e (bad (.getMessage e))))))

(defn- read-overlay-file
  "[forms nil] for one overlay .clj file, or [nil unreadable] when a form in it
  does not read: eh/read-forms, the engine's reader for this format, refuses
  such a file as :unreadable with the line it opens on."
  [^java.io.File f]
  (try [(eh/read-forms f) nil]
       (catch clojure.lang.ExceptionInfo e
         [nil {:file (.getName f) :line (:line (ex-data e)) :message (ex-message e)}])))

(defn load-overlay!
  "Load the overlay knowledge in `overlay-dir` into `kb` the way the engine loads a
  text KB, each .clj file into its own context.

  1. First, if the dir holds excepts.edn — a vector of [sentence context]
     pairs — each sentence is excepted from except-context (CxWell) by
     content (utils/except-sentex!), so the except binds CxWell and every
     context that sees it. Each result is logged — a failure as
     `overlay: WARN except ... not applied` — then a count summary; an absent
     excepts.edn logs `overlay: no excepts.edn`.
  2. contexts.edn, when present, maps a .clj file's name to the context its
     forms load into: CxWell for knowledge the deployment intends to promote
     into a shared, engine-standard context (or an existing middle context
     CxWell sees, where the vocabulary obviously belongs), default-context
     for knowledge this overlay keeps to itself. A file it does not name
     loads into default-context. Each routed file is logged as
     `overlay: <file> -> <context>`. A contexts.edn that is not one map of
     file name to context name is reported as an unreadable file and routes
     nothing, so every file falls back to default-context.
  3. Then every form of every *.clj file (sorted by filename; the .edn
     extension keeps excepts.edn and contexts.edn out) goes into its file's
     context in ONE pass through the engine's loader, eh/load-entries! —
     what vaelii.core/load-text! and a starter's own files use:
     order-insensitive, a form refused because what it rests on is further
     down (or in a later file) is retried while rounds make progress, genlCx
     edges go first. Every form, comments, rules, genlCx and excepts
     included. The retry sees only what throws: a form that lands
     differently for coming early — a reifiable function's term asserted
     before the function is declared reifiable, stored unminted — is not
     retried, so for it the file order still matters.

  Why a wrapper at all, and only this much:
  - excepts.edn names starter sentexes by content, which no text-KB form can
    (an `(except H)` is a handle), so it is applied by except-sentex! ahead
    of the pass;
  - an overlay file's name is not its context, as an engine KB file's is, so
    contexts.edn says where each file goes;
  - the pass's assert! records each form's refusal, and the files are read
    one by one, so the log and the return value name EVERY form and file
    that did not load, where load-entries! throws for the first;
  - it never throws: what loaded stays loaded, each refusal and unreadable
    file is logged as `overlay: ERROR ...`, and server start goes on.

  Returns {:excepts [except-sentex! results] :forms n :contexts {file context}
  :refused [{:form :context :type :message}] :unreadable [{:file :line
  :message}]}. An excepts entry that is not a two-element [sentence context]
  vector, or that throws, answers {:status :error :entry entry :message m}."
  [kb ^String overlay-dir]
  (let [ef      (java.io.File. overlay-dir "excepts.edn")
        excepts (if-not (.isFile ef)
                  (do (log! "no excepts.edn") [])
                  (let [results (mapv #(apply-except! kb %) (read-excepts ef))
                        n       (frequencies (map :status results))
                        applied (+ (n :excepted 0) (n :already 0))]
                    (log! "excepts.edn: " (n :excepted 0) " excepted, " (n :already 0) " already, "
                          (- (count results) applied) " not applied")
                    results))
        cf      (java.io.File. overlay-dir "contexts.edn")
        [routes bad-routes] (if (.isFile cf) (read-contexts cf) [{} nil])
        files   (vec (for [^java.io.File f (sort (filter #(and (.isFile ^java.io.File %)
                                                               (.endsWith (.getName ^java.io.File %) ".clj"))
                                                         (.listFiles (java.io.File. overlay-dir))))]
                       (conj (read-overlay-file f) (get routes (.getName f) default-context) (.getName f))))
        contexts (into (sorted-map) (for [[_ _ c n] files] [n c]))
        entries (vec (for [[forms _ c] files, form forms] [form c]))
        refused (atom {})
        assert! (fn [sentence context opts]
                  (try (let [h (v/assert kb sentence context (or opts {}))]
                         (swap! refused dissoc [sentence context])
                         h)
                       (catch clojure.lang.ExceptionInfo e
                         (swap! refused assoc [sentence context] e)
                         (throw e))))
        ;; a (set/inert S) form: the engine's loader writes S through this one
        assert-inert! (fn [sentence context]
                        (try (let [h (v/assert-inert kb sentence context)]
                               (swap! refused dissoc [sentence context])
                               h)
                             (catch clojure.lang.ExceptionInfo e
                               (swap! refused assoc [sentence context] e)
                               (throw e))))]
    (doseq [[n c] contexts :when (contains? routes n)]
      (log! n " -> " c))
    (doseq [n (sort (remove (set (keys contexts)) (keys routes)))]
      (log! "WARN contexts.edn names " n ", which is not a .clj file in the dir"))
    (try (eh/load-entries! assert! assert-inert! entries)
         (catch Exception e
           ;; the last round's throw is a refusal already recorded, unless it
           ;; came from outside assert! (a malformed set/monotonic, a non-ex-info)
           (when-not (some #(identical? e %) (vals @refused))
             (swap! refused assoc [::load nil] e))))
    (let [unreadable (vec (concat (keep identity [bad-routes]) (keep second files)))
          refused    (vec (for [[[form context] e] @refused]
                            {:form form :context context :type (:type (ex-data e)) :message (ex-message e)}))]
      (doseq [{:keys [file line message]} unreadable]
        (log! "ERROR unreadable " file " line " line " -> " message))
      (doseq [{:keys [form context type message]} refused]
        (log! "ERROR refused " (pr-str form) " in " context " " type " -> " message))
      (log! (count entries) " forms from " (count files) " .clj files: " (count refused) " refused, "
            (count unreadable) " files unreadable")
      {:excepts excepts :forms (count entries) :contexts contexts :refused refused :unreadable unreadable})))
