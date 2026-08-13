(ns sugarops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had no demo
  page and no generator at all. This namespace does NOT hand-write a
  page -- it RUNS this repo's real actor stack
  (`sugarops.operation/build`'s compiled `langgraph.graph` StateGraph
  -> `sugarops.advisor` -> `sugarops.governor` -> `sugarops.store`)
  through a scenario, then renders whatever that run actually
  produced.

  What on the page is REAL RUNTIME OUTPUT (nothing here is typed by
  hand):

    * every production-batch row      -- read back out of the live
                                         `sugarops.store/Store` AFTER
                                         the run, so `:processed?` /
                                         `:shipment-finalized?` reflect
                                         the durable commit-time side
                                         effects of
                                         `sugarops.operation/apply-commit-side-effects!`
    * every measured value + its limit -- batch fields joined against
                                         `sugarops.facts/product-types`
                                         and `.../jurisdictions`
    * every pass/fail marker          -- computed by calling the
                                         Governor's own independent
                                         verifiers in
                                         `sugarops.registry`, not by
                                         eyeballing the numbers
    * every hard-hold row             -- the `:violations` (rule AND
                                         the Governor's own Japanese
                                         `:detail` string) off the
                                         audit ledger
    * every audit-ledger row          -- `sugarops.store/ledger`, in
                                         append order
    * every human-approval row        -- the `:approval` and `:record`
                                         channels of the resumed
                                         `g/run*` state, plus a
                                         per-row MEASUREMENT of whether
                                         the approver identity survives
                                         into anything the store
                                         persists (see
                                         `approver-attribution-present?`
                                         -- derived, never hard-coded)

  BUILD-TIME INVARIANT. `-main` refuses to write the file unless the run
  actually produced `:governor-hold` facts AND every rule that fired is
  named in the rendered document (`assert-hard-holds!`). A console with
  no HARD hold on it cannot be distinguished from a console whose
  governor was never wired up, so it is treated as a build failure
  rather than a page.

  What on the page is a STATIC DESCRIPTION OF A FIXED CONTRACT (and
  said so plainly in the section itself): the \"action gate\" table.
  Even that is not hand-typed data -- its rows are generated from the
  live `sugarops.governor/allowed-ops`,
  `.../always-escalate-ops`, `.../high-stakes` and
  `.../confidence-floor` vars, so it cannot drift away from the code
  it describes. Only the one-line human gloss per op is prose.

  DETERMINISM. No timestamp, no random value and no clock reading
  reaches the page; two consecutive runs are byte-identical (verify
  with `clojure -M:dev:render-html /tmp/a.html && clojure
  -M:dev:render-html /tmp/b.html && diff /tmp/a.html /tmp/b.html`).
  The clock is read in exactly two places, and stated here rather than
  buried: `recent-calibration-epoch-ms` seeds the metal-detector
  calibration date of the compliant batches as an offset from now, so
  they stay inside the 90-day window whenever the page is regenerated;
  and `physical-row` passes `now` into
  `sugarops.registry/metal-detector-calibration-overdue?` because that
  verifier takes it as an argument (exactly as
  `sugarops.governor` does). Neither epoch number is rendered -- only
  that predicate's boolean verdict is, and it is stable in both
  directions (10 days ago -> current, epoch 0 -> overdue) no matter
  when the page is built.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [sugarops.facts :as facts]
            [sugarops.governor :as governor]
            [sugarops.operation :as operation]
            [sugarops.registry :as registry]
            [sugarops.store :as store]))

;; ----------------------------- scenario seed -----------------------------

(def ^:private plant-operator
  {:actor-id "operator-01" :role :plant-operator})

(def ^:private full-evidence
  "The evidence checklist every jurisdiction in `sugarops.facts`
  requires (`:required-evidence`)."
  [:cane-or-beet-intake-record :extraction-clarification-log :moisture-test
   :polarization-test :color-test :so2-residue-test :allergen-declaration
   :weight-check])

