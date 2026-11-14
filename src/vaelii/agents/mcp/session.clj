;; SPDX-License-Identifier: Apache-2.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.agents.mcp.session
  "Session registry with durable, linearizable security authority.

  ALL mutable state lives in ONE atom: sessions, revoked principals,
  fork KBs, fork metadata, and a monotonic generation counter.

  Authority changes (revocation, restoration, provisioning, lease retirement)
  are commit-before-ack: the durable store is written BEFORE in-memory state
  becomes less restrictive.

  The generation counter increments on every revocation, restoration, or lease
  retirement. Dispatchers acquire a permit (generation snapshot) at admission;
  the mutation boundary checks the permit is still current.

  Three lifetimes:  connection ≠ session ≠ fork"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [vaelii.core :as v])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption CopyOption]
           [java.util UUID]))

;; ---- Authority Store protocol -----------------------------------------------

(defprotocol AuthorityStore
  (store-read [this] "Read persisted {:revoked #{}, :fork-meta {}} or nil.")
  (store-write! [this state] "Durably write state. Throws on any failure.")
  (store-healthy? [this] "Returns true if the store can accept writes."))

(deftype FileAuthorityStore [^File dir
                             ^clojure.lang.Atom poisoned]
  ;; B2: poisoned flag — set when a post-replace failure leaves disk and
  ;; memory potentially divergent. Once poisoned, the store reports unhealthy
  ;; and refuses writes until explicitly reconciled (reload from disk).
  AuthorityStore
  (store-read [_]
    (let [f (File. dir "authority.edn")]
      (when (.exists f)
        (edn/read-string (slurp f)))))
  (store-write! [_ state]
    (when @poisoned
      (throw (ex-info "authority store poisoned — reconcile before writing"
                      {:type :authority-store-poisoned})))
    (let [target (File. dir "authority.edn")
          tmp    (File/createTempFile ".vaelii-authority-" ".tmp" dir)
          replaced (atom false)]
      (try
        (spit tmp (pr-str state))
        (with-open [raf (java.io.RandomAccessFile. tmp "rw")]
          (.sync (.getFD raf)))
        (let [moved (Files/move (.toPath tmp) (.toPath target)
                                (into-array CopyOption
                                            [StandardCopyOption/ATOMIC_MOVE
                                             StandardCopyOption/REPLACE_EXISTING]))]
          (when-not moved
            (throw (ex-info "authority store: move returned nil" {}))))
        (reset! replaced true)
        ;; B2: directory sync for metadata durability — fail closed if uncertain
        (with-open [ch (java.nio.channels.FileChannel/open
                        (.toPath dir)
                        (into-array java.nio.file.OpenOption []))]
          (.force ch true))
        (catch Throwable t
          (try (.delete tmp) (catch Exception _))
          ;; B2: if replace succeeded but dir-sync failed, disk has new state
          ;; but memory won't be updated (caller throws). Poison the store.
          (when @replaced
            (reset! poisoned true))
          (throw (ex-info "authority store: durable write failed"
                          {:cause t :type :authority-write-failed
                           :poisoned @replaced}
                          t))))))
  (store-healthy? [_]
    (and (not @poisoned) (.exists dir) (.canWrite dir))))

(defn file-authority-store
  "Create a FileAuthorityStore at the given directory path."
  [dir-path]
  (let [d (io/file dir-path)]
    (.mkdirs d)
    (->FileAuthorityStore d (atom false))))

(deftype RecordingAuthorityStore [^clojure.lang.Atom store-atom
                                  ^clojure.lang.Atom fail-stage]
  AuthorityStore
  (store-read [_] @store-atom)
  (store-write! [_ state]
    ;; B2 cutpoint port: fail-stage can be :write, :sync, :replace, :dir-sync
    ;; to simulate failures at specific filesystem stages.
    (when-let [stage @fail-stage]
      (throw (ex-info (str "injected authority failure at " stage)
                      {:stage stage :type :authority-write-failed})))
    (reset! store-atom state))
  (store-healthy? [_] (nil? @fail-stage)))

(defn recording-authority-store
  "Create a recording authority store for tests."
  ([] (recording-authority-store (atom nil) (atom nil)))
  ([store-atom fail-stage-atom]
   (->RecordingAuthorityStore store-atom fail-stage-atom)))

