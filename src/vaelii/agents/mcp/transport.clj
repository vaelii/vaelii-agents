;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.transport
  "MCP JSON-RPC 2.0 transport over stdio.

  Reads newline-delimited JSON-RPC messages from stdin, dispatches them
  through the transport-neutral dispatcher, and writes JSON-RPC responses
  to stdout.  Stderr is reserved for server-side logging.

  This transport owns:
    - Reading bytes from stdin, writing bytes to stdout
    - Parsing/serializing JSON
    - Authentication (token extraction from initialize or env var)

  This transport does NOT own:
    - MCP method routing (dispatcher's job)
    - Session/verb semantics (dispatcher's job)
    - HTTP concepts (Streamable HTTP adapter is separate)"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [vaelii.agents.mcp.dispatcher :as dispatcher])
  (:import [java.io BufferedReader InputStreamReader]))

;; ---- I/O helpers -----------------------------------------------------------

(defn- write-message! [^java.io.Writer out msg]
  (let [s (json/write-str msg)]
    (.write out s)
    (.write out "\n")
    (.flush out)))

(defn- log! [& args]
  (binding [*out* *err*]
    (apply println args)))

;; ---- main loop --------------------------------------------------------------

(defn start-stdio!
  "Start the stdio MCP transport.  Reads JSON-RPC from stdin, dispatches
  through the transport-neutral dispatcher, writes to stdout.  Blocks until
  stdin closes.

  `session-atom` holds the authenticated session map (set during init or
  by the caller before starting the loop).

  `config` is the server config map (unused by stdio transport directly,
  retained for API compatibility).

  `auth-fn` is a function (raw-token → session-map | nil)."
  [session-atom _config auth-fn]
  (let [rdr (BufferedReader. (InputStreamReader. System/in))
        out *out*]
    (log! "vaelii MCP server starting on stdio")
    (loop []
      (when-let [line (.readLine rdr)]
        (when-not (str/blank? line)
          (try
            (let [msg    (json/read-str line)
                  method (get msg "method")]

              ;; Handle auth on initialize (stdio-specific: token from message or env)
              (when (= method "initialize")
                (when-not @session-atom
                  (let [params (get msg "params" {})
                        token  (or (get-in params ["_meta" "token"])
                                   (System/getenv "VAELII_TOKEN"))]
                    (when token
                      (when-let [sess (auth-fn token)]
                        (reset! session-atom sess)
                        (log! "authenticated as" (:principal-id sess)
                              "fork" (:fork-id sess)))))))

              ;; Dispatch through the transport-neutral dispatcher
              (let [result (dispatcher/dispatch msg @session-atom)]
                (cond
                  ;; Initialize result — extract the response
                  (and (map? result) (= :initialize-result (:type result)))
                  (write-message! out (:response result))

                  ;; Normal response
                  (some? result)
                  (write-message! out result)

                  ;; nil = notification, no response needed
                  :else
                  (log! "notification:" method))))

            (catch Exception e
              (log! "parse error:" (.getMessage e))
              (write-message! out (dispatcher/jsonrpc-error nil -32700 "parse error")))))
        (recur)))))
