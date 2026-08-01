---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring
---

# ADR 0030 — Step-up authentication: the production tier, the freshness control, and the challenge contract

**Status:** Accepted — planning (SUPERVISOR-STEP-UP phase ①)
**Date:** 2026-08-01
**Context tags:** RFC 9470, acr/auth_time, refresh laundering, operator-managed tags, structured deny, root attribute enrichment

> Pins the **elevation** forks for the supervisor slice: which reads are sensitive, how the second factor
> is proven to the resource server, and how a "you must step up" answer travels from policy to client. The
> **scope** half — who can see what at all — is [[0029-supervised-read-scope|ADR 0029]].

## Context

The first consumer requires that a unit manager's view of their reports' work at
**production-environment detail** be gated by a **second authentication factor**, and that the factor be
swappable without application changes.

Nothing in Spring Security provides this. Its multi-factor support is servlet-login-side only and reads no
`acr`/`amr` from JWTs; the open issue for resource-server step-up has no milestone. Across the ecosystem
the authorization-server side exists and the **resource-server side is do-it-yourself everywhere**. An
RFC 9470 challenge emitter is therefore a genuine differentiator for this starter, not a re-implementation.

The central threat is **refresh laundering**. `acr` and `auth_time` are fixed at authentication; a refresh
mints a new access token that **preserves both**. So "issue a short-lived elevated token" is not a control
— one second factor at 09:00 would otherwise cover production reads all day.

### The blocking precondition, discharged

The design's viability rested on whether `auth_time` reaches **access** tokens on the rig's Keycloak, given
a known upstream regression. Measured on the rig (Keycloak **26.3.2**, 2026-08-01):

| Flow | `acr` | `auth_time` |
|---|---|---|
| Authorization code + PKCE | present | **present**, pinned to authentication |
| Resource-owner password (ROPC) | present (`1`) | **absent** |

Three results follow, all empirical:

1. **`auth_time` does reach access tokens** — the regression does not bite here. The initial absence was
   self-inflicted realm configuration: `defaultClientScopes` was pinned to a literal four-name list, which
   *replaces* Keycloak's built-in assignment and left the built-in **`basic`** scope (which carries the
   `auth_time` mapper) and **`acr`** scope assigned to **no client in the realm**.
2. **Refresh laundering is real and confirmed**: after a refresh, `auth_time` and `acr` are preserved while
   `iat` advances. The resource-server-side freshness computation is the only control that survives.
3. **ROPC cannot carry `auth_time`** even once `basic` is assigned — the mapper reads a user-session note
   that the direct grant never sets. Since every token-minting path in the e2e harness is ROPC, a
   non-ROPC token-acquisition path is a **known deliverable**, not a mid-run discovery.

A fourth observation is recorded because it is a trap: ROPC reports `acr=1` while a genuine browser session
reusing SSO reports `acr=0`. **`acr` alone is not a control.**

## Decision

### 1. The sensitive act — child reads, not a new catalog verb

`GET /catalogs/{id}` is **already metadata-only** (name, description, tags, affordances); a catalog's
contents are literally its child endpoints. The proposed `catalog:read` / `catalog:read-detail` verb split
is therefore dropped — there is no catalog-level detail to gate.

Browsing stays ordinary; **opening contents** is the sensitive act:

| Unelevated (metadata) | Elevated when the governing root is `env=production` |
|---|---|
| `GET /catalogs` | `GET /catalogs/{id}/categories` |
| `GET /catalogs/{id}` | `GET /catalogs/{id}/categories/{cid}` |
| | `GET …/categories/{cid}/products` |
| | `GET …/categories/{cid}/products/{pid}` |

This adds **no verb** to the permission vocabulary, and therefore requires no change to the mirrored
`permission_categories.json` in both services — avoiding a known bundle-drift hazard.

### 2. Elevation attaches to the supervised path only

Step-up conditions attach to the **supervision-derived** access path
([[0029-supervised-read-scope|ADR 0029]] §7), not to the `env` tag globally. An ordinary team member
reading their own team's production catalog is **unaffected**.

