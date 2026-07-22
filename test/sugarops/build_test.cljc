(ns sugarops.build-test
  "Integration tests for `sugarops.operation/build` -- builds the REAL
  compiled `langgraph.graph` StateGraph and runs it end-to-end via
  `langgraph.graph/run*` through all three terminal routes (commit /
  escalate-then-approve / hard-hold / escalate-then-reject). This
  namespace did not exist before: `sugarops.operation` had no `build`
  at all (only the pure `run-operation`, which took a bare proposal
  argument rather than ever obtaining one from an Advisor -- there
  was no Advisor implementation to call). These tests prove the
  compiled graph is real, that the advisor is genuinely wired into
  `:advise`, and that the audit ledger (`sugarops.store/append-
  ledger!`) is genuinely wired into the `:commit`/`:hold` nodes."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sugarops.operation :as operation]
            [sugarops.store :as store]))

(def ^:private plant-operator {:actor-id "operator-01" :role :plant-operator})

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))

(def ^:private clean-batch
  {:product-type :sugar/refined-white
   :jurisdiction :us/fda
   :moisture-percent 0.04
   :polarization-percent 99.8
   :color-icumsa 30
   :ash-content-percent 0.02
   :so2-ppm 5
   :granulation-microns 650
   :foreign-material-detected? false
   :metal-detector-last-calibration-date ten-days-ago
   :weight-variance-grams 20
   :declared-allergens #{}
   :sanitation-score 85
   :evidence-checklist [:cane-or-beet-intake-record :extraction-clarification-log :moisture-test
                        :polarization-test :color-test :so2-residue-test :allergen-declaration :weight-check]})

(defn- fresh-store []
  (store/mem-store {:initial-batches {"batch-001" clean-batch}}))

(deftest commit-path-clean-proposal
  (testing "schedule-maintenance is the one op that isn't always-escalate --
            clean + high-confidence commits through the real compiled
            graph and durably appends to the store's audit ledger"
    (let [st (fresh-store)
          actor (operation/build st)
          result (g/run* actor
                         {:request {:op :schedule-maintenance :subject "batch-001"}
                          :context plant-operator}
                         {:thread-id "t-commit"})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :commit (:disposition state)))
      (is (false? (:hard? (:verdict state))))
      (is (false? (:escalate? (:verdict state))))
      (testing "the ledger (not just transient :audit) has the committed fact"
        (let [ledger (store/ledger st)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :schedule-maintenance (:op (first ledger))))
          (is (= "batch-001" (:subject (first ledger)))))))))

(deftest hard-hold-path-unregistered-batch
  (testing "an unregistered batch is a HARD violation -- the real graph
            routes straight to :hold, no interrupt, and durably
            records the hold fact"
    (let [st (fresh-store)
          actor (operation/build st)
          result (g/run* actor
                         {:request {:op :schedule-maintenance :subject "batch-999"}
                          :context plant-operator}
                         {:thread-id "t-hold"})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (is (true? (:hard? (:verdict state))))
      (let [ledger (store/ledger st)]
        (is (= 1 (count ledger)))
        (is (= :governor-hold (:t (first ledger))))
        (is (some #(= :batch-not-registered (:rule %)) (:violations (first ledger))))))))

(deftest escalate-then-approve-commits-and-marks-processed
  (testing "log-production-batch ALWAYS escalates (high-stakes) -- the
            real graph GENUINELY interrupts (checkpointed) at
            :request-approval; a human approve! resumes the SAME
            compiled graph, commits via the graph's own
            :request-approval -> :commit edge, durably appends to the
            ledger, AND marks the batch processed via
            store/log-batch! (the real batch-lifecycle side effect)"
    (let [st (fresh-store)
          actor (operation/build st)
          held (g/run* actor
                       {:request {:op :log-production-batch :subject "batch-001"}
                        :context plant-operator}
                       {:thread-id "t-escalate"})]
      (is (= :interrupted (:status held)))
      (is (= [:request-approval] (:frontier held)))
      (is (empty? (store/ledger st)) "not yet committed -- awaiting human sign-off")
      (is (nil? (:processed? (store/batch st "batch-001"))) "not yet marked processed either")
      (let [approved (g/run* actor {:approval {:status :approved :by "operator-01"}}
                             {:thread-id "t-escalate" :resume? true})
            approved-state (:state approved)]
        (is (= :done (:status approved)))
        (is (= :commit (:disposition approved-state)))
        (let [ledger (store/ledger st)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-production-batch (:op (first ledger)))))
        (testing "the real, durable batch-lifecycle effect: the batch is
                  now marked processed on the SAME store the graph was
                  built with"
          (is (true? (:processed? (store/batch st "batch-001")))))))))

(deftest escalate-then-reject-holds
  (testing "coordinate-shipment ALWAYS escalates -- a human rejecting
            routes to :hold via the :request-approval node's own
            disposition (not a hand-rolled parallel path), and durably
            records the rejection; the batch's shipment is NOT marked
            finalized since the proposal never committed"
    (let [st (fresh-store)
          actor (operation/build st)
          _held (g/run* actor
                        {:request {:op :coordinate-shipment :subject "batch-001"}
                         :context plant-operator}
                        {:thread-id "t-reject"})
          rejected (g/run* actor {:approval {:status :rejected :by "operator-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger st)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger)))))
      (is (nil? (:shipment-finalized? (store/batch st "batch-001")))))))
