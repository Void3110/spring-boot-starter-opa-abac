---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
---

# GATEWAY-AUDIENCE-BINDING — tokens bound to this gateway, on APISIX 3.18

> **Status: 📋 researched, not yet designed.** Opened 2026-09-07 from a maintainer question — *"is
> an audience check something anyone using the example app as a starting point would miss?"* — and
> answered by measurement: **yes, because the example itself has none.** Two decisions are already
> taken (below); the grill-me, `00-DESIGN`, **ADR 0034** and the decomposition are the next steps.
> **Build: collaborative** (the [[SPA-CHALLENGE-UX]] shape — mini package, no autonomous prompt).
> Slice-shaped, not backlog-shaped: it changes the rig's security contract, the gateway version, the
> realm, the e2e suite and three guides at once.

## What the rig does today (measured 2026-09-06, all three claims re-checkable)

- **The gateway is the only verifier.** All three example services run with
  `opa.abac.jwt.trust-forwarded-jwt=true` and decode the forwarded token without verifying anything.
  APISIX **3.11.0**'s `openid-connect` plugin (`use_jwks: true`) verifies the **signature and expiry**;
  the hand-written `serverless-pre-function` in `infra/apisix/init-routes.sh` checks **`iss`** against
  `ISSUER_ALLOWLIST`. **Nothing checks `aud`.** The 3.11.0 plugin source has no `claim_validator`, and
  `lua-resty-openidc` validates audience only on the browser-flow **ID token**, never on a bearer
  access token.
- **The realm mints interchangeable tokens.** `infra/keycloak/realm-export.json` has **no audience
  mapper on any of its six clients** and defines no client scopes of its own, so every access token
  looks the same to the gateway. A `catalog-directory` **service-account** token — meant only for the
  Keycloak admin API — would be accepted on any route. No e2e cell proves otherwise.
- **Who legitimately reaches the gateway with a token:** `catalog-gateway` (the 20 runners' ROPC
  mints + the browser flow), `catalog-spa` (the SPA's PKCE flow + the step-up miner), and the three
  `catalog-agent-*` clients (ROPC → the `/mcp` route). **Unaffected hops:** the MCP server → catalog
  service call is in-network (`CatalogApiClient`, never through the gateway), and the directory
  service-account token only ever addresses `keycloak:8888/admin/…` (`run-team-matrix.sh` E-pre,
  `seed-demo-data.sh`).

## Decisions taken (maintainer, 2026-09-06 → 07)

1. **Bump APISIX first, then bind the audience with the plugin — not with more Lua.** Target
   **3.18.0** (released 2026-08-20; `apache/apisix:3.18.0-debian` on Docker Hub 2026-08-21). Its
   `openid-connect` plugin carries `claim_validator.audience` (added 3.12.0; **3.18.0** hardened it —
   `match_with_client_id` now implies `required`, and a token *without* the claim is rejected) and
   **`claim_validator.issuer.valid_issuers`**, which **retires the Lua issuer guard** entirely. The
   guard's contract (three rig authorities, case-insensitive scheme match, the E9 pins in
   `run-step-up-matrix.sh`) becomes plugin configuration.
2. **A mini-slice with a short ADR (0034, "audience-bound gateway tokens")**, built collaboratively
   on its own branch. Not a backlog item, not a direct change.

## What the bump drags in (from the 3.11.0 → 3.18.0 changelog — verify each at the source)

| Change | Where it bites here |
|---|---|
| **3.14.0** — `session.secret` **required** when `bearer_only=false` | the default browser-flow mode (`ENABLE_SPA=0`): `init-routes.sh` must set `session: { secret: … }` on every openid-connect block |
| **3.12.0** — opentelemetry collector config moved from `plugin_attr` (config.yaml) to **plugin metadata** (`PUT /apisix/admin/plugin_metadata/opentelemetry`) | `infra/apisix/config.yaml` `plugin_attr.opentelemetry` is silently ignored → **traces stop reaching Jaeger** unless `init-routes.sh` writes the metadata |
| **3.16.0** — `ssl_verify` default flipped to `true` | every block already sets `ssl_verify: false` explicitly (plain http to Keycloak) — no-op, keep it explicit |
| 3.17.0 — batch-requests schema tightened, jwt-auth claims hardened | not used here |
| `etcd` stays `quay.io/coreos/etcd:v3.5.16` | expected compatible — prove with the full matrix, do not assume |

