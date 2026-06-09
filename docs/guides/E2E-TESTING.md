---
tags:
  - status/active
  - type/guide
  - area/build
  - area/apisix
---

# End-to-end testing — Postman / Newman through the gateway

> How to run the e2e API suite against the local rig: a Keycloak token, then CRUD over
> Catalog → Category → Product through APISIX. The suite lives in `scripts/postman/`.
> The rig itself is documented in [`infra/README.md`](../../infra/README.md).

## What the suite covers

The collection exercises the **whole request path** — APISIX (OIDC + OPA + tracing) → a catalog pod
→ Postgres — not just the app in isolation. It proves the plumbing works end to end: an identity is
obtained from Keycloak, presented to the gateway, validated, and CRUD over the resource hierarchy
succeeds. Folders:

1. **Auth** — Keycloak password-grant → capture `access_token`. Auto-skips when `run-tests.sh` has
   already injected a token (a prerequest `pm.execution.skipRequest()` guard), so the host path
   doesn't try to reach the in-network issuer.
2. **Catalog** — create → get; capture `catalog_id`.
3. **Category** — create under the catalog; capture `category_id`.
4. **Product** — create → get (field-level assertions) → update → list → delete → get-after-delete
   (404); capture `product_id`.
5. **Cleanup** — delete the catalog (cascade removes category + any product).

Requests chain via **collection** variables (`catalog_id` → `category_id` → `product_id`). Each
request asserts the status code and field-level response shape.

> **Variable-scope gotcha:** the chained ids live in the *collection* scope (set by each folder's
> test script), and are deliberately **not** declared in the environment file. Newman resolves
> `{{var}}` with environment scope winning over collection scope — so an empty `catalog_id` declared
> in the environment would shadow the captured collection value and every downstream URL would render
> with an empty id (`/catalogs//categories/…`). Keep them out of the environment.

> **Two suites now.** The original `catalog-e2e` collection proves the *plumbing* (the create→read→
> update→delete chain through the gateway). The **ABAC allow/deny matrix** (`catalog-abac-matrix`,
> run via `run-matrix.sh`) proves *fine-grained decisions*: the library spine is live —
> `@OpaPreAuthorize` → role-definition-driven OPA — so a **viewer** token reads (200) but cannot write
> (**403**), and an **editor** token writes (201/200/204). It mints **both** the `viewer` and `editor`
> realm tokens in-network and injects `viewer_token` + `editor_token`. See "Running it" below.

## Prerequisites

- The full rig up with OIDC:
  ```bash
  ENABLE_OIDC=1 ./deploy.sh up --pods 2
  ```
  This brings up Postgres, APISIX (proxy on `:9085`), Keycloak (realm `catalog-demo`, user
  `demo`/`demo`, client `catalog-gateway`), and the catalog pods.
- `newman` installed (`npm install -g newman` or `brew install newman`).
- Docker/podman available (needed to mint an in-network token — see below).

## The in-network token caveat (important)

Keycloak is **hostname-aware**. Inside the compose network it issues and advertises the issuer
`http://keycloak:8888`; from the host it advertises `http://localhost:28888`. **APISIX discovers and
validates the issuer in-network**, so a token used *through the gateway* must be minted in-network
(issuer `keycloak:8888`) — a token obtained against `localhost:28888` has a mismatched issuer and the
gateway rejects it.

So the suite does **not** grab the token from the host. The runner mints it from inside the shared
compose network (`opa-abac-example_default`) and hands it to newman:

```bash
TOKEN=$(docker run --rm --network opa-abac-example_default curlimages/curl -s \
  -X POST http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token \
  -d grant_type=password -d client_id=catalog-gateway -d client_secret=catalog-gateway-secret \
  -d username=demo -d password=demo | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

newman run catalog-e2e.postman_collection.json -e local.postman_environment.json \
  --env-var "access_token=$TOKEN"
```

