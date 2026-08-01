---
tags:
  - status/planning
  - type/design
  - area/abac
  - area/opa
  - area/spring
---

# SUPERVISOR-STEP-UP — 00-DESIGN

**Phase ① complete — 2026-08-01.** Research (2026-07-27) → precondition probe → fork-resolving interview.
Decisions are pinned in [[0029-supervised-read-scope|ADR 0029]] (scope) and
[[0030-step-up-decision-contract|ADR 0030]] (elevation). **Next: `/decompose`.**

## The feature in one paragraph

A **unit manager** — a member of no team — logs in and sees the catalogs owned or managed by the people who
report to them, transitively. Browsing and metadata are ordinary reads. **Opening the contents of a
catalog tagged `env=production` requires a second authentication factor**, proven to the resource server by
a freshness check that a token refresh cannot launder. Access is strictly read-only, derived entirely from
the reporting structure, and audited.

## What this pierces, and what it must not break

Slice B4 made **team membership the sole access path**: the coarse `catalog:list` gate is gone,
`GovernedScopeResolver` is the only authority, the `filter` entrypoint is role-definition-only with no
fallback. A supervisor who is a member of nothing therefore sees nothing — by design.

This slice adds a **second, disjoint** access path. It must not reintroduce the realm-role fallback B4
removed, and it must leave every existing persona's behavior byte-identical.

## The design, end to end

```
                    ┌─ M = membership ids ──── residual(membership role) ──┐
GET /catalogs ──────┤                                                      ├── OR ── page
                    └─ S\M = supervised ids ── residual(supervisor role) ──┘

GET /catalogs/{id}                      metadata — never elevated
GET /catalogs/{id}/categories/…         contents — elevated IFF supervised path AND root env=production
GET …/products/{pid}                            ↑ root_attributes.env, fetched by path id, memoized
```

| Layer | Owns |
|---|---|
| **Starter library** | `deny_reason` on the decision result (additive, omitted when absent); the RFC 9470 challenge handler mapping *only* the structured step-up deny to 401; `root_attributes` enrichment; `GovernedScopeResolver` contract-text revision; step-up guide + Rego pattern docs. **Not**: acr validation, Keycloak automation, a published org-relation SPI. |
| **user-service** | Reporting-edge fixture + transitive derivation (depth 10, cycle-guarded) behind `/internal`; the non-membership branch of `/internal/effective-role` returning the synthesized read-only supervisor role; the `operatorManaged` column + `env` seed. |
| **catalog-service** | Two-leg partitioned `CatalogListAuthorizer` (replaces `governedIds.get(0)`); step-up rules + structured deny in `catalog`/`category`/`product` Rego; root-attribute enrichment; `operatorManaged` enforcement in `TagAssignmentService`; the audit logger. |
| **SPA** | 9470 challenge parser; `returnTo` persistence; redirect forwarding **`max_age` + essential `acr`**; amber "requires verification" affordance. |
| **Keycloak (rig)** | Assign built-in `basic` + `acr` client scopes; `acr.loa.map`; conditional browser flow (level 2 = OTP Form, Max Age 300); TOTP enrolment for the supervisor persona. |

## Resolved forks

### Ratified from research (not reopened)

| Fork | Resolution |
|---|---|
| F1 mechanism | Derived id set behind governed scope, re-derived per request |
| F3 scope | Supervised subtree — never flat read-all |
| F4 relation source | HR-mastered projection via a **new** fail-closed seam; not `UserDirectory`, not Keycloak-native; example-side this slice |
| F5 transitivity | Full subtree, depth-capped 10, cycle-guarded, fail-closed to empty on a detected cycle |
| F6 realm claim | UX-only eligibility marker, **never** resolver input |
| F8 fail-closed | Errored source → own memberships; partial derivation → membership-only; `_actions` read-only |
| S5 factor | TOTP-first + demonstrated pluggability; SMS = inform, don't block (NIST 800-63B-4 RESTRICTED) |

### Settled in the interview (2026-08-01)

| # | Fork | Resolution | Why it mattered |
|---|---|---|---|
| G1 | `env` governance | New additive **`operatorManaged`** boolean on the tag definition, carried through `TagDefinitionView`, enforced in `TagAssignmentService` | The `abac_deny` precedent does **not** transfer (it is protected by *not existing*), and `is_system` already means something else on keys the demo assigns |
| G1b | Untagged default | Untagged = non-production, unelevated detail OK | Defensible only because G1 makes tagging operator-controlled — dependency recorded |
| G1c | How `env` gets set | Seed/fixture only; **no runtime path** | Makes "not self-strippable" true by construction |
| F2/F7 | Read-verb set | **No new verb.** Gate child reads; root GET is already metadata-only | Avoids a mirrored `permission_categories.json` change in both services |
| — | Blast radius | Elevation attaches to the **supervised path only** | Ordinary members unaffected; no e2e re-tokenising; keeps the slice read-only |
| — | Precedence | **Membership always wins**; `supervised := S \ M` | Dual-hatted managers keep frictionless access to their own data, **and** the scopes become disjoint |
| G3 | Mixed list | Two-leg partitioned query over the disjoint scopes | Disjointness makes the critic's fail-open structurally impossible |
| — | Provenance | Reserved synthesized role code + marker in the role's existing `attributes` map | Zero envelope shape change; spoofing the code is self-demotion, not escalation |
| — | Reach | **CONTROL-capable** memberships only (OWNER/ADMINISTRATOR/SENIOR) | Literal "own or manage"; a report's READER seat elsewhere does not widen the supervisor |
| G2 | Child-tier propagation | `root_attributes`, fetched by the **path's** catalogId, request-memoized | `ParentRef` carries no attributes and neither resolver loads entities — widening it would defeat the ltree optimization |
| S1/S2 | Elevation scope + lifetime | RS-side freshness only; **no dual token, no TTL pairing**; time-boxed | P0 showed `auth_time` is available, so the whole contingency apparatus is unnecessary |
| S3 | Challenge channel | RFC 9470 401 for the structured deny only; plain denials stay 403; **client must forward `max_age`** | Omitting `max_age` causes an infinite challenge loop |
| S6 | Audit | Dedicated structured logger; emission point only, nothing persisted | No audit infrastructure exists today; retention is the consumer's |
| — | Envelope | Additive `deny_reason`, omitted when absent — **no version bump** | House pattern for evolving the serialized record; versioning is disproportionate for one field |

