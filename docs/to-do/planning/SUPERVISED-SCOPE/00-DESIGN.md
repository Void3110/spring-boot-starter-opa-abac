---
tags:
  - status/planning
  - type/design
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — 00-DESIGN

**Phase ① complete — 2026-08-01.** Research (2026-07-27) → precondition probe → fork-resolving interview
→ slice-sizing split. The scope contract is pinned in [[0029-supervised-read-scope|ADR 0029]].
**Next: the decomposition in [[01-DECOMPOSITION]].**

## The feature in one paragraph

A **unit manager** — a member of no team — logs in and sees the catalogs owned or managed by the people
who report to them, transitively. The page is correct, live and **strictly read-only**, derived entirely
from the reporting structure and never from a realm grant. **Contents — categories and products — are
closed in this slice**; opening them behind a production tier and a second factor is slices B and C.

## What this pierces, and what it must not break

Slice B4 made **team membership the sole access path**: the coarse `catalog:list` gate is gone,
`GovernedScopeResolver` is the only authority, the `filter` entrypoint is role-definition-only with no
subject-roles fallback. A supervisor who is a member of nothing therefore sees nothing — by design.

This slice adds a **second, disjoint** access path. It must not reintroduce the realm-role fallback B4
removed, and it must leave **every existing persona's behavior byte-identical**.

## The epic and this slice's boundary

The full supervisor feature was scoped as one slice and failed the sizing gate
([[AUTONOMOUS-IMPLEMENTATION-FLOW]] §2a — ~13 tickets over five deployables; smells (a), (b), (c)). Split
so each slice only ever *widens* what the previous one closed:

| | Slice | Ships | ADR |
|---|---|---|---|
| **A** | **SUPERVISED-SCOPE** (this) | list + metadata, read-only; contents closed | 0029 |
| **B** | PRODUCTION-TIER | `operatorManaged` + `env` + root attributes; non-prod contents open | 0030 §1–4 |
| **C** | STEP-UP-ELEVATION | `deny_reason` + RFC 9470 + freshness; prod contents open when elevated | 0030 §5–9 |

**Explicitly NOT in this slice:** the `env` tag and the `operatorManaged` flag; root-attribute enrichment;
any `deny_reason` or envelope change; the RFC 9470 challenge; `acr` / `auth_time` ingestion; the
step-up-related Keycloak realm work (the `basic`/`acr` scopes, `acr.loa.map`, the conditional browser
flow, TOTP enrolment); the SPA. **No library module changes at all.**

**The one realm change slice A does make:** an e2e persona *is* a Keycloak user (every matrix mints its
token by password grant), so T6 adds the new persona accounts and the UX-only `unit-supervisor` realm
role to `infra/keycloak/realm-export.json`. Nothing else in the realm is touched.

## The design

```
GET /catalogs   →   findAuthorized(scope, ctx, subtreeSpec)   [the SHIPPED PAGED 5-arg call]
                      scope        = id IN (M ∪ supervised)
                      ctx role     = the membership role
                      subtreeSpec  = id IN supervised
                    ⇒ scope ∧ (residual_membership ∨ id∈supervised) ∧ notDenied()

                    M = membership ids · supervised = S \ M  (disjoint)

GET /catalogs/{id}                    metadata — allowed on both paths
GET /catalogs/{id}/categories/…       DENIED on the supervised path (no child verbs AND no
                                      inherited grant — ADR 0031's confinement, T3)
```

### 1. The reporting relation (T1)

A `reporting_edge` (`manager_id`, `report_id`) table in the user-service, seeded by fixture — the same
shape and lifecycle as the existing bootstrap fixtures. Derivation is **per request**, depth-capped and **cycle-guarded**. **Depth counts HOPS from the manager
— a direct report is hop 1; hops 1–10 inclusive are derived, and discovering an 11th hop is a cap
breach.** (U33 pins the inclusive boundary: a breach collapses the *whole* set, so an off-by-one
silently empties a legitimate manager.) A cycle-closing edge is rejected on write, and a cycle
nevertheless detected at read time fails **closed to empty**, never to a partial set.

Reach is **CONTROL-capable memberships only** — a report contributes a team only where they hold
`OWNER` / `ADMINISTRATOR` / `SENIOR` per the shipped `TeamRoleCapabilities` ladder. `MEMBER` and `READER`
seats do not propagate: otherwise one report's reader seat on an unrelated team silently widens their
manager's reach into it.

Exposed as `GET /internal/supervised-targets?subject=&resourceType=catalog` → `200` + a JSON array,
**always 200**, an empty array being the authoritative "supervises nothing" — mirroring the shipped
`/internal/governed-targets` exactly.

### 2. The non-membership role branch (T2)

A wider id set alone yields an **empty page**: the `filter` is role-definition-only, and the list
authorizer resolves its residual-driving role through membership, which a supervisor has none of —
`/internal/effective-role` answers `204` and the residual compiles to `DENY_ALL`.

So `effective-role` gains a **non-membership branch**: when the subject supervises the governing root, it
answers with a **synthesized, read-only supervisor role**, granting

```
catalog : ["READ"]            # the COARSE token; NOTHING on category or product
```

