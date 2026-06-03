# Local container-pool rig (Phase A + B)

Run the catalog example app as **N replicas behind an APISIX round-robin upstream**, so we can
exercise concurrency / load balancing locally — now with **distributed tracing** (Jaeger) and
an **OPA** decision in the gateway path.

```
client ─▶ APISIX :9085 ─▶ OPA decision (allow-all) ─▶ catalog-1..N ─▶ Postgres :5433
            │  (round-robin over pods, host :28081..)
            └─ spans ─┐        ┌─ spans (OTEL Java agent)
                      ▼        ▼
                   Jaeger (UI :26686, Badger storage)
```

**Two-layer authorization is now live** (see [`docs/architecture/TWO-LAYER-AUTHORIZATION.md`](../docs/architecture/TWO-LAYER-AUTHORIZATION.md)):

- **Gateway (coarse):** APISIX validates the OIDC token and forwards the Bearer; the OPA `gateway`
  policy is still the coarse allow-all placeholder for the route layer.
- **App (fine-grained):** with `ENABLE_OIDC=1`, the catalog app does real ABAC via the library —
  `AbacFilter` extracts the subject, `@OpaPreAuthorize` asks OPA against **per-type** policies
  (`opa/policies/{catalog,category,product}.rego`, role-definition-driven). The demo Lua enricher has
  been **retired**; the app does identity extraction natively.

Tracing/OPA are on by default; run a bare Phase-A rig with `ENABLE_TRACING=0 ENABLE_OPA=0 ./deploy.sh up`.

## Gateway auth (Keycloak OIDC) — opt-in

Off by default. Turn it on with `ENABLE_OIDC=1 ./deploy.sh up` to add Keycloak and terminate
OIDC **at the gateway** (the service still does no JWT validation — that comes with the library).

```bash
ENABLE_OIDC=1 ./deploy.sh up --pods 2
# realm catalog-demo, client catalog-gateway — imported from
# infra/keycloak/realm-export.json. Keycloak UI: http://localhost:28888 (admin/admin)
#
# Users (for the ABAC allow/deny matrix):
#   demo/demo     -> catalog-viewer + catalog-editor (back-compat; holds BOTH roles)
#   viewer/viewer -> catalog-viewer only  (reads allowed, writes 403)
#   editor/editor -> catalog-editor (+viewer)  (reads + writes allowed)
#   outsider/outsider -> catalog-viewer  (a non-member, for the team matrix's "no team -> deny" case)

# no token -> 302 redirect to Keycloak login (unauth_action: auth)
curl -s -o /dev/null -w '%{http_code}\n' localhost:9085/actuator/health        # 302

# get a token IN-NETWORK (issuer must match what APISIX validates against), then call:
TOKEN=$(docker run --rm --network opa-abac-example_default curlimages/curl -s \
  -X POST http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token \
  -d client_id=catalog-gateway -d client_secret=catalog-gateway-secret \
  -d grant_type=password -d username=demo -d password=demo | sed 's/.*"access_token":"//;s/".*//')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:9085/actuator/health  # 200
```

> **Issuer gotcha:** Keycloak is hostname-aware. In-network it issues/advertises
> `http://keycloak:8888`; from the host it advertises `http://localhost:28888`. APISIX discovers
> + validates in-network, so a token used through the gateway must be **minted in-network**
> (issuer `keycloak:8888`) or via the real browser redirect flow — a token minted against
> `localhost:28888` has a mismatched issuer and APISIX rejects it.

## User-management service (app-resolved roles) — opt-in

Off by default. `ENABLE_USER_SERVICE=1 ./deploy.sh up` adds the `user-management-service` (the ABAC
**attribute source**) + its own Postgres, and points the catalog pods at it
(`CATALOG_ROLE_SOURCE=http`, base URL `http://usermgmt:8080`). The catalog then resolves the caller's
effective role from **real team membership** instead of the static demo supplier — the Phase-4
app-resolved path.

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# user-mgmt at http://localhost:28090 (its own DB on :5434); resolve API at /internal/effective-role.

# The team-based allow/deny matrix (mints in-network tokens, bootstraps the team data, asserts):
cd scripts/postman && ./run-team-matrix.sh
```

The matrix proves, through the gateway: the catalog owner writes; a viewer-member cannot; a member with
a team-scoped custom editor role can; a non-member is denied — all with the role coming from the
user-service. It also dogfoods the user-service's own management API (owner manages, member 403). See
[[TEAM-BASED-AUTHORIZATION]] for the model and [[E2E-TESTING]] for the in-network token caveat.

The **tag-based** matrix (Phase 4.5) extends this with grants driven by the *resource's tags*:

```bash
# The tag-based allow/deny matrix (seeds tag-gated roles + differently-tagged Categories, asserts):
cd scripts/postman && ./run-tag-matrix.sh
```

It proves the decisive contrast — the **same** member with the **same** tag-gated role reads a
matching-tag Category (200) and a non-matching one (403); plus ANY_OF/ALL_OF, the dictionary define
dogfood (owner 201 / member 403), and an illegal assignment (422). A team tag key defined at runtime
governs assignment + decisions immediately, no redeploy. See [[TAG-BASED-AUTHORIZATION]]. The updated
`category.rego` (with the `tags_satisfied` match) is served by the shared OPA container — restart OPA
after editing it.

> The `team.rego` policy the user-service dogfoods is served by the shared OPA container — it lives in
> both `../example-user-management-service/src/main/resources/opa/policies/` (the source of truth) and
> `opa/policies/` (mounted into the rig's OPA). Restart OPA after editing it (`docker restart
> opa-abac-opa`) — `--watch` doesn't always reload.

## Quick start

```bash
./profile.sh up                 # 1. base infra: Postgres (host :5433)
./deploy.sh up --pods 2         # 2. build app image (first run), start 2 pods + APISIX, wire upstream
curl -s localhost:9085/actuator/health        # through the gateway -> a pod -> Postgres
./deploy.sh status              # containers + current upstream node set
./deploy.sh scale --pods 4      # rescale the pool; APISIX upstream resynced automatically
./deploy.sh down                # stop pods + APISIX (Postgres left running)
./profile.sh down               # stop Postgres too
```

## Verifying load balancing

The route sets an `X-Upstream-Addr` response header echoing the pod that served each request:

```bash
for i in $(seq 1 20); do
  curl -s -D - -o /dev/null localhost:9085/actuator/health | grep -i x-upstream-addr