(defn- recent-calibration-epoch-ms
  "Epoch-ms of a metal-detector calibration performed 10 days ago --
  comfortably inside `sugarops.registry`'s 90-day window, whenever the
  page is regenerated. THE ONLY CLOCK READ IN THIS NAMESPACE, and it
  never reaches the rendered page (see the ns docstring): only the
  stable boolean verdict derived from it does. `sugarops.sim` seeds its
  own demo batch the same way."
  []
  (- (System/currentTimeMillis) (* 10 24 60 60 1000)))

(defn- seed-batches
  "The plant-floor batch registrations this scenario runs against, in a
  vector (not a map) so both seeding and rendering iterate in a fixed
  order. Each batch is deliberately clean on every Governor invariant
  EXCEPT the one it is there to demonstrate."
  []
  [["batch-001"
    ;; fully compliant refined white sugar -- clears the whole lifecycle
    {:product-type :sugar/refined-white
     :jurisdiction :us/fda
     :moisture-percent 0.04
     :polarization-percent 99.8
     :color-icumsa 30
     :ash-content-percent 0.02
     :so2-ppm 5
     :granulation-microns 650
     :foreign-material-detected? false
     :metal-detector-last-calibration-date (recent-calibration-epoch-ms)
     :weight-variance-grams 20
     :declared-allergens #{}
     :sanitation-score 85
     :evidence-checklist full-evidence}]

   ["batch-002"
    ;; SO2 residue 45ppm against the refined-white action level of 20ppm
    ;; -- the food-safety hazard specific to sugar refining. Sulfites ARE
    ;; declared, so the labelling invariant stays clean and the hold
    ;; isolates :so2-residue-exceeded.
    {:product-type :sugar/refined-white
     :jurisdiction :jp/mhlw
     :moisture-percent 0.04
     :polarization-percent 99.8
     :color-icumsa 30
     :ash-content-percent 0.02
     :so2-ppm 45
     :granulation-microns 650
     :foreign-material-detected? false
     :metal-detector-last-calibration-date (recent-calibration-epoch-ms)
     :weight-variance-grams 20
     :declared-allergens #{:sulfites}
     :sanitation-score 85
     :evidence-checklist full-evidence}]

   ["batch-003"
    ;; foreign material caught on this batch's own inspection
    {:product-type :sugar/beet-refined
     :jurisdiction :eu/efsa
     :moisture-percent 0.05
     :polarization-percent 99.9
     :color-icumsa 20
     :ash-content-percent 0.015
     :so2-ppm 4
     :granulation-microns 600
     :foreign-material-detected? true
     :metal-detector-last-calibration-date (recent-calibration-epoch-ms)
     :weight-variance-grams 15
     :declared-allergens #{}
     :sanitation-score 88
     :evidence-checklist full-evidence}]

   ["batch-004"
    ;; metal-detector calibration epoch 0 -- permanently >90 days stale,
    ;; so this hold is reproducible forever without a fixture clock.
    {:product-type :sugar/raw-cane
     :jurisdiction :jp/mhlw
     :moisture-percent 0.5
     :polarization-percent 98.2
     :color-icumsa 1200
     :ash-content-percent 0.2
     :so2-ppm 8
     :granulation-microns 700
     :foreign-material-detected? false
     :metal-detector-last-calibration-date 0
     :weight-variance-grams 30
     :declared-allergens #{}
     :sanitation-score 80
     :evidence-checklist full-evidence}]

   ["batch-005"
    ;; fully compliant -- exists to show the OTHER kind of hold: a human
    ;; who is asked and says no (recoverable), vs. a hard hold (not).
    {:product-type :sugar/brown-soft
     :jurisdiction :eu/efsa
     :moisture-percent 1.5
     :polarization-percent 91.5
     :color-icumsa 3800
     :ash-content-percent 0.6
     :so2-ppm 6
     :granulation-microns 350
     :foreign-material-detected? false
     :metal-detector-last-calibration-date (recent-calibration-epoch-ms)
     :weight-variance-grams 25
     :declared-allergens #{}
     :sanitation-score 82
     :evidence-checklist full-evidence}]])

