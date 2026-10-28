---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# GATEWAY-AUDIENCE-BINDING — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. **U** = unit / slice tests — JUnit in
> `example-mcp-server` (`ApplicationContextRunner` for the startup contract, MockMvc for the endpoint), vitest in
> `example-demo-ui`. **I** = a probe on the live rig whose raw evidence is recorded in STATUS before the ticket
> relies on it. **E** = e2e through the gateway — newman cells (every `pm.test` asserts the actual answer:
> status **and** body or claim, never shape alone), the runners' shell preflights, and one manual runbook case.
> A cell named for a refusal is paired with an allow control on the same path (mx-0520eb), so a refusal that
> bites everything cannot pass. The runners' own cell numbers differ from these ids — see the mapping table in
> [[01-DECOMPOSITION]].

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | Discovery off is a legitimate state | both `example.mcp.oauth.protected-resource.*` values unset → the context starts; `GET` on both paths → `404`; exactly one startup WARN naming both keys | T3 |
| U2 | Half-configured fails startup | `resource` set and `authorization-server` unset, and the reverse → the context fails; the message names the missing key | T3 |
| U3 | A malformed value fails startup | each of: a relative URL, a non-`http(s)` scheme, a fragment, a `resource` whose path is not `/mcp` (incl. `/mcp/` and `/`) → the context fails naming the key and the reason | T3 |
| U4 | The document is exact | both set → `GET /.well-known/oauth-protected-resource` and `…/oauth-protected-resource/mcp` → `200`, `application/json`, body exactly `{"resource":…,"authorization_servers":[…],"bearer_methods_supported":["header"]}` — no `scopes_supported`, no other key | T3 |
| U5 | Never request-derived | the U4 requests with `Host: attacker.invalid`, `X-Forwarded-Host: attacker.invalid`, `X-Forwarded-Proto: https` → the byte-identical U4 body | T3 |
| U6 | Exactly two paths, `GET` only | the chain permits only `GET` on the two paths: unauthenticated `GET /.well-known/oauth-protected-resource/other`, `GET /.well-known/openid-configuration` and `POST` on either document path → `403` (the chain's default entry point for an unauthenticated request; the test pins the status it observes and fails on `200`); an unauthenticated `POST /mcp` still refused | T3 |
| U7 | The host agent is wired from `application.yml` | the shipped `application.yml` binds four agent profiles incl. `agent-host` (`[READ]`, `[view, list]`, four tools, `low`), `opa.abac.mcp.identity.agent-clients` = the four agent clients and `attribute-claims` = `[act_chain, azp]` → the context starts (`ApplicationYamlBindingTest` updated to these); the same with `azp` removed by a test property → the existing wiring check fails startup | T3 |
| U8 | The SPA refreshes once, however many callers | vitest: `freshUser()` called N times concurrently on an expired user with a refresh token → `signinSilent()` called **exactly once** and every caller gets the same renewed user; a failed refresh → every caller gets `null`, and the next call after it starts a new refresh | T3 |

## Rig probes (I*) — measured, recorded raw, before they are relied on

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | What Keycloak 26.3.2 does with RFC 8707 `resource` | tokens minted **with** `resource=http://localhost:9085/mcp` on authorize and token, and **without** → each `aud` recorded raw. Pass = both contain `catalog-gateway`. **Fail = T3 stops** (every MCP client sends `resource`); T2 still lands; ADR 0035 §1/§5 revisited with the maintainer | T2 |
| I2 | What APISIX 3.19 answers on the audience edge, on what this realm mints | through a `match_with_client_id` route: the `catalog-directory` token (expected `aud: realm-management`) → status + body recorded (expected `403 {"error":"mismatched audience"}`); a legitimate token after the mapper → `200`, its `aud` shape (string or array) recorded. The no-`aud` arm is cited from source (`openid-connect.lua` 3.19 lines 1275–1280 → `403 {"error":"required audience claim not present"}`) — no client of this realm mints one | T2 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | The bump changes nothing | on APISIX 3.19.0, with the Lua guard and `valid_issuers` both present: the **full suite** green — all nineteen runners on the user-service rig with `ENABLE_MCP=1 ENABLE_RESILIENCE_STUB=1` **exported**, plus `run-tests.sh` and `run-matrix.sh` on the OIDC-only rig ([[01-DECOMPOSITION]] §The full suite) | T1 |
| E2 | Traces still reach Jaeger | a request through the gateway → an `apisix` service span in Jaeger (the opentelemetry plugin metadata replaced `plugin_attr`) | T1 |
| E3 | The browser-flow mode boots | the OIDC-only rig (`ENABLE_SPA=0`) → every `openid-connect` route accepted by the Admin API (the `session.secret` requirement met); a token-less `GET /api/v1/catalogs` → `302` to the realm's login, as on 3.11.0 | T1 |
| E4 | A token for another audience is refused on every surface (step-up cell **E9i**) | the `catalog-directory` service-account token → the I2-measured refusal (expected `403` `{"error":"mismatched audience"}`), never `200`, on **each** of: `GET /api/v1/catalogs` (catalog), `GET /api/v1/teams` (user-service), an MCP `initialize` `POST /mcp` | T2 |
| E5 | The allow control per surface, and the issuer contract with the guard gone (step-up cell **E9j** + E9a–h) | a `catalog-gateway` password-grant token → `200` on the same three requests as E4; the E9 issuer controls (foreign issuer refused, gateway origin admitted, the `BEARER` spelling) green with the **issuer guard** removed from every `openid-connect` route; `GET /internal/catalog/{any id}/created-by` through the gateway → `404` (the `internal-blocked` route keeps its own `serverless-pre-function`) | T2 |
| E6 | Every legitimate client carries the audience; the directory does not | tokens from `catalog-gateway`, `catalog-agent-readonly`, `-overreach`, `-revoked` (password grant) and `catalog-spa` (the miner, legacy mode) → each decoded `aud` contains `catalog-gateway`; `catalog-directory`'s does not (the per-client-mapper drift guard) | T2 |
| E7 | The document through the gateway (agent cell **E13**) | unauthenticated `GET http://localhost:9085/.well-known/oauth-protected-resource/mcp` and `…/oauth-protected-resource` → `200` with `resource` = `http://localhost:9085/mcp` and `authorization_servers` = `["http://localhost:9085/realms/catalog-demo"]`; the same with `Host: attacker.invalid` → the identical body; `POST` on the document path and `GET …/oauth-protected-resource/other` → `401` (they fall to the catch-all, bearer-only in SPA mode), not the document | T3 |
| E8 | The whole discovery flow mints an agent token (agent cell **E14**, minted by the runner) | `mint-code-flow-token.py --discover http://localhost:9085/mcp --client catalog-mcp-host --redirect-uri http://localhost:33418/callback --user demo --field access_token,refresh_token` → exit 0; stderr records which AS-metadata URL answered (expected the third, OIDC appended); the access token: `aud ∋ catalog-gateway`, `azp = catalog-mcp-host`, `act_chain = ["agent-host"]`, `iss = http://localhost:9085/realms/catalog-demo` | T3 |
| E9 | The host agent is narrowed on the real login path (agent cell **E15**) | with the E8 token: `tools/list` = exactly `[get_catalog, list_catalogs, list_categories]` by name; `tools/call get_product` on a product `demo` may read → the structured denial `layer: tool-gate`, `code: tool-gate-denied` (get_product is LISTED in the profile, so the risk cap is the only reason); the same product read by `demo` over REST → `200` (the contrast that proves the agent branch decided) | T3 |
| E10 | The host's refresh tokens rotate (agent cell **E16**, runner-driven) | with the E8 refresh token, through the gateway origin as the host (`POST http://localhost:9085/realms/catalog-demo/protocol/openid-connect/token`, `client_id=catalog-mcp-host`) → `200` and a **different** refresh token; replaying the first → `400` `invalid_grant` with `error_description` "Maximum allowed refresh token reuse exceeded" (not merely any `invalid_grant`) | T3 |
| E11 | The SPA client's refresh rotates too (step-up cell **E9k**) | a `catalog-spa` access + refresh pair from the miner's legacy mode; refresh **in-network** (as E9h does) → `200` and a different refresh token; replay the first → `invalid_grant` "Maximum allowed refresh token reuse exceeded". `run-spa-auth-smoke.sh` stays green as the bearer-posture control (it never refreshes — the concurrency side is U8) | T3 |
| E12 | The MCP surface without SPA mode | `ENABLE_SPA=0 ENABLE_MCP=1 ./deploy.sh up --pods 2` → the `/realms/*` passthrough present; token-less `POST /mcp` → `401` (not a `302`); E8 succeeds on that rig; a `catalog-directory` bearer on `GET /api/v1/catalogs` → the audience refusal there too (the bearer path is audience-checked in browser-flow mode) | T3 |
| E13 | A real MCP client logs in (manual runbook) | Claude Code registered against `http://localhost:9085/mcp` with `--client-id catalog-mcp-host --callback-port 33418`: the login completes and the client lists exactly the three low-risk tools — executed once, the client version and the observed result recorded in STATUS-04 | T4 |
