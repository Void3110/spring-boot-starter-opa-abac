---
tags:
  - status/done
  - type/project
  - area/architecture
  - area/spring
---

# STATUS — T7: Rig rebuild + e2e fleet + PERFORMANCE.md re-baseline + docs sweep

**Status:** ✅ DONE (perf re-baseline PARTIAL by validity — see below; the fleet is fully green)

## What shipped

- **Both example images rebuilt from the ported tree** (Dockerfiles → Temurin 25 build+runtime;
  catalog image also OTEL agent 2.11.0 → **2.29.0** — 2.11 emits **zero spans on Framework 7**,
  silently breaking trace attribution). Freshness verified per invariant 7 — and the invariant
  earned its keep twice: `./deploy.sh build` rebuilds ONLY the catalog image (usermgmt builds
  lazily on `up` *if missing*, silently reusing a stale image — built explicitly), and the
  created-at check caught it.
- **The full newman fleet: 14/14 runners green, ZERO collection edits (E1)**, each on its required
  posture: run-tests + run-matrix (BASIC `ENABLE_OIDC=1`), ten matrices on the directory rig
  (`ENABLE_DIRECTORY=1`), resilience on the B3 stub rig, spa-smoke on `ENABLE_SPA=1`.
- **One environmental failure, diagnosed to the row, no code/collection change:** the isolation
  matrix's E1/E2c assume a *fresh* realm alice; the maintainer's 2026-07-11 demo session had
  granted alice `alice-role` on the Demo team (subject = her Keycloak UUID — membership queries by
  username miss it). Removed that single membership row (demo catalog/team/roles/dictionary all
  intact; re-grant via the SPA picker if wanted) → 20/20 green. **Maintainer note: alice's demo
  role binding was removed.**
- **PERFORMANCE.md: partial re-baseline, ADR-0021-honest.** Valid + recorded: every pinned
  per-request call bound HOLDS on Boot 4 (resolve 1 / decide 1 / batch-eval 2 / compile 1;
  multi-root resolve 2) with 281–284 attributed traces per scenario; steady guarded latency
  consistent with 7.3; ladder stages 10+25 rps clean twice (p99 177/217 ms — the old knee was AT
  10); all three fault timelines green (typed denials, fail-closed walls intact). NOT validly
  re-measured (7.3 rows stay official): the two-pass gate delta (one run computed +1.49 ms/+34%
  p50 but crossed validity thresholds elsewhere — not recorded) and the list ceiling. Causes were
  environmental (host memory starvation during the overnight window — contradictory stage results
  within the hour; a Docker-VM freeze mimicking OPA death) — re-run `full` + `ceiling` on a quiet
  host.
- **Two genuine findings the faster stack exposed** (recorded in the ledger):
  1. The ceiling ladder outlives one ~5-min access token now that no early knee stops it —
     **run-load.sh re-mints the perf token per stage** (the one surgical harness fix; commented).
  2. Past ~25 rps the OPA container collapses and the app answers **fail-closed empty pages**
     (200, fast, count=0) — invisible to the knee rule (p99/failed%); the `wrong_count` gate REDs
     the run instead of declaring a knee. Extending the knee definition is an ADR 0021 methodology
     question, deferred (noted in the ledger).
- **Docs sweep:** root `CLAUDE.md` build line (Java 25 · Boot 4.0 · Gradle 9.x + the no-JAVA_HOME
  note), `README.md` requirements, `HTTP-RESILIENCE.md` forward note (single-line per ADR 0026;
  R4j stays — SF7 has no breaker), **ADR 0026 implementation addendum** (zero default-flip
  restores needed; Hibernate 7.2's Jackson-2-only FormatMapper; resolved pins), ADR 0017 addendum
  landed in T3 confirmed.
- `pre-sb4-port` tag: **maintainer action at merge time** (not performed by the run).

## Tests

- E1: 14/14 runners green, zero collection edits. E2: image freshness verified (created-at vs
  build time, both images).
- P1: partial per validity — the recorded numbers passed every gate; the unrecorded modes are
  explicitly listed with reasons and re-run commands. Double-attribution fine print included.
- `opa test` 228/228 — byte-untouched through the entire slice.

## Architecture review + refactor

The live fleet is the review: every allow/deny cell, isolation cell, resilience contrast,
enrichment affordance, and pagination envelope answers byte-identically through the real gateway
on the ported images. The one behavior-shaped anomaly (isolation E1) was traced to a data row, not
code. The harness token fix is validity-preserving (auth is never the measured quantity);
the OPA-cliff finding is recorded, not papered over. No app code changed in T7.

## Integration / e2e

This ticket IS the integration proof. Rig restored to the guarded posture, healthy (10 containers
up, gateway validating).

## Decisions

- Perf recorded partially rather than looping for a lucky window at 4 a.m. — ADR 0021's "no
  invalid number is ever recorded" cuts both ways; the ledger says exactly what is and isn't
  re-measured and how to finish.
- The alice demo membership removal (above) — smallest possible intervention, prominently flagged.

## Commit

`test(port): rig on ported images — fleet 14/14 green, partial perf re-baseline, docs sweep (T7)`
