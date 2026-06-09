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
| `6666…` | `run-hierarchy-matrix.sh` | foreign (never granted) |

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
