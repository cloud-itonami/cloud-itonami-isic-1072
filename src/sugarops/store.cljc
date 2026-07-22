(ns sugarops.store
  "Store abstraction for sugar-manufacturing production batches.

  This namespace has two layers, kept deliberately compatible so
  neither breaks the other:

    1. Pure functions on a plain data value (`{:batches {batch-id
       batch-map} :facts [...]}`) -- `production-batch`,
       `batch-already-processed?`, `batch-shipment-finalized?`,
       `log-batch`, `finalize-shipment`, `audit-trail`, `append-fact`.
       This is the ORIGINAL surface (`test/sugarops/store_test.cljc`
       and `sugarops.governor`'s violation checks call these directly
       against plain maps) and stays byte-identical in behavior for
       plain-map input -- these are the historical \"MemStore\"
       convention: a farm/plant's whole state as one immutable EDN
       value, no ceremony.

    2. A `Store` protocol + two REAL backends -- `MemStore` (an atom
       wrapping the same plain-map shape) and `DatomicStore` (backed
       by `langchain.db`, a Datomic-API-compatible EAV store, via
       `langchain-store.core` -- no hand-rolled EDN-blob codec,
       ADR-2607141600). This is what `sugarops.operation/build`'s
       compiled `langgraph.graph` StateGraph is injected with: the
       `:commit`/`:hold` nodes need a store they can durably mutate
       (`log-batch!`/`finalize-shipment!`/`append-ledger!`) across
       graph nodes/checkpoint resumes, not a plain map they'd have to
       thread through channels by hand.

  `production-batch`/`batch-already-processed?`/`batch-shipment-
  finalized?` are made POLYMORPHIC (`satisfies? Store st`) so
  `sugarops.governor`'s violation checks work UNCHANGED whether
  called with a plain map (as every existing test does) or with a
  live `Store` (as the compiled graph does) -- the Governor's domain
  rules are not touched by this layer.

  Both `Store` backends pass the same contract
  (test/sugarops/store_contract_test.cljc). `ledger`/`append-ledger!`
  is the append-only audit ledger `sugarops.operation`'s `:commit`/
  `:hold` graph nodes append every committed/held decision fact to --
  previously `append-fact` existed but was never called from
  `sugarops.operation` at all, only from `test/sugarops/store_test.cljc`."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

;; ----------------------------- Store protocol (used by the compiled graph) -----------------------------

(defprotocol Store
  (batch [store batch-id]
    "Retrieve a batch record by id, or nil if not registered.")
  (register-batch! [store batch-id batch-data]
    "Seed/update a batch record directly (no :processed?/
    :shipment-finalized? side effects) -- plant-floor batch
    registration prior to any proposal being made against it.")
  (log-batch! [store batch-id batch-data]
    "Mark a batch logged/processed (:processed? true), preserving/
    overwriting the supplied fields -- the commit-time effect of a
    successful :log-production-batch proposal.")
  (finalize-shipment! [store batch-id]
    "Mark a batch's shipment finalized -- the commit-time effect of a
    successful :coordinate-shipment proposal.")
  (already-processed? [store batch-id])
  (shipment-finalized? [store batch-id])
  (ledger [store]
    "The append-only audit ledger: every committed/held decision
    fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact."))

;; ----------------------------- pure functions on plain maps (ORIGINAL, unchanged contract) -----------------------------

(defn production-batch
  "Retrieve a batch by id, or nil if it does not exist / is not yet
  registered. Polymorphic: `st` may be a plain `{:batches ...}` map
  (the original contract, still exercised directly by
  `sugarops.governor`'s tests) or anything satisfying `Store` (the
  compiled StateGraph's injected backend)."
  [st batch-id]
  (if (satisfies? Store st)
    (batch st batch-id)
    (get-in st [:batches batch-id])))

(defn batch-already-processed?
  "True only if the batch exists and has already been marked processed."
  [st batch-id]
  (if (satisfies? Store st)
    (already-processed? st batch-id)
    (true? (:processed? (production-batch st batch-id)))))

(defn batch-shipment-finalized?
  "True only if the batch exists and its shipment has already been
  finalized."
  [st batch-id]
  (if (satisfies? Store st)
    (shipment-finalized? st batch-id)
    (true? (:shipment-finalized? (production-batch st batch-id)))))

(defn log-batch
  "Register/update `batch-data` under `batch-id` and mark it processed
  (one-way flag). Used once a `:log-production-batch` proposal commits.
  Plain-map function -- unchanged from the original contract."
  [st batch-id batch-data]
  (assoc-in st [:batches batch-id] (assoc batch-data :processed? true)))

(defn finalize-shipment
  "Mark an existing batch's shipment as finalized (one-way flag). Used once
  a `:coordinate-shipment` proposal commits. Plain-map function --
  unchanged from the original contract."
  [st batch-id]
  (assoc-in st [:batches batch-id :shipment-finalized?] true))

(defn audit-trail
  "Return the append-only audit ledger (empty vector if none yet).
  Plain-map function -- unchanged from the original contract."
  [st]
  (get st :facts []))

(defn append-fact
  "Append `fact` to the store's audit ledger. Plain-map function --
  unchanged from the original contract."
  [st fact]
  (update st :facts (fnil conj []) fact))

;; ----------------------------- MemStore (default Store backend) -----------------------------

(defrecord MemStore [state]
  Store
  (batch [_ batch-id] (production-batch @state batch-id))
  (register-batch! [_ batch-id batch-data]
    (swap! state assoc-in [:batches batch-id] batch-data)
    batch-data)
  (log-batch! [this batch-id batch-data]
    (swap! state log-batch batch-id batch-data)
    (batch this batch-id))
  (finalize-shipment! [this batch-id]
    (swap! state finalize-shipment batch-id)
    (batch this batch-id))
  (already-processed? [_ batch-id] (batch-already-processed? @state batch-id))
  (shipment-finalized? [_ batch-id] (batch-shipment-finalized? @state batch-id))
  (ledger [_] (audit-trail @state))
  (append-ledger! [_ fact]
    (swap! state append-fact fact)
    fact))

(defn mem-store
  "Create an in-memory `Store`. `initial-batches` is an optional map of
  batch-id -> batch-record, seeded directly (no :processed?/
  :shipment-finalized? side effects)."
  [& [{:keys [initial-batches] :or {initial-batches {}}}]]
  (->MemStore (atom {:batches initial-batches :facts []})))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  `:batch/payload` is an opaque EDN-string blob (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand a
  caller-defined batch record into sub-entities -- the same
  identity-schema/EDN-blob-codec/seq-keyed-event-log machinery every
  sibling cloud-itonami DatomicStore uses (ADR-2607141600)."
  (ls/identity-schema [:batch/id :ledger/seq]))

(defn- batch-lookup [conn batch-id]
  (when batch-id
    (ls/dec* (d/q '[:find ?p .
                    :in $ ?bid
                    :where [?e :batch/id ?bid] [?e :batch/payload ?p]]
                  (d/db conn) batch-id))))

(defrecord DatomicStore [conn]
  Store
  (batch [_ batch-id] (batch-lookup conn batch-id))
  (register-batch! [_ batch-id batch-data]
    (d/transact! conn [{:batch/id batch-id :batch/payload (ls/enc batch-data)}])
    batch-data)
  (log-batch! [this batch-id batch-data]
    ;; Same value-level effect as the plain-map `log-batch` fn (mark
    ;; :processed? true), applied to the Datomic-backed payload blob
    ;; instead of a plain map's :batches key.
    (d/transact! conn [{:batch/id batch-id :batch/payload (ls/enc (assoc batch-data :processed? true))}])
    (batch this batch-id))
  (finalize-shipment! [this batch-id]
    (let [b (batch this batch-id)]
      (d/transact! conn [{:batch/id batch-id :batch/payload (ls/enc (assoc b :shipment-finalized? true))}])
      (batch this batch-id)))
  (already-processed? [_ batch-id] (true? (:processed? (batch-lookup conn batch-id))))
  (shipment-finalized? [_ batch-id] (true? (:shipment-finalized? (batch-lookup conn batch-id))))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (append-ledger! [store fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger store)) fact)
    fact))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `initial-batches`
  (batch-id -> batch-record); empty when omitted."
  [& [{:keys [initial-batches] :or {initial-batches {}}}]]
  (let [s (->DatomicStore (d/create-conn schema))]
    (doseq [[batch-id batch-data] initial-batches]
      (register-batch! s batch-id batch-data))
    s))
