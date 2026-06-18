# Catalog e2e suite (Postman / Newman)

End-to-end API tests that drive **Catalog → Category → Product** CRUD through the gateway
(APISIX + Keycloak + OPA). Full guide: [`docs/guides/E2E-TESTING.md`](../../docs/guides/E2E-TESTING.md).

## Quick start

```bash
# 1. Bring the full rig up (Postgres + APISIX + Keycloak + catalog pods)
ENABLE_OIDC=1 ./deploy.sh up --pods 2

# 2. First time: copy the env template
cd scripts/postman
cp local.postman_environment.example.json local.postman_environment.json

# 3. Run
./run-tests.sh                  # full suite
./run-tests.sh --folder Product # one folder
./run-tests.sh --verbose
```

Requires `newman` (`npm install -g newman` or `brew install newman`) and docker/podman (to mint the
token in-network).

## Files

| File | Role |
|------|------|
| `run-tests.sh` | Runner: mints an in-network Keycloak token, injects it, runs the lifecycle collection. |
| `catalog-e2e.postman_collection.json` | The lifecycle collection: Auth → Catalog → Category → Product → Cleanup. |
| `run-matrix.sh` + `catalog-abac-matrix.postman_collection.json` | The **role-based** allow/deny matrix (viewer reads / can't write; editor writes) — Phase 3. |
| `run-team-matrix.sh` + `team-abac-matrix.postman_collection.json` | The **team-based** allow/deny matrix (Phase 4): roles resolved from real team membership in the user-service. Mints four tokens, bootstraps the team data via the user-service internal API, then asserts owner-writes / viewer-denied / custom-editor-writes / non-member-denied through the gateway + the dogfood management path. |
| `run-tag-matrix.sh` + `tag-abac-matrix.postman_collection.json` | The **tag-based** allow/deny matrix (Phase 4.5): grants driven by the *resource's tags* matched against a role's `requiredTags`, in Rego. Seeds two tag-gated roles + three differently-tagged Categories, then proves the decisive contrast — the SAME member reads a matching-tag Category (200) and a non-matching one (403) — plus ANY_OF/ALL_OF, the dictionary define dogfood (owner 201 / member 403), and an illegal assignment (422). Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`. |
| `run-filter-matrix.sh` + `data-filter-matrix.postman_collection.json` | The **data-filtering** matrix (Phase 5): OPA partial-evaluation **list** filtering. Seeds two single-region-gated readers (emea / apac) + an allow-all owner + an unbound stranger, and three region-tagged Categories, then hits the SAME list endpoint (`GET /catalogs/{id}/categories`) for each: reader-emea sees only the emea row, reader-apac only the apac row (a **different** set), owner all three, and the stranger (no role definition) **none** — proving the residual is pushed into SQL and the `filter` rule fails *closed* on a missing role. Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`. |
| `run-hierarchy-matrix.sh` + `hierarchy-abac-matrix.postman_collection.json` | The **hierarchical** allow/deny matrix (Phase 5.5-A): N-level ancestor inheritance. Grants the reader `read` on the **Catalog** and proves a Category nested under it is readable (inheritance, 200); an explicit leaf deny (`abac_deny` tag) carves one Category out (403) while a **sibling** stays readable (200, deny-overrides); then **re-parents** the movable Category under a foreign Catalog the reader can't see (rewriting the ltree subtree + its products) and asserts the read **flips to 403**. The role is resolved once on the governing root. Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1` (hierarchy is on by default). |
| `run-hierarchy-list-matrix.sh` + `hierarchy-list-matrix.postman_collection.json` | The **hierarchy-aware list** matrix (Slice 5.5-B): an ancestor (Catalog) grant **widens a category list** to the whole catalog subtree. Seeds an **inherit reader** (read on the **catalog only** — no category tag grant), a **region reader** (`category:read` gated to `region=emea`), and an unbound stranger; three Categories (emea / apac / one `abac_deny`). Proves: the inherit reader sees the whole subtree (emea **+** apac — rows its own tags wouldn't surface) minus the denied row; the region reader sees **only** emea (a different set, same endpoint); the stranger gets `[]`/403; and an ltree **re-parent** makes the apac Category **leave** catalog C's widened list. The inherit reader passes the coarse type-level list gate via the additive `allow` list clause; the fine which-rows cut stays in SQL. Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; run `./deploy.sh build` to force the 5.5-B app code into the pods. |
| `run-pagination-matrix.sh` + `pagination-matrix.postman_collection.json` | The **pagination** matrix (Phase 5.95): the shared `{count, page, perPage, items}` list envelope composed with the Phase-5 filter. Seeds a **dedicated** fixture set (5 EMEA + 3 APAC categories under the `7777…` catalog) and two single-region readers, then proves: the SAME paged URL answers **different `count`s** per subject (5 vs 3 — *the count is the count of rows you may see*); a `perPage=2` **walk** returns disjoint pages whose union is exactly the single-page set, `count` stable on every page, past-the-end = `200` + empty + the exact count; and `perPage=500` is a live `400 VALIDATION_FAILED` `problem+json` through APISIX (strict bounds, no clamping). Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; run `./deploy.sh build` to force the 5.95 app code into the pods. |
| `run-permission-categories-matrix.sh` + `permission-categories-matrix.postman_collection.json` | The **permission-categories** matrix (Phase 6.5, ADR 0007): the coarse READ/WRITE/TAG/GRANT categories expanding to fine actions in OPA `data`, deny-overrides, the five-tier ladder, and the hybrid delegation gates — live through the gateway. Seeds the dedicated `9999…` catalog (seven per-cell categories) and REBINDS one ladder subject between cell groups (one role per user per team), then proves: deny-overrides (PUT 200 / DELETE 403 on the same role), the **TAG/WRITE boundary in both directions** (the delta-dispatched second decision), **senior delegation** with the LIVE `data.role.assignable` verdict (201 / three 422s), the admin tier incl. **the designed cell** (an admin whose own role denies delete still assigns full WRITE — tier, not subset; the system admin row carries a temporary denial, trap-reverted), the **stale flat role** deciding nothing (∅-expansion, DB-seeded), and ladder parity (reader/member). Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; rebuild BOTH app images (`./deploy.sh build` covers the catalog only — build usermgmt explicitly). |
| `run-resource-resolution-matrix.sh` + `resource-resolution-matrix.postman_collection.json` | The **resource-resolution** matrix (Phase 5.97, ADR 0013): with the catalog's `AbacResourceResolver` registered, id'd `@OpaPreAuthorize` decisions resolve the **instance** and decide on its real tags + ancestors, the role looked up once on the **governing root** — the team/tag model finally governs id'd writes. Seeds a dedicated pair (`8888…` team-governed + `8889` team-less) with emea/apac categories + products and three subjects, then proves the 00-DESIGN §3 cells: the **headline flip** (viewer-realm member with a tag-matched write role → 200, was 403), the **closed fallback hole** (editor-realm member, tag-mismatched → 403, was 200), the narrowing (read-only role disables the fallback), the unchanged non-member fallback, hierarchy parity **via the gate**, the T5 product-policy sibling (403/200), and the pinned **missing-id 403** (was 404). Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; run `./deploy.sh build` to force the 5.97 app code into the pods. |
| `run-action-enrichment-matrix.sh` + `action-enrichment-matrix.postman_collection.json` | The **action-enrichment** matrix (Phase 6, ADR 0016): the read-side `_actions` affordance map the `ActionEnrichmentAdvice` attaches to returned `Enrichable` resources. Seeds the dedicated `aaaa…` team-governed catalog (a read-only role `ae-reader` + a read+write role `ae-writer`; emea/apac categories). The per-verb contrast is **grant-based, not tag-based** (a tag-gated role gates the *whole* decision incl. `view`, so a tag-mismatched row is unreadable — not a per-verb cell). It proves: the **honest map** (reader → `view:true, update/delete/assign-tags:false`; writer → all true — the decision contrast), **per-row complete maps on a page** (each `items[i]._actions` keyed and real, one bulk call), the reader's list↔single agreement, **affordance ≠ enforcement** (the reader's `update:false` matches a real `403`), and the **verified verb sets** (catalog excludes `assign-tags`). The script also runs an **omit-on-failure** smoke check (OPA paused → no 5xx, no fabricated all-false map; the gate-allowed omit case is proven by the catalog `ActionEnrichmentIT`/U6). The **team `_actions` cells are covered by the user-mgmt module tests** (not the gateway — `/teams` isn't gateway-routed): `ActionEnrichmentIT` (the live ungated-degrade) + `TeamEnrichmentAdviceTest` (the populated subset-only map). This slice **adds the `bulk` entrypoint** to `catalog`/`product`/`team` rego (the enrichment `allowAll` needs it), so **OPA must reload** — the runner restarts the OPA container itself before minting tokens. Needs `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`; rebuild BOTH app images (`./deploy.sh build` covers the catalog only — build usermgmt explicitly). |
| `run-resilience-matrix.sh` + `resilience-matrix.postman_collection.json` | The **cross-service HTTP resilience** matrix (Slice B3, ADR 0017): the headline through the gateway. The resolve edge is **fault-injected** by a tiny stub (`infra/compose.resilience-stub.yaml`), and the SAME protected id'd `GET /api/v1/categories/{id}` is asserted under two outage shapes the runner flips between (one rig, two passes): **E1** `STUB_MODE=transient` (1×503 then the role) → the resolve `CallGuard` (2 retries) rides out the blip → **200**; **E2** `STUB_MODE=down` (always 503) → the guard exhausts → `RoleResolutionException` → **403** (B2's wall, un-breached — no realm-fallback widening rode the outage to a 2xx). Resolve runs against the stub, **not** the user-service DB (so it touches no shared grant fixtures). Needs `ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2 && ./deploy.sh build`. **No Rego change → no OPA restart.** |
| `local.postman_environment.example.json` | Committed env template (copy to `local.postman_environment.json`). |
| `local.postman_environment.json` | Your local copy — **gitignored**. |

## Fixture-id registry (cross-matrix discipline)

The user-service DB **persists across runs**, so the teams/grants one matrix bootstraps are still
there when another matrix runs — fixture catalog ids therefore collide *across* matrices, not just
within one. Rule: an id one matrix uses as a **negative case** ("the reader has no grant here") must
never be an id another matrix **grants on**. Current registry — keep it unique when adding a matrix:

| Catalog id (prefix) | Used by | As |
|---|---|---|
| `1111…` | `run-team-matrix.sh` | demo (granted) |
| `2222…` | `run-tag-matrix.sh` | demo (granted) |
| `3333…` | `run-filter-matrix.sh` + `run-hierarchy-matrix.sh` | demo / granted root |
| `4444…` | `run-hierarchy-list-matrix.sh` | granted root |
| `5555…` | `run-hierarchy-list-matrix.sh` | foreign (never granted) |
| `6666…` | `run-hierarchy-matrix.sh` | foreign — the reader is bound to a deliberate NO-GRANT role on it (5.97: a present role-def disables the realm fallback, keeping the re-parent flip at 403); no matrix may grant a USABLE role on it |
| `7777…` | `run-pagination-matrix.sh` | demo (granted) — the dedicated pagination fixture set (5+3 categories; counts pinned, so no other matrix may touch it) |
| `8888…8888` | `run-resource-resolution-matrix.sh` | team-governed (tag-gated write role + root read role granted) — the 5.97 fixture tree (emea/apac categories + products + a nested category) |
| `8888…8889` | `run-resource-resolution-matrix.sh` | team-LESS (must never be granted on — it is the live realm-fallback "non-member unchanged" cell) |
| `9999…` | `run-permission-categories-matrix.sh` | team-governed (the 6.5 ladder/boundary cells; the ladder subject is REBOUND across roles, and two roles are DB-seeded bypasses: `pc-stale` flat, `pc-super20` GRANT-at-20) |
| `aaaa…` | `run-action-enrichment-matrix.sh` | team-governed (Phase 6 affordance `_actions` cells: a read+write role `ae-writer` + a read-only role `ae-reader`; emea/apac categories — the honest-`false`, writer-all-true, per-row-page, and verb-set-exclusion cells; the seeded team's own `_actions` cells live in the user-mgmt module tests, not this gateway matrix) |
| `cccc…` | `run-resilience-matrix.sh` | a single fixture Category the B3 resilience matrix GETs; the role is **resolved from the fault-injecting stub**, not the user-service DB, so this id grants nothing in the shared store and collides with no other matrix |

(Discovered the hard way: the hierarchy matrix originally used `4444…` as its foreign catalog; a past
list-matrix run had granted the same reader an inheritable role on it, flipping the re-parent assert
from 403 to a *policy-correct* 200.)

## Why the token is minted in-network

Keycloak is hostname-aware and APISIX validates the issuer as `keycloak:8888` (in-network). A token
obtained from the host (`localhost:28888`) has a mismatched issuer and the gateway rejects it. So
`run-tests.sh` mints the token from inside the `opa-abac-example_default` compose network and passes
it to newman as `access_token`. Full explanation in
[`docs/guides/E2E-TESTING.md`](../../docs/guides/E2E-TESTING.md).

## Status

**Working suite** — auth + the full Catalog → Category → Product lifecycle (create → get → update →
list → delete → 404-after-delete) with id-chaining and field-level assertions, then a cascade
cleanup, **plus the full ABAC matrix set listed above**: viewer-vs-editor role decisions, team-based
role resolution, tag-based grants, partial-eval data filtering (exact row sets per subject), and the
hierarchy allow/deny + re-parent matrix. All run green against the local rig, through the gateway,
with real OPA decisions and (since Phase 5.9) RFC-7807 `problem+json` error-contract assertions on
the deny paths.

Each matrix landed with its slice — the file table above is the authoritative list of what is proven
end-to-end today.
