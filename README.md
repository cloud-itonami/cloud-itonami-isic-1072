# cloud-itonami-isic-1072: Sugar Manufacturing Coordination Actor

**ISIC Rev. 5 1072** — Manufacture of Sugar

A distributed actor for autonomous, compliant coordination of sugar-manufacturing plant operations: cane/beet intake → extraction (milling/diffusion) → clarification (liming/carbonation/sulfitation) → crystallization/evaporation → centrifuging → moisture/polarization/color/ash-content/granulation/SO2-residue inspection → sulfite labeling → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Vacuum-pan evaporator, crystallizer, and centrifuge operation and food-safety certification authority remain exclusive to licensed sugar-refinery plant staff and regulators.

## Scope

This actor coordinates **plant-operations workflow** for sugar manufacturing (refined white sugar, raw cane sugar, refined beet sugar, brown/soft sugar):
- Production batch logging (cane/beet intake, refining parameters, evidence checklist)
- Equipment maintenance scheduling (evaporators, crystallizers, centrifuges, metal detectors)
- Food-safety concern escalation (sulfur-dioxide residue, foreign-material contamination)
- Finished-product shipment coordination

**Out of scope:**
- Direct crystallization/refining-line equipment control (plant staff exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch crystallization/refining-line control or food-safety certification
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Plant/batch record not independently verified/registered before any proposal is made against it (`:batch-not-registered`) — applies to every proposal op, not only shipment coordination
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Finished-product moisture outside the product's safe storage/quality range (`:moisture-out-of-target`)
  - Polarization (sucrose purity) below the product's minimum grade requirement (`:polarization-below-minimum`)
  - Color (ICUMSA units) exceeds the product's maximum grade window (`:color-exceeds-max`)
  - Conductivity-ash content exceeds the product's maximum purity window (`:ash-content-exceeds-max`)
  - Sulfur-dioxide (SO2) residue exceeds the product's regulatory action level (`:so2-residue-exceeded`)
  - Particle size (granulation) out of the product's grade window (`:granulation-out-of-range`)
  - Foreign material detected on the batch's own inspection — metal/stone/glass/insect fragments (`:foreign-material-detected`)
  - Metal-detector/magnet calibration overdue (`:metal-detector-calibration-overdue`)
  - Finished-product weight variance excessive (`:weight-variance-excessive`)
  - Sulfite labeling mismatch — SO2 residue crosses the jurisdiction's declaration threshold without a `:sulfites` declaration (`:sulfite-label-mismatch`)
  - Plant sanitation/pest-control score insufficient (`:sanitation-score-insufficient`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (SO2 residue, foreign material) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log cane/beet-intake → extraction → clarification → crystallization → inspection batch into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose equipment maintenance for evaporators/crystallizers/centrifuges/metal detectors (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety or contamination concern (e.g. SO2 residue, foreign-material detection); always escalates
- **`:coordinate-shipment`** — Finalize shipment of finished product (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct crystallization/refining-line control, or food-safety certification — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
