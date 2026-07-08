---
tags:
  - status/planned
  - type/index
  - area/infra
  - area/architecture
---

# LOAD-TESTING — the pre-publish performance baseline (Phase 7.2)

> **Status: Planning.** A committed, one-command, re-runnable **k6 harness** (`scripts/load/`) that
> measures the library's four hot paths through the real rig and writes the publish-facing
> **`PERFORMANCE.md`**: the authorization-gate overhead delta (p50/95/99 vs an unguarded baseline —
> the headline number), the partial-eval list-filtering ceiling, the enrichment fan-out boundedness,
> resilience under fault, and the cross-service amplification ratio. Report-only numbers with
> validity-only gates; every methodology fork is pinned in [[0021-load-testing-methodology|ADR 0021]].
> Phase 7.2 of [[POC-ROADMAP]].

## Why this slice exists

**The gap.** The library is approaching publish and has zero performance evidence. An adopter's first
question about an authorization layer that fronts every request is *what does it cost* — and "run it
yourself" is not an answer without a harness.

**The mechanism.** A `scripts/load/` harness in the same idiom as the e2e runners: `run-load.sh`
(preflight, in-network token minting, bulk fixture seed, two-pass guarded/baseline orchestration,
fault injection, summary extraction) + one k6 scenario per hot path + `amplification.py` (Jaeger-
attributed per-request outbound counts).

**The headline.** One command produces `PERFORMANCE.md`: *what the gate costs per request, where the
filtered list saturates, that enrichment and cross-service chatter are bounded by design, and that a
dependency outage degrades fast and closed* — the numbers the publish story needs and the 7.3 tuning
baseline.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the pinned forks (from ADR 0021), the validity posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | Harness skeleton: `run-load.sh` + `perf` realm user + bulk fixtures + registry entries | ✅ DONE |
| T2 | Gate-overhead scenario + the two-pass guarded/baseline orchestration (the headline) | ✅ DONE |
| T3 | Partial-eval list scenario + the ceiling ramp (knee detection) | ✅ DONE |
| T4 | Enrichment fan-out scenario + `amplification.py` (Jaeger-attributed ratio) | 📋 TODO |
| T5 | Resilience-under-fault passes (B3 stub modes + OPA `docker pause`) | 📋 TODO |
| T6 | Official `REPS=3` baseline → `PERFORMANCE.md` + README link + `scripts/load/README.md` + folder move | 📋 TODO |

## Related

- [[POC-ROADMAP]] — Phase 7.2 (pre-publish gauntlet: 7.1 manual test → **7.2 this** → 7.3 tweaks → 7.4 delta review → publish).
- [[0021-load-testing-methodology|ADR 0021]] — the pinned methodology forks (grill-me 2026-07-07).
- [[0017-cross-service-http-resilience|ADR 0017]] — the B3 edges + fault injector T5 reuses.
- [[0005-partial-eval-to-jpa-specification|ADR 0005]] — the PE path under ceiling test.
- [[0016-action-enrichment-affordance-metadata|ADR 0016]] — the batch-eval fan-out under proof.
