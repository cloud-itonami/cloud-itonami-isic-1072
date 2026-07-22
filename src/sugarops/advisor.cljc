(ns sugarops.advisor
  "SugarOpsAdvisor -- the contained LLM/decision node that proposes
  plant-operations coordination actions (production-batch logging,
  equipment maintenance scheduling, food-safety concern flagging,
  finished-product shipment coordination) from a request and the
  batch's on-file record. The advisor is SEALED into the `:advise`
  step of `sugarops.operation/build`'s compiled StateGraph; every
  proposal is routed through the independent Governor
  (`sugarops.governor/check`) before anything commits.

  This replaces the previous version of this namespace, which was a
  pure docstring with NO `defprotocol`/implementation at all -- a
  skeleton, not a stub. `sugarops.operation`'s `run-operation` used to
  take a bare proposal map as a function argument rather than ever
  obtaining one from an advisor; the compiled StateGraph's `:advise`
  node now calls `-advise` for real.

  The advisor makes proposals but has NO direct authority -- every
  proposal is always censored by the Governor (`sugarops.governor`),
  independently of the advisor's own confidence.

  Mirrors `cerealops.advisor` (cloud-itonami-isic-0111) /
  `vegops.advisor` (cloud-itonami-isic-0113) in shape."
  (:require [sugarops.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request]
    "Given the injected store and a request `{:op .. :subject ..}`,
    return a proposal map with :op, :effect (always :propose),
    :value, :cites, :summary, :confidence."))

;; Mock advisor for testing / default runtime -- production should
;; swap in an LLM-backed Advisor (same seam point as every sibling
;; cloud-itonami actor's advisor).
(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor store request]
    (let [{:keys [op subject]} request
          b (store/production-batch store subject)
          jurisdiction (:jurisdiction b :us/fda)]
      (case op
        :log-production-batch
        {:op :log-production-batch
         :effect :propose
         :value {:subject subject :jurisdiction jurisdiction}
         :cites [{:spec "Codex-CXS-212"}]
         :summary "Production batch (cane/beet intake through inspection) logged from plant records"
         :confidence 0.85}

        :schedule-maintenance
        {:op :schedule-maintenance
         :effect :propose
         :value {:subject subject
                 :equipment (:equipment request "evaporator")
                 :reason (:reason request "routine-maintenance")}
         :cites [{:spec "Equipment-Manual"}]
         :summary "Equipment maintenance (evaporator/crystallizer/centrifuge/metal-detector) scheduling proposed"
         :confidence 0.85}

        :flag-food-safety-concern
        {:op :flag-food-safety-concern
         :effect :propose
         :value {:subject subject :concern (:concern request "unspecified concern")
                 :jurisdiction jurisdiction}
         :cites [{:spec "Plant-HACCP-Plan"}]
         :summary "Food-safety concern (SO2 residue/foreign-material) flagged for plant-operator review"
         :confidence 0.8}

        :coordinate-shipment
        {:op :coordinate-shipment
         :effect :propose
         :value {:subject subject :jurisdiction jurisdiction}
         :cites [{:spec "Shipment-Manual"}]
         :summary "Finished-product shipment coordination proposed"
         :confidence 0.85}

        ;; fallback -- unrecognized op. The Governor's closed allowlist
        ;; independently rejects this regardless of what the advisor says.
        {:op op
         :effect :propose
         :value {:subject subject}
         :cites []
         :summary "Operation not recognized"
         :confidence 0.0}))))

(defn mock-advisor [] (->MockAdvisor))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a
  proposal is generated, regardless of whether it's approved."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :proposal-summary (:summary proposal)
   :confidence (:confidence proposal)})
