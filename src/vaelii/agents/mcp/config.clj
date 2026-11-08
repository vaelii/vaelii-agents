;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.config
  "Server configuration reader.

  A config file maps bearer-token fingerprints to stable principal IDs and
  fork IDs.  Raw tokens never appear in the config — only their SHA-256 hex
  digest.

  EDN format:

    {:state-dir     \"path/to/authority\"
     :forks-dir     \"path/to/forks\"
     :principals
     {\"alice\" {:token-sha256 \"abcdef...\"
               :fork-id      \"alice\"
               :lifecycle    :durable}
      \"bob\"   {:token-sha256 \"012345...\"
               :fork-id      \"bob\"
               :lifecycle    :durable}}}

  All paths are resolved relative to the config file's parent directory.
  Unknown top-level keys are rejected (closed schema).
  :state-dir is ALWAYS required — even all-leased configs need durable authority."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set]))

(defn- validate-principals!
  [principals path]
  (let [valid-lifecycles #{:durable :leased}
        fingerprints     (atom #{})
        fork-ids         (atom {})]
    (doseq [[pid pmap] principals]
      (when-not (:token-sha256 pmap)
        (throw (ex-info (str "principal " pid " missing :token-sha256") {:path path :principal pid})))
      (when-not (and (string? (:token-sha256 pmap))
                     (re-matches #"[0-9a-f]{64}" (:token-sha256 pmap)))
        (throw (ex-info (str "principal " pid " has malformed :token-sha256 (expected 64 hex chars)")
                        {:path path :principal pid})))
      (when-not (:fork-id pmap)
        (throw (ex-info (str "principal " pid " missing :fork-id") {:path path :principal pid})))
      (when-not (and (string? (:fork-id pmap))
                     (re-matches #"[a-zA-Z0-9][a-zA-Z0-9_-]*" (:fork-id pmap)))
        (throw (ex-info (str "principal " pid " has unsafe :fork-id")
                        {:path path :principal pid :fork-id (:fork-id pmap)})))
      (let [lc (:lifecycle pmap :durable)]
        (when-not (valid-lifecycles lc)
          (throw (ex-info (str "principal " pid " has unsupported :lifecycle " lc)
                          {:path path :principal pid :lifecycle lc}))))
      (let [fp (:token-sha256 pmap)]
        (when (@fingerprints fp)
          (throw (ex-info "duplicate :token-sha256 across principals"
                          {:path path :principal pid})))
        (swap! fingerprints conj fp))
      ;; Shared forks: several principals may bind one :fork-id (a shared
      ;; board several principals trust). What they say about the fork has to
      ;; agree — one fork cannot be both :durable and :leased, or carry two
      ;; lease ttls — so the first principal to name a fork fixes its shape
      ;; and later ones must match it.
      (let [fid    (:fork-id pmap)
            shape  {:lifecycle    (:lifecycle pmap :durable)
                    :lease-ttl-ms (:lease-ttl-ms pmap)}
            seen   (get @fork-ids fid)]
        (when (and seen (not= seen shape))
          (throw (ex-info (str "principals disagree about shared :fork-id " fid)
                          {:path path :principal pid :fork-id fid
                           :declared shape :established seen})))
        (swap! fork-ids assoc fid shape)))))

(def ^:private allowed-top-keys
  #{:principals :forks-dir :state-dir :body-limit :sweep-interval-ms
    :port :host :origins})

(defn read-config
  "Read and validate a server config from an EDN file."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "config file not found: " path) {:path path})))
    (let [cfg (edn/read-string (slurp f))
          base-dir (.getParent f)]
      (when-not (map? cfg)
        (throw (ex-info "config must be an EDN map" {:path path})))
      (let [unknown (clojure.set/difference (set (keys cfg)) allowed-top-keys)]
        (when (seq unknown)
          (throw (ex-info (str "unknown config keys: " (pr-str unknown))
                          {:path path :unknown-keys unknown}))))
      (when-not (:principals cfg)
        (throw (ex-info "config missing :principals" {:path path})))
      (validate-principals! (:principals cfg) path)
      ;; state-dir is ALWAYS required — every topology needs durable authority
      (when-not (:state-dir cfg)
        (throw (ex-info "config missing :state-dir (required for durable security authority)"
                        {:path path})))
      ;; forks-dir required for durable principals
      (let [has-durable? (some (fn [[_ pmap]]
                                 (= :durable (:lifecycle pmap :durable)))
                               (:principals cfg))]
        (when (and has-durable? (not (:forks-dir cfg)))
          (throw (ex-info "config missing :forks-dir but has durable principals"
                          {:path path}))))
      (let [resolve-path (fn [p]
                           (when p
                             (let [pf (io/file p)]
                               (if (.isAbsolute pf)
                                 (str pf)
                                 (str (io/file base-dir p))))))]
        (-> cfg
            (update :forks-dir resolve-path)
            (update :state-dir resolve-path))))))

(defn lookup-principal
  "Given a raw bearer token, hash it and find the matching principal entry.
  Returns [principal-id principal-map] or nil if no match."
  [config raw-token]
  (when (and raw-token (string? raw-token) (seq raw-token))
    (let [digest (let [md (java.security.MessageDigest/getInstance "SHA-256")
                       bytes (.digest md (.getBytes ^String raw-token "UTF-8"))]
                   (apply str (map #(format "%02x" %) bytes)))]
      (some (fn [[pid pmap]]
              (when (= digest (:token-sha256 pmap))
                [pid pmap]))
            (:principals config)))))
