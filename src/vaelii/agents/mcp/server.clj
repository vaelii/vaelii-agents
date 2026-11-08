;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.server
  "Production entry point for the Vaelii MCP server.

  A4: The production startup path MUST NOT construct a RecordingAuditSink
  or any in-memory test sink. The A1 adapter is mandatory."
  (:require [clojure.string :as str]
            [vaelii.agents.engine :as eh]
            [vaelii.agents.mcp.config :as config]
            [vaelii.agents.mcp.dispatcher :as dispatcher]
            [vaelii.agents.mcp.http-transport :as http]
            [vaelii.agents.mcp.overlay :as overlay]
            [vaelii.agents.mcp.session :as session]
            [vaelii.core :as v]
            [vaelii.starter :as starter]
            [vaelii.web :as web])
  (:gen-class))

(defn- canonical-path ^String [^String p]
  (.getCanonicalPath (java.io.File. p)))

(defn- ancestor-or-equal?
  "True when canonical path `a` equals `b` or is an ancestor of `b`."
  [^String a ^String b]
  (or (= a b)
      (.startsWith b (str a java.io.File/separator))))

(defn validate-audit-dir-custody!
  "P1#5 + P2: the audit journal directory must be an absolute, symlink-free
  path outside /tmp, and must not equal, contain, or live inside the fork
  storage or authority state directories. All comparisons are canonical —
  a symlinked alias of forks-dir is still forks-dir. Throws :startup-failed."
  [audit-dir forks-dir state-dir]
  (let [audit-path (java.io.File. ^String audit-dir)]
    (when-not (.isAbsolute audit-path)
      (throw (ex-info "VAELII_AUDIT_DIR must be an absolute path"
                      {:type :startup-failed :path audit-dir})))
    (let [canonical (.getCanonicalPath audit-path)
          absolute  (.getAbsolutePath audit-path)]
      (when-not (= canonical absolute)
        (throw (ex-info "VAELII_AUDIT_DIR must not contain symlinks"
                        {:type :startup-failed
                         :path audit-dir
                         :canonical canonical})))
      (when (.startsWith canonical "/tmp")
        (throw (ex-info "VAELII_AUDIT_DIR must not be under /tmp"
                        {:type :startup-failed :path audit-dir})))
      (doseq [[label dir] [["forks-dir" forks-dir] ["state-dir" state-dir]]]
        (when dir
          (let [other (canonical-path dir)]
            (when (or (ancestor-or-equal? canonical other)
                      (ancestor-or-equal? other canonical))
              (throw (ex-info (str "VAELII_AUDIT_DIR must not overlap " label
                                   " — audit custody is separate from belief"
                                   " and authority storage")
                              {:type :startup-failed
                               :path audit-dir
                               :overlaps label
                               :other other})))))))))

