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

> Concrete cases; each becomes a ticket's *Acceptance*. **U** = unit / slice tests in `example-mcp-server`
> (JUnit; `ApplicationContextRunner` for the startup contract, MockMvc for the endpoint). **I** = a probe on
> the live rig whose raw evidence is recorded in STATUS before the ticket relies on it. **E** = e2e through
> the gateway — newman cells (every `pm.test` asserts the actual answer: status **and** body or claim, never
> shape alone), the runners' shell preflights, and one manual runbook case. A cell named for a refusal is
> paired with an allow control on the same path (mx-0520eb), so a refusal that bites everything cannot pass.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | Discovery off is a legitimate state | both `example.mcp.oauth.protected-resource.*` values unset → the context starts; `GET` on both paths → `404`; exactly one startup WARN naming both keys | T3 |
| U2 | Half-configured fails startup | `resource` set and `authorization-server` unset, and the reverse → the context fails; the message names the missing key | T3 |
| U3 | A malformed value fails startup | each of: a relative URL, a non-`http(s)` scheme, a fragment, a `resource` whose path is not `/mcp` (incl. `/mcp/` and `/`) → the context fails naming the key and the reason | T3 |
| U4 | The document is exact | both set → `GET /.well-known/oauth-protected-resource` and `…/oauth-protected-resource/mcp` → `200`, `application/json`, body exactly `{"resource":…,"authorization_servers":[…],"bearer_methods_supported":["header"]}` — no `scopes_supported`, no other key | T3 |
| U5 | Never request-derived | the U4 requests with `Host: attacker.invalid`, `X-Forwarded-Host: attacker.invalid`, `X-Forwarded-Proto: https` → the byte-identical U4 body | T3 |
| U6 | Exactly two paths, `GET` only | `GET /.well-known/oauth-protected-resource/other` and `GET /.well-known/openid-configuration` → not the document (the chain's `authenticated()` refuses an unauthenticated caller); `POST` on either path → not `200`; the chain still refuses an unauthenticated `POST /mcp` | T3 |
| U7 | The host agent is wired | the example context binds four agent profiles incl. `agent-host` (`[READ]`, `[view, list]`, four tools, `low`), `agent-clients` lists the four agent clients and `azp` is copied → the context starts; the same with `azp` NOT copied → the existing wiring check fails startup (regression pin) | T3 |

## Rig probes (I*) — measured, recorded raw, before they are relied on

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | What Keycloak 26.3.2 does with RFC 8707 `resource` | a `catalog-gateway` code-flow / password-grant token minted **with** `resource=http://localhost:9085/mcp` on authorize and token, and one **without** → `aud` of each recorded; the binding holds only if both contain `catalog-gateway` (expected: `resource` ignored, `aud` from the mapper) | T2 |
| I2 | What APISIX 3.19 answers on the audience edge | through a route carrying `match_with_client_id`: a token whose `aud` is an array containing `catalog-gateway` (+ e.g. `account`) → admitted; an `aud` without it → status + body recorded (expected `403 {"error":"mismatched audience"}`); a token with no `aud` → status + body recorded (expected `403 {"error":"required audience claim not present"}`) | T2 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | The bump changes nothing | on APISIX 3.19.0, with the Lua guard and `valid_issuers` both present: **every** runner green — `run-tests.sh` and the matrices (`run-*-matrix.sh`, `run-spa-auth-smoke.sh`), on the default rig and with `ENABLE_MCP=1` | T1 |
| E2 | Traces still reach Jaeger | a request through the gateway → an `apisix` service span in Jaeger (the opentelemetry plugin metadata replaced `plugin_attr`) | T1 |
| E3 | The browser-flow mode boots | `ENABLE_SPA=0 ./deploy.sh up` → every `openid-connect` route accepted by the Admin API (the `session.secret` requirement met); a token-less `GET /api/v1/catalogs` → `302` to the realm's login, as on 3.11.0 | T1 |
| E4 | A token for another audience is refused at the edge (step-up runner **E9i**) | a `catalog-directory` service-account token through the gateway → the I2-measured refusal (expected `403`, body `{"error":"mismatched audience"}`), never `200` | T2 |
| E5 | A gateway token is admitted, and the issuer contract holds with the guard gone (**E9j** + E9a–h) | a `catalog-gateway` password-grant token → `200`; the E9 issuer controls (foreign issuer refused, gateway origin admitted, the `BEARER` spelling) green with no `serverless-pre-function` on any route | T2 |
| E6 | Every legitimate client carries the audience; the directory does not | tokens from `catalog-gateway`, `catalog-agent-readonly`, `-overreach`, `-revoked` (password grant) and `catalog-spa` (the miner) → each decoded `aud` contains `catalog-gateway`; `catalog-directory`'s does not (the per-client-mapper drift guard) | T2 |
| E7 | The document through the gateway (agent matrix **E13**) | unauthenticated `GET http://localhost:9085/.well-known/oauth-protected-resource/mcp` and `…/oauth-protected-resource` → `200` with `resource` = `http://localhost:9085/mcp` and `authorization_servers` = `["http://localhost:9085/realms/catalog-demo"]`; the same with `Host: attacker.invalid` → the identical body; `POST` → not `200`; `GET …/oauth-protected-resource/other` → not the document | T3 |
| E8 | The whole discovery flow mints an agent token (agent matrix **E14**, minted by the runner) | `mint-code-flow-token.py --discover http://localhost:9085/mcp --client catalog-mcp-host` → succeeds; stderr records which of the three AS-metadata URLs answered; the token: `aud ∋ catalog-gateway`, `azp = catalog-mcp-host`, `act_chain = ["agent-host"]`, `iss = http://localhost:9085/realms/catalog-demo` | T3 |
| E9 | The host agent is narrowed on the real login path (agent matrix **E15**) | with the E8 token: `tools/list` = exactly `[get_catalog, list_catalogs, list_categories]` by name; `get_product` → a `tool-gate` denial (`layer: tool-gate`, the risk-tier code); the **same human** reads that product over REST → `200` (the contrast that proves the agent branch decided) | T3 |
| E10 | Refresh tokens rotate (agent matrix **E16**, runner-driven) | refresh with the E8 refresh token → `200` and a **different** refresh token; replaying the first → `400` `invalid_grant` | T3 |
| E11 | The SPA keeps working with rotation on | `run-spa-auth-smoke.sh` green on the re-imported realm (oidc-client-ts stores each new refresh token) | T3 |
| E12 | The MCP surface without SPA mode | `ENABLE_SPA=0 ENABLE_MCP=1 ./deploy.sh up` → the `/realms/*` passthrough present; token-less `POST /mcp` → `401` (not a redirect); E8 succeeds on that rig | T3 |
| E13 | A real MCP client logs in (manual runbook) | Claude Code registered against `http://localhost:9085/mcp` with `--client-id catalog-mcp-host`: the login completes and the client lists exactly the three low-risk tools — executed once, the client version and the observed result recorded in STATUS-04 | T4 |
