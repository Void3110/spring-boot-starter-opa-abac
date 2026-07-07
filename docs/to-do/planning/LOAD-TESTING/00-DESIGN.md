---
tags:
  - status/planned
  - type/architecture
  - area/infra
  - area/architecture
---

# LOAD-TESTING — design

> The settled design for [[LOAD-TESTING|Phase 7.2]] — the pre-publish performance baseline. Grilled to
> pinned forks 2026-07-07; the rationale lives in **[[0021-load-testing-methodology|ADR 0021]]** (this
> design references it, it is not repeated here). Scope was pinned 2026-07-06: four library hot paths
> + the amplification ratio; demo UI, Keycloak login throughput, and APISIX tuning are OUT.

## 1. The mechanism

A committed harness under `scripts/load/`, every integration point a **named** link:

```
scripts/load/
    run-load.sh              — the runner (the postman-runner idiom): preflight (rig up? k6 present?
                               pod state ASSERTED via docker exec env probe), in-network token mint
                               (the proven curlimages/curl pattern; identity: the `perf` realm user),
                               bulk fixture seed (SQL generate_series into the catalog DB; dddd… ids),
                               scenario orchestration (warm-up run → measured run, per scenario),
                               two-pass guarded/baseline flip (mode: guarded | baseline | full),
                               fault orchestration (stub rig / docker pause opa), summary extraction
                               (k6 --summary-export JSON → the PERFORMANCE.md tables), teardown-on-green
                               (KEEP_FIXTURES=1 keeps).
    scenarios/gate-overhead.js      — single GET catalog/{id}; constant-arrival-rate; runs in BOTH passes
    scenarios/list-filter.js        — GET the category list (PE residual + tag conjunct); the ceiling ramp
    scenarios/enrichment.js         — GET a perPage=100 category page with _actions (the fan-out page)
    scenarios/resilience.js         — single GET under the three-phase fault timeline
    amplification.py                — Jaeger API → ~500 traces/scenario → ATTRIBUTED per-request
                                      outbound counts (which downstream, which operation)
    results/                        — gitignored raw k6 JSON exports
    README.md                       — harness usage (the scripts/postman/README.md idiom)

PERFORMANCE.md (repo root)   — the deliverable: environment + methodology, per-path sections
                               (guarded vs baseline p50/95/99 + delta; the ceiling + which signal
                               broke; fan-out boundedness; per-phase fault numbers), the
                               amplification expected-vs-measured table, one-command rerun.
infra/keycloak/realm-export.json — + the `perf` user (password perf; the load identity)
scripts/postman/README.md        — registry: the dddd… prefix + the `perf` reservation
```

Consumers: `PERFORMANCE.md` is read by adopters (publish artifact, README-linked) and by Phase 7.3
(the tuning baseline); the harness is re-run by the maintainer per release; 7.4's delta security
review checks the harness introduced no weakened path (it introduces **no app/library code at all**).

## 2. Decided forks (see [[0021-load-testing-methodology|ADR 0021]] for full rationale)

1. **k6, host-installed** (`brew install k6`), scenarios as committed JS — no JVM harness module, no
   generator competing for the rig's VM CPU; `scripts/postman/` posture mirrored (§1).
2. **The unguarded baseline is a rig flip, not an endpoint** — `ENABLE_OIDC=1 ENABLE_OPA=0` gives
   `OPA_ABAC_ENABLED=false` pods behind the identical gateway; `run-load.sh full` orchestrates
   guarded → baseline → restore, asserting actual pod state before each pass (§2).
3. **Open model** — `constant-arrival-rate`, default 50 req/s (`RATE=` overridable), identical across
   passes; 60 s discarded warm-up invocation + 120 s measured window per scenario; `REPS=n`
   (official baseline `REPS=3`, medians) (§3).
4. **Fixtures**: 1 load catalog + 1,000 categories (`FIXTURE_ROWS=`), tags varied for a
   discriminating residual; bulk SQL seed; **`dddd…` registry prefix**; teardown-on-green;
   dedicated **`perf`** realm user + load team + read/write role membership (§4).
5. **The ceiling is operationally defined**: `ramping-arrival-rate` 60 s stages
   `10→25→50→100→150→200`; knee = first stage sustaining p99 > 1 s OR >1 % fails/dropped
   iterations; ceiling = last stage passing both, reported with the breaking signal (§5).
6. **Amplification via the Jaeger API** (`amplification.py`, ~500 traces/scenario), attributed per
   downstream + operation; `PERFORMANCE.md` states the expected bound per scenario (single GET →
   1 resolve + 1 decide; list → 1 resolve + 1 compile; enriched page → 1 batch eval, not N×rows)
   beside the measured value (§6).
7. **Faults**: supplier edge via the existing B3 stub (`transient`/`down`); OPA edge via
   `docker pause`/`unpause`; both on the fixed three-phase timeline (60 s healthy / 60 s fault /
   60 s recovery), analyzed per phase (§7).
8. **Report-only + validity gates; root `PERFORMANCE.md`; no CI perf runs** (§8).

## 3. Validity posture (the harness's fail-closed analogue)

**No invalid number is ever recorded.** Every failure mode lands on a red run, never a silently wrong
table row:

| Failure mode | Where it lands |
|---|---|
| Rig down / wrong flags / k6 absent | preflight abort with the actionable command |
| Pod state ≠ the pass's expectation (guarded/baseline) | `docker exec` env probe abort — never trust-the-flag |
| Errors during a measurement window | k6 validity threshold → runner exits red, nothing recorded |
| Fault-phase anomalies (slow or untyped denials, failed recovery) | per-phase validity checks → red run |
| Jaeger returns too few traces to attribute | `amplification.py` aborts (min-sample guard), never extrapolates |
| A stale fixture population (wrong row count) | seed asserts the count post-seed |

The load harness changes **zero** app/library/rego code — the one rig-visible addition is the `perf`
realm user; the baseline flip reuses an existing deploy mechanism and the runner restores the guarded
rig after a `full` run.

## 4. Considered & rejected

See [[0021-load-testing-methodology|ADR 0021]] "Considered & rejected" — Gatling (JVM harness module
pre-publish), containerized k6 (generator/rig CPU interference), a dedicated unguarded endpoint
(ships a weakened path), closed-model load (coordinated omission), counter-diff amplification
(unattributable), toxiproxy (new infra for one scenario), CI perf gates (laptop flake).

## 5. Scope boundary (NOT in this slice)

- **No tuning** — 7.3 applies what these numbers surface; this slice only measures.
- **No demo-UI / Keycloak-login / APISIX throughput** work (pinned out 2026-07-06).
- **No Prometheus/Grafana provisioning** — k6 percentiles + Jaeger traces cover the pinned scope;
  an observability stack is a separate decision if 7.3 needs it.
- **No app/library code change of any kind** — a harness-only slice.

## Related

- [[LOAD-TESTING]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0021-load-testing-methodology|ADR 0021]] (the decisions) · [[0017-cross-service-http-resilience|ADR 0017]] (the fault injector) · [[0005-partial-eval-to-jpa-specification|ADR 0005]] (the PE path) · [[0016-action-enrichment-affordance-metadata|ADR 0016]] (the fan-out)
- [[POC-ROADMAP]] (Phase 7.2)
