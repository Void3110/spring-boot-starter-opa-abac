---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# ADR 0035 — Gateway tokens are bound to an audience and an issuer, and the MCP surface is discoverable by OAuth

**Status:** Accepted (implemented 2026-10-29, release 1.5.0) — slice **GATEWAY-AUDIENCE-BINDING** ([[GATEWAY-AUDIENCE-BINDING]], design [[GATEWAY-AUDIENCE-BINDING/00-DESIGN|00-DESIGN]])
**Date:** 2026-10-28
**Context tags:** gateway trust, `aud`, `iss`, APISIX `openid-connect`, Keycloak audience mapper,
RFC 9728 protected-resource metadata, RFC 8707 resource indicators, MCP authorization, token passthrough

> Every example service runs with `opa.abac.subject.trust-forwarded-jwt=true`: it decodes the forwarded
> token and verifies no signature, issuer or audience (only its structure and expiry), because the gateway is
> the only verifier. The gateway verified the
> **signature and expiry** and — through a hand-written Lua guard — the **issuer**, but **never the
> audience**. Any realm-signed token was accepted on any route, including a service-account token meant
> only for the Keycloak admin API. And a real MCP client had no way to discover how to log in to the
> `/mcp` surface. This ADR binds every gateway token to one audience and an issuer allowlist **in the
> gateway's own plugin**, and makes the MCP surface discoverable by RFC 9728. Settled with the
> maintainer in the grill of 2026-10-28 (thirteen forks, [[00-DESIGN]]
> §Considered and rejected).

## Context

Measured on the rig (2026-09-06, re-read 2026-10-28):

- **The gateway is the only verifier.** APISIX 3.11.0's `openid-connect` (`use_jwks: true`) checks the
  signature against the realm JWKS and the expiry. A `serverless-pre-function` (`init-routes.sh`) checks
  `iss` against three rig authorities (`keycloak:8888`, the gateway origin `localhost:9085`, the published
  Keycloak port `localhost:28888`). 3.11.0's plugin has no audience validator, and its OIDC library
  validates audience only on the browser flow's ID token, never on a bearer access token.
- **The realm mints interchangeable tokens.** No client carries an audience mapper and the realm defines
  no client scopes of its own, so every access token looks the same to the gateway. A `catalog-directory`
  service-account token — meant only for `keycloak:8888/admin/…` — would be accepted on every route.
- **APISIX added `claim_validator.audience` in 3.12** (3.18 hardened `match_with_client_id`: it now
  implies `required`, and a token without the claim is rejected) and **`claim_validator.issuer.valid_issuers`**.
  The current release is **3.19.0** (2026-09-28); its breaking changes (introspection-issuer validation,
  `https` upstream TLS verification, unrelated plugins) do not touch this rig.
- **The MCP authorization spec (revision 2025-11-25)** requires an MCP server to publish RFC 9728
  protected-resource metadata (`authorization_servers` at least), through **one** of a `WWW-Authenticate`
  `resource_metadata` parameter or a well-known URI — clients must support both and fall back to the
  path-inserted, then the root, well-known URI. It requires the server to accept only tokens issued for
  it, and says the server *"MUST NOT pass through the token it received"* to an upstream API.
- **Keycloak sets `aud` from mappers, not from RFC 8707.** Measured on another Keycloak realm
  (2026-09-16): an MCP client's `resource=<mcp url>` on the authorize request left `aud` as the mapper's
  client id. To be re-measured on this rig's Keycloak 26.3.2 before the binding ticket (P0 probe).
- **The example MCP server forwards the caller's bearer to the catalog service in-network**
  (`CatalogApiClient`, ADR 0028's two-layer design: the catalog's own policies decide with the caller's
  own identity). That call never crosses the gateway.

## Decision

### 1. One gateway audience for every route

Every route the gateway authenticates — the catalog, the user-service, `/mcp` — requires
**`aud ∋ catalog-gateway`** of every bearer-presented token: `claim_validator.audience.match_with_client_id:
true` on each `openid-connect` block (whose `client_id` is `catalog-gateway`). That holds in the browser-flow
mode too, for a request carrying a bearer header; a browser-flow *session* carries the token the gateway's own
client obtained — stamped by its own mapper, the cookie encrypted under `session.secret` — so that path needs
no audience check. There is **one protected resource**
behind **one gateway**: the MCP surface and the catalog API share its audience.

A token without that audience is refused at the edge, whatever its signature. The service-account client
`catalog-directory` deliberately never carries it — it is the negative control.

### 2. The audience comes from a mapper on each legitimate client

