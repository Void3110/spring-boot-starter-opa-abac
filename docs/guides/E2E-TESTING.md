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

1. **Auth** — Keycloak password-grant → capture `access_token`.
2. **Catalog** — create / get / list; capture `catalogId`.
3. **Category** — create under the catalog; capture `categoryId`.
4. **Product** — create / get / update / list under the category; capture `productId`.
5. **Cleanup** — delete the catalog (cascade removes category + product).

Requests chain via collection variables (`catalogId` → `categoryId` → `productId`). Each request
asserts the status code and the response shape.

> **Authz depth today.** OPA runs an allow-all placeholder and the service does no service-side
> ABAC yet, so the suite proves *plumbing*, not fine-grained decisions. A viewer-vs-editor matrix
> gets added when `@OpaPreAuthorize` + a real policy land in a later Phase-3 slice.

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
./run-tests.sh                 # full suite
./run-tests.sh --folder Product   # one folder
./run-tests.sh --verbose
```

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
- Implementation plan (ticket 5 fleshes out the suite): [[DOMAIN-MODEL-FOUNDATION]]