**Tokens, not verbs.** Since ADR 0007/0015 `permissions[<type>]` carries the coarse tokens
`READ`/`WRITE`/`TAG`/`GRANT`/`CONTROL`, expanded to fine verbs by `data.permission_categories`
(`READ` → `view, list, list-members`). Writing the fine verbs directly expands to the **empty set** —
the documented fail-closed ∅-expansion — so the role would grant nothing and the supervised page would
be silently empty. U14 asserts this against the policy, not only the Java shape.

Required tags are **vacuous** — safe **only** because the scopes are disjoint (§3). The role is
synthesized in code and never stored. Provenance rides the reserved role code plus a marker in the role's existing
generic `attributes` map, so `input.role_definition` carries it with **zero** envelope change.

**Contents are closed by the role PLUS the confinement rule (T3) — not by the role alone.** The naive
reading ("the role names no `category`/`product` key, so child reads deny") is **false against the
shipped corpus and was proven false by evaluation**: `category_inheritable.json` declares
`catalog → category` inheritance, and `inherited_grant` admits a leaf verb when the role's effective
actions **on the ancestor type** contain it — so `catalog: ["READ"]` (→ `view, list, list-members`)
inherits `category:view` and `product:view` from the very catalog the supervisor may read, whenever the
ancestor chain is present. It always is at runtime; an ancestor-less probe returns `false`, which is
exactly how the original U14 was written (a green-lighting trap, now re-pinned).

So this slice ships **one narrow policy change**, pinned by [[0031-inheritance-confined-to-membership-roles|ADR 0031]]
and built in **T3**: ancestor inheritance requires **membership provenance**. `resourceRole()` — the
single production funnel for membership-derived roles — stamps `attributes.provenance = "membership"`;
`inherited_grant` and `list_inheritable_grant` in `category.rego` **and** `product.rego` (four clauses)
open only on that stamp. The supervisor role carries `provenance = "supervised"` and therefore inherits
nothing, while **direct grants are untouched** — a role naming a type explicitly still reaches it with no
stamp at all, which is why every shipped per-type role is unaffected and why slice B's tiered role (it
adds `category`/`product` keys explicitly) needs no policy change. Absence of the stamp is **closed**, so
slices B and C are confined by default.

Slice B widens the grant; this slice must not.

### 3. Confining inheritance to membership-derived roles (T3)

Pinned by [[0031-inheritance-confined-to-membership-roles|ADR 0031]] and detailed in §2 above: the
single membership funnel `EffectiveRoleService.resourceRole()` stamps
`attributes.provenance = "membership"`, and the four inheritance clauses
(`inherited_grant` + `list_inheritable_grant`, in `category.rego` **and** `product.rego`) open only on
that stamp. **The conjunct may NOT be centralized into `permissions.effective_actions`** — direct
grants use the same helper, so the supervisor would lose its own `catalog:view`, and `permissions.rego`
is byte-mirrored into the user-service bundle (a drift surface for no benefit). `category.rego` and
`product.rego` are **not** mirrored, so the drift guard stays out of play.

Because `opa test` inputs are hand-written, the policy tests cannot catch the Java side silently
ceasing to stamp — so T3 also carries **one test at the seam** asserting `resourceRole()` applies it.

### 4. Precedence and disjointness (T4, T5)

Where a subject is **both** a member and a supervisor of a team, **membership wins**:

```
supervised := S \ M
```

This is load-bearing twice. It is the correct semantic — a dual-hatted manager must not be pushed onto
the stricter branch for their own team's data. And it makes a row's provenance **unambiguous**, so the
residual applied to it is never in question and the supervisor role's vacuous tag requirement can never
widen a tag-gated membership row. Unioning the two roles' permissions is exactly that fail-open, and is
rejected.

### 5. The list (T5)

`CatalogListAuthorizer` today resolves one role from `governedIds.get(0)` and compiles **one** residual,
on the stated assumption that "every governed catalog is one the subject is a member of". This slice
breaks that assumption. The two scopes are composed through the **shipped** `findAuthorized` **paged
5-arg**
overload — the ADR-0010 base-scope-widening idiom — with the supervised ids riding the `subtreeSpec`
slot, so a membership row is judged by the membership residual while a supervised row is admitted by
the widening arm. `totalElements` stays the authorized total; every branch fails closed to empty.

**The composition above holds on the PURE-SQL branch only.** `findAuthorized` has four branches, and
`subtreeSpec` reaches the query in exactly one of them (verified by reading `AbacQueryService`):
partial-eval **disabled** → one `opaClient.allow(queryContext)` decides the whole union and
`subtreeSpec` is **ignored**; `residual.fromError()` → **empty page** (fail-closed, unchanged);
`!fullySupported()` + allowlist fallback → candidates are **batch-rechecked against the membership
queryContext**, `subtreeSpec` again **ignored**; pure SQL → the documented composition.

