---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# GATEWAY-AUDIENCE-BINDING — decomposition

> T1…T4, in order. Each ticket is one focused commit's worth of work, **built collaboratively** — the
> maintainer and the agent on one branch, ticket by ticket, the rig up. The design these decompose is
> [[00-DESIGN]]; the contract is
> [[0035-audience-bound-gateway-tokens|ADR 0035]]. Every third-party seam a ticket names was checked
> against the real artifact on 2026-10-28; the line under each ticket says how.

## Critical path

```
T1 (APISIX 3.19, issuer-preserving) ──► T2 (audience in the plugin, guard deleted) ──► T3 (MCP OAuth surface) ──► T4 (docs)
```

- **Strictly sequential.** Each ticket ends green on the rig before the next touches anything: a 3.19
  regression must never be read as an audience refusal, and an audience refusal never as a discovery bug.
- **T1 is independently landable** (a gateway upgrade with unchanged behavior); T1+T2 is the original
  audience-binding scope and is landable without T3 if the MCP surface slips.
- **T4 is docs-only** and may start drafting during T3, but lands last: it describes what T2 and T3 measured.

## Cross-cutting acceptance (every ticket)

- **Fail-closed:** no change in this slice may admit a token, a request or a document a correctly configured
  rig would refuse. A missing audience, a foreign issuer, an unconfigured or half-configured document — each
  lands where [[00-DESIGN]] and ADR 0035 §6 say, never on "allow".
- **Every token-minting path is enumerated** (Mulch `opa-abac-rig-deploy-ops` mx-0520eb): the password-grant
  runners (`keycloak:8888`), the code-flow miner (in-network `Host`), the SPA (gateway origin), the published
  Keycloak port, and — new in T3 — the MCP host (gateway origin). A ticket that changes an edge check proves
  each of them.
- **Two-armed flag blocks** (mx-a1144b): every gateway route a flag adds is DELETEd when the flag is off.
- **A realm change is proven on a re-imported realm:** `./deploy.sh down` then up (the realm is imported from
  the bind-mounted export at start; `--import-realm` skips an existing realm).
- `scripts/checks/check-shell-guards.py` and `scripts/checks/check-collection-conformance.py` clean on every
  runner / collection change; `./gradlew build` + the local Sonar gate on every `.java` change.
- **Clean-room:** neutral names only; nothing from any private project.
- One commit per ticket on `feature/void3110/gateway-audience-binding`, identity `Void3110
  <void31102025@gmail.com>`. **Do not push, open a PR or touch `main`** — the maintainer does.

## T1 — APISIX 3.11.0 → 3.19.0, behavior-preserving

**Goal.** The gateway runs APISIX 3.19.0 and every existing behavior is unchanged: the same tokens admitted
and refused, the same routes, traces still reaching Jaeger.

**Deliverables.**
- `infra/compose.apisix.yaml`: `image: apache/apisix:3.19.0-debian`.
- **`claim_validator.issuer.valid_issuers` = the three rig authorities on every `openid-connect` block**
  (catalog, user-service, `/mcp`), fed from the existing `ISSUER_ALLOWLIST` (one list, rendered as a JSON
  array). **Not optional in this ticket:** since 3.12, an `openid-connect` block with no `valid_issuers`
  validates `iss` against the *discovery* issuer (`http://keycloak:8888/realms/catalog-demo`), so a bare bump
  would 401 every SPA token (issuer `http://localhost:9085/realms/catalog-demo`) and every token minted
  through the published port. 3.11.0 validated no issuer at all — the Lua guard was the only check.
- The Lua `serverless-pre-function` issuer guard **stays** in this ticket (it now duplicates the plugin's
  check; T2 deletes it).
- `session.secret` (≥ 16 characters, from `APISIX_OIDC_SESSION_SECRET` with a development default) on every
  `openid-connect` block whose `bearer_only` is `false` — the `ENABLE_SPA=0` browser-flow mode. The plugin
  refuses the route otherwise (`property "session.secret" is required when "bearer_only" is false`).
- opentelemetry: the collector, resource, batch-processor and `trace_id_source` settings move from
  `infra/apisix/config.yaml` `plugin_attr.opentelemetry` to plugin metadata, written by `init-routes.sh`
  (`PUT /apisix/admin/plugin_metadata/opentelemetry`); the `plugin_attr` block is deleted. The 3.19 plugin
  reads only its metadata.
