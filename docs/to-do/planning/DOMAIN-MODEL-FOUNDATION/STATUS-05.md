---
tags:
  - status/done
  - type/project
  - area/build
  - area/catalog-service
---

# STATUS — Ticket 5: E2E suite + docs

> Filled in at the ticket-5 checkpoint. See [[01-DECOMPOSITION]] ticket 5.

**Status:** ☑ done

## What shipped
- **E2E suite** (`scripts/postman/`) fleshed out: the Product folder now runs the full lifecycle —
  create → get (field-level assertions) → update → list → **delete → get-after-delete (404)** — over
  `{{base_url}}/api/v1`, then Cleanup deletes the catalog (cascade). The Auth folder gained a
  prerequest `pm.execution.skipRequest()` guard so it cleanly skips when `run-tests.sh` has already
  injected an in-network token (no spurious failed request).
- **Runner:** renamed the report run-id override env var to a neutral `E2E_RUN_ID` (was carrying a
  non-neutral prefix; clean-room).
- **Env template:** removed the chained-id keys (see the gotcha below); added a disabled placeholder
  documenting why.
- **Docs:** reconciled `docs/guides/E2E-TESTING.md` (delete-flow, the variable-scope gotcha, the
  auto-skip Auth behavior); `scripts/postman/README.md` status updated from "skeleton" to "working
  suite". `docs/architecture/DOMAIN-MODEL.md` + `docs/guides/CONCURRENCY-AND-LOCKING.md` already
  matched what shipped (DOMAIN-MODEL was reconciled in ticket 3 with the DateTimeProvider note).
- **Roadmap:** `POC-ROADMAP.md` marks the domain-model slice of Phase 3 **done** and points "next" at
  the OPA-client / `@OpaPreAuthorize` slice.

## Tests
Rig brought up fresh: rebuilt the app image (`deploy.sh build`, so the pods run tickets 1–4) then
`ENABLE_OIDC=1 ./deploy.sh up --pods 2`. Both pods booted healthy — i.e. **Liquibase 0002 ran and
`ddl-auto: validate` passed in the deployed app too** (verified the `version`/`tags`/`created_by`/
`last_modified_at`/`created_at` columns exist in the live `product` table).

`cd scripts/postman && ./run-tests.sh` → **green: 10 requests, 19 assertions, 0 failed, 0 errors**
(ran twice, stable). Token minted **in-network** by the runner (issuer `keycloak:8888`) and injected
as `access_token`, per the documented caveat. `run-tests.sh` is `bash -n`-clean; collection + env JSON
both valid.

## Architecture review + refactor
The built reality matches the guides. The e2e run is the cross-cutting confirmation of the whole
slice end-to-end: a Keycloak-authenticated identity reaches the app through APISIX and CRUD over
Catalog→Category→Product works against the migrated schema. Two real e2e bugs were found and fixed
(below) — the gate doing its job. No code refactor needed.

## Decisions recorded
- **Newman variable-scope gotcha (the headline fix).** The chain broke — every post-create request hit
  `/catalogs//…` (empty id) and 404'd — because the ids were declared (empty) in the *environment* and
  newman resolves `{{var}}` with **environment scope winning over collection scope**, shadowing the
  values the test scripts captured into the *collection* scope. Fix: keep the chain ids in the
  collection scope only; don't declare them in the environment. **Mulch:** recorded as a `failure`.
- **Auth request skip.** `pm.execution.skipRequest()` (newman 6) in a prerequest cleanly skips the
  redundant token call when a token is pre-injected — avoids a spurious "request url is empty" failure.
- **CI deferral:** the rig isn't in CI, so the newman suite stays a local/manual gate; wiring a
  compose-up → newman job into `.github/workflows/ci.yml` is a tracked follow-up (noted in the guide).
- `ml doctor` clean (see commit checkpoint).

## Commit
`feat(domain-model): flesh out e2e suite (green through gateway) + finalize docs` — hash at commit.
