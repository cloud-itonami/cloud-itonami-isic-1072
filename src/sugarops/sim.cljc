(ns sugarops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a registered production
  batch through a clean phase auto-commit (`:schedule-maintenance`),
  an always-escalate batch-logging commit (plant operator approves),
  an always-escalate shipment coordination (plant operator rejects),
  and a hard-hold (unregistered batch), then prints the resulting
  audit ledger. Mirrors `cerealops.sim` (cloud-itonami-isic-0111) /
  `vegops.sim` (cloud-itonami-isic-0113).

  Replaces the previous version of this namespace, which only printed
  a \"not yet implemented\" placeholder and never touched
  `kotoba-lang/langgraph` at all."
  (:require [langgraph.graph :as g]
            [sugarops.operation :as operation]
            [sugarops.store :as store]))

(def plant-operator {:actor-id "operator-01" :role :plant-operator})

(def ^:private clean-batch
  "A batch whose finished-product actuals are all within the
  refined-white-sugar product window (see `sugarops.facts`) and whose
  evidence checklist / sanitation / calibration are all clean --
  registered up front (plant-floor intake), the way every proposal
  against it presupposes."
  {:product-type :sugar/refined-white
   :jurisdiction :us/fda
   :moisture-percent 0.04
   :polarization-percent 99.8
   :color-icumsa 30
   :ash-content-percent 0.02
   :so2-ppm 5
   :granulation-microns 650
   :foreign-material-detected? false
   :metal-detector-last-calibration-date (- #?(:clj (System/currentTimeMillis) :cljs (js/Date.now))
                                             (* 10 24 60 60 1000))
   :weight-variance-grams 20
   :declared-allergens #{}
   :sanitation-score 85
   :evidence-checklist [:cane-or-beet-intake-record :extraction-clarification-log :moisture-test
                        :polarization-test :color-test :so2-residue-test :allergen-declaration :weight-check]})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "operator-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "operator-01"}}
          {:thread-id tid :resume? true}))

(defn demo
  "Run the compiled StateGraph through a commit path, an
  escalate->approve->commit path, an escalate->reject->hold path, and
  a hard-hold path; print each result and the final audit ledger."
  []
  (let [st (store/mem-store {:initial-batches {"batch-001" clean-batch}})
        actor (operation/build st)]

    (println "=== Sugar Manufacturing Coordination Actor Demo ===")

    (println "\n== schedule-maintenance batch-001 (governor-clean, not high-stakes -> commit) ==")
    (println (exec-op actor "t1"
                      {:op :schedule-maintenance :subject "batch-001"
                       :equipment "crystallizer" :reason "scheduled-descale"}
                      plant-operator))

    (println "\n== log-production-batch batch-001 (ALWAYS escalates -- operator approves) ==")
    (let [r (exec-op actor "t2"
                     {:op :log-production-batch :subject "batch-001"}
                     plant-operator)]
      (println r)
      (println "-- plant operator approves --")
      (println (approve! actor "t2")))

    (println "\n== coordinate-shipment batch-001 (ALWAYS escalates -- operator rejects) ==")
    (let [r (exec-op actor "t3"
                     {:op :coordinate-shipment :subject "batch-001"}
                     plant-operator)]
      (println r)
      (println "-- plant operator rejects --")
      (println (reject! actor "t3")))

    (println "\n== schedule-maintenance batch-999 (unregistered -> HARD hold, no interrupt) ==")
    (println (exec-op actor "t4"
                      {:op :schedule-maintenance :subject "batch-999"}
                      plant-operator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger st)] (println f))

    {:ledger (store/ledger st)}))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
  )