- `ssl_verify: false` stays explicit on every block (the default flipped to `true` in 3.16).
- `PERFORMANCE.md`'s rig table keeps "3.11.0" (a historical record of that run).

*Seams verified:* `apisix/plugins/openid-connect.lua` and `opentelemetry.lua` at tag `3.19.0` (the
`claim_validator` schema; the `valid_issuers = {discovery.issuer}` default, present from 3.12.0 and absent
in 3.11.0; the `session.secret` check; the opentelemetry `metadata_schema`); the image tag on Docker Hub
(`3.19.0-debian`, 2026-09-29).

**Acceptance.** **E1**, **E2**, **E3**. Every runner green on the bumped gateway, on the default rig and
with `ENABLE_MCP=1`; a request through the gateway produces an `apisix` span in Jaeger; `ENABLE_SPA=0`
boots and a token-less catalog request still redirects to the login.

**What NOT to touch.** No audience check (T2). No realm change. The Lua guard. No route added or removed.
`etcd` stays `quay.io/coreos/etcd:v3.5.16` (proven by the suite, not assumed).

## T2 — Audience in the plugin; the Lua guard deleted

**Goal.** Every gateway route requires `aud ∋ catalog-gateway`, and the issuer allowlist lives in the plugin
alone.

**Deliverables.**
- **Two probes first, recorded in STATUS-02 with the raw evidence** (P0, ADR 0035 §Context):
  **I1** — Keycloak 26.3.2's `aud` with `resource=http://localhost:9085/mcp` on authorize and token;
  **I2** — APISIX 3.19's answer to an `aud` array containing `catalog-gateway`, to one without it, and to a
  token with no `aud`.
- **Realm (`infra/keycloak/realm-export.json`): one inline `oidc-audience-mapper` per legitimate client** —
  `catalog-gateway`, `catalog-spa`, `catalog-agent-readonly`, `catalog-agent-overreach`, `catalog-agent-revoked`
  — named `gateway-audience`, config `included.client.audience: catalog-gateway`, `access.token.claim: true`,
  `id.token.claim: false`, `introspection.token.claim: true`. **`catalog-directory` gets none** (the negative
  control). **The export gains no `clientScopes` key** — Keycloak 26.3.2 creates its built-in scopes on import
  only when that key is absent (`RealmManager.importRealm`), so a declared scope list would silently drop
  `profile`, `roles`, `basic`, `acr` and every client's `realm_access` (the reason the shared-scope design was
  reversed, [[00-DESIGN]] §Considered and rejected 2).
- **Gateway (`infra/apisix/init-routes.sh`):** every `openid-connect` block gains
  `claim_validator.audience.match_with_client_id: true` (the block's `client_id` is `catalog-gateway`;
  3.18+ makes it imply `required`). The `ISSUER_GUARD` `serverless-pre-function` and its Lua are **deleted**,
  with the comment block rewritten to say where the issuer contract now lives and why the case-insensitive
  scheme subtlety no longer applies (one plugin authenticates and checks the issuer).
- **e2e (`scripts/postman/run-step-up-matrix.sh`, beside E9):** **E9i** a `catalog-directory`
  service-account token through the gateway → `403` with body `{"error":"mismatched audience"}` (or the I2
  measurement, if it differs — recorded); **E9j** a `catalog-gateway` password-grant token → `200`. The E9a–h
  controls unchanged and green with the guard gone.
- **e2e — the per-client drift guard:** a token from **each** of the five clients carries `aud ∋
  catalog-gateway` (four password-grant mints in the step-up runner's preflight + the miner's `catalog-spa`
  token), and `catalog-directory`'s does not.

*Seams verified:* `AudienceProtocolMapper` at Keycloak tag `26.3.2` (`PROVIDER_ID = "oidc-audience-mapper"`,
`included.client.audience`); `RealmManager.importRealm` (`if (rep.getClientScopes() == null)
createDefaultClientScopes(realm)`); the existing mapper config shape in `realm-export.json`; the 3.19 plugin's
audience block (`match_with_client_id` ⇒ required; `403 {"error":"mismatched audience"}` /
`403 {"error":"required audience claim not present"}`; it runs on the JWKS path too — `introspect()`
returns the verified claims as `response`).

**Acceptance.** **I1**, **I2**, **E4**, **E5**, **E6**; every runner green on a re-imported realm.

**What NOT to touch.** `catalog-directory`'s configuration (it must stay audience-less). No `clientScopes`
key. No new client (T3). No change to any service's `trust-forwarded-jwt` posture. The MCP route's
`bearer_only` (T3).

