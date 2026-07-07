---
tags:
  - status/done
  - type/project
  - area/infra
  - area/architecture
---

# STATUS — T1: Harness skeleton: run-load.sh + the perf identity + bulk fixtures + registry entries

**Status:** ✅ DONE (2026-07-07)

## What shipped

- `scripts/load/run-load.sh` — the runner in the postman-runner idiom: preflight (k6, Docker,
  gateway, user-service health, pod discovery), the **pod-state probe** (`docker exec` env assert on
  `OPA_ABAC_ENABLED` per pass — never trust-the-flag — **plus** `CATALOG_USER_SERVICE_BASE_URL`
  must be the real usermgmt, so a leftover B3 stub rig lands red, **plus** a paused-OPA check on the
  guarded pass), in-network `perf` token minting (the proven `curlimages/curl` pattern, with the
  Keycloak-recreate first-use error), bulk SQL fixture seed (`generate_series`, deterministic
  `dddd…` ids, post-seed count assert, deterministic re-seed), the load-team bootstrap, a **canary
  probe**, teardown-on-green (`KEEP_FIXTURES=1` keeps), mode parsing for all seven modes
  (`ceiling`/`fault-*` stubs land red until T3/T5), knob validation, `results/` plumbing (gitignored).
- `infra/keycloak/realm-export.json` — the **`perf`** realm user (password `perf`,
  `catalog-editor`+`catalog-viewer` like the other demo users). First use needs a Keycloak recreate
  (documented in the runner's mint error and `scripts/load/README.md`).
- `scripts/postman/README.md` — two registry entries: the `dddd…` prefix row and the `perf`
  reserved-account paragraph (no matrix may bind/assert on her — the dora discipline applied to the
  load identity).
- `scripts/load/README.md` — skeleton (usage, prerequisites, knobs, reservations, validity posture).
- `.gitignore` — `scripts/load/results/`.

## Tests

- **U1 (offline)** green: `bash -n`; `--help` lists all seven modes + all seven knobs and exits 0;
  unknown mode / not-yet modes / bad knob (`RATE=abc`) all exit 1; realm export parses as JSON.
- **I1 (live)** green — see Integration below.

## Architecture review + refactor

- **Validity:** every T1 edge lands red — k6/rig/user-service absent (preflight abort with the
  actionable command), pod state ≠ pass (env probe, both directions), leftover stub role-source
  (probe), paused OPA on guarded (probe), stale realm (mint error + recreate command), stale fixture
  population (deterministic re-seed + post-seed count assert). **Review finding → refactor applied:**
  the team/role/membership bootstrap posts are fire-and-forget (the matrix idiom), so a silently
  failed bootstrap would only surface as k6-threshold redness mid-window in T2 — added the **canary
  probe** (one gateway GET of the load catalog as `perf` post-seed, 200 or red) so a broken
  token→resolve→decide chain lands red at seed time, before any load. Also found by U1: `usage()`
  originally grepped the whole file for comments after `cd` (broken path + noisy output) — fixed to
  an awk over the contiguous header block via an absolute self-path.
- **Security:** the one rig-visible addition is the `perf` realm user, deliberately shaped like the
  existing demo users (same realm roles); her only membership is the load team (tag-gated role), the
  runner performs zero rig mutation in T1 (no flag flips, no redeploys), and `/internal/**` stays
  un-routed — the harness reaches bootstrap only via `localhost:28090`, like every matrix runner.
- **Concurrency/idempotency:** seed is deterministic + idempotent (catalog upsert; categories
  DELETE+INSERT; load-team self-reset before bootstrap — a `KEEP_FIXTURES` leftover can't
  accumulate); teardown scoped strictly to the `dddd…` registry ids; the `perf` profile row is kept
  (identity-profile rule).
- **Wiring:** every mode/knob is parsed + validated with red non-happy paths (proven in U1/I1);
  the registry rows' consumers are future matrix authors; the seed/preflight are consumed by every
  scenario ticket (T2–T5).
- **Boundary:** `git diff main -- '*.java' '*.kt' '*.rego' 'opa-abac-*' 'example-*'` is **empty**;
  the surface is `scripts/load/`, the realm export, the registry doc, `.gitignore`. Every existing
  runner and every `deploy.sh` flag default is byte-for-byte unchanged.
- **Pattern reuse:** preflight/mint/seed/teardown mirror `run-team-matrix.sh`; the self-reset +
  psql idiom mirrors `run-isolation-matrix.sh`; `usage()` mirrors `deploy.sh`'s header-as-doc.

## Integration / e2e

**I1 green** (rig: `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`, 2 pods, Docker):
- `baseline` against the guarded rig → **red before any load** at the pod-state probe, with the
  redeploy command (exit 1).
- `guarded` against the pre-`perf` realm → red at mint with the Keycloak-recreate command; after
  the recreate, green.
- `FIXTURE_ROWS=25 KEEP_FIXTURES=1 guarded` → exactly 25 categories (psql-asserted), tags cycling
  from `emea`, canary 200, fixtures kept.
- default `guarded` → 1000 categories re-seeded deterministically over the kept 25, canary 200,
  teardown removed **only** the `dddd…` fixtures (catalog count 6→5; load team gone; the other five
  catalogs and the `perf` profile row untouched).
- Post-recreate housekeeping: `seed-demo-data.sh` re-run (realm subs changed), demo roster restored.

## Decisions

- **The `perf` role is tag-gated (`region=emea`, `ANY_OF`, read/write on catalog+category) and the
  load catalog is tagged `region=emea`.** ADR 0021 §4 pins "tags varied **so the residual
  discriminates**" — an ungated role would reduce the category-list residual to allow-all and the
  T3 ceiling would measure an undiscriminating filter. Gating the role makes the residual a real
  SQL cut (~⅓ of `FIXTURE_ROWS`), while tagging the load catalog itself keeps the T2 single-GET
  green under the 5.97 attribute-rich gate. The canary probe proves this chain live at seed time.
- Category fixture ids are `dddddddd-dddd-dddd-dddd-` + zero-padded row number (deterministic,
  within the reserved prefix); ltree paths follow the app's `catalog_<hex>.category_<hex>` shape.
- `ceiling`/`fault-*` are parsed + listed in `--help` from T1 (U1 pins the full mode surface) but
  exit red with "lands with T3/T5" — honest stubs, no pretend functionality.

## Commit

_(this ticket's commit on feature/void3110/load-testing; see git log)_
