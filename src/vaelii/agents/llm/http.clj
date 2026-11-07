;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.http
  "The part of an HTTP provider's transport that is not its wire format: the connect and
  read deadlines, the typed send, the not-JSON refusal, the bounded excerpt.  Each fn
  takes an endpoint
  `{:label :slug}`: `:label` names the far end in a message, `:slug` in a thread name.
  See docs/llm.md § What both HTTP backends share."
  (:require [cheshire.core :as json])
  (:import [java.io IOException InputStream InputStreamReader]
           [java.net.http HttpClient HttpConnectTimeoutException HttpRequest
            HttpResponse HttpResponse$BodyHandler HttpResponse$BodyHandlers
            HttpTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def connect-timeout-ms
  "The connect deadline, TCP and TLS handshakes included, in ms: every provider's client
  is built on it, whatever `:timeout-ms` the turn allows."
  5000)

(defn excerpt
  "The first 200 characters of `s`, or nil."
  [^String s]
  (when s (subs s 0 (min (.length s) 200))))

(defn send-request
  "`(.send client request handler)`, with the send's own failures typed and the original
  as the cause: no response inside the request's `.timeout` is `:llm-timeout` carrying
  `:timeout-ms`, and a connect timeout or any other `IOException` (refused, reset,
  unresolved) is `:llm-unreachable`."
  ^HttpResponse [{:keys [label]} ^HttpClient client ^HttpRequest request
                 ^HttpResponse$BodyHandler handler]
  (try
    (.send client request handler)
    (catch HttpConnectTimeoutException e
      (throw (ex-info (str label " did not accept a connection inside " connect-timeout-ms " ms")
                      {:type :llm-unreachable} e)))
    (catch HttpTimeoutException e
      (let [ms (some-> ^Duration (.orElse (.timeout request) nil) .toMillis)]
        (throw (ex-info (str label " sent no response inside " ms " ms; raise :timeout-ms"
                             " to wait longer")
                        {:type :llm-timeout :timeout-ms ms} e))))
    (catch IOException e
      (throw (ex-info (str label " could not be reached: "
                           (or (ex-message e) (.getName (class e))))
                      {:type :llm-unreachable} e)))))

(defn decode
  "A JSON body -> data.  A body that is not JSON throws `:llm-bad-response` with
  `:status` and the bounded `:excerpt`; the text stays out of the message."
  [{:keys [label]} ^String text status]
  (try
    (json/parse-string text)
    (catch Exception e
      (throw (ex-info (str label " answered " status " with a body that is not JSON")
                      {:type :llm-bad-response :status status :excerpt (excerpt text)}
                      e)))))

(defn watchdog
  "A started daemon thread that runs `on-expiry` once `ms` have passed; interrupt it to
  cancel."
  ^Thread [{:keys [slug]} ms on-expiry]
  (doto (Thread. ^Runnable (fn []
                             (try
                               (Thread/sleep (long ms))
                               (on-expiry)
                               (catch InterruptedException _ nil)
                               (catch Exception _ nil)))
                 (str "vaelii-" slug "-deadline"))
    (.setDaemon true)
    (.start)))

(defn under-read-deadline
  "Return `(f)`, with a watchdog that runs `on-expiry` (which closes the body) once `ms`
  have passed.  Any `Throwable` from `f` after expiry is rethrown as `:llm-timeout`
  carrying `:timeout-ms` and the original as its cause; before expiry it is rethrown
  unchanged.  The watchdog is cancelled on every exit.  `f` reads the body eagerly."
  [{:keys [label] :as endpoint} ms on-expiry f]
  (let [expired    (atom false)
        ^Thread wd (watchdog endpoint ms (fn [] (reset! expired true) (on-expiry)))]
    (try
      (f)
      (catch Throwable t
        (if @expired
          (throw (ex-info (str label " stopped answering while its response was being"
                               " read — " ms " ms of the turn's :timeout-ms budget"
                               " remained for the body; raise :timeout-ms to wait longer")
                          {:type :llm-timeout :timeout-ms ms}
                          t))
          (throw t)))
      (finally (.interrupt wd)))))

(defn send-for-text
  "Send `request` and read its whole body as UTF-8 text, both inside `ms` of the call:
  `[status text]`.  The send fails as `send-request` does, and a body that stops arriving
  after the headers is `under-read-deadline`'s `:llm-timeout`."
  [endpoint ^HttpClient client ^HttpRequest request ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))
        ^HttpResponse resp (send-request endpoint client request
                                         (HttpResponse$BodyHandlers/ofInputStream))
        ^InputStream in (.body resp)]
    [(.statusCode resp)
     (under-read-deadline endpoint (max 1 (- deadline (System/currentTimeMillis)))
                          #(.close in)
                          #(with-open [r (InputStreamReader. in StandardCharsets/UTF_8)]
                             (slurp r)))]))