## Preconditions — discharged 2026-08-01

Measured on the rig, Keycloak **26.3.2** (the research assumed 26.7.0):

- **`auth_time` reaches code-flow access tokens.** The upstream regression does not bite. The apparent
  absence was realm config: an explicit `defaultClientScopes` list *replaced* Keycloak's built-in
  assignment, leaving the built-in **`basic`** (carries the `auth_time` mapper) and **`acr`** scopes
  assigned to no client. Assigning them fixed it — **a ticket deliverable in the realm export.**
- **Refresh laundering confirmed**: `auth_time` and `acr` preserved across refresh while `iat` advances.
  RS-side freshness is the only surviving control.
- **ROPC cannot carry `auth_time`** even with `basic` assigned. A non-ROPC token path is a **confirmed
  deliverable**, not a mid-run discovery.
- **`acr` alone is not a control**: ROPC yields `acr=1`, an SSO-reused browser session yields `acr=0`.
- **Capabilities present, no upgrade needed**: TOTP, WebAuthn, WebAuthn passwordless/passkey, recovery
  codes, conditional level-of-authentication, `oidc-amr-mapper`. `acr.loa.map` is **unset** — a deliverable.

## Proof plan

Personas: **sup-anna** (realm marker; reports pm-bob + pm-carol; carol has her own report → transitivity),
**sup-victor** (disjoint unit), **pm-bob** (plain member), **outsider-eve** (nothing). One of bob's catalogs
is `env=production`.

| # | Proof |
|---|---|
| P1 | Scope — anna = exactly unit A (including carol's report's teams), victor = unit B, eve = empty; exact counts, no over-fetch |
| P2 | Read-only ceiling — anna on a non-prod catalog: read OK, every mutation 403. **`_actions` = `{view:true, mutations:false}` — NOT omitted** (omit-on-all-false fires only when every verb is false), verb set verified against real endpoints |
| P3 | **Headline** — 1FA contents open → 401 with the exact `WWW-Authenticate` shape → SPA persists route, redirects with `max_age` + essential `acr` → Keycloak prompts **only** TOTP (asserts the conditional-subflow skip) → new token → route restored, contents visible |
| P4 | Expiry + laundering — after Max Age the next open re-challenges; refreshing the elevated session does **not** restore access; **and no challenge loop occurs** |
| P5 | Liveness + two denials — remove bob from anna's reports → his catalogs drop live; a direct read is now **403 (out of scope)**, distinguishable from the under-elevated **401** |
| P6 | Fail-closed outage — org-relation source down → anna sees only her own memberships; no 5xx |
| P7 | Factor swap (manual) — flow edit adds an Alternative; rerun P3 unchanged but for the factor screen; zero app-side diff |
| P8 | Tier not self-strippable — a TAG-holding member tries to set **and** to strip `env` → both rejected; tag still present |
| P9 | Tier reaches contents — 1FA read of a product/category under the prod root → challenged (root-attribute enrichment works) |
| P10 | Audit — a supervised production read emits an event; elevation emits one too |
| P11 | **Non-regression** — a plain member reading their own production catalog is **never** challenged; existing collections unchanged |
| P12 | **Dual-hat** — a subject both member and supervisor of the same catalog takes the membership path: no elevation required |

## Carried into `/decompose`

1. **Pin the non-ROPC token path as a ticket** (scripted code flow + computed TOTP), the way the prior
   slice had to pin the gateway route its e2e needed. It is the single most likely source of a mid-run stall.
2. **Realm-export deliverables**: assign `basic` + `acr`; set `acr.loa.map`; conditional browser flow with
   Max Age 300; TOTP enrolment for anna.
3. **The mirrored-bundle guard**: this slice changes Rego in both service bundles — grow the drift guard if
   a file is added.
4. **`AbacTestConfig` in-process OPA stubs** must be updated whenever a policy's decision shape changes —
   the `deny_reason` addition qualifies.
5. `_actions` verb sets are **verified against real endpoints, never assumed**.
6. Watch which seams this slice strains: it is plausibly the dry run for a second consumer, and that is the
   signal the agent-module extraction has been waiting on.
