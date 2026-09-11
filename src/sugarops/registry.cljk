(ns sugarops.registry
  "Pure validation functions for sugar-manufacturing production
  parameters. These are called by the Governor to independently verify
  physical/operational constraints -- the advisor's confidence is NOT
  sufficient to override these checks.

  All functions here are pure arithmetic/set/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `metal-detector-calibration-overdue?`) obtain it themselves via a
  `:clj`/`:cljs` reader-conditional at the call site (see
  `sugarops.governor`).")

(defn moisture-out-of-target?
  "Independently verify that the batch's finished-product moisture falls
  within tolerance of the product's target moisture. Sugar products
  outside their moisture window risk caking/crystal-lattice collapse
  and microbial growth in storage (too high) or handling/dustiness
  problems (too low)."
  [actual-percent target-percent tolerance-percent]
  (or (< actual-percent (- target-percent tolerance-percent))
      (> actual-percent (+ target-percent tolerance-percent))))

(defn polarization-below-minimum?
  "Independently verify that the batch's actual polarization (sucrose
  purity, degrees/percent) does not fall below the product's minimum
  required purity. Polarization below the product's minimum indicates
  the refining process did not remove enough non-sugar solids for this
  grade -- a purity/grade misclassification with real food-quality
  consequences."
  [actual-percent min-percent]
  (< actual-percent min-percent))

(defn color-exceeds-max?
  "Independently verify that the batch's actual color (ICUMSA units)
  does not exceed the product's maximum allowable color. Color above
  the product's maximum indicates insufficient decolorization/refining
  for the declared grade."
  [actual-icumsa max-icumsa]
  (> actual-icumsa max-icumsa))

(defn ash-content-exceeds-max?
  "Independently verify that the batch's conductivity-ash content
  (mineral residue, a core refining-purity indicator) does not exceed
  the product's maximum allowable level."
  [actual-percent max-percent]
  (> actual-percent max-percent))

(defn so2-residue-exceeds-max?
  "Independently verify that the batch's actual sulfur-dioxide residue
  (ppm, left over from the sulfitation step of juice clarification)
  does not exceed the product's maximum allowable level. SO2 residue
  above the regulatory/product action level is one of the most serious
  food-safety hazards specific to sugar refining -- a hard,
  un-overridable stop."
  [actual-ppm max-ppm]
  (> actual-ppm max-ppm))

(defn granulation-out-of-range?
  "Independently verify that the batch's crystal-size distribution
  (microns) falls within the product's expected range. Granulation
  outside range indicates a crystallization/centrifuge-cycle fault and
  risks misclassifying the product grade (e.g. refined sugar
  crystallized to raw-sugar coarseness, or vice versa)."
  [actual-microns min-microns max-microns]
  (or (< actual-microns min-microns)
      (> actual-microns max-microns)))

(defn metal-detector-calibration-overdue?
  "Independently verify that the metal-detection/magnet equipment
  (catches tramp metal before it reaches the crystallizers or the
  finished product) was calibrated within the last 90 days.
  `last-calibration-epoch-ms` and `now-epoch-ms` are both epoch
  milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock
  call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 90 24 60 60 1000)))

(defn weight-variance-excessive?
  "Independently verify that a batch's finished-product weight variance
  (drift from target, in grams) does not exceed the maximum tolerance.
  Excessive variance indicates the packaging scale is out of calibration
  or the refining yield was measured incorrectly."
  [actual-variance-grams max-variance-grams]
  (> actual-variance-grams max-variance-grams))

(defn sulfite-label-risk?
  "True when the batch's actual SO2 residue crosses the jurisdiction's
  sulfite-declaration threshold but `:sulfites` is NOT present in
  `declared-allergens` (mislabeling / under-declaration risk -- a
  genuine food-safety hazard for sulfite-sensitive consumers).
  Declaring sulfites when not strictly required is conservative and
  never a risk."
  [so2-ppm threshold-ppm declared-allergens]
  (and (some? so2-ppm) (some? threshold-ppm)
       (>= so2-ppm threshold-ppm)
       (not (contains? (set declared-allergens) :sulfites))))

(defn foreign-material-detected?
  "Independently verify a batch's foreign-material-detection result
  (metal, stone, glass, or insect fragments caught by
  magnet/metal-detector/optical-sorter inspection). Any detection is a
  genuine physical hazard -- this predicate simply coerces the raw fact
  to a boolean so the Governor's check functions stay uniform in shape
  with every other independently-verified physical constraint in this
  namespace."
  [actual-detected?]
  (boolean actual-detected?))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-production sanitation/
  pest-control score meets the minimum required. Score is 0-100, assessed
  by a third-party auditor against food-safety sanitation and
  rodent/insect infestation-control standards (a significant HACCP
  concern specific to bulk sugar storage and refining)."
  [actual-score min-score-required]
  (< actual-score min-score-required))