**Pinned semantic:** on the two `subtreeSpec`-ignoring branches the **supervised leg contributes
nothing** — the subject sees their membership rows only, never a supervised row judged by a role that
did not earn it. That is the fail-closed direction (rows are lost, never gained), and it is a
**silent feature-off**, so T5 logs one WARN when a supervised id set is non-empty while the executing
branch cannot honor it. Widening the batch path to carry supervised ids would require a **library
change**, which this slice forbids end to end — it is a recorded limitation for the SPI-promotion
slice, not something to fix here. U42 asserts both branches.

**`findAuthorized` compiles exactly ONE residual from the ONE context it is given** — there is no
overload taking two `(scope, context)` legs. Handing it a pre-composed `legA.or(legB)` as `scope` would
AND that single residual over the whole union, narrowing supervised rows by the membership role. The
composition is therefore correct **only while the supervisor role's residual is unconditional**
(`READ` + empty `requiredTags` → `ALLOW_ALL`); U34 asserts that precondition so the coupling is visible
if a later slice adds a tag requirement.

### 6. The read-only ceiling and the audit event (T5)

Supervised rows are strictly read-only: every mutation denies. The affordance map follows from the role
rather than from special-casing — a supervised row emits `{view: true, …mutations false}`. It is **not
omitted**: the omit-on-all-false degrade fires only when *every* verb is false, and `view` is true here.
The exact verb set is **verified against the real endpoints, never assumed** (Mulch `mx-3446c4` records
two corrections caught exactly this way).

A dedicated, separately-routable logger — `dev.dmitriikonovalov.example.catalog.audit.SupervisedRead` —
emits a structured event per supervised **list** read. **Scope is pinned to the list path in this
slice**, because that is where the supervised authority is applied; supervised single-`GET` auditing
rides the gate and is deferred to slice C's audit work. Nothing is persisted — retention and routing
are the consumer's.

## Fail-closed posture

**Two failure classes, and they land in different places.** Never collapse them into one rule:

| Class | Lands on |
|---|---|
| Org-relation source **errored / unreachable / non-200 / unparseable** | the subject's **own memberships** — leg 2 contributes nothing |
| **Partial** derivation (a cycle, a depth-cap breach, a partly-resolvable closure) | **membership-only**, never a partial supervised set |
| Unauthenticated · no `AbacQueryService` · no `GovernedScopeResolver` · both scopes empty | the **empty page** (unchanged from ADR 0018 §Consequences — “resolver absence ⇒ the safe (empty) outcome”) |
| A role reaching a child type with **no provenance stamp** (unstamped, empty `attributes`, or an unknown value) | **no inherited grant** — ADR 0031; absence is closed, so a future synthesized role that forgets the stamp fails closed |

A partial set is indistinguishable from a correct smaller one, which is why it collapses rather than
degrades. In every branch the floor is the empty page, never the table.

## Considered and rejected

- **A realm-role fallback** for supervisors — the fail-open backdoor B4 removed. The realm claim stays a
  UX-only marker: claim + zero reports sees nothing.
- **Unioning membership and supervisor permissions** on a doubly-reachable row — the fail-open the
  precedence rule eliminates structurally.
- **Extending the `UserDirectory` port** — its contract is two-field text search and says not to add
  fields; widening it turns a user picker into an org-chart oracle.
- **A Keycloak-native org relation** — 26.x organization groups carry no manager relation and cannot drive
  authorization policies.
- **Modelling supervision on the resource hierarchy** — reporting is people-structure, not
  resource-structure; different shape, lifetime and owner.
- **Any membership counting toward reach** (not only CONTROL-capable) — silently widens a manager through
  a report's unrelated reader seat.
- **Precomputing the closure** — at demo scale a per-request walk is correct with zero invalidation
  machinery. Deferred until list latency forces it; it hides behind the same seam.
- **Promoting the org-relation seam to a published SPI now** — a published SPI is permanent API surface,
  and the contract should be pinned by a real consumer's real source, not by our fixture.

## Knowledge destination

A new section in the existing [[TEAM-BASED-AUTHORIZATION]] guide — this is a second access path onto a
surface that guide already owns, not a new subsystem, so it does not earn a new guide
([[AUTONOMOUS-IMPLEMENTATION-FLOW]] §3's arbiter: a new guide is warranted exactly when a new row in the
surface→guide map is).

## Execution parts

**Parts:** part 0 = T1–T3 · part 1 = T4–T6

*(Amended 2026-08-01 after the parts port shipped — this package is the model's first real consumer —
and re-cut 2026-08-02 when [[0031-inheritance-confined-to-membership-roles|ADR 0031]] added T3.)* Part 0
is **the supervised role and the rule that confines it**: after T3 the user-service answers "who does
this subject supervise, and with what role" *and* the corpus provably denies that role any child access —
the independently-landable subset, provable with ITs plus `opa test` alone, no catalog service, no rig.
Part 1 is the **catalog side + the e2e proof** (T4–T6). The boundary sits at the deployable handoff, not
an even split. The two fail-open edges sit one per part — T3's confinement (part 0) and T5's
`supervised := S \ M` set difference (part 1) — so each part-runner's inline-2A review carries exactly
one, and the automatic whole-delivery layer-3 review re-covers both at branch scope
([[AUTONOMOUS-IMPLEMENTATION-FLOW]] §4a).