`run-tests.sh` does exactly this. The "Auth" folder in the collection is kept as documentation of the
grant (and works if you run the suite from inside the network), but the host-side default path injects
the in-network token directly.

> Alternative: run newman itself inside a container attached to `opa-abac-example_default`, so its
> own "Auth" request reaches `keycloak:8888`. The runner's default (mint-then-inject) avoids needing
> a newman image on the network and keeps the collection host-runnable.

## Running it

```bash
cd scripts/postman
cp local.postman_environment.example.json local.postman_environment.json   # first time

# plumbing suite (single token, happy-path CRUD chain)
./run-tests.sh                 # full suite
./run-tests.sh --folder Product   # one folder
./run-tests.sh --verbose

# ABAC allow/deny matrix (mints viewer + editor tokens, proves 200/403/204)
./run-matrix.sh
./run-matrix.sh --verbose

# Tag-based ABAC matrix (Phase 4.5 — requires the full rig WITH the user-service)
#   ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
./run-tag-matrix.sh

# Data-filtering matrix (Phase 5 — same full rig; restart OPA after editing category.rego)
./run-filter-matrix.sh

# Hierarchy-aware list matrix (Slice 5.5-B — an ancestor grant widens a list; same full rig)
./run-hierarchy-list-matrix.sh
```

`run-matrix.sh` mints **two** in-network tokens (the `viewer` and `editor` realm users) and injects
`viewer_token` + `editor_token`. Expected: editor seeds a catalog/category/product (201), viewer reads
them (200), viewer writes are denied (**403**), editor updates/deletes (200/204). 12 requests, all
green; stable across reruns.

### Tag-based ABAC matrix (Phase 4.5)

`run-tag-matrix.sh` proves **tag-based grants** end to end — a decision driven by the *resource's tags*
matched against a role's `requiredTags`, in Rego. It needs the full rig **with the user-service**
(`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`). At run time it mints in-network tokens,
seeds a demo catalog as a team-target, bootstraps two tag-gated roles (a `regional-reader` requiring
`region` ANY_OF `[emea]`, a `strict-reader` requiring `region:[emea]` **and** `sensitivity:[public,
internal]` ALL_OF), and creates three differently-tagged Categories through the gateway. Then 7 requests:

| # | Case | Expected |
|---|------|----------|
| 1 | gated member reads the `region=[emea]` Category | **200** |
| 2 | **the SAME member reads the `region=[apac]` Category** | **403** — identical role; only the tags differ |
| 3a / 3b | ALL_OF role: both keys satisfied / only one | **200** / **403** |
| 4a / 4b | owner defines a team tag key / a member tries | **201** / **403** |
| 5 | assigning a value outside the dictionary | **422** (never stored) |

Request 2 is the decisive proof that **tags** (not just `permissions`) drive the decision. A team key
defined at runtime governs assignment + decisions immediately — no redeploy. All 7 green; stable across
reruns. Guide: [[TAG-BASED-AUTHORIZATION]].

### Data-filtering matrix (Phase 5)

