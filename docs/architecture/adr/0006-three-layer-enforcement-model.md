---
tags:
  - status/active
  - type/architecture
  - area/abac
  - area/opa
  - area/spring
---

# ADR 0006 — The three-layer enforcement model: gateway (coarse) → app (per-resource) → DB (list filter)

**Status:** Accepted
**Date:** 2026-06
**Context tags:** ABAC, OPA, enforcement layers, defense-in-depth, fail-closed

> This ADR pins a **cross-cutting** decision that until now lived only in prose
> ([[TWO-LAYER-AUTHORIZATION]]) and was implied across slices. It records *why* authorization is enforced
> at three independent layers and *why the app never trusts the gateway* — a principle expensive to
> reverse and surprising to a reader who sees "but the gateway already checked." Two of the three layers
> are shipped; the DB layer arrives with [[DATA-FILTERING]] (ADR 0005).

## Context

The same authorization logic could be enforced in one place. The gateway (APISIX + OPA) already
validates the OIDC token and could make a coarse allow/deny; an app-side check then looks redundant; a
DB-side filter looks like a third copy. The temptation is to pick one layer and trust it. The reference
platform (study-only) deliberately enforces at **three** layers and has the app **re-decide every
single-resource return** rather than trust the gateway — because each layer answers a *different*
question and each on its own has a hole.

The driving threat: **horizontal privilege escalation**. A user who is allowed to call
`GET /catalogs/{id}/categories/{categoryId}` *in general* (the gateway/type check passes) could pass a
`categoryId` belonging to a tenant they may not see. Only a check **against the loaded resource's
attributes** catches that — and that check can't happen at the gateway, which has no resource yet.

## Decision

Enforce at three independent layers, each answering its own question, each fail-closed:

1. **Gateway (coarse, route-level).** APISIX validates the OIDC token and runs a coarse OPA `gateway`
   policy — "is this identity allowed to reach this *route* at all?" It is a **filter, not the
   authority**. It never sees resource attributes.
2. **Application (per-resource).** The app, via the library, makes the *fine-grained, role-definition-
   driven* decision against **the specific resource** — `@OpaPreAuthorize` for the type-level pre-check
   and, where the decision needs the loaded resource's tags (ADR 0004), a load-then-check against the
   loaded entity. **The app never trusts the gateway**: every single-resource path re-asks OPA with the
   resource in hand. This is the layer that stops horizontal escalation.
3. **Database (list filter).** For collection endpoints, OPA partial evaluation compiles the policy into
   a residual that becomes a JPA `Specification` in the SQL `WHERE` clause (ADR 0005) — "of N rows, which
   may this subject see?" A row the subject can't see never leaves the database.

> One policy corpus, three entry points: a coarse `gateway` rule, a per-resource `allow` rule, and a
> partial-eval `filter` rule. The *decision logic* (role definition + tags) is shared; the *granularity*
> and *phase* differ by layer.

**The principle, stated for future readers:** **the gateway is defense-in-depth, not the decision.** A
green gateway check is necessary but never sufficient; the app re-decides against the resource, and the
DB filters the list. Removing any one layer leaves a real hole (drop the gateway → unauthenticated
routes; drop the app check → horizontal escalation on guessed IDs; drop the DB filter → over-broad
lists). Each layer **fails closed** independently.

## Considered options

| Option | Why not |
|--------|---------|
| **Trust the gateway; no app check** | The gateway has no resource attributes at decision time, so it cannot stop horizontal escalation on a guessed/known ID. This is the central hole the app layer closes. |
| **App check only; no gateway** | Loses cheap coarse rejection and route-level token enforcement at the edge; every unauthenticated probe reaches the app. Defense-in-depth wants the edge filter too. |
| **One shared decision point (a mediator/service the app must route through)** | The reference platform's older shape; couples the app to a bespoke authorization mediator. This project is deliberately Spring-native — `@OpaPreAuthorize` / `AuthorizationManager` / a `Specification`, no mediator (see [[POC-ROADMAP]] thesis). |
| **Filter lists in the app after fetch instead of in the DB** | O(table) I/O and leaks counts; the DB layer (ADR 0005) exists precisely to push the filter into SQL. |
| **Re-verify the JWT signature in the app** | The gateway already validated it against JWKS; the app does structural + `exp` checks and **trusts the signature** (a `verifySignature` mode is reserved, not built). Trusting the *token* is fine; trusting the *coarse decision* is not — different things. |

## Consequences

- **Good:** horizontal privilege escalation is structurally prevented (the app always re-decides against
  the resource); each layer is independently fail-closed; the layers compose without a bespoke mediator,
  staying idiomatic Spring; the same policy logic is reused across all three entry points.
- **Cost:** apparent redundancy (three checks for one conceptual rule) — justified because each answers a
  distinct question; an extra OPA round-trip per single-resource return (mitigated by the load-then-check
  caching the loaded entity for the handler, as the tag demo does).
- **Boundary:** this ADR records the *model*; the **shipped** state is layers 1–2 (gateway coarse +
  app `@OpaPreAuthorize`/load-then-check). Layer 3 (DB partial-eval) is ADR 0005 / Phase 5. The prose
  guide [[TWO-LAYER-AUTHORIZATION]] is renamed/extended to three layers when layer 3 ships.

## Related
- ADR 0005 (the DB layer — partial-eval → `Specification`) · ADR 0004 (the per-resource tag grant the app
  layer enforces) · ADR 0003 (the role-definition-driven decision shared across layers)
- [[TWO-LAYER-AUTHORIZATION]] (the current prose guide, gateway ↔ app) · [[ABAC-AUTHORIZATION]] ·
  [[POC-ROADMAP]] (the phases that ship each layer)