done | sort | uniq -c
#   11 X-Upstream-Addr: 192.168.65.254:28081
#    9 X-Upstream-Addr: 192.168.65.254:28082     <- traffic spread across both pods
```

## Pieces

| File | Role |
|------|------|
| `../deploy.sh` | Build the app image; generate the N-pod compose; start pods; sync the APISIX `catalog-pool` upstream to round-robin over all running pods. |
| `../example-catalog-management-service/Dockerfile` | Multi-stage build (Gradle bootJar → JRE runtime). |
| `compose.apisix.yaml` | APISIX + etcd (shares the `opa-abac-example` compose project/network with Postgres). |
| `compose.jaeger.yaml` + `jaeger/jaeger-config.yaml` | Jaeger v2 with embedded Badger persistent trace storage. |
| `compose.opa.yaml` + `opa/policies/gateway.rego` | OPA with an allow-all gateway policy; exports its own spans to Jaeger. |
| `compose.keycloak.yaml` + `keycloak/realm-export.json` | Keycloak (opt-in); imports the `catalog-demo` realm/client/user on startup. |
| `compose.usermgmt.yaml` + `../example-user-management-service/Dockerfile` | The user-management service + its own Postgres (opt-in via `ENABLE_USER_SERVICE=1`); the app-resolved role source for the catalog. |
| `opa/policies/team.rego` | The team-management policy the user-service dogfoods (a copy of the service's source policy, mounted into the rig's OPA). |
| `apisix/config.yaml` | APISIX static config (plugins: prometheus, proxy-rewrite, response-rewrite, opentelemetry, opa, openid-connect). |
| `apisix/init-routes.sh` | Seed the `catalog-pool` upstream + `catalog-all` route (idempotent); adds openid-connect + opentelemetry + opa plugins (toggleable). |

## Ports (and why these, not the defaults)

| Port | What | Note |
|------|------|------|
| `5433` | Postgres | base `compose.yaml`; avoids a 5432 clash |
| `9085` | APISIX proxy | **not 9080** — a podman-machine `gvproxy` holds `:9080` on this host |
| `9180` | APISIX admin API | `deploy.sh`/`init-routes.sh` write upstream + route here |
| `28081..` | app pods (host) | **not the 18081 range** — a running portal podman-machine forwards `18081/18082` |
| `26686` | Jaeger UI | **not 16686** (held by portal podman-machine) |
| `24317/24318` | Jaeger OTLP gRPC/HTTP (host) | **not 4317/4318** (held). In-network everything uses `opa-abac-jaeger:4318` |
| `28181` | OPA data API (host) | **not 8181** (held). In-network APISIX calls `http://opa:8181` |
| `28888` | Keycloak (host, opt-in) | **not 8888** (held). In-network APISIX discovers `http://keycloak:8888` |

> All the `2xxxx` remaps are only for **host** access — inside the shared Docker network the
> containers talk by DNS name on the original ports, so the remaps don't affect the topology.

## Tracing (verifying the chain)

Generate traffic, then open Jaeger at **http://localhost:26686** and pick service `apisix`:

```bash
for i in $(seq 1 30); do curl -s -o /dev/null localhost:9085/actuator/health; done
curl -s localhost:26686/api/services        # -> apisix, catalog-management-service, opa, jaeger
```

A single APISIX trace correlates the gateway span → the app's `GET /...` span → its JPA/DB
span (trace context propagated by the OTEL Java agent baked into the app image). OPA emits its
own `v1/data` decision spans (the APISIX `opa` plugin doesn't propagate trace context into the
OPA call, so OPA spans are a separate service rather than nested — expected).

## Notes / gotchas (learned the hard way)

- **etcd image**: Bitnami sunset their free Docker Hub images, so we use
  `quay.io/coreos/etcd` (official) with explicit `--listen/--advertise-client-urls`.
- **APISIX 3.x etcd config** lives under `deployment.etcd`, **not** a top-level `etcd:` key —
  a top-level key is silently ignored and APISIX falls back to `127.0.0.1:2379`.
- **Shared network (by design)**: all composes use `name: opa-abac-example`, so Docker
  Compose auto-creates **one** network `opa-abac-example_default` and joins every container to
  it; they reach each other by container/service DNS name (`opa-abac-postgres`, `jaeger`,
  `opa`). We deliberately do **not** use an explicit pre-created `external: true` network
  (the way a larger multi-project podman setup might) — for a single self-contained example
  rig the shared project network is simpler and needs no `network create` step. Trade-off: a
  container started *outside* this compose project would need a manual `docker network connect`.
  Don't `compose down --remove-orphans` on one file — it'll delete the others' containers.
- **No upstream health-checks in Phase A**: if a pod dies, APISIX keeps round-robining to it
  (you'll see some 5xx) until you rescale. Active health-checking can be added later.