## T3 — The MCP OAuth surface

**Goal.** A real MCP client can discover how to log a user in to `/mcp`, does so through a public **agent**
client, and is narrowed by the tool-gate exactly as the scripted agents are.

**Deliverables.**
- **Realm:** client `catalog-mcp-host` — `publicClient: true`, `standardFlowEnabled: true`,
  `directAccessGrantsEnabled: false`, attribute `pkce.code.challenge.method: S256`, redirect URIs
  `http://localhost:33418/callback` and `http://127.0.0.1:33418/callback`, `defaultClientScopes` = the
  agents' (`openid`, `profile`, `email`, `roles`), the `subject` mapper, the `gateway-audience` audience
  mapper (as T2), and the hardcoded `act-chain` mapper with `claim.value: ["agent-host"]`.
- **Realm-wide refresh rotation:** `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`.
- **The host's capability** — profile `agent-host`: `allowed-categories: [READ]`, `allowed-tools:
  [list_catalogs, get_catalog, list_categories, get_product]`, `allowed-actions: [view, list]`,
  `max-risk-tag: low`. **In both** `example-mcp-server/src/main/resources/application.yml` **and**
  `infra/compose.mcp.yaml` (`OPA_ABAC_MCP_AGENTS_PROFILES_3_*`): the compose file binds the profile list
  from indexed environment variables, and a list bound from the environment replaces the YAML list whole.
- **The agent-clients default:** `OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS` defaults to
  `catalog-agent-readonly,catalog-agent-overreach,catalog-agent-revoked,catalog-mcp-host`, and
  `OPA_ABAC_SUBJECT_ATTRIBUTE_CLAIMS` to `act_chain,azp` (the wiring check requires `azp` whenever
  `agent-clients` is set). The E12 drill in `run-agent-tool-matrix.sh` **extends** that list with
  `catalog-gateway` rather than replacing it (every reader of the switch agrees).
- **The RFC 9728 document — `example-mcp-server`:**
  - `ProtectedResourceProperties` (`@ConfigurationProperties("example.mcp.oauth.protected-resource")`:
    `resource`, `authorization-server`) and its validation — both blank ⇒ discovery off; one blank, or a
    value that is not an absolute `http`/`https` URL, carries a fragment, or (for `resource`) has a path
    other than `/mcp` ⇒ **startup failure** naming the key and the reason; both valid ⇒ on.
  - `ProtectedResourceMetadataController`: exactly two `GET` mappings,
    `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`, answering
    `{"resource", "authorization_servers": [...], "bearer_methods_supported": ["header"]}` from the
    properties only (no `scopes_supported`), `404` when discovery is off; one startup WARN naming both keys
    when off.
  - `SecurityConfig` permits **exactly** those two paths (both chain arms), never `/.well-known/**`.
  - `infra/compose.mcp.yaml` sets the two values to `http://localhost:9085/mcp` and
    `http://localhost:9085/realms/catalog-demo`.
  - *Not* Spring Security's `OAuth2ProtectedResourceMetadataFilter`: it matches
    `GET /.well-known/oauth-protected-resource/**` (every sub-path), presets `resource` from
    `UrlUtils.buildFullRequestUrl` (the request's `Host`) before the customizer runs, and answers whenever
    installed — three breaches of ADR 0035 §6 (verified by `javap -c`, spring-security-oauth2-resource-server
    7.0.7). The design's spike is answered.
- **Gateway (`init-routes.sh`, inside the `ENABLE_MCP` block, two-armed):**
  - route `mcp-protected-resource`: `uris` = the two paths, `methods: ["GET"]`, upstream `mcp-pool`, **no auth
    plugin**, priority 66 (above `/mcp*` at 65 and the catch-all at 0);
  - the `/realms/*` + `/resources/*` Keycloak passthrough is wired when `ENABLE_SPA=1` **or** `ENABLE_MCP=1`
    (the MCP host's whole login runs through the gateway origin) — and removed only when both are off;
  - the `/mcp` route's `openid-connect` is **always** `bearer_only: true` + `unauth_action: deny` (an API
    surface: a token-less MCP client must get `401`, never a browser redirect), independent of `ENABLE_SPA`.
- **The miner (`scripts/postman/mint-code-flow-token.py`) learns `--discover <mcp-url>`:** fetch the
  path-inserted document, fall back to the root one; take `authorization_servers[0]`; try the three
  authorization-server metadata URLs in the spec's order (RFC 8414 path-inserted, OIDC path-inserted, OIDC
  appended) and report on stderr which answered; refuse unless `code_challenge_methods_supported` contains
  `S256`; send `resource=<mcp-url>` on both the authorize and the token request; mint as `--client`
  (`catalog-mcp-host` in the cells). The connect/issuer rebasing the miner already does still applies
  (the authorization server is reached through the gateway origin, so `iss` = `http://localhost:9085/realms/catalog-demo`).
  `--field refresh_token` exposes the refresh token for the rotation cell.
- **e2e — `scripts/postman/run-agent-tool-matrix.sh` + `agent-tool-matrix.postman_collection.json`,
  cells E13–E16** (the collection's next numbers), mapped in [[10-QA-TEST-CASES]].

*Seams verified:* `OAuth2ProtectedResourceMetadataFilter` (`javap -c`, 7.0.7) as above; `SecurityConfig`'s
two chain arms (source); the compose profile binding (`infra/compose.mcp.yaml` lines 62–76); the E12 drill's
overrides (`run-agent-tool-matrix.sh`); the passthrough's `ENABLE_SPA` gate and the `$OIDC_BEARER_ONLY`
inheritance (`init-routes.sh`); the miner's PKCE flow and options (source); the MCP spec 2025-11-25
discovery order and the `resource` requirement (read at the source).

**Acceptance.** **U1–U7**, **E7–E12**; `./gradlew :example-mcp-server:test` + `./gradlew build` green; the
local Sonar gate on the changed `.java`; every runner green on a re-imported realm (`ENABLE_MCP=1`).

**What NOT to touch.** No library module (`opa-abac-*`). No policy. The four existing tools and their
declarations. `catalog-directory`. The SPA's code (rotation must work with it as is — proven, not adapted).
No `WWW-Authenticate` change on the gateway's 401 (ADR 0035 §6: well-known only).

## T4 — Docs, the runbook, the backlog

**Goal.** Every place that states the gateway-trust posture says what the gateway must verify — signature,
expiry, issuer **and** audience — and the MCP OAuth path is documented for adopters and for a person at a
terminal.

**Deliverables.**
- [[ABAC-AUTHORIZATION]] §Signature trust: the full statement, with the example's APISIX `claim_validator`
  configuration as the reference.
- A one-line pointer to it in: README step 3; [[TWO-LAYER-AUTHORIZATION]] §Signature trust (and drop its stale
  "reserved `verifySignature` mode" sentence — the property was deleted); the `trustForwardedJwt` javadoc in
  `opa-abac-spring-boot-starter/.../OpaAbacProperties.java`; `docs/prompts/FROM-EXAMPLE-TO-YOUR-APP.md`.
- `docs/guides/E2E-TESTING.md`: the "in-network token caveat" rewritten to the measured truth (what the
  gateway enforces now; the three authorities plus the MCP host; the audience).
- [[AGENT-TOOL-AUTHORIZATION]]: an "MCP OAuth discovery" section — the document and its failure contract, the
  host agent client and its profile, refresh rotation, and the forwarded bearer as the documented deviation
  with RFC 8693 named as the successor.
- A runbook: logging Claude Code in to the rig — `claude mcp add --transport http catalog
  http://localhost:9085/mcp --client-id catalog-mcp-host --callback-port 33418`, then the login, then a
  `tools/list` — **manual**, executed once and recorded in STATUS-04 (**E13**); the page tells the reader to
  believe `claude mcp --help` over it.
- `infra/README.md` + `infra/compose.keycloak.yaml` header (the per-client audience mapper, rotation, the
  host client); the CHANGELOG `[Unreleased] — 1.5.0` section (a Documentation entry: the trust posture now
  names issuer and audience); root `CLAUDE.md` if a run step changed; `scripts/postman/README.md` rows.
- `ENGINEERING-BACKLOG`: RFC 8693 token exchange at the MCP server; a library seam for protected-resource
  metadata.
- ADR 0035 status → implemented, the ADR index row; this package's index ticked.

**Acceptance.** **E13**; `scripts/planning/verify-package.sh GATEWAY-AUDIENCE-BINDING` green; every
wikilink added resolves; `./gradlew build` green (the javadoc edit).

**What NOT to touch.** No behavior. The CHANGELOG heading itself. `PERFORMANCE.md`'s historical rig table.