;; ---- B2: Testable file store with real filesystem + injectable cutpoints ----

(deftype TestableFileAuthorityStore [^File dir
                                     ^clojure.lang.Atom cutpoints]
  ;; Real filesystem operations with injectable failure points at each stage:
  ;; :before-write, :after-write, :before-sync, :after-sync,
  ;; :before-replace, :after-replace, :before-dir-sync, :after-dir-sync
  AuthorityStore
  (store-read [_]
    (let [f (File. dir "authority.edn")]
      (when (.exists f)
        (edn/read-string (slurp f)))))
  (store-write! [_ state]
    (let [target (File. dir "authority.edn")
          tmp    (File/createTempFile ".vaelii-authority-" ".tmp" dir)
          fire!  (fn [stage]
                   (when-let [hooks @cutpoints]
                     (when-let [hook (get hooks stage)]
                       (hook stage))))]
      (try
        ;; Stage 1: write temp file
        (fire! :before-write)
        (spit tmp (pr-str state))
        (fire! :after-write)
        ;; Stage 2: fsync temp file
        (fire! :before-sync)
        (with-open [raf (java.io.RandomAccessFile. tmp "rw")]
          (.sync (.getFD raf)))
        (fire! :after-sync)
        ;; Stage 3: atomic rename
        (fire! :before-replace)
        (let [moved (Files/move (.toPath tmp) (.toPath target)
                                (into-array CopyOption
                                            [StandardCopyOption/ATOMIC_MOVE
                                             StandardCopyOption/REPLACE_EXISTING]))]
          (when-not moved
            (throw (ex-info "authority store: move returned nil" {}))))
        (fire! :after-replace)
        ;; Stage 4: directory sync
        (fire! :before-dir-sync)
        (with-open [ch (java.nio.channels.FileChannel/open
                        (.toPath dir)
                        (into-array java.nio.file.OpenOption []))]
          (.force ch true))
        (fire! :after-dir-sync)
        (catch Throwable t
          (try (.delete tmp) (catch Exception _))
          (throw (ex-info "authority store: durable write failed"
                          {:cause t :type :authority-write-failed}
                          t))))))
  (store-healthy? [_]
    (and (.exists dir) (.canWrite dir))))

(defn testable-file-authority-store
  "B2: real filesystem authority store with injectable cutpoint hooks.
  cutpoints-atom is a map of stage keywords to (fn [stage] ...) that may throw.
  Stages: :before-write :after-write :before-sync :after-sync
          :before-replace :after-replace :before-dir-sync :after-dir-sync"
  ([dir-path] (testable-file-authority-store dir-path (atom nil)))
  ([dir-path cutpoints-atom]
   (let [d (io/file dir-path)]
     (.mkdirs d)
     (->TestableFileAuthorityStore d cutpoints-atom))))

;; ---- Single atom: ALL state ------------------------------------------------

(defonce ^:private state
  (atom {:sessions   {}   ; session-id → session-map
         :revoked    #{}  ; set of revoked principal-ids
         :forks      {}   ; fork-id → fork-kb
         :fork-meta  {}   ; fork-id → {:lifecycle, :lease-ttl-ms, :provisioned-at, :lease-expires}
         :generation 0})) ; monotonic counter, incremented on revocation/retirement

(defonce ^:private authority-store (atom nil))
(defonce ^{:doc "The authority lock. Held during authority-changing operations (revocation,
  restoration, provisioning, lease retirement). C3: verbs also hold this
  during fence-check + mutation to prevent revocation from slipping in between."}
  authority-lock
  (Object.))

;; ---- Authority persistence (commit-before-ack) ------------------------------

(defn- authority-snapshot
  "Extract the durable authority fields from state."
  [s]
  {:revoked   (:revoked s)
   :fork-meta (:fork-meta s)})

(defn- commit-authority!
  "Persist authority state. Throws on failure — the caller MUST NOT update
  in-memory state if this throws."
  [new-state]
  (when-let [store @authority-store]
    (store-write! store (authority-snapshot new-state))))

(defn set-authority-store!
  "Set the durable authority store. Call at startup."
  [store]
  (reset! authority-store store))

