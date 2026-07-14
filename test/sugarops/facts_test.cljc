(ns sugarops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [sugarops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "refined white sugar product type exists"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (some? p))
      (is (= (:id p) :sugar/refined-white))
      (is (= (:moisture-target-percent p) 0.04))
      (is (= (:so2-max-ppm p) 20))))

  (testing "brown/soft sugar product type exists"
    (let [p (facts/product-type-by-id :sugar/brown-soft)]
      (is (some? p))
      (is (= (:polarization-min-percent p) 89.0))
      (is (= (:color-max-icumsa p) 6000))))

  (testing "raw cane sugar product type exists"
    (let [p (facts/product-type-by-id :sugar/raw-cane)]
      (is (some? p))
      (is (= (:polarization-min-percent p) 96.0))
      (is (= (:so2-max-ppm p) 10))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :sugar/nonexistent)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MHLW jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (some? j))
      (is (true? (:sulfite-declaration-required j)))
      (is (= (:sulfite-declaration-threshold-ppm j) 30))))

  (testing "US FDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda)]
      (is (some? j))
      (is (= (:sulfite-declaration-threshold-ppm j) 10))))

  (testing "EU EFSA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/efsa)]
      (is (some? j))
      (is (= (:sulfite-declaration-threshold-ppm j) 10))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Sulfite Declaration ──────────────────────

(deftest sulfite-declaration-required-test
  (testing "SO2 residue above threshold requires declaration"
    (is (true? (facts/sulfite-declaration-required? :us/fda 15))))

  (testing "SO2 residue at threshold requires declaration"
    (is (true? (facts/sulfite-declaration-required? :us/fda 10))))

  (testing "SO2 residue below threshold does not require declaration"
    (is (false? (facts/sulfite-declaration-required? :us/fda 5))))

  (testing "accepts a resolved jurisdiction map"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (false? (facts/sulfite-declaration-required? j 15)))
      (is (true? (facts/sulfite-declaration-required? j 30))))))

(deftest sulfite-declaration-complete-test
  (testing "declaration present when required passes"
    (is (true? (facts/sulfite-declaration-complete? :us/fda 15 #{:sulfites}))))

  (testing "declaration missing when required fails"
    (is (false? (facts/sulfite-declaration-complete? :us/fda 15 #{}))))

  (testing "declaration not required when SO2 below threshold passes regardless"
    (is (true? (facts/sulfite-declaration-complete? :us/fda 5 #{}))))

  (testing "declaring sulfites even when not required is conservative and passes"
    (is (true? (facts/sulfite-declaration-complete? :us/fda 5 #{:sulfites})))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:cane-or-beet-intake-record :extraction-clarification-log :moisture-test
                    :polarization-test :color-test :so2-residue-test :allergen-declaration :weight-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:cane-or-beet-intake-record :extraction-clarification-log]]
      (is (false? (facts/required-evidence-satisfied? j evidence))))))

;; ──────────────────────── Refining Safety Predicates ──────────────────────

(deftest moisture-in-range-test
  (testing "moisture within tolerance passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/moisture-in-range? 0.04 p)))))

  (testing "moisture at lower tolerance boundary passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/moisture-in-range? 0.02 p)))))

  (testing "moisture below range fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/moisture-in-range? 0.01 p)))))

  (testing "moisture above range fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/moisture-in-range? 0.10 p))))))

(deftest polarization-meets-minimum-test
  (testing "polarization at or above minimum passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/polarization-meets-minimum? 99.7 p)))
      (is (true? (facts/polarization-meets-minimum? 99.9 p)))))

  (testing "polarization below minimum fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/polarization-meets-minimum? 95.0 p))))))

(deftest color-within-max-test
  (testing "color at or below max passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/color-within-max? 45 p)))
      (is (true? (facts/color-within-max? 30 p)))))

  (testing "color above max fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/color-within-max? 100 p))))))

(deftest ash-content-within-max-test
  (testing "ash content at or below max passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/ash-content-within-max? 0.03 p)))
      (is (true? (facts/ash-content-within-max? 0.01 p)))))

  (testing "ash content above max fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/ash-content-within-max? 0.10 p))))))

(deftest granulation-in-range-test
  (testing "granulation within range passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/granulation-in-range? 650 p)))))

  (testing "granulation below minimum fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/granulation-in-range? 300 p)))))

  (testing "granulation above maximum fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/granulation-in-range? 1200 p))))))

(deftest so2-within-max-test
  (testing "SO2 at or below the max passes"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (true? (facts/so2-within-max? 20 p)))
      (is (true? (facts/so2-within-max? 5 p)))))

  (testing "SO2 above the max fails"
    (let [p (facts/product-type-by-id :sugar/refined-white)]
      (is (false? (facts/so2-within-max? 25 p))))))
