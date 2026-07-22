(ns sugarops.store-contract-test
  "MemStore ≡ DatomicStore parity for the `Store` protocol -- proves
  the backend swap (ADR-2607011000 injection boundary) is real: the
  same sequence of operations against either backend produces the
  same observable results. Mirrors `cerealops.store-contract-test`
  (cloud-itonami-isic-0111) / `vegops.store-contract-test`
  (cloud-itonami-isic-0113).

  This is new: before this pass, `sugarops.store` had no `Store`
  protocol / no `DatomicStore` at all -- only pure functions on plain
  maps (still exercised directly, unchanged, by
  `test/sugarops/store_test.cljc`)."
  (:require [clojure.test :refer [deftest is]]
            [sugarops.store :as store]))

(defn- exercise [s]
  (store/register-batch! s "batch-x" {:product-type :sugar/refined-white :moisture-percent 0.04})
  (is (nil? (:processed? (store/batch s "batch-x"))) "registration alone does not mark processed")
  (store/log-batch! s "batch-x" (store/batch s "batch-x"))
  (is (true? (:processed? (store/batch s "batch-x"))))
  (store/finalize-shipment! s "batch-x")
  (store/append-ledger! s {:t :committed :op :log-production-batch :subject "batch-x"})
  (store/append-ledger! s {:t :governor-hold :op :coordinate-shipment :subject "batch-y"})
  {:batch (store/batch s "batch-x")
   :absent (store/batch s "no-such-batch")
   :already-processed? (store/already-processed? s "batch-x")
   :shipment-finalized? (store/shipment-finalized? s "batch-x")
   :ledger (store/ledger s)})

(deftest mem-and-datomic-parity
  (let [mem (store/mem-store)
        dat (store/datomic-store)
        m (exercise mem)
        d (exercise dat)]
    (is (= :sugar/refined-white (:product-type (:batch m))))
    (is (= :sugar/refined-white (:product-type (:batch d))))
    (is (nil? (:absent m)))
    (is (nil? (:absent d)))
    (is (true? (:already-processed? m)))
    (is (true? (:already-processed? d)))
    (is (true? (:shipment-finalized? m)))
    (is (true? (:shipment-finalized? d)))
    (is (= 2 (count (:ledger m))))
    (is (= 2 (count (:ledger d))))
    (is (= :committed (:t (first (:ledger m)))))
    (is (= :committed (:t (first (:ledger d)))))
    (is (= :governor-hold (:t (second (:ledger m)))))
    (is (= :governor-hold (:t (second (:ledger d)))))))

(deftest datomic-store-seeded-batches
  (let [dat (store/datomic-store {:initial-batches
                                   {"batch-y" {:product-type :sugar/raw-cane}}})]
    (is (= {:product-type :sugar/raw-cane} (store/batch dat "batch-y")))
    (is (empty? (store/ledger dat)))))

(deftest polymorphic-plain-map-still-works
  ;; `sugarops.store/production-batch` and friends must still work
  ;; UNCHANGED against a plain map -- the original contract every
  ;; existing `sugarops.governor`/`sugarops.store` test relies on --
  ;; alongside the new `Store` protocol dispatch this test file
  ;; exercises above.
  (let [st {:batches {"batch-001" {:processed? true}}}]
    (is (true? (store/batch-already-processed? st "batch-001")))
    (is (= {:processed? true} (store/production-batch st "batch-001")))))