;; ----------------------------- driving the real actor -----------------------------

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context plant-operator} {:thread-id tid}))

(defn- decision
  "Projects the approval-attribution evidence out of ONE resumed
  `g/run*` result. Everything here is read off the graph's own state
  channels, never re-typed:

    :by                    -- the `:approval` channel, i.e. exactly the
                              value the resuming caller handed the
                              interrupt.
    :record-approved-by    -- the `:record` channel, which
                              `sugarops.operation`'s `:request-approval`
                              node writes as
                              `(assoc (:value proposal) :approved-by
                              (:by approval))`.

  The gap between `:record-approved-by` and what the STORE ends up
  holding is the whole point of the disclosure section below -- see
  `approver-attribution-present?`. This function deliberately does not
  consult the store at all."
  [result]
  (let [st (:state result)
        appr (:approval st)]
    {:subject             (get-in st [:request :subject])
     :op                  (get-in st [:request :op])
     :status              (:status appr)
     :by                  (:by appr)
     :record-approved-by  (get-in st [:record :approved-by])}))

(defn- approve! [actor tid]
  (decision (g/run* actor {:approval {:status :approved :by "operator-01"}}
                    {:thread-id tid :resume? true})))

(defn- reject! [actor tid]
  (decision (g/run* actor {:approval {:status :rejected :by "operator-01"}}
                    {:thread-id tid :resume? true})))

(defn run-demo!
  "Builds a fresh seeded `sugarops.store/mem-store`, compiles the REAL
  StateGraph over it (`sugarops.operation/build`) and drives it through
  every disposition this actor can reach. Returns
  `{:store .. :batch-ids ..}`; the caller renders from the store's
  post-run state, so nothing on the page is asserted independently of
  what the graph actually did.

  Reaches, in order:

    1. batch-001 `:schedule-maintenance` -- Governor clean and NOT an
       always-escalate op, the actor's one auto-commit path.
    2. batch-001 `:log-production-batch` -- ALWAYS escalates
       (`governor/high-stakes`); operator APPROVES; commits and
       durably marks the batch processed.
    3. batch-001 `:log-production-batch` again -- now a HARD hold
       (`:already-processed`) purely because step 2's side effect is
       real; the double-commit guard reads durable store state.
    4. batch-001 `:coordinate-shipment` -- escalates, operator
       APPROVES, shipment finalized.
    5. batch-002 `:flag-food-safety-concern` -- always escalates (a
       food-safety concern is never closed on advisor confidence);
       operator APPROVES.
    6. batch-002 `:log-production-batch` -- HARD hold
       `:so2-residue-exceeded` (45ppm vs the product's 20ppm action
       level). Never offered to a human at all.
    7. batch-003 `:log-production-batch` -- HARD hold
       `:foreign-material-detected`.
    8. batch-004 `:log-production-batch` -- HARD hold
       `:metal-detector-calibration-overdue`.
    9. batch-005 `:coordinate-shipment` -- escalates, operator
       REJECTS: a hold a human produced, which is a different thing
       from a hard hold.
   10. batch-999 `:schedule-maintenance` -- HARD hold
       `:batch-not-registered`; no such batch was ever registered on
       the plant floor.
   11. batch-001 `:crystallizer/control` -- HARD hold
       `:op-not-allowed`. Crystallization/refining-line control is
       outside `governor/allowed-ops` entirely, so no confidence and
       no human sign-off can ever reach it."
  []
  (let [seed (seed-batches)
        st (store/mem-store {:initial-batches (into {} seed)})
        actor (operation/build st)
        decisions (atom [])
        decide! (fn [d] (swap! decisions conj d) d)]

    (exec! actor "t01" {:op :schedule-maintenance :subject "batch-001"
                        :equipment "crystallizer" :reason "scheduled-descale"})

    (exec! actor "t02" {:op :log-production-batch :subject "batch-001"})
    (decide! (approve! actor "t02"))

    (exec! actor "t03" {:op :log-production-batch :subject "batch-001"})

    (exec! actor "t04" {:op :coordinate-shipment :subject "batch-001"})
    (decide! (approve! actor "t04"))

    (exec! actor "t05" {:op :flag-food-safety-concern :subject "batch-002"
                        :concern "SO2 residue above product action level"})
    (decide! (approve! actor "t05"))

    (exec! actor "t06" {:op :log-production-batch :subject "batch-002"})

    (exec! actor "t07" {:op :log-production-batch :subject "batch-003"})

    (exec! actor "t08" {:op :log-production-batch :subject "batch-004"})

    (exec! actor "t09" {:op :coordinate-shipment :subject "batch-005"})
    (decide! (reject! actor "t09"))

    (exec! actor "t10" {:op :schedule-maintenance :subject "batch-999"})

    (exec! actor "t11" {:op :crystallizer/control :subject "batch-001"})

    {:store st :batch-ids (mapv first seed) :decisions @decisions}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str
  "Print a keyword the way the source writes it (`:sugar/refined-white`),
  namespace included -- `name` alone would silently collapse
  `:sugar/refined-white` and `:beet/refined-white` to the same cell."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- verdict-cell
  "`actual` against a limit, coloured by one of `sugarops.registry`'s
  independent verifiers -- the SAME predicate the Governor calls, so
  this table cannot disagree with the hold decision below it."
  [actual limit-label bad?]
  (format "<span class=\"num %s\">%s</span> <span class=\"muted\">/ %s</span>"
          (if bad? "critical" "ok") (esc actual) (esc limit-label)))

(defn- lifecycle-cell [b]
  (cond
    (:shipment-finalized? b) "<span class=\"ok\">logged &amp; shipped</span>"
    (:processed? b) "<span class=\"warn\">logged, not yet shipped</span>"
    :else "<span class=\"muted\">registered, unlogged</span>"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)
        rule (some-> f :violations first :rule)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :governor-hold (str "<span class=\"critical\">HARD hold &middot; "
                          (esc (kw-str rule)) "</span>")
      :approval-rejected "<span class=\"warn\">operator rejected</span>"
      "<span class=\"muted\">no activity</span>")))