Every client that legitimately reaches the gateway — `catalog-gateway`, `catalog-spa`, the three
`catalog-agent-*`, and the MCP host client (§5) — carries one inline `oidc-audience-mapper`
(`included.client.audience = catalog-gateway`, access token only). A client that misses it gets a loud
refusal, never access, and an e2e cell per client guards the copies against drift. *(The grill chose one
shared client scope; the decomposition reversed it on 2026-10-28: Keycloak 26.3.2 creates its built-in
client scopes on import only when the export declares no `clientScopes`, so a shared scope would have meant
owning every built-in scope in the repo or defining the realm in two places.)*

### 3. The issuer is checked by the same plugin; the Lua guard is deleted

`claim_validator.issuer.valid_issuers` carries the three rig authorities (the existing, overridable
`ISSUER_ALLOWLIST`). The `serverless-pre-function` guard is **deleted**. Its one subtle contract — a
case-insensitive scheme match so its skip set could never be larger than the authenticator's reject set —
exists only because the guard and the authenticator were two plugins; one plugin cannot disagree with
itself. The step-up runner's E9 controls (foreign issuer refused, gateway origin admitted, the `BEARER`
spelling) re-prove the contract.

### 4. The gateway moves to APISIX 3.19.0, as its own step

The bump (3.11.0 → 3.19.0) lands **before** any audience change, proven by the full e2e suite on its
own, so a 3.19 regression is never confused with an audience refusal. It must already carry §3's
`valid_issuers`: since 3.12 a block without it validates `iss` against the discovery issuer, which would
refuse every token minted through the gateway origin. It brings `session.secret` on every
browser-flow `openid-connect` block (required since 3.14 when `bearer_only=false`) and moves the
opentelemetry collector configuration from `plugin_attr` to plugin metadata (since 3.12).

### 5. The MCP host client is an agent

A new **public** client for an MCP host (e.g. Claude Code) to log a user in with: authorization code +
PKCE (`S256`), a fixed `localhost` callback, no password grant, the audience mapper (§2). It carries
the **same hardcoded actor mapper** as the `catalog-agent-*` clients, naming its own actor, and is listed in
`opa.abac.mcp.identity.agent-clients` — so a token from it without an actor is refused
`tool-gate-actor-required`. Its capability profile is **reads only, capped at `low` risk**, with every tool
listed: the risk cap, not the tool list, is what removes the `medium`-risk `get_product`. ADR 0028's model —
an agent is its own client with an IdP-minted actor — then holds on the path a real MCP client uses, not
only on the scripted password-grant runners.

### 6. The MCP server publishes RFC 9728 metadata — from configuration, through an unauthenticated route

