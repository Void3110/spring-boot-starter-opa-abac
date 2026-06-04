---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
---

# STATUS — T7: e2e list-filtering matrix + docs + roadmap/Mulch (e2e + docs)

> Filled in at the T7 checkpoint during the autonomous run. See [[01-DECOMPOSITION]] T7 and the
> per-ticket loop in [[AUTONOMOUS-IMPLEMENTATION-PROMPT]].

**Status:** ✅ done

## What shipped

- **`scripts/postman/run-filter-matrix.sh` + `data-filter-matrix.postman_collection.json`** — the
  data-filtering e2e through the gateway. Mints in-network tokens for owner(editor) /
  reader-emea(viewer) / reader-apac(demo) / stranger(`outsider`, the rig's standing unbound user); seeds a
  demo catalog as the team-target; bootstraps a team + two single-region-gated reader roles
  (`emea-reader`, `apac-reader`) and binds owner+two readers (the stranger is deliberately **unbound**);
  creates three region-tagged Categories; runs newman. Four list assertions:
  reader-emea → only the emea row; reader-apac → only the apac row (a **different** set); owner → all three;
  stranger → `[]` (no role definition → the `filter` rule fails closed). `local.postman_environment.example.json`
  + `scripts/postman/README.md` updated.
- **Docs:** `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (the mechanism + adoption recipe + the
  fail-closed/allowlist edge); `docs/guides/E2E-TESTING.md` gains the filter-matrix section + run command +
  Related link; the roadmap marks Phase 5 **DONE** (narrative + phase table).
- **Roadmap move:** the `DATA-FILTERING/` planning folder is marked done (the seven STATUS notes record the
  prompt + outcomes — the workflow-as-artifact record); a follow-up move to `docs/to-do/implemented/` keeps
  the prompt-and-results intact alongside the prior slices.
- **Mulch:** recorded the compile-AST→DNF parser (mx-a932a0), the residual→JSONB `Specification` translation
  (mx — T3), the `AbacQueryService` seam + fail-closed allowlist (mx — T4), the partial-eval-friendly
  `filter` rule + `member_2`-side→CONTAINS rule (mx — T6), and the e2e filter-matrix pattern (mx — T7).

## Tests

- **`bash -n run-filter-matrix.sh`** clean; the collection is **valid JSON** and parses in newman.
- **The live gateway matrix — GREEN: 16/16 assertions, 0 failed, stable across reruns.** Brought the full
  rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`) after a forced `./deploy.sh build`
  to pick up the new filtering code; confirmed OPA serving the `filter` rule via `POST /v1/compile`. The
  four list requests through the gateway: reader-emea → **only the emea row** (the cut is in SQL); reader-
  apac → **only the apac row** (a *different* set, same endpoint); curator → **all three rows**; stranger
  (no role definition) → **`[]`** (the fail-closed boundary). This is the decisive end-to-end proof of
  partial-eval list filtering.
- Cross-cutting (already green from T1–T6): `./gradlew build`; `opa test` 60/60; the real-Postgres
  Testcontainers ITs (two subjects → different row sets); `ddl-auto: validate` clean.

## Architecture review + refactor

- **Doc-naming fix (the review caught a basename collision):** the guide and the planning index were both
  `DATA-FILTERING.md` — Obsidian resolves wikilinks by basename, so the two collided. Renamed the **guide**
  to `PARTIAL-EVALUATION-FILTERING.md` (the TAG-DICTIONARY-plan-vs-TAG-BASED-AUTHORIZATION-guide precedent)
  and repointed the guide references; the remaining `[[DATA-FILTERING]]` links all correctly mean the
  planning index.
- **Matrix design — fail-closed coverage:** the matrix asserts not just the happy path (different row sets)
  but the **stranger → empty** case, so the e2e exercises the fail-open-leak guard end to end.
- No code refactor — T7 is e2e + docs.

## Integration / e2e

The gateway filter matrix is the integration proof; brought the full rig up and ran it (see Tests).

## Decisions recorded

Two findings the **live run** surfaced (that the unit/IT tests, using synthetic role-defs, could not):

1. **The allow-all subject needs a `category`-granting role.** The system `owner` role resolves to
   `{permissions: {catalog: [read, write]}}` — it has **no `category` key**. The `filter` rule checks
   `permissions[category]`, so under the system owner the residual is `DENY_ALL` → empty list. The demo's
   allow-all subject therefore uses a custom **`curator`** role (ungated `category` read+write). This is a
   genuine semantic of the role model (catalog-verbs ≠ category-verbs), correctly enforced — not a filter
   bug. The first live run caught it.
2. **Deterministic seeding.** The `create-category` endpoint isn't idempotent, so the matrix now `DELETE`s
   the demo catalog's Categories before seeding — otherwise reruns accumulate rows and the "exactly N"
   assertions drift. With the clean seed the matrix is stable across reruns (verified 3×).

Other:
- The stranger uses the rig's existing **`outsider`** unbound user (not a new realm user) — consistent with
  `run-team-matrix.sh`'s non-member convention.
- **Build/orchestration gotchas recorded (Mulch):** `deploy.sh` only rebuilds the app image when it's absent
  (force with `./deploy.sh build` after a code change); `./profile.sh up` precedes `./deploy.sh up`; a
  `./deploy.sh down` removes the compose network (so re-`up` from a clean base, restarting Postgres if
  needed); OPA must be confirmed serving the new `filter` rule after a policy edit.

## Commit

`feat(data-filtering): T7 e2e filter matrix + docs + roadmap (Phase 5 shipped)` — _(SHA at commit)_
