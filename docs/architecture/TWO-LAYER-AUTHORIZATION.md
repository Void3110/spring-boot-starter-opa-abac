---
tags:
  - status/active
  - type/architecture
  - area/abac
  - area/spring-security
---

# Two-layer authorization — gateway (coarse) ↔ app (fine-grained)

Authorization in this system is split across two layers. Each does what it is best placed to do, and the
app **never trusts the gateway for the fine-grained decision** — it re-derives identity and re-asks OPA.

```
            ┌─────────────────────────── Gateway (APISIX) ───────────────────────────┐
client ───▶ │  openid-connect: validate the JWT against the realm JWKS, forward the  │ ───▶ app pods
            │  Bearer.  (optional) a coarse route-level OPA check (gateway.rego).     │
            └────────────────────────────────────────────────────────────────────────┘
                                                  │  forwards: Authorization: Bearer <jwt>
                                                  ▼
            ┌──────────────────────────── App (Spring + library) ────────────────────┐
            │  AbacFilter: extract the Subject from the JWT (structural + exp; no     │
            │              signature re-verify — trusts the gateway).                 │
            │  @OpaPreAuthorize: look up the caller's RoleDefinition, build the       │
            │              AbacContext, ask OPA the resource/action question.         │
            └────────────────────────────────────────────────────────────────────────┘
                                                  │  POST /v1/data/<type>  {"input": …}
                                                  ▼
                                                 OPA  (per-type rego, default-deny)
```

## Layer 1 — gateway: coarse

APISIX terminates OIDC (`openid-connect`): it validates the access token against the realm JWKS and
forwards the Bearer upstream. It may also keep a **coarse** route-level OPA decision (`gateway.rego`) —
e.g. "is this path/method allowed at all". The gateway answers *"is this a valid caller reaching a valid
route?"*, not *"may this caller perform this action on this resource?"*.

## Layer 2 — app: fine-grained

The application does the attribute-level decision itself, via the library
([[ABAC-AUTHORIZATION]]): it extracts the `Subject` from the forwarded JWT, looks up the caller's
`RoleDefinition`, and asks OPA whether the **action** is permitted on the **resource type**. Since
Phase 6.5 ([[0007-coarse-grained-permission-categories|ADR 0007]]) that map holds **coarse category
tokens** (`READ`/`WRITE`/`TAG`/`GRANT`), and the policy decides via
`permissions.effective_actions(input.role_definition, input.resource.type)` — the tokens expanded
through `data.permission_categories`, minus `denied_actions`. Fine-grained authorization belongs here
because only the app knows the resource semantics; the gateway shouldn't carry that knowledge.

### Why the app re-derives identity (and doesn't trust gateway headers)

Earlier in the PoC the gateway ran a throwaway Lua `serverless-pre-function` "enricher" that decoded the
JWT and injected `X-User-Id` / `X-Username` headers for OPA — a **demo scaffold** so the topology was
visible before the library existed. That enricher has been **retired**: the app now extracts identity
natively (`AbacFilter` + `AbacSubjectExtractor`). Reasons:

- **No mediator.** The project thesis is to be Spring-native — no bespoke gateway "mediator" translating
  identity for the app. Identity extraction is a library concern.
- **Trust boundary.** Header-injected identity is only as trustworthy as every hop that can set those
  headers. Re-deriving the Subject from the (gateway-validated) JWT inside the app keeps the trust model
  explicit and the decision self-contained.

The gateway's job shrinks to **authn + forward + (optional) coarse route check**, plus two
structural edge guards — the `/internal/*` 404 block and the token-issuer allow-list; everything
attribute-level moves into the app.

## Signature trust between the layers

The app does **not** re-verify the JWT signature — it trusts that the gateway already did
(`openid-connect` against the JWKS) and does structural + `exp` checks only. That posture is an
explicit opt-in: `opa.abac.subject.trust-forwarded-jwt=true`; without it the default extractor
refuses and the app fails closed. This is safe **only because**
the app sits behind a validating gateway and is not directly exposed. A `verifySignature` mode is reserved
for a gateway-less deployment but not implemented in this slice. See the loud tradeoff note in
[[ABAC-AUTHORIZATION]].

## Per-type policy documents

The app posts to `/v1/data/<type>`, so OPA holds one rego document per resource type —
`catalog`/`category`/`product` for the catalog service, `team`/`role` for user-management, and
`agent_tools` for the MCP tool gate — each `package <type>` with `default allow := false`. Per-type documents
keep each type's rules, tests, and ownership separable as the policy surface grows — chosen over a single
shared `allow` rule. The pluggable `PolicyPathResolver` makes per-type the default while still allowing a
single-document or tenant/version-routed override.

The decision is **role-definition-driven**: a rule allows when the action verb is in
`permissions.effective_actions(input.role_definition, input.resource.type)` — the coarse category
tokens in `permissions[<type>]` expanded through `data.permission_categories` and narrowed by
`denied_actions` (deny-overrides), then by `required_tags` ([[0007-coarse-grained-permission-categories|ADR 0007]]).
A literal verb placed in that map expands to **nothing** and denies (fail-closed). There is **no
general subject-roles fallback**: since Slice B4 ([[0018-team-scoped-resource-isolation|ADR 0018]])
every verb requires a resolved role definition, except the single verb-gated `catalog:create`
onboarding check. `gateway.rego` stays coarse for the APISIX layer.

## What this buys

- **Defense in depth:** a request must pass the gateway (valid token, valid route) *and* the app's
  fine-grained ABAC.
- **Separation of concerns:** the gateway owns authentication + transport; the app owns
  attribute/resource decisions.
- **Portability:** the app's authorization works the same whether the gateway is APISIX, another gateway,
  or (with the reserved signature-verify mode) none — because the decision is self-contained.

## Related
- [[ABAC-AUTHORIZATION]] — the spine the app layer runs.
- [[E2E-TESTING]] — the allow/deny matrix proving both layers together.
- [[POC-ROADMAP]] — where this sits in the phases.
