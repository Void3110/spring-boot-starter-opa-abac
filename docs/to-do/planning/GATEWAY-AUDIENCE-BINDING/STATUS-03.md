---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# STATUS — T3: The MCP OAuth surface

**Status:** ✅ DONE (2026-10-28, collaborative build)

## What shipped

- **Realm (`infra/keycloak/realm-export.json`)**
  - Client `catalog-mcp-host`: public, standard flow only, `pkce.code.challenge.method: S256`, redirect URIs
    `http://localhost:33418/callback` and `http://127.0.0.1:33418/callback`, the agents' default scopes. Its
    three mappers are `subject`, `gateway-audience` (as T2) and `act-chain` with `["agent-host"]`.
  - Realm-wide `revokeRefreshToken: true` + `refreshTokenMaxReuse: 0`.
  - Still no `clientScopes` key.
- **SPA (`example-demo-ui/src/auth.ts`)**
  - `freshUser()` shares **one** in-flight `signinSilent()` among every caller that finds the token expired.
  - It is cleared when the refresh settles, success or failure, so the next expiry starts a fresh one.
  - No other auth change.
- **MCP server: the host is an agent** (`application.yml`, mirrored in `infra/compose.mcp.yaml`, which binds
  the list from the environment and so replaces it whole):
  - profile `agent-host`: `[READ]`, the four tools, `[view, list]`, `low`;
  - `opa.abac.mcp.identity.agent-clients` = the three demo agents + `catalog-mcp-host`;
  - `opa.abac.subject.attribute-claims` = `[act_chain, azp]`.
- **MCP server: RFC 9728 discovery** (new package `dev.dmitriikonovalov.example.mcp.oauth`):
  - `ProtectedResourceProperties` (`example.mcp.oauth.protected-resource.{resource,authorization-server}`).
  - `ProtectedResourceDocument` holds the contract:
    - both unset → off;
    - one unset → startup failure naming the missing key;
    - not an absolute `http(s)` URL, or a fragment → startup failure;
    - a `resource` whose path is not `/mcp` → startup failure;
    - the body is built once from configuration.
  - `ProtectedResourceConfiguration`: the validation at startup, and one WARN naming both keys when off.
  - `ProtectedResourceMetadataController`: exactly two `GET` mappings; `404` when off.
  - `SecurityConfig` permits exactly those two `GET` paths, on both chain arms.
  - `application.yml` documents both keys, blank, so discovery is off by default. The compose file sets them to
    the gateway origin.
- **Gateway (`infra/apisix/init-routes.sh`)**
  - The shared `openid-connect` fragment became `oidc_conf <bearer_only> <unauth_action>`: still one
    definition. `/mcp` takes `oidc_conf true deny` whatever `ENABLE_SPA` says.
  - The `/realms/*` + `/resources/*` passthrough is wired when `ENABLE_SPA=1` **or** `ENABLE_MCP=1`, and removed
    only when both are off.
  - New route `mcp-protected-resource`: the two paths, `GET`, `mcp-pool`, priority 66, no auth plugin
    (tracing only). It is deleted with `/mcp` when MCP is off.
- **Miner (`scripts/postman/mint-code-flow-token.py`): `--discover <mcp-url>`**
  - Discovery: the path-inserted document, then the root one. Only `200` + a JSON object counts, and redirects
    are never followed. `resource` must equal `--discover`.
  - Then `authorization_servers[0]`'s metadata at RFC 8414 path-inserted → OIDC path-inserted → OIDC appended,
    reporting on stderr which answered. The metadata's `issuer` must equal it, and `S256` must be listed.
  - The flow uses the metadata's endpoints, with `resource` on authorize and token. The transport is the
    issuer's origin, both to connect to and as the `Host`.
  - The legacy mode is byte-for-byte unchanged outside the flag.
- **e2e**
  - `run-agent-tool-matrix.sh` + `agent-tool-matrix.postman_collection.json`:
    - **E13**: the documents through the gateway, plus a runner check that a forged `Host` and `X-Forwarded-*`
      leave the document byte-identical.
    - **E14**: the discovery login, run by the runner.
    - **E15**: the host token on the tool surface, plus the REST contrast on the same token.
    - **E16**: rotation through the gateway origin.
    - The E12 drill now **extends** the default agent-client list with `catalog-gateway`. The E12-restore
      cell and the header are reworded: the default list is non-empty, and the restore proves `catalog-gateway`
      is no longer listed.
  - `run-step-up-matrix.sh`: **E9k**, the `catalog-spa` refresh rotation, minted with its own cookie jar and
    refreshed in-network.