Route plugins in use: `proxy-rewrite`, `response-rewrite`, `opentelemetry` (per-route sampler only),
`opa`, `openid-connect`, `serverless-pre-function` (the guard — to be deleted), `cors`. The
`PERFORMANCE.md` rig table names 3.11.0 as a **historical record** of that run — leave it.

## The shape on the table (not yet the design)

- **Realm:** one `oidc-audience-mapper` (`included.client.audience`), carried by a **client scope
  defaulted onto the four legitimate clients** — `catalog-gateway`, `catalog-spa`, the three
  `catalog-agent-*` — and deliberately **absent from `catalog-directory`**, which is what gives the
  e2e its negative control. Realm changes land only on a Keycloak **recreate** (`start-dev
  --import-realm` from the bind-mounted export).
- **Gateway:** `claim_validator.audience.match_with_client_id: true` (the plugin's `client_id` is
  `catalog-gateway`) + `claim_validator.issuer.valid_issuers` = the three authorities now in
  `ISSUER_ALLOWLIST`; delete the Lua guard; add `session.secret`; write the opentelemetry metadata.
- **E2E:** two pins beside the E9 issuer controls in `run-step-up-matrix.sh` — a directory
  service-account token through the gateway → **401**; a gateway-minted token → **200** — and the
  existing E9 foreign-issuer / gateway-origin / `BEARER`-spelling controls re-prove `valid_issuers`.
  The full matrices are the bump's proof.
- **Docs:** `infra/compose.keycloak.yaml` header, `docs/guides/E2E-TESTING.md` "in-network token
  caveat", and — the reason this slice exists — an explicit sentence in the starter's
  `trust-forwarded-jwt` documentation: *audience binding is the gateway's job; here is the example
  doing it.*

## Forks for the grill-me (decide there, not here)

1. **Audience value** — the plugin's own client id `catalog-gateway` (what `match_with_client_id`
   checks; zero config) vs a named API audience (`claim_validator.audience.claim` + a custom
   audience mapper; reads better for adopters whose gateway client id is an implementation detail).
2. **Mapper placement** — one shared client scope (one place to audit, one negative control) vs a
   mapper per client (visible in each client's config, no scope indirection).
3. **Ticket split** — the APISIX bump as **its own ticket**, proven by the full e2e matrices before
   any audience ticket touches the realm, vs one combined bump-and-bind ticket.
4. **Where the adopter warning lives** — the starter's `trust-forwarded-jwt` docs, the E2E guide's
   caveat, or both (and whether [[TWO-LAYER-AUTHORIZATION]] gets a line).

## Next steps

1. Prime `opa-abac-rig-deploy-ops` + `opa-abac-e2e-suite` (`--budget 8000`), then `/grill-me` on
   the four forks. 2. Write `00-DESIGN.md` + ADR 0034. 3. `/decompose` (collaborative package).
4. Build on `feature/void3110/gateway-audience-binding`; the first whole-delivery review is one
   multi-lens `/deep-review` pass, follow-ups a Fable+Opus pair (the repo's routing since 2026-09-05).

## Related

- [[SPA-CHALLENGE-UX]] — the collaborative mini-package model
- [[STEP-UP-ELEVATION]] · `docs/code-review/STEP-UP-ELEVATION-REVIEW.md` — where the Lua issuer
  guard was hardened (the SPA-issuer and `BEARER`-spelling lessons the plugin config must keep)
- [[AGENT-TOOL-AUTHZ]] — the `catalog-agent-*` clients and the `/mcp` route
- [[ENGINEERING-BACKLOG]] item 10 — the other post-1.2.0 hygiene item (Tomcat, BOM-managed)
