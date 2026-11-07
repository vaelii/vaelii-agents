;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.llm.pool
  "A yes/no question put to a model several times across a pool of Ollama hosts, and
  the vote over the answers.  See docs/llm.md § The vote pool."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [vaelii.agents.llm.ollama :as ollama]
            [vaelii.agents.llm.protocol :as proto]
            [vaelii.agents.llm.stub :as stub]))

(def default-tokens
  "How many calls one host takes at once when its spec does not say."
  2)

(defn parse-hosts
  "`\"gpu1*4,gpu2:8080,localhost\"` → `[{:host \"gpu1\" :tokens 4} {:host \"gpu2:8080\"
  :tokens 2} {:host \"localhost\" :tokens 2}]`: each entry is `host[*n]`, the host as
  `vaelii.agents.llm.ollama/normalize-host` reads it.  A blank or nil string is no hosts;
  an entry with a blank host or an `n` that is not a positive whole number a `long` holds
  throws `:llm-bad-hosts`."
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [spec]
               (let [[_ h n] (re-matches #"([^*\s]+)(?:\*([1-9]\d*))?" spec)
                     tokens  (if n (parse-long n) default-tokens)]
                 (if (and h tokens)
                   {:host h :tokens tokens}
                   (throw (ex-info (str "host entry " (pr-str spec) " is not host[*n], n a"
                                        " positive whole number")
                                   {:type :llm-bad-hosts :entry spec}))))))))

(defn configured-hosts
  "`VAELII_OLLAMA_HOSTS` parsed when set, else the one host
  [[vaelii.agents.llm.ollama]] is configured with (`{:host nil}`)."
  []
  (let [spec (System/getenv "VAELII_OLLAMA_HOSTS")]
    (if (some? spec)
      (parse-hosts spec)
      [{:host nil :tokens default-tokens}])))

(defn- host-provider
  "An Ollama provider on `host`, or the configured host when nil.  A nil `:model`,
  `:num-ctx` or `:timeout-ms` is left out, so the provider's own default applies."
  [{:keys [host] :as opts}]
  (ollama/provider (cond-> (into {} (remove (comp nil? val))
                                 (select-keys opts [:model :num-ctx :timeout-ms]))
                     host (assoc :host host))))

(defn pool
  "A pool over `hosts`, each entry `{:host :tokens}`: a channel holding one provider per
  token, with a capacity fixed at construction.  An empty `hosts` throws
  `:llm-bad-hosts`, since every call on a pool of none would wait out
  `acquire-timeout-ms`."
  [hosts & [opts]]
  (when (empty? hosts)
    (throw (ex-info "a pool needs at least one host" {:type :llm-bad-hosts})))
  (let [providers (vec (for [{:keys [host tokens]} hosts
                             _ (range (max 1 (or tokens default-tokens)))]
                         (host-provider (assoc opts :host host))))
        ch (async/chan (max 1 (count providers)))]
    (doseq [p providers] (async/>!! ch p))
    {:ch ch :capacity (count providers) :hosts (vec hosts) :out (atom 0)}))

(defn stub-pool
  "A pool of one stub provider built with `opts`."
  [& [opts]]
  (let [ch (async/chan 1)]
    (async/>!! ch (stub/provider (or opts {})))
    {:ch ch :capacity 1 :hosts [{:host :stub :tokens 1}] :out (atom 0)}))

(def acquire-timeout-ms
  "How long `with-provider` waits for a free provider, in ms."
  300000)

(defn with-provider
  "Take a provider from `p`, return `(f provider)`, and offer the provider back on every
  exit.  `{:status :no-host}` when none frees up within `acquire-timeout-ms`."
  [p f]
  (let [prov (async/alt!! (:ch p) ([v] v)
                          (async/timeout acquire-timeout-ms) ([_] nil)
                          :priority true)]
    (if (nil? prov)
      {:status :no-host}
      (do (some-> (:out p) (swap! inc))
          (try (f prov)
               (finally
                 (async/offer! (:ch p) prov)
                 (some-> (:out p) (swap! dec))))))))

(defn outstanding
  "How many providers are checked out now, from the pool's own counter.  Nonzero on an
  idle pool means a leaked token."
  [p]
  (some-> (:out p) deref))

(def ^:private yes-no-system
  "You decide whether a claim about an entity is true. Answer with exactly yes or no.")

(defn- answer-text
  "The text of an `end_turn` response, or nil for any other stop reason or empty text."
  [response]
  (when (= "end_turn" (:stop-reason response))
    (let [t (proto/text response)]
      (when-not (empty? t) t))))

(defn- request [system user opts]
  (merge {:system [{:text system}]
          :messages [{:role "user" :content user}]
          :max-tokens (or (:max-tokens opts) 8)}
         (select-keys opts [:model :num-ctx])))

(defn read-yes-no
  "`:yes` or `:no` when the answer's first word is `yes` or `no`, ignoring case and
  leading punctuation, else nil."
  [text]
  (when text
    (let [t (-> text str/trim str/lower-case (str/replace #"^[^a-z]+" ""))]
      (cond
        (re-find #"^yes\b" t) :yes
        (re-find #"^no\b" t)  :no))))

(defn- turn
  "One yes/no turn's outcome: `:yes`, `:no`, nil for an answer that is not a vote, or
  `:failed` when no provider freed up or the call threw.  An interrupt propagates."
  [p question opts]
  (try
    (let [r (with-provider p #(proto/complete % (request (or (:system opts) yes-no-system)
                                                         question opts)))]
      (if (= :no-host (:status r))
        :failed
        (read-yes-no (answer-text r))))
    (catch InterruptedException e (throw e))
    (catch Exception _ :failed)))

(defn ask-yes-no
  "One yes/no turn. Returns `:yes`, `:no`, or nil when the model refused, was cut off,
  answered something that is not a vote, or could not be asked (no provider freed up, or
  the call threw)."
  [p question & [opts]]
  (let [t (turn p question opts)]
    (when-not (= :failed t) t)))

(def default-votes 3)

(defn vote
  "Ask `question` `:votes` times (default `default-votes`) and compare the counted votes.

  Returns `{:verdict :yes|:no|:abstain :votes [..] :tally {..} :calls n :failed n}`:
  `:yes` when more counted votes say yes than no, `:no` for the reverse, `:abstain` on a
  tie.  A turn that is not a vote (a refusal, a truncation, prose) is not counted, and
  neither is one that failed (no provider freed up, or the call threw), which `:failed`
  counts.  A caller does not mark its target on `:abstain`."
  [p question & [{:keys [votes] :as opts}]]
  (let [n     (or votes default-votes)
        turns (vec (repeatedly n #(turn p question opts)))
        cast  (filterv #{:yes :no} turns)
        tally (frequencies cast)
        yes   (get tally :yes 0)
        no    (get tally :no 0)]
    {:verdict (cond (> yes no) :yes
                    (> no yes) :no
                    :else :abstain)
     :votes cast
     :tally tally
     :calls n
     :failed (count (filter #{:failed} turns))}))
