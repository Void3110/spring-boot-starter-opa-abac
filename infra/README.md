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

## Demo SPA auth (bearer-only gateway) — opt-in

For the browser demo SPA (Phase 7), the gateway runs in a **bearer-only** posture instead of doing
the redirect login itself. `ENABLE_SPA=1` flips the `openid-connect` plugin to
`bearer_only=true` + `unauth_action="deny"` (the gateway only *validates* an incoming
`Authorization: Bearer <jwt>` against the realm JWKS — a missing/invalid token gets a `401`, never a
`302` redirect) and enables the `cors` plugin for the SPA origin. `ENABLE_SPA=1` **force-enables
`ENABLE_OIDC`** (the validation is done by the openid-connect plugin).

The SPA itself does **Authorization Code + PKCE** directly against Keycloak using the new public
client **`catalog-spa`** (`publicClient: true`, `pkce S256`, redirect URIs `http://localhost:3000/*`
+ `http://localhost:9085/*`), holds the access token, and sends it as a Bearer to the gateway.

`ENABLE_SPA=1` is the **complete demo recipe in one flag** — it force-enables both `ENABLE_OIDC`
(the bearer validation) **and `ENABLE_USER_SERVICE`** (the http role source + Phase-6 `_actions`
enrichment the SPA renders). It also proxies Keycloak through the gateway at `/realms/*` +
`/resources/*` so the browser does its whole PKCE flow single-origin against `:9085` (no
`/etc/hosts`, no host-port issuer mismatch). The browser SPA lives in `example-demo-ui/`.

```bash
./deploy.sh build                              # ensure the Phase-6 enrichment code is in the images
ENABLE_SPA=1 ./deploy.sh up --pods 2          # brings up Keycloak + user-service + bearer gateway

# no token -> 401 (unauth_action: deny — NOT a redirect, unlike the default OIDC posture)
curl -s -o /dev/null -w '%{http_code}\n' localhost:9085/actuator/health        # 401

# mint a token IN-NETWORK (see the issuer gotcha above) and call through the gateway.
# NOTE: catalog-spa is a *public* client with direct-access-grants OFF (correct for a real PKCE
# browser client), so for a CLI smoke test mint via the confidential catalog-gateway client — the
# token is realm-scoped, so APISIX validates it regardless of which client minted it:
TOKEN=$(docker run --rm --network opa-abac-example_default curlimages/curl -s \
  -X POST http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token \
  -d client_id=catalog-gateway -d client_secret=catalog-gateway-secret \
  -d grant_type=password -d username=viewer -d password=viewer | sed 's/.*"access_token":"//;s/".*//')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:9085/actuator/health  # 200
```

> The real PKCE flow (against `catalog-spa`) is exercised by the SPA in the browser; the CLI snippet
> above only proves the gateway's bearer-validation posture. When the SPA is served *through* APISIX
> for the packaged demo it is same-origin (CORS moot); the `cors` plugin covers the Vite dev server
> on `:3000` during development.

`ENABLE_DIRECTORY=1` adds the **identity-directory search** to the user-service (the `UserDirectory`
port, ADR 0020 — see the [[USER-DIRECTORY]] guide): the member picker can then offer **any realm
account**, not just provisioned profiles. It force-enables `ENABLE_OIDC` + `ENABLE_USER_SERVICE` and
wires the `catalog-directory` service account (`realm-management → view-users` **only**) into the
usermgmt pod at the **in-network** `http://keycloak:8888`. Off by default — the rig then keeps the
always-empty `NoOp` directory and the search sub-path of `/api/v1/users` answers 200-empty.

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

### Gateway routing for the public self-service API (Slice B4)

When `ENABLE_USER_SERVICE=1`, `init-routes.sh` also routes the **public** user-management prefixes
through APISIX (priority 60, above the catalog catch-all) to a new `usermgmt-pool` upstream, carrying
the **same `openid-connect` bearer validation** as the catalog routes (the user-service does its own
fine-grained `@OpaPreAuthorize`, so these routes do *not* carry the catalog `opa` gateway plugin):

| Route | URI | → upstream |
|-------|-----|-----------|
| `usermgmt-teams` | `/api/v1/teams*` | `usermgmt-pool` (`catalog-... `→ `usermgmt:8080`) |
| `usermgmt-users` | `/api/v1/users*` | `usermgmt-pool` |