`run-filter-matrix.sh` proves OPA partial-evaluation **list** filtering end to end — the residual pushed
into the SQL `WHERE` clause, so two subjects hit the **same** list endpoint and get **different row sets**.
It needs the full rig with the user-service (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`).
At run time it mints tokens, seeds a demo catalog as a team-target, bootstraps two single-region-gated
reader roles (`emea-reader`, `apac-reader`), binds an allow-all owner, leaves the `outsider` user **unbound**
(no role definition), and creates three region-tagged Categories through the gateway. Then 4 list requests:

| # | Subject | `GET …/categories` returns |
|---|---------|----------------------------|
| 1 | reader gated to `region=emea` | **only the emea row** (1 row) |
| 2 | reader gated to `region=apac` | **only the apac row** (1 row) — a *different* set, same endpoint |
| 3 | owner (allow-all) | **all three rows** |
| 4 | stranger (**no role definition**) | **`[]`** — the `filter` rule has no subject-roles fallback, so a missing role fails *closed* to an empty list, never the whole table |

Requests 1+2 are the decisive proof: the **same endpoint** yields **different rows** because the residual
is in the SQL, not a post-filter. Request 4 is the fail-closed boundary (the one the pre-impl audit
flagged). All green; stable across reruns. Guide: [[PARTIAL-EVALUATION-FILTERING]].

### Hierarchy-aware list matrix (Slice 5.5-B)

`run-hierarchy-list-matrix.sh` proves that an **ancestor (Catalog) grant widens a category list** to the whole
catalog subtree — composing the Phase-5 residual with the 5.5-A resolver via an app-built `subtreeSpec`. Same
full rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; run `./deploy.sh build` to force the
5.5-B app code into the pods). At run time it seeds a catalog + a foreign catalog (with their ltree paths),
bootstraps an **inherit reader** (role grants read on the **catalog only** — no category tag grant), a
**region reader** (a direct `category:read` gated to `region=emea`), and leaves the `outsider` **unbound**;
creates three Categories (emea / apac / one flagged `abac_deny`); then runs two passes around an ltree
re-parent:

| # | Subject | `GET …/categories` (catalog C) returns |
|---|---------|----------------------------------------|
| E1 | **inherit reader** (catalog grant only) | the **whole subtree** — emea **+ apac** (rows its own tags would never surface), **minus** the `abac_deny` row |
| E2 | **region reader** (`region=emea`) | **only the emea row** — a *different* set on the same endpoint |
| E4 | stranger (**no role definition**) | **`[]` / 403** — fail-closed (no inheritable grant, no residual) |
| re-parent | move the apac Category to the foreign catalog, re-list as the inherit reader | the apac row **leaves** catalog C's widened list |

E1 is the headline (the widening); E1-vs-E2 is the decisive different-set proof; the `abac_deny` exclusion is
deny-overrides on a widened list; E4 is the fail-closed boundary; the re-parent proves the cut tracks live
lineage. The inherit reader passes the **coarse** type-level list gate via the small additive `allow` list
clause, while the **fine** which-rows cut stays in SQL. Guide: [[PARTIAL-EVALUATION-FILTERING]] (the
hierarchy-aware list section) · [[HIERARCHICAL-AUTHORIZATION]].

Reports land under `build/reports/postman/<run_id>/`. The CLI reporter prints the assertion summary;
the JSON reporter is kept for post-mortem.

## Environment

`local.postman_environment.example.json` is the committed template;
`local.postman_environment.json` is your local copy (gitignored). Key variables:

| Variable | Value | Note |
|----------|-------|------|
| `base_url` | `http://localhost:9085` | APISIX proxy (host) |
| `collection_base_url` | `{{base_url}}/api/v1` | the catalog API **is** versioned — `/api/v1/...` |
| `realm` | `catalog-demo` | Keycloak realm |
| `client_id` / `client_secret` | `catalog-gateway` / `catalog-gateway-secret` | confidential gateway client |
| `username` / `password` | `demo` / `demo` | demo user |
| `keycloak_token_url` | `http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token` | **in-network** issuer (see caveat) |

## CI

CI does **not** run the rig yet (it runs `./gradlew build` only), so the e2e suite is a local/manual
gate for now. Wiring an e2e job into `.github/workflows/ci.yml` (compose up → newman → teardown) is a
sensible follow-up, tracked separately.

## Related

- The rig: [`infra/README.md`](../../infra/README.md)
- The library being exercised: [[DOMAIN-MODEL]], [[CONCURRENCY-AND-LOCKING]]
- Tag-based authorization (the tag matrix): [[TAG-BASED-AUTHORIZATION]]
- Data filtering (the filter matrix): [[PARTIAL-EVALUATION-FILTERING]]
- Implementation plan (ticket 5 fleshes out the suite): [[DOMAIN-MODEL-FOUNDATION]]