`example-mcp-server` serves the protected-resource document at exactly two paths,
`/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`, through a gateway
route that carries **no auth plugin** (the document is what a client reads before it has a token). The
document names `resource` (the MCP endpoint's absolute URL, `http://localhost:9085/mcp` on the rig),
`authorization_servers` (the realm as the host reaches it, `http://localhost:9085/realms/catalog-demo`)
and `bearer_methods_supported: [header]`. It carries **no `scopes_supported`**: each legitimate client's own
mapper adds the audience whatever scope is requested, so no scope a client asks for grants anything.

The failure contract:

| State | Behavior |
|---|---|
| Both values unset | Discovery **off**: both paths answer 404; one startup WARN names the keys. A legitimate state — the gate still enforces every call; only the OAuth login is unavailable. |
| Exactly one value set, or a value that is not an absolute `http(s)` URL, carries a fragment, or (for `resource`) names a path other than the MCP endpoint's | **Startup failure.** A half-written document would advertise a guess. |
| Any request, any `Host` header | The **identical** document — values only ever come from configuration, never from the request (a request-derived URL would let a forged `Host` send a client to an attacker's authorization server). |

Discovery uses the **well-known URI mechanism only**: the gateway's 401 on `/mcp` stays the plugin's own,
without a `resource_metadata` parameter — the spec requires one mechanism, and clients fall back to the
path-inserted URI this server answers.

### 7. Refresh tokens rotate, realm-wide

`revokeRefreshToken: true`, `refreshTokenMaxReuse: 0` — the MCP spec's requirement for public clients, applied
to both. Keycloak 26.3.2's semantics: every refresh returns a new refresh token; reusing one already used →
`invalid_grant` "Maximum allowed refresh token reuse exceeded", and the client session is **detached**; an
older token after a newer one was used → "Stale token" (second-granularity). Because reuse detaches the
session, **the SPA's refresh is single-flight**: its `freshUser()` shares one in-flight renewal among
concurrent callers (a catalog view's parallel reads each refreshed on their own before, and oidc-client-ts does
not de-duplicate). The password-grant runners never refresh.

### 8. The library changes only in documentation

The RFC 9728 support is **example code**. The published modules change by one javadoc sentence on
`trust-forwarded-jwt` (§Consequences); 1.5.0's published surface stays the one already reviewed. A library
seam for protected-resource metadata waits for a second consumer (ADR 0036's incubating stance).

## The documented deviation: the in-network bearer

The example MCP server still forwards the caller's bearer to the catalog service. Under §1 that token was
issued for the gateway's single audience, which the catalog shares, so the catalog is not a third-party
upstream holding a token meant for someone else. A **strict** reading of the MCP spec still forbids it —
an MCP server *"MUST NOT pass through the token it received"*. The spec-aligned successor is **RFC 8693
token exchange** at the MCP server (a catalog-audience token minted per call), which needs per-resource
audiences and a confidential exchange client; it is recorded as an engineering-backlog item, not built
here. The guide states the deviation where it describes the forwarded bearer.

## Considered options

| Option | Why not |
|---|---|
| **Per-resource audiences** (`/mcp` vs the catalog API) + RFC 8693 exchange | Spec-purist and the eventual successor, but it adds Keycloak token-exchange configuration, a confidential exchange client, an exchange step in the example's catalog client and new failure classes to pin — a slice of its own. |
| A **named API audience** (`claim_validator.audience.claim` + a custom value) | Reads better for an adopter whose gateway client id is an implementation detail, but costs a custom claim value and one more place to configure; `match_with_client_id` is zero configuration and is what the plugin hardened in 3.18. |
| One **shared client scope** | One definition to audit (the grill's first choice), but Keycloak's import then stops creating its built-in scopes — the repo would own them all, or a post-import step would define the realm in two places. |
| **Keep the Lua issuer guard** | Two plugins deciding one question is how its case-sensitivity trap existed; the plugin's own `valid_issuers` removes the class. |
| The MCP host as a **human** client | An AI host acting for the user would get the user's full ceiling on the one path real MCP clients use — the narrowing this repo demonstrates would not apply there. |
| Spring Security's `OAuth2ProtectedResourceMetadataFilter` | It matches every `/.well-known/oauth-protected-resource/**` sub-path, presets `resource` from the request's URL (its `Host`) and answers whenever installed — three breaches of §6's contract (`javap -c`, 7.0.7). A two-mapping controller instead. |
| The **gateway** serves the metadata statically | The document would live in a shell script instead of the resource it describes, and an adopter copying the MCP server would get nothing. |
| Stamp `resource_metadata` on the gateway's 401 | One fewer client round trip, but it needs the gateway to rewrite a header on a response its own auth plugin generated; the spec requires only one mechanism. |
| 404 + WARN for **every** misconfiguration | The service always boots, but a typo in one value silently turns discovery off instead of failing the deploy that introduced it. |
| Library code in 1.5.0 | New published API that would need its own review pass, and a Spring Security dependency question for `opa-abac-mcp`. |

## Consequences

- **The example's gateway now enforces what `trust-forwarded-jwt` assumes.** The adopter warning is stated
  once in full in [[ABAC-AUTHORIZATION]] §Signature trust — the gateway must verify signature, expiry,
  **issuer and audience** — with a pointer from every place that restates the posture (README, [[TWO-LAYER-AUTHORIZATION]],
  the property's javadoc, the "from example to your app" prompt). The E2E guide's "in-network token
  caveat" is rewritten to the measured truth.
- **A realm change needs a Keycloak recreate** (the realm is imported from the bind-mounted export at
  start), so every realm ticket's proof starts from a recreated Keycloak.
- **New e2e controls:** a directory service-account token refused at the gateway, a gateway-minted token
  admitted, the metadata document on both paths (identical under a forged `Host`), a token minted through
  the full discovery flow carrying the audience and the actor, its narrowed roster, `get_product` refused
  at `tool-gate` while the same human reads it over REST, and a refresh-token replay refused.
- **The SPA's refresh becomes single-flight** (§7) — the one change to the demo console, made because rotation
  turns a concurrent refresh into a session loss.
- **A real MCP client can log in to the rig** — documented as a manual runbook, not a CI step (client
  versions move faster than the suite).
- **No published-API change.** The 1.5.0 cut waits for this slice only because the slice changes what the
  example demonstrates about the library's trust posture.

## Related

- [[0028-agent-tool-call-authorization|ADR 0028]] — the two-layer model and the forwarded bearer
- [[0036-mcp-auth-library-module|ADR 0036]] — `opa-abac-mcp`, `agent-clients` and the actor guard
- [[0030-step-up-decision-contract|ADR 0030]] — the step-up runner whose E9 issuer controls this re-proves
- [[GATEWAY-AUDIENCE-BINDING]] — the slice, its design and its forks