## Tests

- **U1–U6** (`ProtectedResourceConfigurationTest` 15, `ProtectedResourceMetadataEndpointTest` 11,
  `ProtectedResourceMetadataOffTest` 2):
  - The endpoint tests speak raw HTTP/1.0 over a socket (`RawHttp`): the JDK clients refuse a caller-chosen
    `Host`, and U5 forges exactly that.
  - U6 pins what the chain answers for an unauthenticated request outside the two `GET` paths: `403`.
- **U7**:
  - `ApplicationYamlBindingTest` covers the four profiles, the `agent-host` dimensions, the four agent clients
    and `[act_chain, azp]`.
  - `HostAgentWiringTest` is the falsifier: the real app, booted with `attribute-claims=act_chain`, refuses to
    start with the wiring check's message. It took two corrections to this test. First, `builder.properties()`
    are default properties, the lowest precedence, so the YAML list won and the server started. They are now
    command-line arguments. Second, the check throws un-wrapped.
- **U8** (`example-demo-ui/src/auth.test.ts`) was written first and **run against the unfixed `freshUser()`:
  5 concurrent callers made 5 `signinSilent()` calls**. After the fix: one call; a failed refresh answers `null`
  to every caller, and the next call starts a new refresh. `npm test` 72/72; `tsc -b --noEmit` clean.
- `./gradlew :example-mcp-server:test`: 82/82. This host's JDK cannot download Spring AI 2.0.1 (PKIX), so the
  run used `RELEASING.md` §4's keychain truststore + `--no-daemon`.
- **The local Sonar gate on the changed `.java`** reported 2 findings, both on pre-existing `SecurityConfig`
  lines and both standing by-design classes in Mulch mx-302e78:
  - S4502: CSRF off on a stateless bearer chain;
  - S112: the `throws Exception` that Spring Security's builder API imposes.
  Nothing on the new classes.
- `bash -n`, `scripts/checks/check-shell-guards.py` (28 clean), `scripts/checks/check-collection-conformance.py`
  (18 clean). The lint caught the two E13 `401` cells as ABSENCE-ONLY: their only body check was a `not.include`.
  They now assert the catch-all's exact `WWW-Authenticate: Bearer realm="catalog-demo"`.

## Architecture review + refactor

Path: inline review in the collaborative session (the slice's whole-delivery `/deep-review` follows T4).

- **Refactored:** T1's shared fragment became a two-argument function instead of a second copy for `/mcp`. The
  "one definition, every route" property T1 established survives the one posture `/mcp` differs in.
- **Fail-closed sweep:**
  - The two document paths are the only unauthenticated surface added, `GET` only, at the gateway and in the
    app's chain.
  - A misconfigured document cannot be served. A missing one is a `404`, never a guess.
  - The host is an agent, so on the tool surface its token cannot reach the principal's full ceiling. (Corrected
    by the slice review, 2026-10-29: on the REST API the same token is the person's, by ADR 0028 §2 — backlog 25
    is what would close it.) Two things stop that on `/mcp`: the
    risk cap is in its profile, and `agent-clients` refuses a host token whose actor went missing.
  - Rotation only narrows: a replayed refresh token is refused.
  - The miner refuses a document for another resource, an issuer mismatch, and an AS without S256.
- **Considered and left alone:**
  - **The passthrough now also exposes `/realms/*` on a browser-flow `ENABLE_MCP=1` rig.** That is the same
    public Keycloak surface the SPA rig always exposed (local rig, demo realm); it is what an MCP host's login
    needs.
  - **APISIX's own browser-flow session refresh under rotation** (`ENABLE_SPA=0`): the plugin stores the rotated
    refresh token in its session cookie, but two concurrent requests renewing one expired session could race.
    The access token lives 30 minutes, and no runner refreshes through a gateway session. That posture is the
    opt-in non-SPA one, so this is recorded, not engineered around.

## Integration / e2e

Run 2026-10-28 on fresh images and a re-imported realm. The image builds retry: BuildKit's resolver failed
`github.com`, the Gradle wrapper download, on 1 of 2 catalog builds today, while plain containers resolved it
5/5. One log per step.

