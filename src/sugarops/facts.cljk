(ns sugarops.facts
  "Reference facts for sugar manufacturing: product-type refining
  parameters (moisture/polarization/color/ash-content/granulation/SO2
  windows), jurisdiction sulfite-declaration and evidence-checklist
  requirements. This namespace contains pure lookup functions for
  regulatory/food-safety compliance checks -- the Governor calls these
  to independently validate proposals; the advisor's confidence is
  never sufficient on its own."
  (:require [clojure.set :as set]))

(def product-types
  "Valid sugar product categories and their safe refining windows.
  `polarization-min-percent` is the minimum sucrose purity (measured by
  polarimeter, in degrees/percent) the product must meet -- refined
  white sugar demands near-total sucrose purity while raw and brown
  sugars retain more non-sugar solids by design. `color-max-icumsa` is
  the maximum allowable color (ICUMSA units -- lower is whiter/more
  refined). `ash-content-max-percent` is the maximum conductivity-ash
  (mineral residue) purity indicator. `so2-max-ppm` is the maximum
  allowable sulfur-dioxide residue (parts per million) left over from
  the sulfitation step of juice clarification -- deliberately
  per-product-type since a fully refined white sugar and a
  minimally-processed raw sugar carry different residue expectations."
  {:sugar/refined-white
   {:id :sugar/refined-white
    :name "精製白糖"
    :moisture-target-percent 0.04
    :moisture-tolerance-percent 0.02
    :polarization-min-percent 99.7
    :color-max-icumsa 45
    :ash-content-max-percent 0.03
    :so2-max-ppm 20
    :granulation-min-microns 500
    :granulation-max-microns 850}

   :sugar/raw-cane
   {:id :sugar/raw-cane
    :name "粗糖(原料糖)"
    :moisture-target-percent 0.5
    :moisture-tolerance-percent 0.15
    :polarization-min-percent 96.0
    :color-max-icumsa 2000
    :ash-content-max-percent 0.35
    :so2-max-ppm 10
    :granulation-min-microns 400
    :granulation-max-microns 1000}

   :sugar/beet-refined
   {:id :sugar/beet-refined
    :name "精製甜菜糖"
    :moisture-target-percent 0.05
    :moisture-tolerance-percent 0.02
    :polarization-min-percent 99.8
    :color-max-icumsa 35
    :ash-content-max-percent 0.02
    :so2-max-ppm 15
    :granulation-min-microns 450
    :granulation-max-microns 800}

   :sugar/brown-soft
   {:id :sugar/brown-soft
    :name "含蜜糖(ブラウンシュガー)"
    :moisture-target-percent 1.5
    :moisture-tolerance-percent 0.3
    :polarization-min-percent 89.0
    :color-max-icumsa 6000
    :ash-content-max-percent 0.8
    :so2-max-ppm 20
    :granulation-min-microns 200
    :granulation-max-microns 600}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Sugar-manufacturing jurisdictions and their sulfite-declaration and
  evidence-checklist requirements. Sulfur dioxide (used in the
  sulfitation stage of juice clarification, and carried through into
  finished-product residue) is a regulated allergen-adjacent hazard --
  most jurisdictions require a \"contains sulfites\" declaration once
  residual SO2 exceeds a threshold. `sulfite-declaration-threshold-ppm`
  intentionally varies per jurisdiction to reflect differing regulatory
  thresholds."
  {:jp/mhlw
   {:id :jp/mhlw
    :name "日本 (食品表示法・厚生労働省)"
    :sulfite-declaration-required true
    :sulfite-declaration-threshold-ppm 30
    :required-evidence
    [:cane-or-beet-intake-record
     :extraction-clarification-log
     :moisture-test
     :polarization-test
     :color-test
     :so2-residue-test
     :allergen-declaration
     :weight-check]}

   :us/fda
   {:id :us/fda
    :name "United States (FDA/FALCPA)"
    :sulfite-declaration-required true
    :sulfite-declaration-threshold-ppm 10
    :required-evidence
    [:cane-or-beet-intake-record
     :extraction-clarification-log
     :moisture-test
     :polarization-test
     :color-test
     :so2-residue-test
     :allergen-declaration
     :weight-check]}

   :eu/efsa
   {:id :eu/efsa
    :name "European Union (EFSA)"
    :sulfite-declaration-required true
    :sulfite-declaration-threshold-ppm 10
    :required-evidence
    [:cane-or-beet-intake-record
     :extraction-clarification-log
     :moisture-test
     :polarization-test
     :color-test
     :so2-residue-test
     :allergen-declaration
     :weight-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn sulfite-declaration-required?
  "True when `so2-ppm` crosses the jurisdiction's sulfite-declaration
  threshold and therefore the finished product must carry a \"contains
  sulfites\" (or equivalent) allergen-adjacent label. `jurisdiction` may
  be a resolved jurisdiction map or a raw jurisdiction id."
  [jurisdiction so2-ppm]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (boolean
     (and j so2-ppm
          (>= so2-ppm (:sulfite-declaration-threshold-ppm j))))))

(defn sulfite-declaration-complete?
  "Verify that when sulfite declaration is required for `so2-ppm` under
  `jurisdiction`, `:sulfites` is present in `declared`. Declaring
  sulfites even when not strictly required is conservative and always
  passes."
  [jurisdiction so2-ppm declared]
  (if (sulfite-declaration-required? jurisdiction so2-ppm)
    (contains? (set declared) :sulfites)
    true))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn moisture-in-range?
  "Positive-sense convenience predicate: does `percent` fall within
  `product`'s moisture tolerance window (inclusive) around its target?
  Sugar products must stay within a narrow moisture band -- too high
  risks caking/microbial growth and crystal-lattice collapse in
  storage, too low is a refining-yield/handling problem."
  [percent product]
  (boolean
   (and (some? product)
        (let [target (:moisture-target-percent product)
              tol (:moisture-tolerance-percent product)]
          (and (>= percent (- target tol))
               (<= percent (+ target tol)))))))

(defn polarization-meets-minimum?
  "Positive-sense convenience predicate: does `percent` meet or exceed
  `product`'s minimum required sucrose purity (polarization)?"
  [percent product]
  (boolean
   (and (some? product)
        (>= percent (:polarization-min-percent product)))))

(defn color-within-max?
  "Positive-sense convenience predicate: does `icumsa` stay at or below
  `product`'s maximum allowable color (ICUMSA units)?"
  [icumsa product]
  (boolean
   (and (some? product)
        (<= icumsa (:color-max-icumsa product)))))

(defn ash-content-within-max?
  "Positive-sense convenience predicate: does `percent` stay at or below
  `product`'s maximum allowable conductivity-ash content?"
  [percent product]
  (boolean
   (and (some? product)
        (<= percent (:ash-content-max-percent product)))))

(defn granulation-in-range?
  "Positive-sense convenience predicate: does `microns` fall within
  `product`'s expected crystal-size window (inclusive)?"
  [microns product]
  (boolean
   (and (some? product)
        (>= microns (:granulation-min-microns product))
        (<= microns (:granulation-max-microns product)))))

(defn so2-within-max?
  "Positive-sense convenience predicate: does `ppm` stay at or below
  `product`'s maximum allowable sulfur-dioxide residue?"
  [ppm product]
  (boolean
   (and (some? product)
        (<= ppm (:so2-max-ppm product)))))
