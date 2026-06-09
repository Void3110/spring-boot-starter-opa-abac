---
tags:
  - status/done
  - type/project
  - area/api
  - area/spring
---

# STATUS T4 — e2e: EXTEND the existing newman matrices (`problem+json` + `errorCode` on negatives; `Location` on 201s)

> ✅ **Shipped.** One focused commit on `feature/void3110/rest-api-refinement`. The new error body is
> proven **through the gateway** by extending three existing matrices — no new collection.

## What shipped

- **Three existing collections extended** with RFC-7807 contract assertions (appended to the existing
  test scripts, original status assertions preserved):
  - `catalog-abac-matrix` — the three live **403** (viewer-write-denied) requests assert
    `Content-Type: application/problem+json` + `errorCode == ACCESS_DENIED` + status 403 + canonical
    members (`type`/`title`) + **no `message`**; the three **201** creates assert a `Location` header.
  - `tag-abac-matrix` — the live **422** (illegal tag value) asserts `TAG_VALUE_ILLEGAL`; the two live
    **403** (tag-mismatch read, viewer-cannot-define) assert `ACCESS_DENIED`; the **201** define-tag
    asserts `Location`.
  - `team-abac-matrix` — the three live **403** (viewer-member/non-member/dogfood denies) assert
    `ACCESS_DENIED` + `problem+json`.
- **`scripts/postman/augment_problem_json.py`** — a small **idempotent** helper that appends these
  assertions to the targeted requests (matched by asserted status), so the extension is reproducible and
  re-runnable (it guards on a `// [phase-5.9 contract]` marker). No new collection file.

## Tests (through the live rig / gateway)

Full two-service rig up (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; both app images rebuilt with the new code).
All matrices **green** through APISIX:

| Matrix | Requests | Assertions | Failed | New contract assertions |
|---|---|---|---|---|
| `catalog-abac` (augmented) | 12 | 19 | 0 | 403 `ACCESS_DENIED` ×3 · 201 `Location` ×3 |
| `tag-abac` (augmented) | 7 | 12 | 0 | 422 `TAG_VALUE_ILLEGAL` · 403 `ACCESS_DENIED` ×2 · 201 `Location` |
| `team-abac` (augmented) | 8 | 11 | 0 | 403 `ACCESS_DENIED` ×3 |
| `catalog-e2e` (regression) | 10 | 19 | 0 | — happy path unchanged |
| `filter` / `hierarchy` / `hierarchy-list` (regression) | — | 16 / 4 / 20 | 0 | — unchanged against the new images |

This proves the **gateway round-trip**: a live `@OpaPreAuthorize` deny renders `application/problem+json`
with `ACCESS_DENIED` through APISIX (the catalog 403 case the MockMvc IT could not drive on its permissive
chain); the catalog 422 keeps `TAG_VALUE_ILLEGAL`; every live 201 carries `Location`. E1–E5 satisfied.

## Architecture review + refactor (the ★ gate)

- **Test-asset-only:** newman JSON + one Python helper; **no compiled-code change**, no new collection.
- **No fail-open:** every augmented case still asserts the **same status** (403/422) the matrix asserted
  before — the new assertions only add body-shape checks on top; a deny stays a deny, now a problem body.
- **Refactored:** nothing — the assertions are additive. (The augment script is kept as a reproducible
  artifact, not throwaway.)

## Integration / e2e

The matrices above ARE the e2e. The reruns are stable.

## Decisions

- **Negative-case host matrices chosen by which already drove the status live:** 403 across
  catalog/tag/team; 422 in tag (catalog illegal-tag) — no requests were added, only assertions.
- **Augmentation via an idempotent script** rather than hand-editing each test block — reproducible and
  guarded by a marker so a re-run is a no-op.

## Rig note (environment, not code — a real gotcha)

`./deploy.sh build` rebuilds **only the catalog image** — `build_usermgmt_image` is gated behind
`usermgmt_image_exists ||` in the `up` path, so a *changed* user-service is **not** rebuilt by
`deploy.sh build` (or by `up` when a stale image already exists). The first tag/team runs failed on the
two user-svc endpoints (no `Location`, `application/json` not `problem+json`) — the **stale 6-day usermgmt
image**. Fix: force `docker build -t opa-abac-usermgmt:local -f example-user-management-service/Dockerfile .`
then recreate the usermgmt container. Recorded to Mulch (`opa-abac`). No code change.

## Commit

`test(e2e): assert RFC-7807 problem+json + errorCode + Location across the abac matrices` on
`feature/void3110/rest-api-refinement`.
