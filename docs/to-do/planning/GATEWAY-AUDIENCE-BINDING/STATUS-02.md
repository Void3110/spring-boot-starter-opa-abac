---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# STATUS — T2: Audience in the plugin; the Lua guard deleted

**Status:** ✅ DONE (2026-10-28, collaborative build)

## What shipped

- **Realm (`infra/keycloak/realm-export.json`):** one inline `oidc-audience-mapper` named `gateway-audience`
  (`included.client.audience: catalog-gateway`, `access.token.claim: true`, `id.token.claim: false`,
  `introspection.token.claim: true`) on each of `catalog-gateway`, `catalog-spa`, `catalog-agent-readonly`,
  `catalog-agent-overreach`, `catalog-agent-revoked`. `catalog-directory` has none (the negative control). The
  export still has no `clientScopes` key, so Keycloak still creates its built-in scopes on import.
- **Gateway (`infra/apisix/init-routes.sh`):** the shared `openid-connect` fragment (T1's `OIDC_CONF`) gains
  `claim_validator.audience: {"required": true, "match_with_client_id": true}`. It now rides every
  authenticating route: the catalog catch-all, both user-service routes, `/mcp`. The Lua issuer guard
  (`ISSUER_GUARD`, `ISSUER_ALLOWED_LUA`) is deleted from the three blocks and from the script. Its comment block
  is rewritten as "the token contract at the edge": issuer and audience, both in the plugin, and why the
  case-insensitive scheme subtlety no longer applies. `internal-blocked` keeps its own
  `serverless-pre-function`, and the plugin stays in `config.yaml`'s list, whose comment now names only that use.
- **`infra/compose.keycloak.yaml`:** the issuer note now says the plugin checks `iss` itself. Before, it said an
  allowlist pre-function did. The rest of the header (the mapper, rotation, the host client) is T4's.
- **`scripts/postman/run-step-up-matrix.sh`:**
  - New preflight cells after E9h:
    - **E9i**: the `catalog-directory` token is refused on the catalog, user-service and `/mcp` surfaces, with
      the gateway's exact status and body.
    - **E9j**: the `catalog-gateway` token is admitted on the same three requests.
    - **The per-client drift guard (QA E6)**: five tokens, each `azp`-checked. Each must carry
      `aud ∋ catalog-gateway`; the directory's must not, or E9i would prove nothing.
  - New helpers: `token_aud_has` (string or array), `mint_service_token` (client_credentials, in-network) and
    `surface_call` (the three requests, status plus body).
  - The E9d/E9f comments and errors now name the plugin's allowlist, not the guard. The cased-scheme loop stays,
    as the regression for the bypass the guard once had.

## Tests

- `bash -n` on both scripts; `scripts/checks/check-shell-guards.py` → 28 scripts clean. It caught one unguarded
  `body="$(cat …)"` in the new block, and that line was fixed before the run.
- The realm edit was made by a JSON round-trip. That round-trip is byte-identical on the unmodified export, so
  the diff is exactly the five mapper objects.

### I1 — Keycloak 26.3.2 and RFC 8707 `resource` (raw)

Run on the T2 realm, re-imported. The code-flow arms ran the repo's miner, wrapped by a throwaway that adds
`resource` to the authorize URL and to the token request (`resource sent on authorize=True token=True`).

```
I1a password grant, catalog-gateway/editor, WITHOUT resource
{"aud": "catalog-gateway", "azp": "catalog-gateway", "iss": "http://keycloak:8888/realms/catalog-demo", ...}
I1b password grant, catalog-gateway/editor, WITH resource=http://localhost:9085/mcp
{"aud": "catalog-gateway", "azp": "catalog-gateway", "iss": "http://keycloak:8888/realms/catalog-demo", ...}
I1c code flow, catalog-spa/demo, WITHOUT resource
{"aud": "catalog-gateway", "azp": "catalog-spa", "iss": "http://keycloak:8888/realms/catalog-demo", ...}
I1d code flow, catalog-spa/demo, WITH resource on authorize AND token
{"aud": "catalog-gateway", "azp": "catalog-spa", "iss": "http://keycloak:8888/realms/catalog-demo", ...}
```

**Pass.** Keycloak ignores `resource` on both grant paths: the mapper's audience stands, and the token minted
with `resource` reaches a `200` through the gateway (I2c below). T3 proceeds as designed.

### I2 — APISIX 3.19 on the audience edge (raw)

```
stored claim_validator (route catalog-all):
{"audience": {"match_with_client_id": true, "required": true},
 "issuer": {"valid_issuers": ["http://keycloak:8888/realms/catalog-demo",
                              "http://localhost:9085/realms/catalog-demo",
                              "http://localhost:28888/realms/catalog-demo"]}}
plugins: ['cors', 'opa', 'openid-connect', 'opentelemetry', 'response-rewrite']   <- no serverless-pre-function

I2a catalog-directory (client_credentials): {"aud": "realm-management", "azp": "catalog-directory", ...}
  GET  /api/v1/catalogs        -> HTTP/1.1 403 Forbidden  Content-Type: text/plain; charset=utf-8  {"error":"mismatched audience"}
  GET  /api/v1/teams           -> HTTP/1.1 403 Forbidden  Content-Type: text/plain; charset=utf-8  {"error":"mismatched audience"}
  POST /mcp (initialize)       -> HTTP/1.1 403 Forbidden  Content-Type: text/plain; charset=utf-8  {"error":"mismatched audience"}
  apisix error.log: openid-connect.lua:1297: OIDC introspection failed: audience does not match the client id
I2b catalog-gateway/editor: aud shape: str catalog-gateway   GET /api/v1/catalogs -> 200
I2c the I1d token (code flow WITH resource):                 GET /api/v1/catalogs -> 200
```

Measured facts the later tickets rely on:
- **`aud` is a plain string on every legitimate token.** The realm's default roles put no other client's roles
  in the token, so the `roles` scope's audience-resolve adds nothing. The plugin's string arm (`conf.client_id ~=
  audience_value`) is the one exercised. `token_aud_has` reads either shape.
- **The refusal's body is JSON, but its `Content-Type` is `text/plain`** (the plugin's raw `return 403, body`). The
  cells compare the body text exactly, never a parsed media type.
