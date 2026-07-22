(ns sugarops.operation
  "OperationActor -- one sugar-manufacturing plant-operations
  coordination request = one supervised actor run, expressed as a
  langgraph-clj StateGraph. The advisor (`sugarops.advisor`) is
  sealed into a single node (`:advise`); its proposal is ALWAYS
  routed through the independent Sugar Governor (`:govern`) before
  anything commits to the SSoT.

  This replaces the previous version of `build`, which did not exist
  at all -- the only entry point was `run-operation`, a pure function
  that took a bare PROPOSAL as a function argument rather than ever
  obtaining one from an Advisor (there was no Advisor implementation
  to call: `sugarops.advisor` was a pure docstring skeleton). `build`
  is the actor's REAL entry point now: a compiled `langgraph.graph`
  StateGraph whose `:advise` node calls `sugarops.advisor/-advise`,
  mirroring `cerealops.operation` (cloud-itonami-isic-0111) /
  `vegops.operation` (cloud-itonami-isic-0113) node/edge structure.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit                        (:hard? false, :escalate? false)
                                             +-> :request-approval -> :commit    (:escalate? true, interrupt-before)
                                             +-> :hold                          (:hard? true)
  ```

  `run-operation` is KEPT UNCHANGED (still exercised directly by
  `test/sugarops/operation_test.cljc`'s pre-existing tests): it's the
  pure govern-only half of the flow (given an already-formed
  proposal, censor it) -- useful as a standalone entry point
  independent of a compiled graph or a store's advisor. The compiled
  graph's own `:govern` node calls `sugarops.governor/check` directly
  (not `run-operation`) so the SAME governor call is exercised whether
  a caller drives the pure flow or the full graph.

  Almost every op this actor may propose (`:log-production-batch`,
  `:coordinate-shipment`, `:flag-food-safety-concern`) ALWAYS
  escalates for human plant-operator sign-off -- see
  `sugarops.governor/always-escalate-ops`; only `:schedule-maintenance`
  is eligible to auto-commit when the Governor is otherwise clean.
  `:commit` durably marks the batch processed/shipped
  (`store/log-batch!`/`store/finalize-shipment!`) AND appends the
  committed fact to the audit ledger (`store/append-ledger!`); `:hold`
  durably appends the hold/rejection fact -- the core missing
  behavior this actor previously had (`store/append-fact` existed but
  was never called outside tests)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [sugarops.advisor :as advisor]
            [sugarops.governor :as governor]
            [sugarops.store :as store]))

(defn run-operation
  "Drive a single, already-formed proposal through Governor
  validation. Returns {:ok? bool :facts [..] :verdict ..}. Pure
  govern-only half of the flow -- kept for direct testability
  independent of a compiled graph; the compiled StateGraph's own
  `:govern` node calls `sugarops.governor/check` directly."
  [request context proposal store governor-fn]
  (let [verdict (governor-fn request context proposal store)]
    (if (:ok? verdict)
      {:ok? true
       :facts []}
      {:ok? false
       :facts [((:hold-fact-fn context) request context verdict)]
       :verdict verdict})))

(defn- commit-fact
  "The audit fact written when a proposal commits."
  [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :record     (:value proposal)})

(defn- apply-commit-side-effects!
  "The real, durable batch-lifecycle effect of a committed proposal:
  :log-production-batch marks the batch processed (via
  `store/log-batch!`, preserving its on-file data), and
  :coordinate-shipment marks its shipment finalized. Other ops
  (`:schedule-maintenance`, `:flag-food-safety-concern`) have no
  batch-lifecycle side effect -- the ledger fact IS the durable
  record of what happened, same as `cerealops`/`vegops`."
  [store op subject]
  (case op
    :log-production-batch
    (when-let [b (store/batch store subject)]
      (store/log-batch! store subject b))

    :coordinate-shipment
    (store/finalize-shipment! store subject)

    nil))

(defn build
  "Compiles an OperationActor graph bound to `store` (a
  `sugarops.store/Store`, e.g. `store/mem-store` or
  `store/datomic-store`). opts:
    :advisor      -- a `sugarops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [verdict]}]
          (cond
            (:hard? verdict)
            {:disposition :hold}

            (:escalate? verdict)
            {:disposition :escalate}

            :else
            {:disposition :commit})))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (:value proposal) :approved-by (:by approval))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (let [f (commit-fact request context proposal)]
            (apply-commit-side-effects! store (:op request) (:subject request))
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [request context verdict audit]}]
          (let [hf (or (last (filter #(#{:approval-rejected} (:t %)) audit))
                       (governor/hold-fact request context verdict))]
            (store/append-ledger! store (assoc hf :disposition :hold))
            {:audit [hf]})))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