- **The full suite on the three rigs: 784 newman assertions, 0 failed**, plus the SPA smoke 4/4. That is T2's 769
  plus the 15 new E13/E15 assertions.
  - rig 1, the resilience stub: resilience 5/0.
  - rig 2, `ENABLE_MCP=1`, seeded: run-tests 22/0, run-matrix 21/0, action-enrichment 19/0, **agent-tool 107/0**,
    demo-world 176/0, filter 29/0, hierarchy-list 18/0, hierarchy 4/0, isolation 20/0, pagination 27/0,
    permission-categories 31/0, production-tier 73/0, step-up 69/0, supervised-scope 55/0, tag 46/0, team 24/0.
  - rig 3, the OIDC-only quickstart: run-tests 19/0, run-matrix 19/0.
  - `run-resource-resolution-matrix.sh` is still red for its pre-existing ADR 0034 fixture reason (STATUS-01).
  - The `/internal/*` 404 and the no-pre-function route check hold.
- **QA E7 (cell E13):**
  - Both documents through the gateway with no token → `200`, exactly
    `{"resource":"http://localhost:9085/mcp","authorization_servers":["http://localhost:9085/realms/catalog-demo"],"bearer_methods_supported":["header"]}`.
  - A forged `Host` / `X-Forwarded-*` → the byte-identical document.
  - A `POST` on the document path and `GET …/oauth-protected-resource/other` → `401` with the catch-all's
    `WWW-Authenticate: Bearer realm="catalog-demo"`.
- **QA E8 (E14):**
  - The miner's discovery login exits 0. The path-inserted document answered `200`.
  - The AS metadata: RFC 8414 path-inserted → `401`, OIDC path-inserted → `401` (both the catch-all), and it
    **answered at the third, OIDC appended**, as expected.
  - The token: `aud="catalog-gateway"`, `azp=catalog-mcp-host`, `act_chain=["agent-host"]`,
    `iss=http://localhost:9085/realms/catalog-demo`.
- **QA E9 (E15):**
  - The host's `tools/list` is exactly `[get_catalog, list_catalogs, list_categories]`.
  - `list_categories` is callable and returns the granted category.
  - `get_product` — listed in the profile — is denied `layer: tool-gate`, `code: tool-gate-denied`.
  - The same product over REST on the same token → `200`.
- **QA E10 (E16):** the host's refresh through the gateway origin → `200` with a new refresh token; replaying the
  first → `400 invalid_grant` "Maximum allowed refresh token reuse exceeded".
- **QA E11 (E9k):** the `catalog-spa` refresh rotated in-network; the replay → `400 invalid_grant`, the same
  message. `run-spa-auth-smoke.sh` 4/4.
- **E12 drill:** with `catalog-gateway` added to the four default agent clients, the human token is refused
  `tool-gate-actor-required`. E12-restore passes as reworded.
- **QA E12, the browser-flow rig** (`ENABLE_SPA=0 ENABLE_MCP=1`), recorded:
  - `mcp` has `bearer_only=true`, `unauth_action=deny` and no `session`. `catalog-all`, `usermgmt-teams` and
    `usermgmt-users` keep `bearer_only=false` + `session`. All four carry the audience block.
  - `keycloak-realms`, `keycloak-resources` and `mcp-protected-resource` are present, the last with no auth
    plugin.
  - Token-less `POST /mcp` → **`401`**; token-less `GET /api/v1/catalogs` → `302`, unchanged.
  - The discovery login succeeds here too. Its first two metadata URLs answered **`302`** on this rig, the
    catch-all's login redirect, and were skipped without being followed. The token's `azp`, `act_chain` and
    `iss` are as on the SPA rig.
  - A `catalog-directory` bearer → `403 {"error":"mismatched audience"}`, and a `catalog-gateway` bearer →
    `200`.

## Decisions

- **The REST contrast for E15 uses the host's own token** (QA E9 said "demo over REST"). The same bearer
  admitted by the gateway and allowed by the catalog service is the stronger contrast: it isolates the tool-gate's
  agent narrowing from both the principal and the token.
- **E13's forged-`Host` probe lives in the runner (curl), not in the collection:** curl sends a caller-chosen
  `Host` as given, and the cell must not depend on whether newman does.
- **Discovery is off in the shipped `application.yml`** (both keys documented, blank) and on in the rig's
  compose file — U1 is the default, the rig is the configured state.

## Commit

`feat(mcp): the MCP OAuth surface — RFC 9728 discovery in example-mcp-server, the catalog-mcp-host agent client, refresh rotation + a single-flight SPA refresh, /mcp always bearer-only (GATEWAY-AUDIENCE-BINDING T3)`
