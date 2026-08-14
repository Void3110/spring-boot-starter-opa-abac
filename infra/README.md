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
#
# Supervised-scope personas (ADR 0029 — password == username for each; see the section below):
#   sup-anna, sup-victor      -> catalog-viewer + unit-supervisor  (members of NO team; the headline)
#   sup-noreports             -> catalog-viewer + unit-supervisor  (the claim with ZERO reports: E10)
#   pm-bob, pm-carol, pm-dave, pm-erin -> catalog-editor           (the reports whose seats propagate)
#   outsider-eve              -> catalog-viewer                    (neither member nor supervisor: E3)
#   The `unit-supervisor` realm role is a UX-only eligibility marker and grants NOTHING.

# no token -> 302 redirect to Keycloak login (unauth_action: auth)
curl -s -o /dev/null -w '%{http_code}\n' localhost:9085/actuator/health        # 302

# get a token IN-NETWORK (issuer must match what APISIX validates against), then call:
TOKEN=$(docker run --rm --network opa-abac-example_default curlimages/curl -s \
  -X POST http://keycloak:8888/realms/catalog-demo/protocol/openid-connect/token \
  -d client_id=catalog-gateway -d client_secret=catalog-gateway-secret \
  -d grant_type=password -d username=demo -d password=demo | sed 's/.*"access_token":"//;s/".*//')
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:9085/actuator/health  # 200
```

> **Issuer gotcha:** Keycloak is hostname-aware — the `iss` claim follows the request's Host
> header (`KC_HOSTNAME_STRICT=false`; see `compose.keycloak.yaml`'s issuer note). In-network mints
> carry `http://keycloak:8888`; host-port mints carry `http://localhost:28888`. The openid-connect
> plugin validates the signature against the realm JWKS and does not itself enforce `iss`
> (measured 2026-08-14), so the routes carry an **issuer-allowlist pre-function** beside it: only
> those two rig authorities pass, and a forged-Host mint through the published port is refused 401
> (the step-up runner's E9 foreign-issuer control pins it). Mint in-network: it is the canonical
> authority, and the miner presents it from the host for the same parity.

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

`ENABLE_SPA=1` also serves the **packaged SPA itself**: `deploy.sh` builds the bundle host-side
(`npm ci` on first/stale install + `npm run build` — npm on PATH is a hard prerequisite) and starts
an nginx sidecar (`compose.spa.yaml` + `spa/default.conf`, no published host port). `init-routes.sh`
fronts it with two **public GET/HEAD** routes at priority 40 — `spa-index` (`/`, `/index.html`, the
favicons) and `spa-assets` (`/assets/*`) — above the catalog catch-all, below the Keycloak proxy and
the usermgmt prefixes, so no protected surface is shadowed. On a re-up **without** the flag, both the
routes and the sidecar are positively torn down (the gateway's posture at `/` follows this run's
flags, not deploy history). Open the demo at `http://localhost:9085`, then seed:
`scripts/postman/seed-demo-data.sh`. The Vite dev server on `:3000` (`npm run dev`) stays the
edit-refresh loop.

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

**Two pods (HA parity with catalog).** The user-service runs as **two instances** — `usermgmt` (:28090)
and `usermgmt-2` (:28092) — **sharing one Postgres** (`usermgmt-postgres`). The gateway `usermgmt-pool`
round-robins the public self-service API (`/api/v1/teams*`, `/api/v1/users*`) across both, so concurrent
membership/role mutations that land on *different* pods contend on the *same* rows and serialize through
the JPA `@Version` / `mutate()` locked-write path — exactly as a real 2-replica deployment would. The
`X-Upstream-Addr` response header shows which pod served each request. (The catalog's internal
`/internal/effective-role` read is pinned to `usermgmt-1`; both pods read the same DB, so the answer is
identical — the concurrency-critical *writes* are the ones spread across both.) Override the pool with
`USERMGMT_NODES=host.docker.internal:28090` for a single-pod rig.

> **Cross-pod concurrency, verified (2026-07-13):** 24 concurrent role-changes on one membership across
> both pods → all `200`, final state a single clean role (no torn/lost update, no 5xx); 20 concurrent
> add/remove of one user → only legitimate `201/409/204/404` (the shared-DB unique constraint + idempotent
> delete), exactly **1** final row (no double-apply). The HA topology serializes correctly.

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
# user-mgmt: 2 pods http://localhost:28090 + http://localhost:28092 (shared DB :5434, gateway round-robins both);
# resolve API at /internal/effective-role.

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
> `/api/v1/teams*`, `/api/v1/users*`, `/api/v1/catalogs*` (catch-all), the Keycloak `/realms/*` +
> `/resources/*` paths (`ENABLE_SPA`), `/mcp*` (`ENABLE_MCP`), and the packaged-SPA static paths
> `/` + `/index.html` + favicons + `/assets/*` (GET/HEAD only, `ENABLE_SPA`).
> The user-service's `/internal/**` (resolve, governed-targets, bootstrap) and the
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

## Supervised read scope (Slice A of the supervisor epic, ADR 0029)

The **supervised-scope** matrix proves the second, **disjoint** access path beside team membership: a
**unit manager who is a member of no team** sees the catalogs of the teams their reports own or manage,
derived per request from a **reporting relation** and never from a realm grant. Read-only and live.
The contents (categories, products) **open on a non-production root and close by the `env` tier**
since slice B (PRODUCTION-TIER, ADR 0030 §1–4): absent/unproven and `production` roots stay closed,
untagged and `staging` roots open — proven by `run-production-tier-matrix.sh` (see below); the A
matrix's E6 cells assert the open half.

The personas are new realm accounts in `keycloak/realm-export.json` — **`sup-anna`**, **`sup-victor`**,
**`sup-noreports`**, **`outsider-eve`**, **`pm-bob`**, **`pm-carol`**, **`pm-dave`**, **`pm-erin`** — plus
the **UX-only `unit-supervisor` realm role**, which grants **nothing** (E10 asserts exactly that: the
claim with zero reports sees an empty page). Because they are new, the rig must come **down** first so
Keycloak re-imports the realm.

```bash
./deploy.sh down                                    # so Keycloak RE-IMPORTS the realm (new personas)
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
./deploy.sh build                                   # catalog image (T4/T5)
docker build -t opa-abac-usermgmt:local -f example-user-management-service/Dockerfile .   # T1-T3
cd scripts/postman && ./run-supervised-scope-matrix.sh
```

The runner **restarts OPA itself** before minting tokens: both supervisor slices land narrow Rego
changes in `category.rego` + `product.rego` (slice A's ADR 0031 confinement clauses, slice B's
provenance-scoped tier denies), and `--watch` does not reliably reload — a stale policy would pass a
boundary cell for the wrong reason.

**Two passes, one rig.** The outage cell (E8) faults the supervised edge **alone**: T4 gave it its own
`catalog.user-service.supervised-base-url` (env `CATALOG_USER_SERVICE_SUPERVISED_BASE_URL`, defaulting to
the shared user-service URL), so the runner recreates just the catalog pods with it repointed at a dead
port, asserts the degrade, and restores the rig on exit. **Do not use `ENABLE_RESILIENCE_STUB=1` for
this** — that repoints the *whole* user-service the rest of the matrix needs. The proof it is confined:
during the faulted pass a supervisor degrades to their own memberships (an empty page here) while an
ordinary member's page is **unchanged**.

## Production tier (Slice B of the supervisor epic, ADR 0030 §1–4)

The **production-tier** matrix proves how far the supervised path's oversight goes: an
**operator-managed `env` tag** on the governing catalog (written only through the in-network
`/internal/bootstrap/resource-tags` endpoint — the gateway 404s it) is carried to child decisions as
`input.resource.root_attributes`, and two provenance-scoped `denied` clauses per leaf policy close
**production** and **unproven** (absent) tiers while untagged/staging contents stay open. Same
personas, zero realm diff. The headline cells: the tier flips **live** in both directions (E4), and
nothing the supervised population can do through the public API moves the tag (E5 — each write
answers 409 `TAG_OPERATOR_MANAGED`).

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
cd scripts/postman && ./run-production-tier-matrix.sh
```

The runner restarts OPA before minting tokens (the tier denies are policy, `--watch` is unreliable)
and needs the catalog service's **published host port** for the operator curl (`BASE_PORT=28080` +
pod index — `docker ps` shows `catalog-1` at `28081`).

## Step-up elevation (Slice C of the supervisor epic, ADR 0030 §5–9)

### The realm changes (and the down-first re-import they force)

Slice C opens the production tier behind a **fresh second factor**, so the realm export
(`keycloak/realm-export.json`) grows four things — all declarative, all fixture-only:

| Change | Why |
|---|---|
| `basic` + `acr` added to `defaultClientScopes` of **`catalog-spa` and `catalog-gateway`** | The literal scope list *replaces* Keycloak's built-in assignment, which left the built-in `basic` scope (it carries the `auth_time` session-note mapper) and the `acr` scope assigned to **no** client — ADR 0030 §Context's diagnosis. Restoring them is the whole claims fix; no custom mapper is needed. |
| Realm attribute `acr.loa.map` = `{"aal1":1,"aal2":2}` | Names the two levels the policy's `data.step_up.loa` mirrors. |
| Browser flow **`browser-stepup`** (bound as the realm browser flow): `level-1` conditional subflow (password) then `level-2` conditional subflow (`Condition - Level of Authentication` **level 2, max age 300** + **OTP Form**, both REQUIRED) | Demands TOTP only when the client asks for LoA 2 (`acr_values=aal2` + `max_age`). An ordinary login is unchanged: password only, `acr: aal1`. |
| `sup-anna` gains a seeded **`otp` credential** with a fixed, committed fixture secret | So the e2e can compute codes offline. It is public **on purpose** — this is a demo realm with fixture identities, exactly like every committed fixture password here. No other persona has a factor: a supervisor who cannot satisfy level 2 simply never elevates (fail-closed). |

> **One freshness window, stated twice.** The level-2 condition's **max age (300 s)** and the policy
> data's **`step_up.max_age` (300 s, `infra/opa/policies/step_up.json`)** are the *same* window. Both
> are JSON-hosted values and JSON holds no comments, so this table is the cross-reference: **change one,
> change the other.** The `skew` (30 s) is decision-side only. The e2e side follows the data, not a
> copy: the step-up matrix's challenge cells read the window off the live `data.step_up` (the runner
> passes `shipped_max_age`/`drill_max_age` into the collection) — but
> `scripts/postman/production-tier-matrix.postman_collection.json`'s seven C-flip cells assert the
> challenge **literally** (`max_age="300"`), so a window change must update those cells too. The same
> goes for `step_up.required_acr` (`aal2`): the policy's challenge reads it from data, and the same
> seven cells plus the realm's `acr.loa.map` name it literally.

Two non-obvious things, both measured on Keycloak 26.3.2 during T1 and worth keeping:

- **The level-1 subflow is load-bearing, not decoration.** `Condition - Level of Authentication`
  evaluates **true whenever the session has not yet reached any level**, whatever the client asked
  for — so a level-2 condition on its own demands TOTP on *every* fresh login. Wrapping the password
  in a level-1 conditional subflow is what lets an ordinary login settle at LoA 1 and leaves level 2
  for clients that actually request it.
- **A partial `authenticationFlows` block is fine.** Declaring only the custom flows does **not**
  suppress Keycloak's built-ins: the import still creates `browser`, `registration`, `direct grant`,
  `reset credentials`, `clients`, `docker auth` and `first broker login`, so the other flow bindings
  keep resolving.

**The rig must come `down` first** — Keycloak only imports the realm into a fresh container, so a
running rig keeps the old flow, the old scopes and a factor-less `sup-anna`:

```bash
./deploy.sh down                                    # so Keycloak RE-IMPORTS the realm
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
```

ROPC is untouched and stays structurally unable to carry `auth_time` (the mapper reads a user-session
note the direct grant never sets), so every existing runner's `mint_token()` keeps working exactly as
before — it just also sees `acr: aal1` now, which nothing asserts on. Step-up cells need the scripted
code flow instead.

> **One measured exception to "ROPC is untouched": `sup-anna` herself.** Keycloak's **direct-grant**
> flow demands a code from any identity that owns a factor, so a plain ROPC mint for her now answers
> `invalid_grant / Invalid user credentials` — which reads as a wrong password and is not. The three
> runners that mint her (`run-supervised-scope-matrix.sh`, `run-production-tier-matrix.sh`,
> `run-step-up-matrix.sh`) pass an `otp` parameter computed by `mint-code-flow-token.py --print-otp`,
> so the fixture secret and the RFC 6238 parameters live in exactly one place. Every other persona is
> unaffected.

### The step-up matrix

```bash
./deploy.sh down                                   # the realm changed — Keycloak must RE-IMPORT it
./deploy.sh build                                  # fresh app images BEFORE the up — `up` reuses an
                                                   # existing image, so building after leaves the pods
                                                   # on the pre-C code with nothing to tell you so
ENABLE_MCP=1 ./deploy.sh up --pods 2               # force-enables OIDC + OPA + the user-service
cd scripts/postman && ./run-step-up-matrix.sh
```

`ENABLE_MCP=1` is a **superset** of what the REST cells need, so the whole set runs on one flavour
rather than two. The runner restarts OPA itself (T2 added `step_up.json` and amended the tier denies;
`--watch` is unreliable) and then polls a **real decision** rather than `/health` — OPA answers
`/health` before the bundle is loaded, and a decision asked in that window is undefined, which every
fail-closed client here reads as *deny*. Anna's `aal1`/`aal2` tokens come from the scripted code flow;
every other persona is minted in-network as usual. Fixture prefix `f00d…`, torn down on green.

Two rig-level things the runner owns rather than assumes:

- **The freshness drill overrides the LEAF path.** `PUT /v1/data/step_up/max_age` with body `5` — OPA's
  data `PUT` is create/overwrite and **not** merge, so `PUT /v1/data/step_up {"max_age":5}` would take
  `loa` and `skew` with it, leaving every token unelevatable and producing an instant, vacuous 401.
  The runner asserts `loa` and `skew` survived, runs a **positive control** (a fresh elevation still
  opens under the 5-second window), waits `> max_age + skew`, and only then asserts the expiry.
  Restoration is a plain **OPA restart** in an `EXIT` trap — the file bundle is the source of truth —
  so a run that dies mid-drill still leaves the shipped 300-second window behind it.
- **The audit assertion is a log grep, not a cell.** Both `opa.abac.audit` events are grepped off
  **every** catalog pod (the pool round-robins, so the challenge and the elevated read can land on
  different ones), and `STEP_UP_CHALLENGED` is asserted to carry **no** `authTime` — at challenge time
  the subject is precisely not elevated.

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
| `compose.spa.yaml` + `spa/default.conf` | The **packaged demo SPA** (opt-in via `ENABLE_SPA=1`): nginx serving the built `example-demo-ui/dist` bundle, fronted through the gateway by the public `spa-index`/`spa-assets` routes (no published host port). `deploy.sh` builds the bundle host-side first; torn down on a re-up without the flag. |
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
- **Gateway OPA plugin timeout = 1000 ms** (`init-routes.sh`, integer milliseconds; APISIX default
  is 3000). Under a *hung* OPA the plugin times out then **denies** (typed 403) — the 7.2 fault run
  measured that deny at ~3.0 s with the default; 1000 ms bounds it at ~1.0 s (PERFORMANCE.md §4).
  Semantics unchanged: a timeout still denies; only the wait shortens. **Tune against your OPA's
  loaded p99, not its idle latency**: this OPA also serves the app's compile + bulk evals, and a
  500 ms timeout produced steady-state 403s at 10 req/s under list load (measured, Slice 7.3).