This is what lets the SPA's **self-service create** work: a `POST /api/v1/teams` through the gateway
arrives with the validated `sub`, so `CallerIdentity` sees the real subject — required by both
owner-on-create and the Slice-B4 **ownership squat-check** (`createTeam` verifies the caller owns the
target catalog, 403 otherwise; ADR 0019). The user-service must run with `ABAC_OWNERSHIP_ENABLED=true`
+ `ABAC_OWNERSHIP_SERVICES_CATALOG=http://catalog-1:8080` (set in `compose.usermgmt.yaml`) or every
public `createTeam` fails closed to 403.

> **`/internal/**` is NEVER gateway-exposed — the load-bearing invariant.** The gateway proxies ONLY
> `/api/v1/teams*`, `/api/v1/users*`, `/api/v1/catalogs*` (catch-all), and the Keycloak `/realms/*` +
> `/resources/*` paths. The user-service's `/internal/**` (resolve, governed-targets, bootstrap) and the
> catalog's `/internal/catalog/{id}/created-by` are `permitAll` + in-network only — exposing them through
> the gateway would let anyone forge a `sub` or read a creator id. Verify:
> `curl :9085/internal/governed-targets` → **404 (not routed)**; `curl -H 'Authorization: Bearer <jwt>'
> :9085/api/v1/users` → **200**.

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

The **resource-resolution** matrix (Phase 5.97) proves the attribute-rich gate: id'd decisions resolve
the instance and decide on its real tags + ancestors (role on the governing root), so the team/tag
model governs id'd writes — the headline flip, the closed realm-fallback hole, and the missing-id 403
posture, live:

```bash
# The resource-resolution matrix (seeds the 8888… fixture pair + three subjects, asserts):
cd scripts/postman && ./run-resource-resolution-matrix.sh
```

See [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]. Note `product.rego`/`catalog.rego` now carry the same
`tags_satisfied` match as `category.rego` — restart OPA after editing any of them.

The **permission-categories** matrix (Phase 6.5) proves the coarse-category model: `READ`/`WRITE`/
`TAG`/`GRANT` tokens expanding to fine actions through `data.permission_categories`
(`opa/policies/permission_categories.json` — a colocated data file, loaded with the policies),
deny-overrides, the delta-dispatched `assign-tags` second decision, the five-tier ladder, and the
hybrid delegation gates with the live `data.role.assignable` verdict:

```bash
# The permission-categories matrix (fixture 9999…; rebuild BOTH app images first):
cd scripts/postman && ./run-permission-categories-matrix.sh
```

See [[PERMISSION-MODEL]]. Since 6.5 the policies are **category-token only** — every runner's
bootstrap payloads send `READ`/`WRITE`/`TAG` (+ `roleLevel`); a stale flat `read`/`write` token
expands to nothing and denies. This slice rewrote every catalog policy and added
`permissions.rego`/`role.rego` + the data file — **restart OPA** after pulling it (the runner does
so itself).

The **action-enrichment** matrix (Phase 6) proves the read-side `_actions` affordance map the response
advice attaches: a read-only subject's map is honest (`view:true`, mutating verbs `false`), a writer's is
all-`true`, each page element carries its own complete map, the verb set excludes `assign-tags` for
catalog, **affordance ≠ enforcement** (a `_actions:false` matches a real `403`), and **omit-on-failure**
(OPA paused → no 5xx, no fabricated all-`false` map):

```bash
# The action-enrichment matrix (fixture aaaa…; rebuild BOTH app images first; NO OPA restart — zero Rego change):
cd scripts/postman && ./run-action-enrichment-matrix.sh
```

See [[ACTION-ENRICHMENT]]. This slice **extended** the Phase-5 `bulk` batch primitive (an `allow`-over-a-
list comprehension) to `catalog.rego`/`product.rego`/`team.rego` so every enriched type has it — additive,
decision-preserving, so the existing matrices are byte-identical; **restart OPA** after pulling those
policies for the first time (the `bulk` rule is new, though it changes no decision).

