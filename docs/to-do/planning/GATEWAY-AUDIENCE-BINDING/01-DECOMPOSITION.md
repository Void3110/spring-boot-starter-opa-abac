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

## Two id spaces — read this before the tickets

The tickets cite **QA case ids** from [[10-QA-TEST-CASES]] (`U*`, `I*`, `E*`). The runners and collections
have **their own cell numbers**, which this slice extends; where the two could be confused, the tickets say
*QA E…* or *cell E…*:

| QA case | Becomes |
|---|---|
| QA E4, E5 | step-up runner preflight cells **E9i**, **E9j** (beside the existing E9a–h) |
| QA E7, E8, E9, E10 | agent matrix cells **E13**, **E14**, **E15**, **E16** (the collection's next numbers) |
| QA E11 | a step-up runner preflight cell **E9k** (the SPA client's rotation) |
| QA E3, QA E12 | recorded rig steps on the browser-flow rig (`ENABLE_SPA=0 ENABLE_MCP=1`), in STATUS-01 / STATUS-03 — no cell |
| QA E13 | a manual runbook step, recorded in STATUS-04 — no cell |
| *the agent matrix's E12 drill* | the existing `agent-clients` cell, *not* QA E12 |

## The full suite (what "every runner green" means)

The nineteen runners `scripts/postman/run-*.sh` (incl. `run-tests.sh`, `run-matrix.sh`,
`run-spa-auth-smoke.sh`), on **three** rigs, in this order (rigs 1–2: the recipe Mulch `opa-abac-rig-deploy-ops`
mx-7c81b9 measured; rig 3: [[E2E-TESTING]] §Two rig flavours):

0. **Fresh images first:** `ENABLE_MCP=1 ./deploy.sh build` (the catalog + MCP images) **and** `docker build -t
   opa-abac-usermgmt:local -f example-user-management-service/Dockerfile .` (`build` never rebuilds the
   user-service image) — `up` reuses existing `opa-abac-*:local` images.
1. **The resilience rig, on its own** — `./deploy.sh down; ./profile.sh up; ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1 ./deploy.sh up
   --pods 2`, then `run-resilience-matrix.sh` only. `ENABLE_RESILIENCE_STUB=1` repoints the catalog pods'
   **whole** role source at the stub, so no other runner can share this rig.
2. **The main rig** — `./deploy.sh down; ./profile.sh up; ENABLE_MCP=1 ./deploy.sh up --pods 2` (the SPA and the
   directory default on; OIDC and the user-service are forced), then `scripts/postman/seed-demo-data.sh` (the
   demo-world matrix's prerequisite), then the other **eighteen** runners. **Keep `ENABLE_MCP=1` on every
   call:** a runner may re-`up` the rig mid-run (the supervised-scope matrix does, forwarding the optional
   flags), and the flag-off arm tears
   the MCP server down — `export` it in an interactive shell; in a session where every command gets a fresh
   shell (an agent's), pass it inline on each runner call.
3. **The OIDC-only quickstart rig** — `./deploy.sh down; ./profile.sh up;` then, in an environment with **no**
   leftover `ENABLE_MCP` / `ENABLE_RESILIENCE_STUB` (unset them — `ENABLE_MCP=1` forces the user-service),
   `ENABLE_SPA=0 ENABLE_DIRECTORY=0 ENABLE_OIDC=1 ./deploy.sh up --pods 2`, then `run-tests.sh` and
   `run-matrix.sh`, the two runners that run on both flavours.

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

**Acceptance.** **E1**, **E2**, **E3**. The full suite (above) green on the bumped gateway, all three rigs; a
request through the gateway produces an `apisix` span in Jaeger; the **browser-flow rig** (`ENABLE_SPA=0
ENABLE_MCP=1 ./deploy.sh up --pods 2` — `ENABLE_SPA=0` sets `bearer_only: false`, and `ENABLE_MCP=1` brings the
user-service and the MCP server, so the catalog, user-service and `/mcp` blocks all run in browser-flow mode) boots with every route accepted and a token-less catalog request still
redirects to the login. (The OIDC-only rig wires only the catalog route, so it cannot prove the `session.secret`
on the other two blocks.)

**What NOT to touch.** No audience check (T2). No realm change. The Lua guard. No route added or removed.
`etcd` stays `quay.io/coreos/etcd:v3.5.16` (proven by the suite, not assumed).

## T2 — Audience in the plugin; the Lua guard deleted

**Goal.** Every gateway route requires `aud ∋ catalog-gateway`, and the issuer allowlist lives in the plugin
alone.

**Deliverables.**
- **Two probes first, recorded in STATUS-02 with the raw evidence** (P0, ADR 0035 §Context):
  **I1** — Keycloak 26.3.2's `aud` with `resource=http://localhost:9085/mcp` on authorize and token. **If a
  token minted with `resource` does NOT contain `catalog-gateway`, T2 lands but T3 stops** — every MCP client
  sends `resource` (the spec requires it), so every host token would be refused — and ADR 0035 §1/§5 are
  revisited with the maintainer (candidates: a custom-audience mapper naming the MCP URL +
  `claim_validator.audience.claim`, or turning Keycloak's resource-indicator handling off).
  **I2** — APISIX 3.19's answer on the audience edge, **measuring what this realm actually mints** (the shape
  of `aud` is recorded per client, not assumed): the `catalog-directory` token (expected `aud: realm-management`
  → mismatched); a legitimate token after the mapper (string or array — whichever occurs). The no-`aud` arm
  (`403 {"error":"required audience claim not present"}`, `openid-connect.lua` 3.19 lines 1275–1280) is cited
  from source, not minted — no client of this realm emits a token without `aud`.
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
  3.18+ makes it imply `required`). The **`ISSUER_GUARD`** `serverless-pre-function` on the three
  `openid-connect` blocks, and its Lua, are **deleted**, with the comment block rewritten to say where the
  issuer contract now lives and why the case-insensitive scheme subtlety no longer applies (one plugin
  authenticates and checks the issuer). **`internal-blocked` keeps its own `serverless-pre-function`** (the
  `/internal/*` 404 that keeps the catalog's creator lookup off the edge), and `serverless-pre-function`
  stays in `config.yaml`'s plugin list.
- **What "every route" means, precisely:** the audience check runs on every **bearer-presented** token on
  every `openid-connect` route — in browser-flow mode too, since a request carrying a bearer header goes
  through the same JWKS + `claim_validator` path. A browser-flow **session** (no bearer header) carries the
  token the gateway's own client `catalog-gateway` obtained, which its own mapper stamps; the session cookie is
  encrypted under `session.secret`. That path is not audience-checked and needs not be.
- **e2e (`scripts/postman/run-step-up-matrix.sh`, beside E9):** **cell E9i** — a `catalog-directory`
  service-account token refused **on each authenticated surface**: the catalog (`GET /api/v1/catalogs`), the
  user-service (`GET /api/v1/teams`) and `/mcp` (an MCP `initialize` POST) → the I2-measured refusal
  (expected `403`, body `{"error":"mismatched audience"}`), never `200`; **cell E9j** — a `catalog-gateway`
  password-grant token → `200` on the same three requests (the paired allow control per surface). The E9a–h
  controls unchanged and green with the guard gone, and `GET /internal/catalog/{id}/created-by` through the
  gateway still `404`.
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
- **Realm-wide refresh rotation:** `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0` — with Keycloak
  26.3.2's actual semantics (`TokenManager.validateTokenReuse`): reusing a refresh token that was already
  used → `invalid_grant` "Maximum allowed refresh token reuse exceeded", and the client session is
  **detached** (`validateTokenReuseForRefresh`); an older token after a newer one was used → `invalid_grant`
  "Stale token" (second-granularity `iat`).
- **The SPA's refresh becomes single-flight** (maintainer decision, 6b round 1): `example-demo-ui/src/auth.ts`
  `freshUser()` shares **one** in-flight `signinSilent()` among concurrent callers. Today each expired-token
  read calls it independently (`api.ts` → `freshUser()`; a catalog view fans out several reads at once), and
  oidc-client-ts does not de-duplicate — under rotation the losers would replay the same refresh token and
  Keycloak would detach the session. A vitest pins it (**U8**).
- **The host's capability** — profile `agent-host`: `allowed-categories: [READ]`, `allowed-tools:
  [list_catalogs, get_catalog, list_categories, get_product]`, `allowed-actions: [view, list]`,
  `max-risk-tag: low`. **In both** `example-mcp-server/src/main/resources/application.yml` **and**
  `infra/compose.mcp.yaml` (`OPA_ABAC_MCP_AGENTS_PROFILES_3_*`): the compose file binds the profile list
  from indexed environment variables, and a list bound from the environment replaces the YAML list whole.
- **The agent-clients default, in both places** (as the profile): `example-mcp-server`'s `application.yml`
  gains `opa.abac.mcp.identity.agent-clients: [catalog-agent-readonly, catalog-agent-overreach,
  catalog-agent-revoked, catalog-mcp-host]`, and its existing `opa.abac.subject.attribute-claims: [act_chain]`
  gains `azp` (the wiring check requires `azp` whenever `agent-clients` is set); `infra/compose.mcp.yaml`'s
  `MCP_AGENT_CLIENTS` / `MCP_SUBJECT_ATTRIBUTE_CLAIMS` defaults carry the same values.
  `ApplicationYamlBindingTest` (which pins the three-profile list today) is updated to the four profiles, the
  new `agent-clients` key and `azp` in `attribute-claims`.
- **The agent matrix's E12 drill** (`run-agent-tool-matrix.sh`) **extends** the default list with
  `catalog-gateway` rather than replacing it (every reader of the switch agrees), and the **E12-restore**
  cell's name and script comment — "the guard is gone with the drill", "with agent-clients back to its empty
  default the guard never runs" — are rewritten: after this slice the default list is non-empty, and what the
  restore proves is that `catalog-gateway` is no longer listed. The runner header (`:32–35`, `:46–47`)
  likewise.
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
- **The miner (`scripts/postman/mint-code-flow-token.py`) learns `--discover <mcp-url>`**, with its own
  transport rules — the legacy defaults stay untouched outside this mode (the step-up E9d issuer-parity cell
  depends on them):
  - **Transport:** in discovery mode `--keycloak` **and** `--issuer` both become the origin of
    `authorization_servers[0]` (`http://localhost:9085`) — the presented `Host` is the gateway origin, no
    rewrite to `keycloak:8888`, no rebasing onto `:28888`. (The legacy mode forces `Host: <--issuer netloc>`
    on every request and rebases rendered URLs onto the connect URL — through the gateway that would mint
    `iss = http://keycloak:8888/…`.)
  - **Discovery:** `GET` the path-inserted document, then the root one; only a `200` with a JSON body counts
    as an answer; redirects are never followed (the existing `NoRedirect` session); refuse if both are absent,
    or if the document's `resource` ≠ the `--discover` URL. Take `authorization_servers[0]`; try the spec's
    three metadata URLs in order (RFC 8414 path-inserted, OIDC path-inserted, OIDC appended) under the same
    "200 + JSON" rule — the first two land on the gateway's catch-all (`401` in SPA mode, `302` in browser-flow
    mode) and are skipped — and report on stderr which answered; refuse if the metadata's `issuer` ≠
    `authorization_servers[0]` or `code_challenge_methods_supported` lacks `S256`.
  - **The grant:** send `resource=<mcp-url>` on both the authorize and the token request; mint as `--client`.
    The cells always pass the full invocation: `--discover http://localhost:9085/mcp --client catalog-mcp-host
    --redirect-uri http://localhost:33418/callback --user demo` (the agent runner's `DEMO_USER`, the human whose
    REST read is the cell-E15 contrast), `--field access_token,refresh_token` (one invocation for both).
  - **A note for the reader:** RFC 9728 §3.3 read strictly would void the root-path document (its `resource`
    names `/mcp`, not the root); the MCP spec's fallback is what places it there, and the miner accepts it.
- **e2e — `scripts/postman/run-agent-tool-matrix.sh` + `agent-tool-matrix.postman_collection.json`,
  cells E13–E16** (QA E7–E10, the mapping table above); **step-up runner cell E9k** (QA E11 — the SPA client's
  rotation: mint a `catalog-spa` pair as `demo` with the miner's legacy mode and its **own** cookie jar (never
  the runner's shared `COOKIE_JAR`), refresh **in-network** as E9h does → a new refresh token; replay the first →
  `invalid_grant` "Maximum allowed refresh token reuse exceeded").
  `run-spa-auth-smoke.sh` stays green as the gateway's bearer-posture control only — it never refreshes.
- **QA E12** runs as a recorded rig step on the browser-flow rig after T3's `/mcp` pin, in STATUS-03.

*Seams verified:* `OAuth2ProtectedResourceMetadataFilter` (`javap -c`, 7.0.7) as above; `SecurityConfig`'s
two chain arms (source); the compose profile binding (`infra/compose.mcp.yaml` lines 62–78); the E12 drill's
overrides (`run-agent-tool-matrix.sh`); the passthrough's `ENABLE_SPA` gate and the `$OIDC_BEARER_ONLY`
inheritance (`init-routes.sh`); the miner's PKCE flow and options (source); the MCP spec 2025-11-25
discovery order and the `resource` requirement (read at the source).

**Acceptance.** **U1–U8**, **E7–E12**; `./gradlew :example-mcp-server:test` + `./gradlew build` green;
`npm test` in `example-demo-ui` green; the local Sonar gate on the changed `.java`; the full suite green on a
re-imported realm.

**What NOT to touch.** No library module (`opa-abac-*`). No policy. The four existing tools and their
declarations. `catalog-directory`. In the SPA, **only** `freshUser()`'s single-flight — no other auth change.
No `WWW-Authenticate` change on the gateway's 401 (ADR 0035 §6: well-known only). The miner's legacy
(non-`--discover`) defaults.

## T4 — Docs, the runbook, the backlog

**Goal.** Every place that states the gateway-trust posture says what the gateway must verify — signature,
expiry, issuer **and** audience — and the MCP OAuth path is documented for adopters and for a person at a
terminal.

**Deliverables.**
- [[ABAC-AUTHORIZATION]] §"Signature-trust posture (read this)": the full statement, with the example's APISIX
  `claim_validator` configuration as the reference — and drop that section's stale "a `verifySignature` mode is
  reserved" sentence (the property was deleted).
- A one-line pointer to it in: README step 3; [[TWO-LAYER-AUTHORIZATION]] §"Signature trust between the layers"
  (and drop its own copy of the stale `verifySignature` sentence); the `trustForwardedJwt` javadoc in
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
