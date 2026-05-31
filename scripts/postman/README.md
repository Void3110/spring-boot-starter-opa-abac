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
| `run-tests.sh` | Runner: mints an in-network Keycloak token, injects it, runs newman. |
| `catalog-e2e.postman_collection.json` | The collection: Auth → Catalog → Category → Product → Cleanup. |
| `local.postman_environment.example.json` | Committed env template (copy to `local.postman_environment.json`). |
| `local.postman_environment.json` | Your local copy — **gitignored**. |

## Why the token is minted in-network

Keycloak is hostname-aware and APISIX validates the issuer as `keycloak:8888` (in-network). A token
obtained from the host (`localhost:28888`) has a mismatched issuer and the gateway rejects it. So
`run-tests.sh` mints the token from inside the `opa-abac-example_default` compose network and passes
it to newman as `access_token`. Full explanation in
[`docs/guides/E2E-TESTING.md`](../../docs/guides/E2E-TESTING.md).

## Status

**Working suite** — auth + the full Catalog → Category → Product lifecycle (create → get → update →
list → delete → 404-after-delete) with id-chaining and field-level assertions, then a cascade
cleanup. Runs green against the local rig.

The **authz** depth is still shallow on purpose: OPA runs an allow-all placeholder and the service
does no JWT/ABAC check yet, so the suite proves the *plumbing* (a Keycloak-authenticated identity
reaches the app and CRUD works through the gateway), not fine-grained decisions. The viewer-vs-editor
matrix is added when `@OpaPreAuthorize` + a real policy land in a later Phase-3 slice — see the
`DOMAIN-MODEL-FOUNDATION` plan, ticket 5, and `10-QA-TEST-CASES.md` (E9+).