(defn- batch-row [ledger st batch-id]
  (let [b (store/production-batch st batch-id)
        p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc batch-id)
            (str (esc (:name p)) " <span class=\"muted\">" (esc (kw-str (:id p))) "</span>")
            (esc (:name j))
            (verdict-cell (:moisture-percent b)
                          (str (:moisture-target-percent p) "% ±" (:moisture-tolerance-percent p))
                          (registry/moisture-out-of-target? (:moisture-percent b)
                                                            (:moisture-target-percent p)
                                                            (:moisture-tolerance-percent p)))
            (verdict-cell (:polarization-percent b)
                          (str "min " (:polarization-min-percent p))
                          (registry/polarization-below-minimum? (:polarization-percent b)
                                                                (:polarization-min-percent p)))
            (verdict-cell (:color-icumsa b)
                          (str "max " (:color-max-icumsa p))
                          (registry/color-exceeds-max? (:color-icumsa b) (:color-max-icumsa p)))
            (verdict-cell (:so2-ppm b)
                          (str "max " (:so2-max-ppm p) "ppm")
                          (registry/so2-residue-exceeds-max? (:so2-ppm b) (:so2-max-ppm p)))
            (lifecycle-cell b)
            (status-cell ledger batch-id))))

(defn- bool-cell [bad? bad-label ok-label]
  (if bad?
    (str "<span class=\"critical\">" bad-label "</span>")
    (str "<span class=\"ok\">" ok-label "</span>")))

