;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.http-transport-test
  "POST /op — the raw-operation route beside /mcp. It authenticates exactly as
  /mcp does (origin check, then a config.edn principal bearer token) and writes
  through the MCP assert verb into the authenticated principal's fork."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [vaelii.agents.mcp.dispatcher :as dispatcher]
            [vaelii.agents.mcp.http-transport :as http]
            [vaelii.agents.mcp.session :as session]
            [vaelii.agents.test-util :as tu]
            [vaelii.core :as v]
            [vaelii.starter :as starter])
  (:import [java.io ByteArrayInputStream]))

(def ^:private token "token-op-principal")

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes s "UTF-8"))))))

(def ^:private audit (atom []))

(defn- base-kb []
  (-> (v/open-kb {:backend :memory :space [::http-transport-test]})
      starter/load-into))

(use-fixtures :each
  (fn [f]
    (session/reset-registry!)
    (reset! audit [])
    (dispatcher/set-audit-sink! (dispatcher/recording-sink audit))
    (reset! @#'http/server-config
            {:principals {"op-principal" {:token-sha256 (sha256-hex token)
                                          :fork-id "op-principal"
                                          :lifecycle :durable}}})
    ((tu/neutral-fresh base-kb)
     (fn []
       (session/provision-fork! tu/*kb* "op-principal" {:lifecycle :durable})
       (f)))
    (session/reset-registry!)))

(def ^:private edit-body
  "{:op :edit :args [{:add [[(unary_predicate op_probe_type) CxUniverse]]}]}")

(defn- op-request
  ([method headers] (op-request method headers edit-body))
  ([method headers ^String body]
   {:uri "/op" :request-method method
    :headers (merge {"content-type" "application/edn"} headers)
    :body (when body (ByteArrayInputStream. (.getBytes body "UTF-8")))}))

(defn- registry [] @@#'session/state)

(defn- probe-landed? []
  (boolean (v/handle-of (get-in (registry) [:forks "op-principal"])
                        '(unary_predicate op_probe_type) 'CxUniverse)))

(defn- refused-and-audited [status headers]
  (let [before (count @audit)
        resp   (http/handler (op-request :post headers))]
    (is (= status (:status resp)))
    (is (not (probe-landed?)) "a refused /op must not write")
    (is (> (count @audit) before) "the denial is audited as on /mcp")))

(deftest post-op-without-auth-is-refused
  (testing "no Authorization header"
    (refused-and-audited 401 {}))
  (testing "a bearer that names no principal"
    (refused-and-audited 401 {"authorization" "Bearer not-a-token"}))
  (testing "a disallowed origin is refused before the bearer is read, as on /mcp"
    (refused-and-audited 403 {"origin" "http://evil.example.com"
                              "authorization" (str "Bearer " token)})))

(deftest post-op-with-auth-reaches-the-handler
  (let [resp (http/handler (op-request :post {"authorization" (str "Bearer " token)}))]
    (is (= 200 (:status resp)))
    (is (probe-landed?) "the :add lands in the authenticated principal's fork")
    (is (empty? (:sessions (registry)))
        "the ephemeral /op session is revoked after the request")))

(deftest post-op-refuses-an-unsupported-body
  (let [auth {"authorization" (str "Bearer " token)}]
    (testing "malformed EDN"
      (is (= 400 (:status (http/handler (op-request :post auth "{:op"))))))
    (testing "an op other than :edit/:add"
      (is (= 400 (:status (http/handler (op-request :post auth "{:op :retract :args [1]}"))))))
    (is (not (probe-landed?)))))

(deftest get-op-is-not-a-route
  (let [resp (http/handler (op-request :get {"authorization" (str "Bearer " token)} nil))]
    (is (contains? #{404 405} (:status resp)))
    (is (not (probe-landed?)))))
