---
tags:
  - status/implemented
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T1: Multi-root list scenario + fixtures + pre-change baseline

**Status:** ✅ DONE — the scenario/mode/fixtures shipped and the **pre-change baseline is recorded**
(the P3 "before": **resolve = 51 wire calls per 50-row page, constant across 290 attributed
traces**).

## What shipped

- `scripts/load/scenarios/multi-root-list.js` — steady-posture catalogs-list scenario as `perf`
  (`GET /api/v1/catalogs?perPage=M`): strict validity trio + `auth_failures==0` + `wrong_count==0`
  + two page-purity checks (items.length == M; **every row carries `_actions`** — an omitted map is
  the advice degrade rung firing, i.e. a silently smaller resolve fan-out = wrong measurement
  subject).
- `run-load.sh` gained the **`multi-root` mode** (preflight → guarded pod-state + gateway asserts →
  seed → warm-up + REPS measured windows → Jaeger attribution → teardown-on-green) and two knobs:
  `MULTI_ROOT_CATALOGS` (M, default 50, validated 1..100 = one page) and `MULTI_ROOT_RATE`
  (default 5 — the mode ignores `RATE`; pre-7.3 the page costs M sequential resolves so it must
  run below the knee).
- **Fixtures:** M catalogs bulk-SQL-seeded in the reserved sub-range
  `dddddddd-dddd-dddd-dddd-dd0000000001…` (`'dd0'` is the range key — it can never match the load
  catalog `…-dddddddddddd` or its `…-000000000001…` categories), each with its own team + a `perf`
  membership (un-gated catalog-READ role `load`) via the user-mgmt **bootstrap API** (the matrix
  idiom). Deterministic wipe-first re-seed; **three count asserts** (catalogs / teams /
  memberships — table `team_membership`); **two canary probes** before any load (single-GET 200;
  list `count == M` exactly).
- `amplification.py`: `multi-root-list` pinned at the ADR 0024 target (`resolve: 1, compile: 1`) —
  the baseline run records the honest `EXCEEDED` finding as the "before" artifact (the 7.2 idiom).
- `tests/test-offline.sh`: new mode/knobs on the `--help` surface + a synthetic
  `multi-root-baseline.json` attribution case (M-per-page → EXCEEDED; compile within).
- Registry: `scripts/postman/README.md` gained the `dddd…-dd0…` sub-range row and the updated
  `perf` reservation language; `scripts/load/README.md` documents the mode, knobs, and fixtures.

## Tests

- `scripts/load/tests/test-offline.sh` — **GREEN** (script syntax, knee/phases fixtures, the two
  new multi-root amplification cases, the extended `--help` surface).
- Live smoke (`WARMUP=5 DURATION=10 REPS=1`): all validity gates green, 38 traces attributed.

## Architecture review + refactor

Review path: fail-closed / wiring / boundary / pattern-reuse self-review before the live runs.
Findings (both fixed before the baseline):

1. **`team_membership` table name** — the membership count-assert was first written against a
   `membership` table that doesn't exist; verified against the user-mgmt Liquibase changelog and
   fixed. (A wrong assert would have been a *silent* seed-validation hole — it would red on every
   run, but red-for-the-wrong-reason.)
2. **Page purity requires the load-team self-reset** — a leftover `Load test team` (from a
   `KEEP_FIXTURES=1` or mid-run-red guarded run) binds `perf` to the load catalog and puts an
   extra foreign row on the multi-root page. The seed now wipes the load team along with the
   `dd0`-range teams, and the list canary asserts `count == M` exactly.
3. **Floor-vs-fetch-cap arithmetic** — `amplification.py`'s chunked fetch caps at ~300 traces
   (30 slices × limit 10) while the floor formula can demand up to 500; verified against the 7.2
   artifacts (official steady runs attributed 49–95 traces) and pinned the official baseline knobs
   to `DURATION=30 REPS=3` (floor 225 ≤ 300). No code change — a protocol note.

Everything else: pure pass-through additions (no existing scenario/knob changed;
`EXPECTED_COUNT` default preserved via the `K6_EXPECTED_COUNT` override), no library/app code, no
new rig-mutation path (no new restore edge needed — `heal_rig_on_exit` covers the mode).

## Integration / e2e

**The official pre-change baseline** (fresh Badger trace store, canonical guarded rig
`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`, 2 pods; images verified byte-identical to this branch's app
source — `git diff 3eb0b35..HEAD -- '*/src/*'` empty, so no rebuild):

- Run: `DURATION=30 REPS=3 ./run-load.sh multi-root` →
  `scripts/load/results/20260710-145528-multi-root/` (**kept — the P3-before artifact**).
- **Amplification (290 traces, median == max on every op):** resolve **51** (= M rows + the gate)
  vs pinned 1 — **EXCEEDED, the honest "before"**; compile 1 within; governed-scope 1;
  **batch-eval 1** (note: the catalogs list runs ONE batch eval — the 2-batch-eval shape belongs
  to the *categories* list (finisher + enrichment); T6's re-pin must stay per-scenario honest).
- Steady latency at 5 req/s (medians of 3 reps): p50 **136.7 ms** · p95 179.9 ms · p99 226.2 ms.
- Validity: all gates green (zero errors/drops/auth-failures/wrong-counts; every row `_actions`).

## Decisions

- **Seed mechanism:** catalogs via bulk SQL (the `seed_fixtures` idiom); teams/roles/memberships
  via the user-mgmt bootstrap API (`ensure*` endpoints), NOT bulk SQL — the API owns the
  role-definition shape and the FK graph.
- **Un-gated READ role** for the multi-root teams: the catalogs-list cut discriminates by
  *membership* (B4), not tags; a READ role keeps every row's `_actions` non-all-false (so the map
  is present) while the ADR 0022 root-read exemption makes tag-gating irrelevant for root READs
  anyway.
- **Pin-the-target, record-the-EXCEEDED** for the baseline (the exact idiom 7.2 used for the
  2/22/102 findings) rather than pinning M and re-pinning at T6.

## Commit

`perf(load): multi-root catalogs-list scenario + fixtures + pre-change baseline (T1)` — see git.