This is deliberate scoping. Oversight access into other people's work is the privileged act that the second
factor exists for; a member reading their own data is not. Gating on the tag globally would change behavior
for every existing persona, would require re-tokenising an e2e suite that structurally cannot carry
`auth_time` (§Context), and would turn a read-only supervisor slice into a platform-wide authentication
change.

### 3. The tier — an operator-managed `env` tag

`env` is a GLOBAL/ENUM tag definition (`production` | `staging` | `dev`). The critical property is that it
must **not be strippable** by the people being supervised — otherwise a supervised owner (or a stolen
single-factor token) could remove the gate.

The `abac_deny` precedent does **not** transfer. `abac_deny` is protected by *not existing in the
dictionary*: the only assignment gate is "does this key resolve to a definition?". `env` must exist to be
assignable and enum-validated, so the moment it is defined, any tag holder could set or strip it.

The existing `is_system` flag cannot carry this meaning either — the two seeded GLOBAL keys the whole demo
assigns (`sensitivity`, `region`) are both `is_system=true`, where it means "definition immutable through
the API", not "value unassignable".

So the dictionary gains a **new, additive `operatorManaged` boolean**, defaulting to `false`:

- carried through the cross-service `TagDefinitionView` projection, which currently drops it;
- enforced in `TagAssignmentService`: any write touching an operator-managed key is rejected;
- `env` is seeded `operatorManaged=true`; every existing key and all team keys are untouched.

There is **no runtime path** to set `env` — seed and fixtures only. The tier is therefore
non-self-strippable *by construction* rather than by a check being correct.

**Untagged defaults to non-production**, and unelevated detail is allowed. This is defensible **only
because** tagging is operator-controlled: a supervised owner cannot create an untagged catalog to dodge the
gate. The dependency is stated here so it is not silently broken later — if `env` ever becomes writable at
runtime, this default must flip to deny-until-tagged.

### 4. Reaching child decisions — root-only attribute enrichment

A catalog-level tag gates nothing on child reads by itself: tags are **leaf-scoped** and never inherited,
so `input.resource.attributes` on a product is the product's own tags. The root's `env` must reach the
child decision.

`ParentRef` is `(type, id)` only, and neither ancestor resolver loads ancestor entities — the ltree
resolver decodes path label strings, the recursive resolver walks parent links. Widening `ParentRef` with
attributes would force entity loads on exactly the path that optimization exists to avoid, and would change
a published core record plus its supplier SPI.

Instead, the **governing root's id is already in the path** of every child endpoint
(`/catalogs/{catalogId}/…`). So the root is fetched **by id, once per request** (memoized through the
existing resolve-memo machinery) and surfaced as `input.resource.root_attributes`. `ParentRef` and both
ancestor resolvers are untouched.

### 5. Elevation — resource-server-side freshness is the whole control

```rego
elevated if {
    loa[input.subject.attributes.acr] >= 2
    now_s - input.subject.attributes.auth_time <= max_age + skew
}
```

with the LoA map mirroring the realm's, and **`skew` stated explicitly** rather than implied.

Because `auth_time` is available (§Context), the contingency machinery the design carried is **dropped**:
no dual token, no separate elevated client, no `client-session-max` tuning, and **no token-lifetime
pairing** — the realm's access-token lifespan is irrelevant to the control. A single post-step-up token
serves both purposes: ordinary reads ignore `acr` entirely, while production reads re-challenge as
`auth_time` ages. Refresh cannot launder it, because refresh preserves `auth_time` and the window keeps
shrinking (measured).

Claims ingestion is pure configuration — `opa.abac.subject.attribute-claims: [acr, auth_time]` — copying
top-level claims type-preserved into `input.subject.attributes`. A missing `acr` leaves the LoA lookup
undefined, so the subject is **not elevated**: fail-closed. `amr` is **not** ingested: Keycloak emits it in
no assigned scope, no rule consumes it, and shipping a claim that is always absent invites a reader to
believe it is a control. Trust remains gateway-deep — the starter never re-verifies the token — with
Keycloak's client-level minimum-ACR as the server-side tamper backstop.

### 6. The decision envelope — an additive `deny_reason`

`result.allow` is a boolean across the OPA client, both authorization managers and the policy convention.
Rather than versioning that envelope, the step-up deny travels as an **optional, omitted-when-absent**
`deny_reason` object:

