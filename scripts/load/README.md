# Load-testing harness (k6) — Phase 7.2

The committed, one-command, re-runnable performance harness behind the root `PERFORMANCE.md`:
k6 scenarios driving the library's hot paths through the **real rig** (APISIX → catalog pods →
OPA → user-service), in the same idiom as the `scripts/postman/` e2e runners. Methodology is
pinned in [ADR 0021](../../docs/architecture/adr/0021-load-testing-methodology.md) — report-only
numbers, validity-only gates: **no invalid number is ever recorded**.

## Prerequisites

```bash
brew install k6                                        # the one host dependency
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2   # the guarded rig
```

**First use:** the `perf` realm user ships in `infra/keycloak/realm-export.json`; a realm-export
change only imports on container **create**, so recreate Keycloak once:

```bash
docker compose -p opa-abac-example -f infra/compose.keycloak.yaml up -d --force-recreate keycloak
```

## Usage

```bash
cd scripts/load
./run-load.sh guarded          # preflight + seed + the guarded pass
./run-load.sh baseline         # the unguarded pass (rig must be ENABLE_OPA=0 — asserted, not trusted)
./run-load.sh full             # guarded -> redeploy baseline -> measure -> RESTORE guarded (the headline delta)
./run-load.sh ceiling          # the partial-eval list ladder (knee detection, early stop)
./run-load.sh multi-root       # the multi-root catalogs-list scenario (7.3): M own-root rows/page

./run-load.sh fault-opa        # three-phase fault pass (docker pause on OPA)
./run-load.sh fault-supplier-transient   # three-phase fault pass (B3 stub, transient)
./run-load.sh fault-supplier-down        # three-phase fault pass (B3 stub, down)
```

## Knobs (env)

| Knob | Default | Meaning |
|------|---------|---------|
| `RATE` | `50` | Arrival rate (req/s), `constant-arrival-rate` — identical across passes |
| `DURATION` | `120` | Measured window per scenario (s) |
| `WARMUP` | `60` | Discarded warm-up invocation length (s) |
| `REPS` | `1` | Measured-run repetitions (official baseline: `REPS=3`, medians) |
| `FIXTURE_ROWS` | `1000` | Seeded category count under the load catalog |
| `LADDER` | `10,25,50,100,150,200` | Ceiling-mode stages (req/s) |
| `LADDER_DURATION` | `60` | Ceiling-mode per-stage window (s); ADR-pinned 60 for the official run |
| `PHASE` | `60` | Fault-mode phase length (s); ADR-pinned 60 — shorter for smokes |
| `MULTI_ROOT_CATALOGS` | `50` | Multi-root mode: the seeded catalog/team count M (1–100 — one page) |
| `MULTI_ROOT_RATE` | `5` | Multi-root mode's arrival rate (req/s) — the mode ignores `RATE`: pre-7.3 the page costs M sequential resolves, so it must run below the knee |
| `KEEP_FIXTURES` | `0` | `1` = skip the teardown-on-green (keep the `dddd…` fixtures) |

## Fixtures + identity (registry-reserved)

Both live in the fixture registry (`scripts/postman/README.md`) — the cross-matrix law:

- **`dddd…`** — the load fixture set: catalog `dddddddd-dddd-dddd-dddd-dddddddddddd` (tagged
  `region=emea`) + `FIXTURE_ROWS` bulk-seeded categories, tags cycling `emea/apac/amer` so the
  partial-eval residual **discriminates**. Deterministic ids, post-seed count asserted,
  teardown-on-green.
- **`dddd…-dd0…`** (multi-root mode, 7.3) — `MULTI_ROOT_CATALOGS` catalogs
  (`dddddddd-dddd-dddd-dddd-dd0000000001` …), **each with its own team and a `perf` membership**
  (an un-gated catalog-READ role) so every page row is its own governing root and actually
  resolves a role. The list cut is membership-scoped: perf's authorized `count` is exactly M —
  seed-time canary + count asserts enforce it (the mode also self-resets the single load-catalog
  team, which would otherwise add a foreign row to the page). Same determinism/teardown rules.
- **`perf`** (password `perf`) — the reserved load identity: one membership on the load team, a
  tag-gated (`region=emea`, `ANY_OF`) read/write role — plus, in multi-root mode, the M
  multi-root team memberships. No matrix may bind or assert on her.

## Validity posture

Every failure mode lands on a **red run** (see `00-DESIGN.md` §3): preflight aborts (rig down,
k6 absent), the **pod-state probe** (`docker exec` env assert — never trust-the-flag, incl. a
leftover stub role-source or a paused OPA), the post-seed count assert, k6 validity thresholds
(T2+), per-phase fault checks (T5), and the `MIN_TRACES` guard (T4).

Raw artifacts land in `results/<run>/` (gitignored): k6 summary exports per rep, the
gate-overhead delta, per-stage ladder summaries + `ceiling.json`, per-scenario amplification
tables (JSON + markdown), and per-mode fault streams + phase tables.

## Official-run protocol

The published baseline lives in the root [`PERFORMANCE.md`](../../PERFORMANCE.md). To reproduce it:

1. **Quiesce the machine** (no builds, no browser stress — the generator and the rig share it).
2. **Fresh images, fresh rig, fresh trace store:** `./deploy.sh build` (+ the usermgmt image),
   `docker compose -p opa-abac-example -f infra/compose.jaeger.yaml down -v`, then
   `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`. The validity gates REFUSE a
   degraded rig (a prior saturation event leaves thrashed JVMs / open breakers / a bloated trace
   store) rather than record it — redeploy instead of retrying.
3. `REPS=3 ./run-load.sh full`, then `ceiling`, then the three `fault-*` modes.
4. Scenarios that exceed a measured ceiling get their steady numbers below the knee, explicitly
   labeled (the official baseline used 5 req/s for list/enrichment — see PERFORMANCE.md §2/§3).

## Offline tests

`tests/test-offline.sh` runs the no-rig checks (script syntax; the analysis functions against the
committed synthetic fixtures — `knee.py` over `tests/knee-cases/`). In ceiling-ladder stages,
saturation signals (slow p99, errors, dropped iterations) are recorded **data** for the knee
function; the one validity gate kept is `auth_failures == 0` — a 401/403 means a broken rig/ACL
chain and lands red, never an instant fake knee.
