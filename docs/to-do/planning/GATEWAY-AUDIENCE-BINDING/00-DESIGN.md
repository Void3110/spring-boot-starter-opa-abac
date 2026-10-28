---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# GATEWAY-AUDIENCE-BINDING — 00-DESIGN

> Bind every gateway token to one audience and an issuer allowlist **in APISIX's own plugin**, and make
> the example's MCP surface discoverable by an OAuth client (RFC 9728). The contract is
> [[0035-audience-bound-gateway-tokens|ADR 0035]]. Settled 2026-10-28 (grill-me: thirteen forks, all in
> §Considered and rejected). **Collaborative build** — a mini package, no autonomous prompt (the research's
> standing decision). Lands **before the 1.5.0 cut**; it changes no published API.

## The feature in one paragraph

Today the gateway is the only verifier, and it checks a token's signature, expiry and (by a Lua guard) its
issuer — never its audience, so any realm-signed token is accepted on any route. After this slice, every
route requires `aud ∋ catalog-gateway`, stamped by one shared client scope onto the clients that
legitimately reach the gateway; the issuer allowlist moves from the Lua guard into the same plugin; the
gateway runs APISIX 3.19.0. And a real MCP client — Claude Code, say — can find out how to log in to
`/mcp`: the MCP server publishes an RFC 9728 document, the client logs the user in through a new public
**agent** client, and the tool-gate narrows that agent exactly as it narrows the scripted ones.

## What this builds on, and what it must not break

- **ADR 0028 / 0036** — the two-layer agent model, the `agent-clients` actor guard, the capability
  profiles; the MCP server's in-network catalog call with the caller's bearer (kept, as a documented
  deviation — ADR 0035 §The documented deviation).
- **ADR 0030 / the step-up runner's E9 controls** — the three rig authorities and the issuer contract the
  Lua guard implemented (foreign issuer refused, gateway origin admitted, `BEARER` spelling). They must
  stay green with the guard deleted.