```
allow  -> {"allow": true}                                    # byte-identical to today
deny   -> {"allow": false}                                   # byte-identical to today
step-up-> {"allow": false,
           "deny_reason": {"type": "insufficient_user_authentication",
                           "required_acr": "aal2", "max_age": 300}}
```

Every existing consumer keeps reading `allow` and serializes unchanged when the field is absent. This is
the established pattern in this repo for evolving a public Jackson-serialized record, and it deliberately
**deviates from the earlier plan to bump an envelope version** — version negotiation, unversioned-response
handling and a sweep of every consumer is disproportionate machinery for one optional field. Should a
second structured deny type ever arrive, this decision is worth revisiting.

### 7. The challenge — RFC 9470, and the loop the client must not create

The policy enforcement point maps **only** the structured step-up deny to a `401` challenge. Plain denials
stay `403` — both because RFC 9470 warns about fingerprinting, and because the two must remain
distinguishable (an out-of-scope read is a different answer from an under-elevated one).

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="A second factor is required to read production content",
  acr_values="aal2", max_age="300"
```

**The client MUST forward `max_age` in the resulting authorization request**, alongside an *essential*
claims request for `acr` (a voluntary `acr_values` may silently under-deliver). This is recorded as a
decision, not an implementation note, because omitting it produces an **infinite challenge loop**:
Keycloak would see a still-valid session, skip the second-factor prompt, and reissue the *same* stale
`auth_time`, which the resource server would reject again. `max_age` is what forces re-authentication.

Elevation is **time-boxed, not per-resource**: one elevation covers production reads for the freshness
window. Per-operation authorization (RAR) is overkill for a read-only oversight feature.

### 8. Audit

There is no application audit-event infrastructure today — only JPA auditing for `createdBy`/`createdDate`.
This slice ships the **emission point**, not a retention story: a dedicated, separately-routable logger
emitting structured events for (a) an elevation and (b) a supervised production-contents read — subject,
governing root, resource, access path, `acr`, `auth_time`.

Nothing is persisted. Read-audit is opt-in in every major platform surveyed (CloudTrail data events are
paid opt-in; GCP `DATA_READ` is disabled by default), so retention, routing and review cadence are properly
the consumer's. But these privileged reads are the exact risk the second factor exists for, and shipping
the gate with no evidence trail would undercut the feature's own rationale.

### 9. Factor policy

The example implements **TOTP first** and *demonstrates* pluggability: adding a second factor as an
Alternative inside the level-2 conditional subflow is a **flow edit**, with no application-side change —
applications see only standard OIDC, and `acr` is identical whichever factor satisfied the level.

The factor **choice** is the consumer's Keycloak team's concern, not this project's. If SMS is requested,
the stance is **inform, do not block**: NIST SP 800-63B-4 classifies PSTN/SMS out-of-band as **RESTRICTED**
(risk acceptance, an unrestricted alternative, user notice and a migration plan), and AAL2 verifiers are
required to offer a phishing-resistant option. No SMS plugin ships in the rig by default.

Rig capabilities were verified rather than assumed (Keycloak 26.3.2): TOTP, WebAuthn, WebAuthn
passwordless/passkey, recovery codes, the conditional level-of-authentication authenticator and an `amr`
mapper are **all present**. No Keycloak upgrade is required, including for the passkey swap demonstration.

## Consequences

**Good.** The control is a single resource-server-side comparison that provably survives refresh, and the
whole dual-token apparatus disappears with it. No permission-vocabulary change, no envelope version, no
change to any existing persona's behavior. The RFC 9470 emitter is a differentiating capability no Spring
library currently ships.

**Costs.** The tag dictionary grows a protection concept it did not have, spanning two services. The
decision result is no longer strictly boolean for consumers that want the challenge. The e2e harness needs
a second, non-ROPC token-acquisition path. And the tier is only as strong as `env` staying
operator-managed — a coupling stated in §3 precisely so it is not quietly broken.

**Deferred.** Persisted/queryable audit; a published step-up SPI in the starter; transactional (RAR)
elevation; and Keycloak flow automation, which stays rig configuration.