- **The no-`aud` arm is cited from source, not minted:** `openid-connect.lua` 3.19 lines 1275–1280 return
  `403 {"error":"required audience claim not present"}` when `required` or `match_with_client_id` is set. No
  client of this realm mints a token without `aud`.

## Architecture review + refactor

Path: inline review in the collaborative session. The multi-lens `/deep-review` after T4 is the slice's first
whole-delivery review.

- **Found and applied: `required: true` is stated explicitly.** 3.18+ already implies it from
  `match_with_client_id`; being explicit means the audience-less refusal no longer rests on that version detail.
  One extra key; recorded under Decisions.
- **Fail-closed sweep:**
  - The audience check only narrows.
  - Deleting the guard removes no refusal. A foreign issuer is still 401, now from the plugin itself.
  - The E9f controls (the cased-scheme loop included) ran with no pre-function on any route, which is the first
    run that proves the plugin's own issuer check. In T1, the guard still answered first.
  - `/internal/*` still answers 404.
  - The browser-flow session path carries the gateway client's own token. That token has the mapper and is
    encrypted under `session.secret`, so no unchecked bearer reaches an upstream.
- **Considered and left alone:** the E9i/E9j cells stay in the step-up runner, per the decomposition, rather than
  getting their own runner. That runner already owns the gateway's token-edge preflight (E9a–h). A separate
  runner would need a second MCP-flavoured rig check for three requests.

## Integration / e2e

Run 2026-10-28 as one local sequence (a log per step) on the re-imported T2 realm. Fresh images first: the
catalog + MCP build, and the user-service image rebuilt. One earlier rebuild attempt failed on `UnknownHostException:
github.com` inside `docker build`, a transient network fault; the next attempt passed.

