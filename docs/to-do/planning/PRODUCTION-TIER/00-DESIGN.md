---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring
---

# PRODUCTION-TIER — 00-DESIGN

> Slice **B** of the supervisor epic: implements [[0030-step-up-decision-contract|ADR 0030]] **§1–4**.
> Supervised **non-production** contents open; **production** contents stay denied (a plain 403 — the
> structured deny and the challenge are slice **C**). Members are untouched. The input contract this
> slice adds to the library is pinned by [[0032-root-attribute-enrichment-input-contract|ADR 0032]].
> Settled 2026-08-07 (grill-me: six forks, all recorded in §Considered-and-rejected).

## The feature in one paragraph

Slice A left a supervisor's view **metadata-only**: the synthesized role grants `catalog: ["READ"]`
and nothing on contents, and ADR 0031 confines ancestor inheritance to membership so the closure is
real. This slice adds the **tier** that decides how much further oversight may go: an operator-managed
`env` tag on the governing root ([[0030-step-up-decision-contract|ADR 0030]] §3), carried to child
decisions by root-attribute enrichment (§4, contract in ADR 0032), lets the supervised path open
**non-production** contents through the ordinary direct-grant machinery while **production** contents
stay closed — closed for a *stated* reason now, and relaxed to "unless freshly elevated" only in
slice C. The headline: `sup-anna` opens her report's **staging** catalog down to its products with no
ceremony; the same catalog flipped to `production` refuses her very next child read; and nothing an
owner can do through the API removes the tag that makes it so.

## What this builds on, and what it must not break

- **A's scope machinery is untouched.** Who is supervised, the two-leg list, `supervised := S \ M`,
  the read-only ceiling, E4 liveness — none of it changes. This slice changes only what a supervisor
  may do **inside** a catalog already in scope.
- **ADR 0031 stays exact.** Ancestor inheritance remains membership-only. Contents open via the
  role's **own** child keys (direct grant), never by re-opening inheritance for supervised roles.
- **Members are structurally unaffected** (ADR 0030 §2). Every new closure keys on
  `provenance == "supervised"`; a membership decision can never reach it — including during an
  enrichment outage.
- **The mirrored bundle stays untouched.** No new verb, no `permission_categories.json` change
  (ADR 0030 §1); the policy diff lives in `category.rego` + `product.rego` only — the same
  non-mirrored pair slice A's T3 touched.

## The epic and this slice's boundary

| | Slice | Ships | Status |
|---|---|---|---|
| A | [[SUPERVISED-SCOPE]] | the list + metadata, read-only; contents closed | ✅ shipped 2026-08-07 |
| **B** | **PRODUCTION-TIER** (this) | the tier + enrichment; **non-prod contents open, prod denied** | 📋 |
| C | STEP-UP-ELEVATION | `deny_reason` + RFC 9470 + `auth_time` freshness; prod opens when elevated | ⏳ |

**Explicitly NOT in this slice** (all slice C): the `deny_reason` envelope field (ADR 0030 §6), the
RFC 9470 challenge (§7), `acr`/`auth_time` ingestion and the freshness rule (§5), audit emission
points (§8), any Keycloak realm/flow work (§9 — **zero realm diff in B**), the non-ROPC e2e token
path, and the SPA. A production child read by a supervisor in B is a **plain 403**,
indistinguishable on the wire from any other deny — deliberately: distinguishing it *is* slice C.

## The design

### 1. The tier lives in the dictionary (ADR 0030 §3, concretized)

- `TagDefinition` gains an additive **`operatorManaged` boolean**, default `false` (entity column +
  Liquibase changeset + response schemas). It is **not client-authorable**: it appears in **no**
  request schema — GLOBAL definitions have no public create path at all (only
  `createTeamTagDefinition` exists), so there is nothing to strip and nothing to reject at the
  definition layer.
- The cross-service **`TagDefinitionView` projection carries the flag** (today it would drop it) —
  the catalog service cannot enforce what it never receives.
- **`env` is seeded**: GLOBAL, ENUM `production | staging | dev`, `is_system = true` (definition
  immutable through the API, the existing meaning) **and** `operatorManaged = true` (values
  unwritable through the API, the new meaning). The two flags answer different questions and both
  are needed.
- **Enforcement** sits in the catalog service's `TagAssignmentService` (where tag values are
  written): any public-path write that touches an operator-managed key — assign, re-value, or strip,
  on any resource type — is rejected with the **new error code `TAG_OPERATOR_MANAGED`, HTTP 409**,
  added to the **public** `ProblemDetail.errorCode` enum (tag assignment is a documented endpoint;
  contrast `REPORTING_EDGE_INVALID`, which stays internal-only). Reusing
  `TAG_DEFINITION_IMMUTABLE` was rejected: it names definition-mutation protection, and the e2e
  strip cell must be able to assert *which* guard fired.