- **The three rig authorities** (Mulch `opa-abac-rig-deploy-ops` mx-0520eb): `keycloak:8888` (every
  runner's mint, the miner, APISIX's own discovery), `localhost:9085` (the gateway origin — the SPA's whole
  PKCE flow, and now the MCP host's), `localhost:28888` (the published Keycloak port). Any edge check keyed
  on `iss` must admit all three; enumerate the token-*minting* paths, not the token-using ones.
- **Flag-gated route blocks are two-armed** (mx-a1144b): a block that PUTs on `flag=1` DELETEs on
  `flag=0`, or a flip leaves stale public routes.
- **Realm changes land only on a Keycloak recreate** — the realm is imported from the bind-mounted export.
- **Must not break:** any of the twenty e2e runners; the SPA (packaged and vite-dev); the agent matrix
  E1–E12; the step-up miner; Jaeger traces.

## The slice boundary

**In:** the APISIX bump; plugin-side issuer + audience validation on every `openid-connect` route; the
`gateway-audience` client scope; realm-wide refresh-token rotation; the MCP host agent client and its
capability profile; the RFC 9728 document in `example-mcp-server` and its gateway route; the miner's
discovery mode; the e2e cells; the docs (§T4).

**Out:** RFC 8693 token exchange at the MCP server (the successor to the documented deviation — a backlog
item); a library seam for protected-resource metadata (waits for a second consumer); the `WWW-Authenticate`
`resource_metadata` parameter on the gateway's 401; Client ID Metadata Documents and dynamic client
registration (the host client is pre-registered); `https` on the rig.

## The design

### T1 — APISIX 3.11.0 → 3.19.0, nothing else

- `apache/apisix:3.19.0-debian` in the compose file.
- `session.secret` on every `openid-connect` block whose `bearer_only` is `false` (required since 3.14) —
  a rig secret through the environment, with a development default.
- The opentelemetry collector configuration moves from `config.yaml` `plugin_attr` to plugin metadata
  (`PUT /apisix/admin/plugin_metadata/opentelemetry`, written by `init-routes.sh`) — since 3.12 the old
  location is silently ignored and traces would stop.
- `ssl_verify: false` stays explicit on every block (the default flipped to `true` in 3.16).
- The Lua issuer guard **stays** in this ticket.
- **Proof:** every e2e runner green on the bumped gateway, and a request's trace still reaches Jaeger.
  `etcd` stays `v3.5.16` — proven by the suite, not assumed.

### T2 — Issuer and audience, in the plugin

- **Gateway:** every `openid-connect` block gains
  `claim_validator: { issuer: { valid_issuers: [<ISSUER_ALLOWLIST>] }, audience: { match_with_client_id: true } }`;
  the `serverless-pre-function` issuer guard and its Lua are **deleted**. `ISSUER_ALLOWLIST` stays the one
  documented, overridable variable.
- **Realm:** a client scope `gateway-audience` with one `oidc-audience-mapper`
  (`included.client.audience: catalog-gateway`, access token yes, ID token no), a **default** scope of
  `catalog-gateway`, `catalog-spa` and the three `catalog-agent-*`. `catalog-directory` never gets it.
- **P0 probes, before the gateway change is trusted** (recorded in STATUS with the raw evidence):
  1. Keycloak 26.3.2 with `resource=http://localhost:9085/mcp` on authorize and token: what is `aud`?
     (Expected: the mapper's value; RFC 8707 ignored. If Keycloak honors it, `aud` must still contain
     `catalog-gateway` for the binding to hold — record which.)
  2. APISIX 3.19 on a token whose `aud` is an **array** containing `catalog-gateway` plus others (Keycloak
     adds `account` for some clients): admitted? On a token without it: which status (expected a plain
     403), which body?
- **Proof (beside E9 in the step-up runner):** a `catalog-directory` service-account token through the
  gateway → refused (the P0-measured status); a `catalog-gateway` password-grant token → 200; the E9
  foreign-issuer / gateway-origin / `BEARER` controls green with the guard gone; every runner green on a
  recreated Keycloak.

### T3 — The MCP OAuth surface

**The host client** — `catalog-mcp-host`: public, standard flow, PKCE `S256` required, redirect URIs
`http://localhost:<fixed port>/callback` and `http://127.0.0.1:<fixed port>/callback`, no direct access
grants, default scopes = the existing ones + `gateway-audience`, and the hardcoded actor mapper with its own
actor id `agent-host`.

**Its capability** (`opa.abac.mcp.agents.profiles[]` in the example):

```yaml
- actor-id: agent-host
  allowed-categories: [READ]
  allowed-tools: [list_catalogs, get_catalog, list_categories, get_product]
  allowed-actions: [view, list]
  max-risk-tag: low          # the cut: get_product is the one medium-risk read
```

`catalog-mcp-host` joins `opa.abac.mcp.identity.agent-clients` (with `azp` copied, the wiring check's
requirement), so a token from it without an actor is refused `tool-gate-actor-required`. Every reader of
that switch must agree — the E12 drill's temporary listing is reconciled with the new default.

**Refresh tokens rotate realm-wide:** `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`.

**The RFC 9728 document** — `example-mcp-server`, two exact paths, values from configuration
(`example.mcp.oauth.protected-resource.resource`, `…authorization-server`; set by the MCP compose file to
`http://localhost:9085/mcp` and `http://localhost:9085/realms/catalog-demo`):

```json
{
  "resource": "http://localhost:9085/mcp",
  "authorization_servers": ["http://localhost:9085/realms/catalog-demo"],
  "bearer_methods_supported": ["header"]
}
```

| State | Behavior |
|---|---|
| Both values unset | Both paths 404; one startup WARN naming the keys. Discovery off; the gate still enforces. |
| One set, or a value not an absolute `http(s)` URL, with a fragment, or a `resource` path other than the MCP endpoint's | Startup failure, naming the key and the reason. |
| Any request, any `Host` | The identical document — never request-derived. |

The application's security chain permits **exactly** those two paths (never `/.well-known/**`). Spring
Security 7.0.7's `OAuth2ProtectedResourceMetadataFilter` or a small controller — **decided by a spike** in
the ticket; the example authenticates by forwarded JWT, not as a resource server, so the filter may not fit.

**The gateway route** — `mcp-protected-resource`: `uris` = the two paths, `GET` only, **no auth plugin**,
upstream `mcp-pool`, a priority above the catch-all; two-armed with `ENABLE_MCP`.

**The miner learns discovery** — `mint-code-flow-token.py --discover http://localhost:9085/mcp`: fetch the
path-inserted document, fall back to the root one; read `authorization_servers`; try the spec's three
authorization-server metadata URLs in order (RFC 8414 path-inserted, OIDC path-inserted, OIDC appended);
refuse unless `code_challenge_methods_supported` lists `S256`; send `resource` on authorize **and** token;
mint as `catalog-mcp-host`. The probes' answers (which of the three succeeded, what the gateway answered for
the others) are recorded in STATUS — the first two hit the gateway's catch-all.

**Proof (the agent matrix, new cells):**
- the document on both paths, unauthenticated: 200, exact `resource` and `authorization_servers`; the same
  with `Host: attacker.invalid` → the identical document;
- a token minted by `--discover`: `aud ∋ catalog-gateway`, `azp = catalog-mcp-host`, an actor present,
  `iss` = the gateway origin;
- its `tools/list` = exactly the three `low`-risk tools; `get_product` → `tool-gate` denial on risk tier,
  while the same human reads that product over REST (200);