(defn load-persisted-state!
  "Load persisted authority from the store. MUST be called before provisioning."
  []
  (when-let [store @authority-store]
    (when-let [persisted (store-read store)]
      (swap! state (fn [s]
                     (-> s
                         (assoc :revoked (or (:revoked persisted) #{}))
                         (assoc :fork-meta (or (:fork-meta persisted) {}))))))))

(defn authority-healthy?
  "Returns true if the authority store is configured and healthy."
  []
  (when-let [store @authority-store]
    (store-healthy? store)))

;; ---- Fork provisioning -----------------------------------------------------

(def ^:private default-lease-ttl-ms (* 60 60 1000))

(defn provision-fork!
  "Provision a named fork. Rejects expired persisted forks (B5).
  Preserves existing lease deadlines on restart (B4)."
  [base-kb fork-id {:keys [lifecycle forks-dir lease-ttl-ms]}]
  (locking authority-lock
    ;; B5: check if expired metadata exists — reject
    (let [existing-meta (get-in @state [:fork-meta fork-id])]
      (when (and existing-meta
                 (:lease-expires existing-meta)
                 (> (System/currentTimeMillis) (:lease-expires existing-meta)))
        (throw (ex-info (str "fork " fork-id " has an expired lease and cannot be reprovisioned")
                        {:type :expired-fork :fork-id fork-id}))))
    ;; Shared forks: a second principal binding an already-live fork-id attaches
    ;; to the existing fork rather than forking the base again — a re-fork over
    ;; the same disk dir would open the store twice and orphan the first handle.
    ;; The config validator already requires every principal naming one fork-id
    ;; to declare the same shape, so a live handle is the whole attach decision.
    (or
     (get-in @state [:forks fork-id])
     (let [fork-opts (if (and (= lifecycle :durable) forks-dir)
                       (let [parent (File. ^String forks-dir)
                             child  (File. ^String forks-dir ^String fork-id)]
                         (when-not (.startsWith (.getCanonicalPath child)
                                                (.getCanonicalPath parent))
                           (throw (ex-info (str "fork-id " fork-id " escapes forks-dir")
                                           {:fork-id fork-id :forks-dir forks-dir})))
                         {:backend :disk-log :dir (str child)})
                       {})
           fk  (v/fork base-kb fork-opts)
           now (System/currentTimeMillis)
           ttl (when (= lifecycle :leased) (or lease-ttl-ms default-lease-ttl-ms))
           existing-meta (get-in @state [:fork-meta fork-id])
           ;; B4: preserve existing lease deadline, don't re-mint
           meta-entry (if (and existing-meta (:lease-expires existing-meta))
                        existing-meta
                        {:lifecycle      (or lifecycle :durable)
                         :lease-ttl-ms   ttl
                         :provisioned-at now
                         :lease-expires  (when ttl (+ now ttl))})
           ;; B1: persist BEFORE making in-memory state visible.
           ;; Compute the new authority snapshot and persist it first.
           ;; If persistence throws, the fork is never visible in memory.
           prospective-state (-> @state
                                 (assoc-in [:fork-meta fork-id] meta-entry))]
       (commit-authority! (assoc prospective-state
                                 :forks (assoc (:forks prospective-state) fork-id fk)))
       ;; Persistence succeeded — now make it visible in memory
       (swap! state (fn [s]
                      (-> s
                          (assoc-in [:forks fork-id] fk)
                          (assoc-in [:fork-meta fork-id] meta-entry))))
       fk))))

;; ---- Generation and permits -------------------------------------------------

(defn current-generation
  "Return the current authority generation."
  []
  (:generation @state))

(defn acquire-permit
  "Snapshot the current generation for fencing. Returns the generation value."
  []
  (:generation @state))

(defn permit-valid?
  "Check if a permit is still valid (generation hasn't changed)."
  [permit-generation]
  (= permit-generation (:generation @state)))

;; ---- Session lifecycle ------------------------------------------------------

(defn create-session!
  "Bind a new session. Atomic check of revocation + fork existence + lease."
  [principal-id fork-id]
  (let [sid    (str (UUID/randomUUID))
        now    (System/currentTimeMillis)
        result (atom nil)]
    (swap! state
           (fn [{:keys [revoked forks fork-meta] :as s}]
             (cond
               (contains? revoked principal-id)
               (do (reset! result :revoked) s)

               (not (contains? forks fork-id))
               (do (reset! result :no-fork) s)

               ;; B5/B6: check fork metadata and lease expiry at admission
               (let [meta (get fork-meta fork-id)]
                 (or (nil? meta)
                     (and (:lease-expires meta)
                          (> now (:lease-expires meta)))))
               (do (reset! result :expired-or-missing) s)

               :else
               (let [session {:session-id   sid
                              :principal-id principal-id
                              :fork-id      fork-id
                              :fork-kb      (get forks fork-id)
                              :created-at   now}]
                 (reset! result session)
                 (assoc-in s [:sessions sid] session)))))
    (let [r @result]
      (case r
        :revoked
        (throw (ex-info "principal revoked"
                        {:type :principal-revoked :principal principal-id}))
        :no-fork nil
        :expired-or-missing nil
        r))))

(defn get-session
  "Look up a live session by ID."
  [session-id]
  (get-in @state [:sessions session-id]))

(defn principal-revoked?
  "Check if a principal has been revoked."
  [principal-id]
  (contains? (:revoked @state) principal-id))

(defn revoke-principal!
  "Revoke all sessions for a principal. Commit-before-ack:
  persist FIRST, then update memory. Increments generation for fencing.

  Linearization: both revocation and verb writes acquire authority-lock.
  Whoever gets it first wins. If the write acquired it first, it runs to
  completion with a success receipt that reflects what it actually committed.
  Revocation then increments
  the generation after the lock is released, fencing the NEXT request."
  [principal-id]
  (locking authority-lock
    (let [new-state (let [s @state]
                      (-> s
                          (assoc :revoked (conj (:revoked s) principal-id))
                          (assoc :sessions
                                 (into {} (remove (fn [[_ sess]]
                                                    (= principal-id (:principal-id sess))))
                                       (:sessions s)))
                          (update :generation inc)))]
      ;; B1: persist BEFORE making in-memory state less restrictive
      (commit-authority! new-state)
      (reset! state new-state))))

(defn restore-principal!
  "Remove a principal from the revoked set."
  [principal-id]
  (locking authority-lock
    (let [new-state (-> @state
                        (update :revoked disj principal-id)
                        (update :generation inc))]
      (commit-authority! new-state)
      (reset! state new-state))))

(defn revoke-session!
  "Revoke a single session (does not affect the fork).
  Takes authority-lock and advances generation so that any permit acquired
  before this call becomes invalid — closing the DELETE/write race (P1#3)."
  [session-id]
  (locking authority-lock
    (let [new-state (-> @state
                        (update :sessions dissoc session-id)
                        (update :generation inc))]
      (commit-authority! new-state)
      (reset! state new-state))))

;; ---- Lease enforcement ------------------------------------------------------

(defn check-session-lease
  "Check if a session's fork lease is valid. Returns session or nil.
  Missing fork metadata → fail closed (B6)."
  [session-id]
  (when-let [sess (get-session session-id)]
    (let [meta (get-in @state [:fork-meta (:fork-id sess)])]
      (cond
        (nil? meta) nil
        (and (:lease-expires meta)
             (> (System/currentTimeMillis) (:lease-expires meta)))
        nil
        :else sess))))

(defn sweep-expired-leases!
  "Atomically: find expired forks, kill bound sessions, remove forks/meta.
  Commit-before-ack. Increments generation under authority-lock."
  ([] (sweep-expired-leases! (System/currentTimeMillis)))
  ([now]
   (locking authority-lock
     (let [s @state
           expired-ids (set (keep (fn [[fid m]]
                                    (when-let [exp (:lease-expires m)]
                                      (when (> now exp) fid)))
                                  (:fork-meta s)))]
       (if (empty? expired-ids)
         0
         (let [new-state (-> s
                             (assoc :sessions
                                    (into {} (remove (fn [[_ sess]]
                                                       (contains? expired-ids (:fork-id sess))))
                                          (:sessions s)))
                             (assoc :forks (apply dissoc (:forks s) expired-ids))
                             (assoc :fork-meta (apply dissoc (:fork-meta s) expired-ids))
                             (update :generation inc))]
           (commit-authority! new-state)
           (reset! state new-state)
           (count expired-ids)))))))

;; ---- Reset (testing only) ---------------------------------------------------

(defn reset-registry!
  "Clear all state. For testing."
  []
  (reset! state {:sessions {} :revoked #{} :forks {} :fork-meta {} :generation 0})
  (reset! authority-store nil))