(defn- physical-row [st batch-id]
  (let [b (store/production-batch st batch-id)
        j (facts/jurisdiction-by-id (:jurisdiction b))
        threshold (:sulfite-declaration-threshold-ppm j)]
    (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
                 "<td>%s</td><td>%s</td><td>%s</td></tr>")
            (esc batch-id)
            (bool-cell (registry/foreign-material-detected? (:foreign-material-detected? b))
                       "detected" "none")
            (bool-cell (registry/metal-detector-calibration-overdue?
                        (:metal-detector-last-calibration-date b)
                        (System/currentTimeMillis))
                       "overdue (&gt;90d)" "current (&le;90d)")
            (verdict-cell (:weight-variance-grams b) "max 50g"
                          (registry/weight-variance-excessive? (:weight-variance-grams b) 50))
            (verdict-cell (:sanitation-score b) "min 75"
                          (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
            (bool-cell (registry/sulfite-label-risk? (:so2-ppm b) threshold
                                                     (:declared-allergens b))
                       "undeclared"
                       (str "ok <span class=\"muted\">(threshold " threshold "ppm)</span>")))))

(def ^:private op-gloss
  "One-line human gloss per op. This map is the ONLY prose on the
  action-gate table -- the gate column itself is computed from
  `sugarops.governor`'s live vars, so it cannot describe a policy the
  code does not have."
  {:log-production-batch     "Log a cane/beet intake → extraction → clarification → crystallization → inspection batch"
   :schedule-maintenance     "Schedule evaporator / crystallizer / centrifuge / metal-detector maintenance"
   :flag-food-safety-concern "Surface an SO2-residue or foreign-material food-safety concern"
   :coordinate-shipment      "Finalize shipment of finished product"})

(defn- action-gate-rows
  "Generated from `governor/allowed-ops` / `always-escalate-ops` /
  `high-stakes`, sorted for determinism."
  []
  (for [op (sort-by kw-str governor/allowed-ops)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (kw-str op))
            (esc (op-gloss op ""))
            (cond
              (governor/high-stakes op)
              "<span class=\"warn\">ALWAYS human sign-off &middot; real actuation event</span>"

              (governor/always-escalate-ops op)
              "<span class=\"warn\">ALWAYS human sign-off &middot; never closed on advisor confidence</span>"

              :else
              (str "<span class=\"ok\">auto-commit when Governor clean and confidence &ge; "
                   governor/confidence-floor "</span>")))))

(defn- hard-hold-rows
  "One row per `:violations` entry on every `:governor-hold` ledger
  fact -- rule keyword AND the Governor's own detail string, verbatim."
  [ledger]
  (for [f ledger
        :when (= :governor-hold (:t f))
        v (:violations f)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (:subject f)) (esc (kw-str (:op f)))
            (esc (kw-str (:rule v)))
            (esc (:detail v "")))))

;; --------------- approver attribution (DERIVED, never asserted) ---------------