- **QA E4 / E5 / E6: the step-up runner's preflight, all green.**
  - **E9i:** the `catalog-directory` token → `403 {"error":"mismatched audience"}` on `GET /api/v1/catalogs`,
    `GET /api/v1/teams` and an MCP `initialize`.
  - **E9j:** the `catalog-gateway` token → `200` on the same three.
  - **The drift guard:** `aud ∋ catalog-gateway` on all five legitimate clients' tokens, absent from the
    directory's.
  - **E9a–h unchanged and green with no pre-function on any `openid-connect` route.** The forged-Host token is
    refused `401` under `Bearer`, `BEARER` and `bEaReR`, and on `/api/v1/teams` and `/mcp`; the gateway-origin
    issuer is admitted. Before this ticket the Lua guard answered first, so this run is the first proof of the
    plugin's own issuer check.
  - The demo-world runner, which drives the step-up runner, re-proved them in passing.
- **QA E5, the `/internal/*` block:** `GET /internal/catalog/{id}/created-by` through the gateway → `404`.
- **No pre-function on any authenticating route.** The Admin API's route list shows every `openid-connect` route
  with `audience = {"required": true, "match_with_client_id": true}` and no `serverless-pre-function`;
  `internal-blocked` keeps its own.
- **The full suite on the three rigs: 769 newman assertions, 0 failed**, plus the SPA smoke 4/4.
  - rig 1, the resilience stub: resilience 5/0.
  - rig 2, `ENABLE_MCP=1`, seeded: run-tests 22/0, run-matrix 21/0, action-enrichment 19/0, agent-tool 92/0,
    demo-world 176/0, filter 29/0, hierarchy-list 18/0, hierarchy 4/0, isolation 20/0, pagination 27/0,
    permission-categories 31/0, production-tier 73/0, step-up 69/0, supervised-scope 55/0, tag 46/0, team 24/0.
  - rig 3, the OIDC-only quickstart: run-tests 19/0, run-matrix 19/0.
  - It is the same total as T1. T2's cells live in the step-up runner's shell preflight, not in newman.
  - **Two runners need a note:**
    - **`run-resource-resolution-matrix.sh` is still red, for the pre-existing reason in STATUS-01:** its fixture
      create answers the catalog service's `403 ACCESS_DENIED` (ADR 0034). That is an application refusal, not
      the gateway's. Its fix is a separate task.
    - **`run-agent-tool-matrix.sh` failed once and passed twice.** In the suite run, the E7 drill's first request
      after recreating the MCP pod (gate OFF) answered `504` after about 60 s. The token was admitted, since a
      refusal would be a `401`/`403`; the gateway then timed out on the upstream. The folder's five dependent
      assertions failed with it, because the session was never assigned. Re-run twice on a fresh main rig, same
      code: **92/0 both times**. The 769 total counts the re-run, and the flake is recorded below.
- **The browser-flow rig** (`ENABLE_SPA=0 ENABLE_MCP=1`):
  - All four `openid-connect` routes were accepted with `bearer_only=false`, `session` present and the audience
    block: `catalog-all`, `mcp`, `usermgmt-teams`, `usermgmt-users`.
  - A token-less `GET /api/v1/catalogs` → `302`.
  - **On the bearer path,** a `catalog-directory` bearer → `403 {"error":"mismatched audience"}` and a
    `catalog-gateway` bearer → `200`. A bearer is audience-checked in browser-flow mode too, which is ADR 0035's
    "every route". QA E12 re-proves it after T3.

## Decisions

- **`claim_validator.audience.required: true` alongside `match_with_client_id: true`** (above).
- **No source change, so no Java gate:** T2 touches no `.java` (realm, gateway script, one runner, comments),
  so `./gradlew build` and the local Sonar gate do not apply.
- **The E7 `504` is recorded as a rig flake, not fixed here.** It reproduced in 0 of 2 re-runs on the same
  code. It happened after the token was admitted, and T2 changes nothing on the upstream side. The suspected
  cause is a pooled gateway connection to the recreated pod's published port, and that is a hypothesis: the
  rig was torn down before APISIX's log could be read. Mulch `opa-abac-rig-deploy-ops` records it so the next
  run does not chase it as an audience regression.
- **`infra/compose.keycloak.yaml`'s issuer note was corrected here, not in T4.** It described the mechanism this
  ticket deletes, and T1+T2 is the independently landable subset. T4 still owns the rest of that header.

## Commit

`feat(gateway): audience-bound gateway tokens — a gateway-audience mapper per client, claim_validator.audience on every openid-connect route, the Lua issuer guard deleted (GATEWAY-AUDIENCE-BINDING T2)`
