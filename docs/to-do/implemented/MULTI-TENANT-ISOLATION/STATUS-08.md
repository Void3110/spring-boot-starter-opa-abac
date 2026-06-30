---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T8: gateway routing: usermgmt-pool + /api/v1/teams* /api/v1/users*

**Status:** ✅ DONE

## What shipped

- **`infra/apisix/init-routes.sh`** — gated under `ENABLE_USER_SERVICE` (which `ENABLE_SPA` implies):
  - `usermgmt-pool` upstream → `host.docker.internal:28090` (the usermgmt pod).
  - routes `usermgmt-teams` (`/api/v1/teams*`) + `usermgmt-users` (`/api/v1/users*`) at **priority 60**
    (above the catalog catch-all's 0), carrying the **same `openid-connect` bearer plugin** (+ tracing,
    + cors when SPA) as the catalog route — but **NOT** the catalog `opa` gateway plugin (the user-service
    authorizes itself via `@OpaPreAuthorize`).
  - **No `/internal/**` route** — the in-network-only invariant.
- **`deploy.sh`** — passes `ENABLE_USER_SERVICE` through to `init-routes.sh`.
- **`infra/compose.usermgmt.yaml`** — enables the ownership resolver so the gateway-routed `createTeam`
  can do the squat-check: `ABAC_OWNERSHIP_ENABLED=true` +
  `ABAC_OWNERSHIP_SERVICES_CATALOG=http://catalog-1:8080` (the catalog's in-network `/internal/.../created-by`
  read, reached DIRECTLY at a catalog pod — never via the gateway). Without this every public createTeam
  fails closed to 403 (the T7 default).
- **`infra/README.md`** — the routing table + the `/internal`-stays-off-gateway invariant + the verify
  curls.

## Tests / acceptance (live rig)

Re-applied routes against the running APISIX (`ENABLE_USER_SERVICE=1`) and curled `:9085`:

| Check | Result |
|-------|--------|
| route table has `usermgmt-teams` / `usermgmt-users` → `usermgmt-pool` @ priority 60 | ✅ |
| `GET /api/v1/users` + valid in-network bearer | **200** (reaches user-mgmt) |
| `GET /internal/governed-targets` through `:9085` | **404 (not routed)** — the load-bearing invariant |
| route table contains ANY `/internal` route | **NONE** |
| `GET /api/v1/users` no token | **401** (bearer-only posture preserved) |

`bash -n init-routes.sh` / `deploy.sh` + `docker compose config -q` all clean.

## Architecture review + refactor

- **Boundary (headline):** `/internal/**` is NOT gateway-routed — proven both ways (no `/internal` route in
  the table; the live 404). The gateway proxies ONLY the public `/api/v1/{teams,users,catalogs}*` +
  Keycloak. This is what keeps `permitAll` `/internal` (T3/T6) safe.
- **Bearer validation:** the user-mgmt routes carry `openid-connect` → no token = 401.
- **No collision:** distinct prefixes, priority 60 > the catch-all's 0; the catalog + Keycloak routes are
  untouched.
- **Plugin set:** correctly omits the catalog `opa` gateway plugin (user-service self-authorizes).
- **No refactor.** Noted: the ownership URL points at `catalog-1` (single pod); if it's down, ownership
  fails closed (403) — acceptable + documented for the demo.

## Decisions

- **Ownership read goes DIRECT to a catalog pod (`catalog-1:8080`), not through the gateway** — because
  `/internal/catalog/{id}/created-by` is in-network-only (never gateway-exposed). A real deployment would
  use a load-balanced internal URL.
- **The user-mgmt routes do NOT carry the catalog `opa` gateway plugin** — the user-service makes its own
  fine-grained `@OpaPreAuthorize` decisions; a coarse gateway OPA check would be redundant.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