- **The operator path** is the catalog service's **first internal endpoint**:
  `POST /internal/bootstrap/resource-tags` — a **narrow merge-upsert** (`{resourceType, resourceId,
  tags}`; only the posted keys change, so it never fights the public flows managing team keys on the
  same rows). In-network only, same routing posture as the user-service's four internal endpoints
  (the gateway routes only the public prefixes). It bypasses the operator-managed rejection **by
  construction** — it *is* the operator. There remains **no runtime path** through the public API,
  which is what keeps §3's untagged-defaults-to-non-production sound.

### 2. The input contract (ADR 0032)

`input.resource.root_attributes` — the governing root's **full tag map**, three distinguishable
states, `NON_NULL` serialization:

| state | meaning | tier consequence |
|---|---|---|
| absent | enrichment failed / never attempted | **unproven → supervised path closed** |
| `{}` | root fetched, untagged | non-production → open |
| `{"env": …}` | root fetched, tagged | as tagged |

Library surface (both **additive**): `core`'s `AbacContext.Resource` gains the fifth component +
compat constructors (the `ancestors` evolution pattern — old inputs byte-identical); the
`AbacResource` SPI gains a `rootAttributes()` **default method** returning `null`;
`OpaPreAuthorizeAuthorizationManager` threads it through. Every existing library test stays
unchanged-green — that is the additivity proof, stated as acceptance, not hoped.

### 3. The role widens — authority stays in the role

`SupervisorRoles.readOnlyFor` grants **`{catalog: ["READ"], category: ["READ"], product: ["READ"]}`**
(child reads resolve the role on the governing root via the shipped `roleResourceType = 'catalog'`
gates, so the one synthesized role covers all three types). Child reads then pass through the
ordinary **direct-grant** path — no inheritance, no ADR 0031 involvement, no new allow clause
anywhere. The role remains **READ-only**: A's ceiling cells (`PUT`/`DELETE` 403) hold unchanged.

### 4. The tier deny — the slice's fail-open edge

Two `denied` clauses in **each** of `category.rego` and `product.rego` (four clause sites — each
needing its **own mutation-guard test**, the slice-A layer-3 lesson):

```rego
denied if {                       # tier unproven — enrichment failed or absent
    input.role_definition.attributes.provenance == "supervised"
    not input.resource.root_attributes
}

denied if {                       # tier proven production
    input.role_definition.attributes.provenance == "supervised"
    input.resource.root_attributes.env == "production"
}
```

Deny-overrides is the corpus's strongest idiom: no other allow clause can bypass it. The
provenance conjunct scopes it to the supervised path — a member's decision **cannot** reach these
clauses, which is how ADR 0030 §2 survives even an enrichment outage. An untagged root arrives as
`{}`: present, `env` undefined, second clause does not fire → open, per §3. **The naive shape
`not root_attributes.env == "production"` is wrong** (absent `env` passes a negated comparison);
the two-clause shape above is the reference.

### 5. Enrichment wiring (catalog service)

Enrichment is attempted **unconditionally** on the four child endpoints (ADR 0030 §1's table: the
two child GETs and the two child lists) — the service cannot know provenance before resolution, and
member requests are indifferent to the field. The governing root's id is **already in the path**
(`/catalogs/{catalogId}/…`); the root is fetched by id **once per request**, memoized through the
existing resolve-memo machinery, and its tag map becomes `rootAttributes`. On **any** fetch failure
the input ships **without** the field — no exception, no 5xx: the supervised deny closes the gated
path while a member's read proceeds (the request-time failure lands on the *narrower result*, the
slice-A discipline). For the two **list** gates the check is type-level (no leaf instance), so the
root id comes from the gate's `roleResourceId` rather than a resolved instance — **a named seam to
verify against the shipped gate code at decompose, not from memory.**

### 6. E2e ownership and the E6 flip

