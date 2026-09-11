(ns sugarops.phase
  "Phase machine: the states a sugar production batch transits through.

  State machine:
    :intake -> :extraction -> :clarification -> :crystallization ->
    :centrifuge -> :package -> :audit -> :archived

  `:intake` is cane/beet receiving; `:extraction` is juice extraction
  (milling for cane, diffusion for beet); `:clarification` is juice
  purification (liming/carbonation/sulfitation -- this is where SO2
  residue originates); `:crystallization` is evaporation and vacuum-pan
  crystallization (never directly controlled by this actor --
  crystallization/refining-line control remains exclusive to plant
  staff); `:centrifuge` is separating sugar crystals from the mother
  liquor (massecuite); `:package` is finished-product packaging;
  `:audit` is compliance audit; `:archived` is the terminal state.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the sugar production workflow."
  [:intake :extraction :clarification :crystallization :centrifuge :package :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :extraction :clarification :crystallization :centrifuge :package :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