> The `team.rego` policy the user-service dogfoods is served by the shared OPA container — it lives in
> both `../example-user-management-service/src/main/resources/opa/policies/` (the source of truth) and
> `opa/policies/` (mounted into the rig's OPA). Restart OPA after editing it (`docker restart
> opa-abac-opa`) — `--watch` doesn't always reload.
>
> **Since Phase 6.7 (ADR 0015) `team.rego` is category-driven** — it expands the resolved role's category
> tokens through the **same** `data.permissions.effective_actions` + `data.permission_categories` the
> catalog uses (symmetric with `catalog.rego`), plus an owner-only-by-code fence for
> `team:define-roles`/`team:transfer-ownership`. So it now **depends on the shared expansion table**: the
> service bundle carries a verbatim copy of `permissions.rego` + `permission_categories.json` (byte-identical
> to the infra copies) so its isolated `opa test` resolves. Edit the `CONTROL`/`list-members` table in
> **both** copies, and **restart OPA** after any `team.rego`/table edit before running the e2e matrices.

## Multi-tenant isolation (Slice B4)

The **isolation** matrix proves the headline of B4 through the gateway: **team membership is the sole
access path** to a catalog, and the self-service flow (create catalog → create team → add members) is
safe. A fresh `catalog-editor` with no team sees an **empty** list (no realm-fallback leak); she creates
a catalog + team and then sees only **hers**; a member she adds sees **her** catalog (scoped, not his
own); a multi-team user sees the **union**; a non-member who deep-links another catalog's id gets **403**;
and a squat (`POST /teams` targeting someone else's catalog) is **denied 403** by the cross-service
ownership check. Needs the user-service rig (`ENABLE_USER_SERVICE=1`, which `ENABLE_SPA=1` implies) — the
three demo users **alice / bob / carol** are in `keycloak/realm-export.json`.

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
./deploy.sh build           # fresh app images carrying B4 (T1–T9)
docker restart opa-abac-opa # reload the B4 policy (T1 + the T9 type-level-gate fix)
cd scripts/postman && ./run-isolation-matrix.sh   # E1–E7, 20 assertions
```

The runner **self-resets** (it wipes the `Alice Co` / `Carol Co` catalogs + teams by name first), so it
is idempotent even though the matrix creates Alice's catalog **live** in E2. B4 removed the realm-role
fallback from the single-decision path, so **every type-level gate** (`list`/`create`/`assign-tags`) now
resolves the caller's role on the governing parent catalog via `@OpaPreAuthorize(roleResource…)` — a
non-member resolves no role and is denied. After this change the `permission-categories` and
`resource-resolution` matrices bind their fixture creators to a real catalog-WRITE role (the fallback that
used to let a bare realm user create is gone); re-running the **full** existing suite stays green
(resilience excepted — it needs the mutually-exclusive `ENABLE_RESILIENCE_STUB` profile below).

## Cross-service HTTP resilience (Slice B3) — opt-in

Off by default. `ENABLE_RESILIENCE_STUB=1 ./deploy.sh up` adds a **fault-injecting** stand-in for the
resolve endpoint and points the catalog's `role-source=http` at it (`http://resolve-stub:8080`, **not** the
real user-mgmt), so the catalog's resolve `CallGuard` sees a controlled outage. It proves the B3 headline
through the gateway: a **transient** blip recovering within budget → the protected request **succeeds**; a
**sustained** outage → it **still denies** (403, B2's wall un-breached).

```bash
ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up --pods 2
./deploy.sh build          # force the B3 app code into the pods
cd scripts/postman && ./run-resilience-matrix.sh   # flips the stub transient→down across two passes
```

The runner brings the stub up in each mode itself, so a single rig serves both passes. **No Rego change in
this slice → no OPA restart.** The stub is the smallest thing that injects "N-transient-then-recover" +
"stay-down" — no image build (runs the mounted script on `python:3.12-alpine`). See [[HTTP-RESILIENCE]] for
the mechanism.

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
| `compose.resilience-stub.yaml` + `resilience-stub/resolve_stub.py` | A tiny **fault-injecting** stand-in for the resolve endpoint (opt-in via `ENABLE_RESILIENCE_STUB=1`), for the Slice B3 resilience e2e. Returns N transient `503`s then the role (`STUB_MODE=transient`) or always `503` (`STUB_MODE=down`); the catalog's `role-source=http` points at it instead of the real user-mgmt. See the B3 section below. |
| `opa/policies/team.rego` | The team-management policy the user-service dogfoods (a copy of the service's source policy, mounted into the rig's OPA). |
| `apisix/config.yaml` | APISIX static config (plugins: prometheus, proxy-rewrite, response-rewrite, opentelemetry, opa, openid-connect). |
| `apisix/init-routes.sh` | Seed the `catalog-pool` upstream + `catalog-all` route (idempotent); adds openid-connect + opentelemetry + opa plugins (toggleable). |

## Ports (and why these, not the defaults)

| Port | What | Note |
|------|------|------|
| `5433` | Postgres | base `compose.yaml`; avoids a 5432 clash |
| `9085` | APISIX proxy | **not 9080** — a podman-machine `gvproxy` holds `:9080` on this host |
| `9180` | APISIX admin API | `deploy.sh`/`init-routes.sh` write upstream + route here |
| `28081..` | app pods (host) | **not the 18081 range** (a local podman-machine may forward `18081/18082`) — the `2xxxx` range avoids that common collision |
| `26686` | Jaeger UI | **not 16686** (often held by another local service) |
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