- **B's matrix** (`run-production-tier-matrix.sh`, new **`ffff…`** fixture prefix per the registry
  rule) owns the tier proof: non-prod contents open (E-cells on both GETs and both lists), prod
  contents 403, untagged default open, **tier-flip liveness** (operator flips `staging →
  production` via the internal endpoint ⇒ the very next supervised child read is 403 — the B
  analogue of A's E4), the strip attempt (`409 TAG_OPERATOR_MANAGED` asserted by code), and
  member-unaffected-on-prod (200).
- **The E6 flip is B's known, deliberate modification of A's matrix**: A's E6 cells assert
  supervised contents 403 on an *untagged* catalog — post-B that is 200 by design. A's matrix is
  rewritten to the B-era contract (untagged ⇒ open) and keeps what remains A's: scope exactness,
  E4 liveness, the read-only ceiling. The closed-contents proof **moves** to B's matrix; it does
  not vanish. Planned here so the run never "discovers" a red matrix.
- **The enrichment-failure class has no e2e cell, and the design says so honestly**: the root fetch
  is an in-service DB read — there is no dead-port trick. Absent-⇒-closed is proven at `opa test`
  (input-shape cases) and IT level (a resolver whose root fetch throws). Promising a rig cell here
  would be theater.
- Rig flavour: same as A (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1`); reuse the `sup-*` realm accounts;
  **zero realm diff**. Non-regression: the E7-style **enumerated** list, which now **must include
  A's supervised matrix** (the role and policy both changed under it).

## Fail-closed posture

- **The floor for the gated path is deny.** Tier unproven (absent `root_attributes`) ⇒ the
  supervised child read is denied; tier proven production ⇒ denied. No error path yields a *wider*
  result than success.
- **One request-time failure class** (enrichment failure), landing on the narrower result for the
  population it gates and on **no change** for everyone else. There is no install-time class in
  this slice: a missing `env` *definition* makes operator writes fail at the internal endpoint
  (dictionary validation), never a silent tier downgrade — untagged stays non-production
  regardless, per §3's stated trust dependency.
- **The safe intermediate state is designed, not accidental**: if the role widens and the denies
  land (T4) before the catalog service populates the field (T5), every supervised child read hits
  absent-⇒-deny — contents stay closed between parts. The parts cut relies on this (§Execution
  parts).
- **The §3 trust dependency is restated**: untagged-defaults-to-non-production is defensible only
  while `env` has no public write path. If that ever changes, the default must flip to
  deny-until-tagged (ADR 0030 §3).

## Considered and rejected

- **Policy-side supervised allow** (role stays catalog-only; leaf policies gain a supervised
  inheritance-like allow keyed on root grant + tier): re-blurs ADR 0031's membership-only line
  months after a confirmed fail-open forced it; authority migrates from the role into policy; more
  allow surface to hold. Rejected for **(i)** role-widens + deny-closes.
- **Fail the request on enrichment failure** (5xx): turns a transient fetch problem into a
  member-facing outage and adds a failure class slice A never had. Rejected for omit-⇒-policy-deny.
- **Reusing `TAG_DEFINITION_IMMUTABLE`**: conflates definition-mutation protection with value
  protection; the strip cell couldn't assert which guard fired.
- **Direct SQL for fixtures**: couples the suite to the schema, bypasses the service, no precedent.
- **Keeping A's E6 cells 403 by tagging their catalog production**: couples A's matrix to B's
  internal endpoint and duplicates the prod-closed proof.
- **`NON_EMPTY` serialization for `root_attributes`**: silently merges "untagged" with "fetch
  failed" — the exact fail-open the three-state contract exists to prevent (ADR 0032).
- **Parts as substrate/feature (T1–T3 / T4–T6)**: concentrates both fail-open edges in part 1 and
  lands nothing user-visible in part 0. Rejected for the cut below.

## Knowledge destination

The guide delta lands in `docs/guides/TEAM-BASED-AUTHORIZATION.md` (the supervised-scope section
grows the tier: the three input states, the deny shape, the operator path, the E6 flip) and
`docs/guides/ABAC-AUTHORIZATION.md` (the `root_attributes` input contract, per ADR 0032's
consequence note). Mulch: `opa-abac-authz-model` (the tier model), `rego-policy` (the two-clause
deny shape vs the naive negation), `spring-security-integration` (the SPI default-method evolution),
`opa-abac-e2e-suite` (the E6-flip pattern: a later slice deliberately rewriting an earlier matrix's
cells).

## Execution parts

**Parts:** part 0 = T1–T4 · part 1 = T5–T6

Part 0 is everything provable **without the rig** — the dictionary flag + seed (T1), the catalog-side
enforcement + operator endpoint (T2, IT-provable), the library input contract (T3, unit-provable +
old-tests-unchanged), and the role widening + tier denies (T4, `opa test`-provable). Part 1 is the
enrichment wiring (T5, ITs) and the e2e proof + the E6 flip (T6, rig). The two fail-open edges sit
**one per part** — T4's deny clauses (part 0) and T5's population-or-absence (part 1) — so each
part-runner's inline review carries exactly one, and the intermediate state between the parts is the
**closed-by-absence** safe state (§Fail-closed posture). The boundary is the deployable handoff:
after part 0 the corpus and both services' units/ITs are green while supervised contents remain
closed; part 1 opens them and proves it end to end.