- a refresh with the host's refresh token → a new refresh token; replaying the old one → `invalid_grant`;
- the SPA auth smoke green with rotation on.

The "discovery off" and "half-configured" states are unit/slice-test cases in the example (no rig drill).

### T4 — Docs

- **ADR 0035** status → implemented; the ADR index row.
- **The adopter warning, everywhere it applies:** the full statement once in [[ABAC-AUTHORIZATION]]
  §Signature trust (the gateway must verify **signature, expiry, issuer and audience**; the example's APISIX
  configuration as the reference); a one-line pointer in README step 3, [[TWO-LAYER-AUTHORIZATION]]
  §Signature trust (and drop its stale "reserved `verifySignature` mode" sentence — that property was
  deleted), the `trustForwardedJwt` javadoc in `OpaAbacProperties`, and `docs/prompts/FROM-EXAMPLE-TO-YOUR-APP.md`.
- **E2E guide:** the "in-network token caveat" rewritten to the measured truth (what the gateway actually
  enforces, the three authorities, the audience).
- **[[AGENT-TOOL-AUTHORIZATION]]:** an MCP OAuth section — discovery, the host agent client and its profile,
  the forwarded bearer as a documented deviation with RFC 8693 named as the successor.
- **A runbook:** logging Claude Code in to the rig (`claude mcp add --transport http … --client-id
  catalog-mcp-host --callback-port <port>`, then the login) — manual, versions drift; verify the client's
  current flags before believing the page.
- `infra/compose.keycloak.yaml` header, `infra/README.md`, the CHANGELOG `[Unreleased] — 1.5.0` section
  (a Documentation line: the trust posture now names issuer + audience), root `CLAUDE.md` if a run step
  changes.
- **Backlog:** RFC 8693 token exchange at the MCP server; a library seam for protected-resource metadata.

## Validation

Every ticket ends green on the rig before the next one touches anything: T1 the full suite; T2 a recreated
Keycloak + the full suite + the new controls; T3 the agent matrix + the SPA smoke + the example's tests;
T4 the doc gates. `./gradlew build` and the local Sonar gate on any `.java` change; `check-shell-guards.py`
and `check-collection-conformance.py` on every runner/collection change. The first whole-delivery review is
one multi-lens `/deep-review` pass; follow-up rounds are the Fable + Opus pair.

## Ship shape

One branch, `feature/void3110/gateway-audience-binding`, one commit per ticket (plus Mulch syncs), one PR.
Then the 1.5.0 cut (ADR 0038 + MCP-AUTH-LIBRARY + this slice's documentation).

## Considered and rejected (the grill, 2026-10-28)

1. **Audience** — *per-resource audiences + RFC 8693 exchange* rejected for now (a slice of its own; the
   recorded successor); *a named API audience* rejected (custom value + one more config place;
   `match_with_client_id` is zero-config). **One gateway audience.**
2. **Mapper placement** — *a mapper per client* rejected (six copies that drift). **One shared scope.**
3. **The host client** — *a human client* rejected (an AI host would get the user's full ceiling on the one
   real MCP path). **An agent client.**
4. **Who serves the metadata** — *the gateway, statically* rejected (the document belongs to the resource,
   and adopters copying the MCP server would get nothing). **`example-mcp-server`.**
5. **The 401 header** — *stamp `resource_metadata` on the gateway's 401* rejected (the spec requires one
   mechanism; it needs the gateway to rewrite its own auth plugin's response). **Well-known only.**
6. **Proof** — *a real client only* rejected (nothing re-proves it); *newman cells only* rejected (no
   login-flow proof). **Scripted discovery flow + cells + a manual runbook.**
7. **APISIX** — *3.18.0* rejected (one release behind, no extra safety). **3.19.0.**
8. **Tickets** — *two slices* rejected (a second package/PR/review before 1.5.0); *bump + bind combined*
   rejected (a red matrix with two possible causes). **Four tickets.**
9. **The adopter warning** — *the canonical place only* rejected (the README step an adopter follows would
   still say only "signature-validating"). **Canonical + every restatement.**
10. **The host profile** — *all reads at medium* rejected (no cell could tell the agent branch from the
    human one); *the readonly persona's two tools* rejected (a near-useless demo). **Reads, capped at low.**
11. **The document's failure contract** — *404 + WARN for everything* rejected (a typo silently turns
    discovery off). **Off is a state, half is an error, never request-derived.**
12. **Refresh tokens** — *no refresh for the host* rejected (a re-login every 30 minutes); *leave reusable*
    rejected (a spec MUST unmet). **Realm-wide rotation.**
13. **Placement** — *library code in 1.5.0* rejected (new published API needing its own review).
    **Example only.**