(defn parse-args
  "The command-line flags `-main` reads, as an opts map. Unknown words are skipped."
  [args]
  (loop [args (seq args) opts {}]
    (if-not args
      opts
      (case (first args)
        "--config" (recur (nnext args) (assoc opts :config-path (second args)))
        "--port"   (recur (nnext args) (assoc opts :port (parse-long (second args))))
        "--host"   (recur (nnext args) (assoc opts :host (second args)))
        "--origin" (recur (nnext args) (update opts :origins (fnil conj #{}) (second args)))
        "--browser-port" (recur (nnext args) (assoc opts :browser-port (parse-long (second args))))
        "--browser-host" (recur (nnext args) (assoc opts :browser-host (second args)))
        "--browser-principal" (recur (nnext args) (assoc opts :browser-principal (second args)))
        "--browser-token-file" (recur (nnext args) (assoc opts :browser-token-file (second args)))
        (recur (next args) opts)))))

(defn- read-token-file
  "The bearer token held in `path`: its content less one trailing line ending, and
  otherwise byte for byte (a secret is not trimmed). A missing, unreadable or blank
  file refuses startup rather than letting the bind fall back to another token."
  [^String path]
  (let [f (java.io.File. path)]
    (when-not (and (.isFile f) (.canRead f))
      (throw (ex-info (str "browser token file is missing or unreadable: " path)
                      {:type :startup-failed :path path})))
    (let [raw   (slurp f)
          token (cond
                  (.endsWith ^String raw "\r\n") (subs raw 0 (- (count raw) 2))
                  (.endsWith ^String raw "\n")   (subs raw 0 (dec (count raw)))
                  :else raw)]
      (when (str/blank? token)
        (throw (ex-info (str "browser token file is blank: " path)
                        {:type :startup-failed :path path})))
      token)))

(defn browser-start-opts
  "The opts `web/start` is given for the browser. `:token` is present only when
  `--browser-token-file` names one; without it the key is absent and the engine
  reads `VAELII_API_TOKEN`, which a non-loopback bind requires."
  [opts]
  (cond-> {:port (:browser-port opts)
           :host (or (:browser-host opts) "127.0.0.1")}
    (:browser-token-file opts) (assoc :token (read-token-file (:browser-token-file opts)))))

(defn check-browser-bind!
  "The engine's public-bind rule, applied before any store opens: a non-loopback
  browser host with no token throws `:unauthorized` here rather than after the
  forks are provisioned. The same guard and fallback `web/start` applies."
  [browser-opts]
  (eh/require-token! "browser" (:host browser-opts)
                     (if (contains? browser-opts :token)
                       (:token browser-opts)
                       (eh/api-token))))

(defn -main [& args]
  (let [opts (parse-args args)
        ;; the browser bind is settled before anything opens: a public host with
        ;; no token refuses here, not after the forks are provisioned
        browser-opts (when (:browser-port opts)
                       (doto (browser-start-opts opts) check-browser-bind!))
        config-path (or (:config-path opts)
                        (System/getenv "VAELII_CONFIG")
                        "config.edn")
        cfg (config/read-config config-path)
        ;; A3: strict startup ordering
        ;; 1. Config validated (done above)
        ;; 2. Open authority store
        state-dir (:state-dir cfg)
        auth-store (session/file-authority-store state-dir)]
    ;; 3. Load persisted state
    (session/set-authority-store! auth-store)
    (session/load-persisted-state!)
    ;; 4. A1 audit sink — A4: MUST be a real durable sink, not recording
    ;; The A1 adapter is assembled separately and passed here.
    ;; For now, production refuses to start without VAELII_AUDIT_DIR.
    (let [audit-dir (or (System/getenv "VAELII_AUDIT_DIR")
                        (:audit-dir cfg))
          _ (when-not audit-dir
              (throw (ex-info "VAELII_AUDIT_DIR or :audit-dir required for production"
                              {:type :startup-failed})))
          ;; P1#5 + P2: custody validation — absolute, symlink-free, non-/tmp,
          ;; and disjoint from fork storage and authority state
          _ (validate-audit-dir-custody! audit-dir (:forks-dir cfg) state-dir)
          ;; A2/A4: production journal audit sink (canonical JSON, fsync'd, locked)
          audit-store (dispatcher/journal-audit-sink audit-dir)
          ;; Sink lifecycle: from this point the sink has an owner, and every
          ;; exit — partial-startup failure, normal transport return, SIGTERM —
          ;; must close it (release the file lock, close the stream). The
          ;; shutdown hook covers signal-driven service stop, which join? true
          ;; otherwise turns into a never-returning call with no close path.
          close-hook (Thread. ^Runnable (fn [] (.close ^java.io.Closeable audit-store))
                              "vaelii-audit-sink-close")]
      (.addShutdownHook (Runtime/getRuntime) close-hook)
      (try
        (dispatcher/set-audit-sink! audit-store true) ;; A4: production=true rejects RecordingAuditSink
        ;; 4b. Register overlay bounded state with the engine's census
        ;; P2#7: register as config (not cache) with required :read accessor
        (eh/register-cache
         {:cache  :mcp/body-limit
          :label  "MCP request body limit"
          :scope  :process
          :unit   "bytes"
          :read   (fn [] @http/body-limit)
          :limit  (fn [] @http/body-limit)
          :note   "Maximum request body bytes before 413. Configuration, not cache."})
        ;; 5. Open base KB + load overlay knowledge
        (let [base-kb (-> (v/open-kb {}) (starter/load-into))
              overlay-dir (or (System/getenv "VAELII_OVERLAY_DIR")
                              (some-> (:overlay-dir cfg) str))
              _ (when overlay-dir (overlay/load-overlay! base-kb overlay-dir))]
          ;; 6. Provision forks (respects existing lease deadlines)
          (doseq [[_pid pmap] (:principals cfg)]
            (let [fork-id   (:fork-id pmap)
                  lifecycle (:lifecycle pmap :durable)]
              (session/provision-fork! base-kb fork-id
                                       {:lifecycle    lifecycle
                                        :forks-dir    (:forks-dir cfg)
                                        :lease-ttl-ms (:lease-ttl-ms pmap)})))
          ;; 6b. Start browser daemon if requested.
          ;; Uses vaelii.web (HTML browser UI), not vaelii.serve (EDN API).
          ;; A non-loopback host requires a bearer token: --browser-token-file,
          ;; else VAELII_API_TOKEN (checked above by check-browser-bind!).
          (when browser-opts
            (web/start base-kb browser-opts)
            (binding [*out* *err*]
              (println (str "browser: listening on " (:host browser-opts) ":" (:port browser-opts)
                            (when (eh/public-bind? (:host browser-opts))
                              " (bearer token required)")))))
          ;; 7. Start HTTP transport
          (http/start-http!
           {:port           (or (:port opts) (:port cfg) 3000)
            :host           (or (:host opts) (:host cfg) "localhost")
            :origins        (or (:origins opts) (:origins cfg) #{"http://localhost"})
            :config         cfg
            :join?          true
            :sweep-interval (or (:sweep-interval-ms cfg) 60000)
            :audit-sink     audit-store
            :authority-store auth-store
            :base-kb        base-kb}))
        (finally
          ;; Normal return or startup throw: close now and retire the hook.
          ;; During JVM shutdown removeShutdownHook throws IllegalStateException;
          ;; the hook itself is then the closer, and close is idempotent.
          (try (.removeShutdownHook (Runtime/getRuntime) close-hook)
               (catch IllegalStateException _))
          (.close ^java.io.Closeable audit-store))))))