(def ^:private approver-attribution-keys
  "Keys that would carry a human approver's identity if anything the
  store persists carried it at all. Matched by KEY, deliberately not by
  value: the committed fact already contains `:actor \"operator-01\"`
  (the requesting context's actor-id), which in this scenario happens to
  be the same string as the approver -- a value scan would therefore
  report attribution that is not actually there."
  #{:approved-by :approver :by})

(defn- approver-attribution-present?
  "Walks everything the STORE persists for `subject` -- its ledger facts
  and its batch register -- and reports whether an approver-attribution
  key survives anywhere in it.

  This is MEASURED at render time, not asserted. The disclosure rendered
  next to each approval is whatever this returns, so if
  `sugarops.operation`'s `:commit` node is later changed to carry the
  `:record` channel (which already holds `:approved-by`) into
  `commit-fact` instead of re-reading `(:value proposal)`, this page
  starts reporting the approver as retained without anyone editing this
  namespace. A hard-coded 'the store drops it' note would have become
  false at that moment and nobody would have noticed."
  [store subject]
  (let [persisted (concat (filter #(= subject (:subject %)) (store/ledger store))
                          (when-let [b (store/production-batch store subject)] [b]))]
    (boolean
     (some (fn [form]
             (some (fn [node]
                     (and (map? node) (some approver-attribution-keys (keys node))))
                   (tree-seq coll? seq form)))
           persisted))))

(defn- decision-row [store {:keys [subject op status by record-approved-by]}]
  (let [retained? (approver-attribution-present? store subject)]
    (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
                 "<td>%s</td><td>%s</td></tr>")
            (esc subject) (esc (kw-str op))
            (if (= :approved status)
              "<span class=\"ok\">approved</span>"
              "<span class=\"warn\">rejected</span>")
            (if by
              (format "<code>%s</code>" (esc by))
              "<span class=\"muted\">none recorded</span>")
            (if retained?
              "<span class=\"ok\">yes — present in the stored record</span>"
              (str "<span class=\"warn\">no — audit only, not retained in record</span>"
                   " <span class=\"muted\">(graph <code>:record</code> channel held <code>:approved-by "
                   (esc (pr-str record-approved-by))
                   "</code>; the committed fact re-reads <code>(:value proposal)</code>)</span>")))))

(defn- basis-str
  "`:basis` is a vector of rule keywords on holds and a vector of
  citation maps on commits -- render both without pretending they are
  the same shape."
  [basis]
  (->> basis
       (map (fn [b] (cond
                      (keyword? b) (kw-str b)
                      (map? b) (str (:spec b))
                      :else (str b))))
       (str/join ", ")))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td>"
               "<td><code>%s</code></td><td>%s</td><td>%s</td></tr>")
          (esc (kw-str t)) (esc (kw-str op)) (esc subject)
          (if (= :commit disposition)
            "<span class=\"ok\">commit</span>"
            "<span class=\"critical\">hold</span>")
          (esc (basis-str basis))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator-console document from the result of
  `run-demo!` (`{:store .. :batch-ids ..}`). Every table below is
  projected out of that live store; see the ns docstring for exactly
  which parts are runtime output and which one section is a
  code-derived description of a fixed contract."
  [{:keys [store batch-ids decisions]}]
  (let [ledger (vec (store/ledger store))
        commits (count (filter #(= :commit (:disposition %)) ledger))
        hard-holds (count (filter #(= :governor-hold (:t %)) ledger))
        any-retained? (some #(approver-attribution-present? store (:subject %)) decisions)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-1072 &middot; sugar manufacturing</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of sugar (ISIC 1072) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · batch logging &amp; shipment always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Build-time snapshot: every row is read back out of the live <code>sugarops.store</code> after the scenario ran, and every pass/fail marker is computed by the Governor's own independent verifiers in <code>sugarops.registry</code> — not by reading the numbers. Regenerate with <code>clojure -M:dev:render-html</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product</th><th>Jurisdiction</th><th>Moisture</th><th>Polarization</th><th>Color (ICUMSA)</th><th>SO<sub>2</sub></th><th>Lifecycle</th><th>Last op</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial batch-row ledger store) batch-ids)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Independent physical &amp; labelling verification</h2>\n"
     "    <p class=\"muted\">The advisor cannot see any of this. Each cell is a call into <code>sugarops.registry</code> against the batch's own on-file inspection record; the sulfite column additionally joins the jurisdiction's declaration threshold from <code>sugarops.facts</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Foreign material</th><th>Metal-detector calibration</th><th>Weight variance</th><th>Sanitation score</th><th>Sulfite declaration</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial physical-row store) batch-ids)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Sugar Governor)</h2>\n"
     "    <p class=\"muted\">Static description of a fixed contract, not telemetry — but generated from <code>sugarops.governor</code>'s live <code>allowed-ops</code> / <code>always-escalate-ops</code> / <code>high-stakes</code> / <code>confidence-floor</code>, so it cannot drift from the code. This allowlist is closed: crystallization and refining-line control (vacuum pan, crystallizer, centrifuge) and food-safety certification are not on it and are refused outright.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>What it does</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (action-gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Hard holds raised in this run</h2>\n"
     "    <p class=\"muted\">A hard hold is un-overridable: it is decided before any human is asked, and no approval path exists that reaches past it. Rule and detail text below are taken verbatim off the audit ledger's <code>:violations</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Op</th><th>Rule</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hard-hold-rows ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Human approvals in this run</h2>\n"
     "    <p class=\"muted\">Every escalation a human actually answered. The right-hand column is <em>measured</em> per row, not asserted: it walks what <code>sugarops.store</code> persisted for that batch — ledger facts and batch register — looking for an approver-attribution key (<code>:approved-by</code> / <code>:approver</code> / <code>:by</code>). Matching is by key, never by value, because the committed fact already carries <code>:actor &quot;operator-01&quot;</code> (the requesting context) which happens to be the same string as the approver here — a value scan would report attribution that is not there.</p>\n"
     (if any-retained?
       ""
       (str "    <p class=\"muted\"><strong>Observed on this build:</strong> the approver identity reaches the graph's <code>:record</code> channel — <code>sugarops.operation</code>'s <code>:request-approval</code> node writes <code>(assoc (:value proposal) :approved-by (:by approval))</code> — but <code>:commit</code> builds its ledger fact from <code>(:value proposal)</code> again and never reads that channel back, and the <code>:approval-granted</code> fact is never passed to <code>store/append-ledger!</code>. So nothing durable retains who approved. This paragraph is emitted only while that is still true.</p>\n"))
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Op</th><th>Decision</th><th>Decided by</th><th>Retained in store?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial decision-row store) decisions)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision facts, in append order, exactly as <code>sugarops.store/ledger</code> returned them — "
     commits " commit(s), " (- (count ledger) commits) " hold(s), of which " hard-holds " were hard.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Batch</th><th>Disposition</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>sugarops.render-html</code> from a real run of this repo's compiled <code>langgraph</code> StateGraph (advisor → governor → store). Not equipment control: vacuum-pan, crystallizer and centrifuge operation, and food-safety certification, remain exclusive to licensed plant staff and regulators.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn- assert-hard-holds!
  "BUILD-TIME INVARIANT, not a comment: refuse to emit a console that
  does not demonstrate the Governor actually stopping something.

  A page showing only green rows is indistinguishable from a page whose
  governor was never wired up -- which is precisely the failure this
  whole artifact exists to rule out. So the two things that would make
  the demonstration vacuous are checked and are fatal:

    1. the run produced zero `:governor-hold` ledger facts, and
    2. the rendered document does not actually name every rule that
       fired (a hold reached the ledger but silently failed to reach
       the page).

  (2) matters because (1) alone would still pass if the hard-hold
  section were dropped from `render`."
  [ledger html]
  (let [holds (filterv #(= :governor-hold (:t %)) ledger)
        rules (into (sorted-set) (comp (mapcat :violations) (map :rule)) holds)]
    (when (empty? holds)
      (throw (ex-info (str "refusing to write the operator console: the scenario produced ZERO "
                           ":governor-hold facts, so the page would demonstrate no HARD hold at all. "
                           "A console of only-green rows cannot be told apart from an unwired governor.")
                      {:ledger-facts (count ledger) :hard-holds 0})))
    (when-let [missing (seq (remove #(str/includes? html (kw-str %)) rules))]
      (throw (ex-info (str "refusing to write the operator console: " (count missing)
                           " HARD-hold rule(s) fired in the run but do not appear in the rendered page.")
                      {:missing (vec missing) :fired (vec rules)})))
    {:holds (count holds) :rules (vec rules)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (vec (store/ledger (:store result)))
        html (render result)
        {:keys [holds rules]} (assert-hard-holds! ledger html)]
    (spit out html)
    (println "wrote" out
             "(" (count ledger) "ledger facts,"
             (count (:batch-ids result)) "registered batches,"
             (count (filter #(= :commit (:disposition %)) ledger)) "commits,"
             (count (:decisions result)) "human decisions,"
             holds "hard holds:" (str/join " " (map kw-str rules)) ")")))
