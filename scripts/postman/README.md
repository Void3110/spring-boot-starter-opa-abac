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
