---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/spring
  - area/keycloak
---

# Supervised read and step-up elevation — the oversight path

How a **unit manager who is a member of no team** nevertheless reads the catalogs of the people who
report to them — read-only, scoped to their unit, and at production detail only behind a **fresh second
factor**.

This is the repo's **second access path**. It sits beside the membership path of
[[TEAM-BASED-AUTHORIZATION]] (read that first) and is deliberately **disjoint** from it: the two never
judge the same row. Everything below hangs off that one idea.

It was built as Phase 10 — three slices plus a console follow-up, each fail-closed at its boundary so
every later one only ever *widens* what the previous closed:

| Slice | Ships | Pinned by |
|---|---|---|
| **A** [[SUPERVISED-SCOPE]] | the list — read-only, contents closed | [[adr/0029-supervised-read-scope\|ADR 0029]], [[adr/0031-inheritance-confined-to-membership-roles\|ADR 0031]] |
| **B** [[PRODUCTION-TIER]] | the operator-managed `env` tier — non-production contents open | [[adr/0030-step-up-decision-contract\|ADR 0030]] §1–4, [[adr/0032-root-attribute-enrichment-input-contract\|ADR 0032]] |
| **C** [[STEP-UP-ELEVATION]] | the RFC 9470 round trip — production opens, briefly | [[adr/0030-step-up-decision-contract\|ADR 0030]] §5–9 |
| **console** [[SPA-CHALLENGE-UX]] | the client consuming the challenge, and `_provenance` | [[adr/0033-catalog-provenance-affordance\|ADR 0033]] |


## Why a second path exists

Slice B4 made **team membership the sole access path** to the catalog root list. That is exact and
intended, and it has an exact consequence: **a subject who is a member of no team sees nothing.** A unit
manager is precisely that subject — they need to see the catalogs of the teams their *reports* own or
manage, without being on any of those teams.

Slice A adds a **second, disjoint access path** for that case. It does **not** reintroduce the realm-role
fallback B4 removed (that would be a fail-open backdoor); it derives reach from a **reporting relation**,
per request, behind a fail-closed seam.

## The two access paths

| | **Membership** (B4) | **Supervision** (ADR 0029) |
|---|---|---|
| Source of reach | a `TeamMembership` on the team that governs the resource | a `reporting_edge` chain: the subject's transitive reports, then the teams **they** hold a CONTROL-capable seat on |
| Where it is derived | `GET /internal/governed-targets` | `GET /internal/supervised-targets` (a sibling endpoint, same shape, **always `200`** + a possibly-empty array) |
| Role driving the decision | the membership's bound `RoleDefinition` | a **synthesized, read-only supervisor role** — `permissions = {"catalog": ["READ"]}`, empty `requiredTags`, `attributes.provenance = "supervised"`. Built in code per request, **never stored** |
| What it reaches | whatever the bound role grants | the catalog **list and metadata only** — read-only, and contents stay closed |

Both legs are composed in **one** query by the catalog's `CatalogListAuthorizer`, through the shipped
paged `AbacQueryService.findAuthorized`: the union as the base scope, the supervised ids on the
`subtreeSpec` widening arm.

## The precedence rule — membership always wins

```
supervised := S \ M          # S = the raw supervised set, M = the membership set
```

The two scopes are **disjoint by construction**, and that is load-bearing twice. It is the correct
semantic — a dual-hatted manager must not be pushed onto the stricter branch for their *own* team's data
— and it makes every row's provenance unambiguous, so the supervisor role's *vacuous* tag requirement can
never end up judging a tag-gated membership row. Unioning the two roles' permissions on a doubly-reachable
row is exactly that fail-open, and is rejected.

The same rule decides which role drives the residual: **whenever `M` is non-empty the role is resolved on
a MEMBERSHIP id**, never on one from the union. Only a *pure* supervisor (`M` empty) resolves it on a
supervised id — correct precisely because there are no membership rows for it to widen.

## The reach rule — CONTROL-capable seats only

A report contributes a team **only** where they hold `OWNER` / `ADMINISTRATOR` / `SENIOR` — the
CONTROL-capable rungs of the shipped `TeamRoleCapabilities` ladder. A `MEMBER` or `READER` seat does not
propagate, and neither does a custom role (custom roles project to `[READ]`). Otherwise one report's
reader seat on an unrelated team would silently widen their manager's reach into it.

Derivation is per request, breadth-first, **depth-capped at 10 hops** (a direct report is hop 1) and
cycle-guarded; a cycle-closing edge is rejected on write.

## The realm claim is a UX-only marker

The realm role `unit-supervisor` exists so a UI can *show* the supervised-unit affordance. It is **never
resolver input**: claim + zero reports sees **nothing**. This is what distinguishes the design from the
fallback B4 removed — reach comes entirely from the org relation, and the reserved role code is
**provenance, not authority** (a custom role bearing it buys no reach; spoofing it is self-demotion).

## Contents stay closed — and it takes TWO things, not one

> **Superseded in part by slice B** — see *[The production tier](#the-production-tier--how-deep-oversight-goes-slice-b-adr-0030-14)* below. B opens **non-production** contents by widening the
> synthesized role to name the child types **directly**. What this subsection describes is still exactly
> true and still load-bearing: **inheritance** stays closed to synthesized roles, and that is why
> widening the role — rather than re-opening inheritance — was the only safe way to open anything.

The synthesized role named **no `category` key, no `product` key, no `"*"`** (in slice A; B adds the two
child keys, still no `"*"`). That alone is **not
sufficient**: the shipped `catalog → category` and `catalog → product` inheritance tables would hand it
`category:view` / `product:view` anyway, from the very catalog it may read, whenever the ancestor chain is
present — which at runtime it always is. (An *ancestor-less* probe returns `false`, which is exactly how
that fail-open survived review once.)

So the second thing is [[adr/0031-inheritance-confined-to-membership-roles|ADR 0031]]:

> **Ancestor inheritance requires membership provenance. A synthesized role is confined to the types it
> names.**

`EffectiveRoleService.resourceRole` — the single funnel for membership-derived roles reaching the
catalog-side policies — stamps `attributes.provenance = "membership"` by **overwrite, never merge**, and
`inherited_grant` + `list_inheritable_grant` in `category.rego` **and** `product.rego` open only on that
stamp. **Direct** grants are untouched: a role naming a type explicitly still reaches it with no stamp at
all, which is why every shipped per-type role is unaffected. `provenance` is a **reserved, system-owned
key** — a client-supplied value is stripped on the write path and overwritten on the read path, so it
cannot be forged. **Absence is closed**: an unstamped role, an empty `attributes`, or an unknown value
grants no inherited access, so a future synthesized role that forgets the stamp fails *closed*.

## The production tier — how deep oversight goes (Slice B, ADR [[adr/0030-step-up-decision-contract|0030]] §1–4)

Oversight that can never open anything is a directory, not oversight. Slice **B** lets a supervisor open
a report's contents **when the environment is routine**, and keeps **production** detail shut.

**The role widens; authority stays in the role.** `SupervisorRoles.readOnlyFor("catalog")` now grants
`{catalog: [READ], category: [READ], product: [READ]}`, so child reads pass through the ordinary
**direct-grant** path. This is deliberately *not* a new inheritance path: ADR 0031 stays exactly as exact
as it was, and the role stays READ-only, so the ceiling cells (`PUT`/`DELETE` → 403) are untouched. Any
supervised type other than `catalog` keeps the single-key shape.

**The tier lives on the governing root.** An operator-managed `env` tag (`production | staging | dev`) is
written on the catalog and reaches child decisions as `input.resource.root_attributes` — see
[[TAG-BASED-AUTHORIZATION]] for the flag and the operator path, and [[ABAC-AUTHORIZATION]] for the
enrichment contract. Nothing a catalog's own owner can do through the API touches that tag
(`409 TAG_OPERATOR_MANAGED`).

**The decision is two deny clauses per leaf policy** (`category.rego` + `product.rego`), and the *shape*
is the point:

```rego
denied if {                       # tier UNPROVEN — enrichment failed or was never attempted
    input.role_definition.attributes.provenance == "supervised"
    not input.resource.root_attributes
}

denied if {                       # tier proven PRODUCTION
    input.role_definition.attributes.provenance == "supervised"
    input.resource.root_attributes.env == "production"
}
```

| Root state | Supervised child read |
|---|---|
| `root_attributes` **absent** | **denied** — an unproven tier is a closed tier |
| `{}` (fetched, untagged) | **open** — untagged means non-production (ADR 0030 §3) |
| `{"env": "staging"}` / `dev` | **open** |
| `{"env": "production"}` | **denied** — a plain `403` in B; slice C makes it a step-up challenge |

> **Do not "simplify" this into one clause.** `not input.resource.root_attributes.env == "production"`
> reads naturally and is **wrong**: an *absent* value passes a negated comparison, so an enrichment
> outage would **open** the tier instead of closing it. The absent state needs its own positive clause.
> Each of the four clause sites carries its own deletion-mutation guard in the test suite for exactly
> this reason.

**Members are structurally unaffected** (ADR 0030 §2). Both clauses require
`provenance == "supervised"`, so a membership decision **cannot reach them** — a member reads their own
team's production contents exactly as before, and keeps reading them during an enrichment outage, when
every supervised child read closes. That is the slice's one request-time failure class, and it lands on
two different answers on purpose: **the supervised path closes, the member's request proceeds unchanged**
— never a 5xx, never an exception out of the manager.

**Where the decision lands, and where it must not.** The list's tier decision happens at the **coarse
type-level gate** (which consults `denied`); it never enters the partial-evaluation `filter` residual —
a `root_attributes` predicate in the SQL would be both a dead end for the compiler and a slice-boundary
breach.

**One known affordance gap, pinned as contract:** the `_actions` enrichment builds per-row inputs with no
root context, so on supervised child rows every verb computes false and the omit-on-all-false convention
**omits the map entirely**. That is the intended B behavior — omitted, never a fabricated `view: false`
on a row the caller can actually read. Member rows are untouched. Threading root context through the
enrichment advice is slice C's work.

**Proved end to end by `scripts/postman/run-production-tier-matrix.sh`** (the `ffff…` fixture set: a
staging, a production and an untagged catalog, one category and product each). Its headline pair is
**liveness and unstrippability together**: the operator flips a catalog `staging → production` through
the in-network `POST /internal/bootstrap/resource-tags` and the supervisor's **very next** child read is
`403` — then flips it back and the next read is `200` again, so the tier is read per request in both
directions rather than latched. Meanwhile the catalog's **own owner** — the most privileged public
identity there is — cannot strip, re-value **or** assign `env` through the API: each attempt is a `409`
whose `errorCode` is asserted to be `TAG_OPERATOR_MANAGED` by value, and a follow-up cell proves none of
the three moved the tier. The matrix also pins the two contracts above as assertions rather than prose:
the supervisor's list rows carry **no `_actions` key at all**, while the member's rows on the very
catalog she is denied carry an honest one.

**This slice deliberately rewrote three cells of slice A's matrix.** `run-supervised-scope-matrix.sh`'s
E6a/E6b/E6c asserted that a supervisor's contents were **closed** (`403`) — the boundary of A, held by a
role that named no child type. Under B those same requests are `200` on exact ids, because the catalog
they use is **untagged** and untagged means non-production. The cells were flipped, not deleted, and the
closed-contents proof **moved** to the production-tier matrix, which owns the production and unproven
cases. A later slice that finds an older document promising "supervised contents are closed" should read
it as the slice-A boundary, not as a regression.

## Two failure classes — and they land in different places

Never collapse them into one rule:

| Class | Lands on |
|---|---|
| The org-relation source **errored / unreachable / non-200 / unparseable** | the subject's **own memberships** — the supervised leg contributes nothing |
| A **partial** derivation (a cycle, a depth-cap breach, a malformed element) | **membership-only** — never a *partial* supervised set |
| Unauthenticated · no `AbacQueryService` · no `GovernedScopeResolver` · both scopes empty · an unresolvable role on every leg | the **empty page** (unchanged from ADR 0018) |
| A role reaching a child type with **no provenance stamp** | **no inherited grant** (ADR 0031) |

A partial set is indistinguishable from a correct smaller one, which is why it *collapses* rather than
degrades. In every branch the floor is the empty collection and the empty page — never a partial
supervised set, and never the whole table.

The supervised edge has its **own** `CallGuard` (its own breaker) and its **own** base-URL property
(`catalog.user-service.supervised-base-url`, defaulting to the shared one). Sharing the resolve breaker
would let a supervised-targets outage trip the one every persona's role resolution depends on — turning a
degrade-to-membership-only into an empty page for everyone.

## What slice A did not carry

*(This subsection scopes **slice A**. Slice B has since shipped ADR 0030 §1–4 — see *The production
tier* above; §5–9 remain slice C's.)* Contents (categories, products) stayed closed in A; opening them
behind a production tier ([[adr/0030-step-up-decision-contract|ADR 0030]] §1–4) and a second factor
(§5–9) is slices **B** and **C**. No `env` tag, no `operatorManaged` flag, no `deny_reason`, no RFC 9470 challenge, no
`acr`/`auth_time` ingestion. A supervised **single-`GET`** is audited by slice C; slice A audits the
**list** path, where the supervised authority is applied.

## Step-up elevation — the production tier opens, briefly (Slice C, ADR [[adr/0030-step-up-decision-contract|0030]] §5–9)

Slice B closed a supervisor's production contents with a plain 403. Oversight that can *never* open
production is a wall, not a gate — so slice C adds the elevation that opens it deliberately, for a
bounded window, behind a fresh second factor.

**The round trip, in five steps:**

1. `sup-anna` reads her report's **production** catalog's contents at ordinary strength.
2. The policy's production deny now carries a `not elevated` conjunct, so it still fires — but because
   step-up is the **sole** blocker (she is otherwise `granted`, and no other deny fires), the decision
   carries a structured `deny_reason`.
3. The library's advice maps that to **`401`** with an RFC 9470 challenge and a `STEP_UP_REQUIRED`
   problem body:

   ```
   HTTP/1.1 401 Unauthorized
   WWW-Authenticate: Bearer error="insufficient_user_authentication",
     error_description="A second factor is required to read production content",
     acr_values="aal2", max_age="300"
   ```

4. The client re-authenticates with **`max_age`** *and* an essential `acr` claim; Keycloak's conditional
   level-2 flow demands TOTP.
5. The very next read is **200** — until the window closes.

**Elevation is `loa[acr] >= 2` and `now − auth_time <= max_age + skew`, and nothing else.** Both claims
come off the token as pure configuration (`opa.abac.subject.attribute-claims: [acr, auth_time,
act_chain]`); the knobs live in `infra/opa/policies/step_up.json`; the clock is OPA's own.

> **Why freshness, not token lifetime.** `acr` and `auth_time` are fixed at authentication, and a
> **refresh grant preserves both** (measured on the rig). A short-lived "elevated token" would therefore
> prove nothing: one second factor at 09:00 would cover production reads all day. Resource-server-side
> freshness is the only control that survives refresh, and `max_age` is what forces the re-authentication
> — omit it and Keycloak reuses the session, reissues the same stale `auth_time`, and the client loops.

**The sole-blocker rule is what keeps the challenge honest.** `deny_reason` is emitted **iff** the
step-up clause fires, the subject is `granted`, and no other deny fires. Four consequences fall out of
that one rule rather than four separate checks:

| Who | Answer | Why |
|---|---|---|
| An **out-of-unit** supervisor | plain **403** | not `granted` — no "this is production" to fingerprint |
| An elevated supervisor's **write** | plain **403** | not `granted` — the read-only ceiling is not an elevation problem |
| An **agent-marked** call | plain **403** | another deny fires — no TOTP prompt for a caller that cannot TOTP |
| An **enrichment outage** | plain **403** | the unproven-tier deny fires — and see below |

**The unproven tier is elevation-proof.** The `not elevated` conjunct amends the *production* clause
only; the absent-`root_attributes` clause is untouched. An enrichment outage is a closed tier for a
freshly-elevated supervisor exactly as for anyone else: elevation proves *who is present*, never *what
the tier is*.

**The supervised path is human-only.** Supervision and elevation are human ceremonies, so an
agent-marked call — the `act_chain` delegation claim's **key** present, at any tier — is refused
outright, in three places because the supervised surface is not one seam: the provenance-scoped deny in
all three leaf policies (single decisions), the supervised **list leg** skipped app-side (the list's cut
comes from `filter`, which never consults `denied`), and the claim ingested by configuration so the
target gate can see it at all. The discriminator is the key's **presence**, never its truthiness —
`act_chain: false` is still an agent call. The tool-gate is untouched: it still narrows, the target gate
still decides. This is a recorded, revisitable decision, not a permanent one; a supervised agent
read-out would be its own designed feature with its own audit story.

**Two audit events**, on the dedicated `opa.abac.audit` logger (emission only — retention and routing
are the consumer's):

| Event | Where | Payload |
|---|---|---|
| `STEP_UP_CHALLENGED` | the advice, at 401-mint | subject, resource type + id, governing root, the challenge parameters — **no** `acr`/`auth_time`, since the subject is precisely *not* elevated yet |
| `PRIVILEGED_READ` | the manager, on an allowed supervised read of a production root | subject, access path, governing root, resource, plus `acr` and `auth_time` **verbatim** |

Elevation is **implied by the allow** and never re-derived app-side — a Java copy of the LoA map or the
window would create a second source of truth for a number ADR 0030 insists exists once. And emission
never affects the decision: the emit path catches and drops its own exceptions, because an audit bug
must not become an authorization outage.

**Fail-closed, layer by layer.** Each class lands where it arose, and none of them widens anything:

| What went wrong | Where it lands |
|---|---|
| Missing / unmapped `acr`, missing or non-numeric `auth_time` | `elevated` is undefined → the production deny holds |
| A **malformed** `deny_reason` on the wire | plain deny at the parse — the reason is dropped, never coerced |
| An OPA outage, breaker open, retries exhausted | plain deny at the resilient wrapper — **never a fabricated reason** |
| A reason with any **null** field | the ordinary **403** at the advice — never a half-formed challenge |
| Audit emission throwing | nothing; the decision stands |

**One freshness window, stated twice.** The realm's level-2 condition max age and `step_up.json`'s
`max_age` are the same 300 seconds and cross-reference each other (see `infra/README.md`); the
challenge's `acr_values`/`max_age` come **from the reason**, so the advice holds no copy of either.

**Proven on the rig** by `scripts/postman/run-step-up-matrix.sh` (`ENABLE_MCP=1 ./deploy.sh up
--pods 2`, after a `./deploy.sh down` so Keycloak re-imports the realm). Anna's tokens come from
`mint-code-flow-token.py` — the scripted PKCE code flow, because ROPC structurally cannot carry
`auth_time` ([[E2E-TESTING]]). The cells: **E1** her `aal1` read of a production child answers
**401** with the challenge parameters asserted *by value* and `STEP_UP_REQUIRED` in the body;
**E2** one TOTP later the same read is **200** on exact ids, and both audit events are grepped off
the pod's `opa.abac.audit` channel; **E4** an out-of-unit supervisor and an *elevated* `PUT` each
get a plain **403** with no `WWW-Authenticate`; **E5** the catalog's owner reads the same rows at
plain `aal1` with an honest `_actions` map; **E6** an agent-client token for her own subject is
refused on production *and* non-production content and its catalog list is the empty page, each
paired with a human-token control on the same row; **E7** the drill overrides `data.step_up.max_age`
to 5 s on the **leaf** path, proves a fresh elevation still opens (the positive control), waits out
`max_age + skew`, and watches the same bearer answer **401** again; and **E3**, inside that shrunk
window, re-authenticates *without* `max_age` over the persisted SSO session — a brand-new token,
`iat` advanced, the **same** `auth_time`, still 401. Slice B's matrix keeps every cell it had except
the seven this slice deliberately flips from the plain 403 to the challenge (`E2a`–`E2d`, `E4b`,
`E4c`, `E5d`), each annotated in place.

## Telling the client which path it is on — `_provenance` (ADR [[adr/0033-catalog-provenance-affordance|0033]])

Everything above is server-side. Nothing on the wire told a client *which* rows it held by supervision —
so a console could neither label the oversight rows nor warn, before the click, that a catalog would ask
for a second factor.

Neither of the two obvious client-side guesses works, and both failure modes are instructive:

- **From the token.** Impossible. The `unit-supervisor` realm claim is an *eligibility marker*, never
  resolver input — a holder with zero reports supervises nothing.
- **From `tags.env`.** Wrong in the one case that matters. A **member's** production catalog is tagged
  `production` and needs no elevation at all (ADR 0030 §2), so predicting from the tag alone would mark
  it "verify to open" and lie to the majority persona.

The client needs the server's answer to *"by which path is this row in front of you?"*, so catalog rows
carry an additive, optional, read-only **`_provenance`** — vocabulary `"member"` | `"supervised"`, on
**catalog list items and the single-catalog `GET` only**. Categories and products carry nothing; they
cannot differ from their catalog, and a client propagates the value on drill-in.

**It is an affordance, not enforcement** — the [[adr/0016-action-enrichment-affordance-metadata|ADR 0016]]
stance that governs `_actions`, verbatim. A client uses it to explain and to predict; every decision
remains the server's, and **predicted-and-wrong is a UI bug, never a security event**. The console's amber
"production · verify to open" chip is a *prediction*; the red state is the server's `_actions`.

**One semantic, two derivations, pinned to agree.** On the **list** the value comes from the leg
(`id ∈ supervised ⇒ "supervised"`, else `"member"`). On the **`GET`** it comes from the stamp of the role
the gate resolved (ADR 0031's `provenance`). They agree by construction — the user-service synthesizes the
supervised role only on the non-membership branch — and an integration case pins the agreement on both
personas.

**Absent when not computed — never `null`, never a default.** This is the property that makes the field
honest, and it is the same distinction `_actions` established: an absent field means *unknown*, not
*member*. **Present-but-empty is not absent** — the list memo is the supervised id set *including the
empty set*, so a plain member's page is every row labeled `"member"`, not an unlabeled page. Every degrade
branch that can still label honestly does: an agent-marked call labels its membership rows `"member"`, a
membership-leg outage labels the supervised-only page `"supervised"`, a supervised-source outage labels
the survivors `"member"`.

The wire contract, the spec gotcha (`type: string` with **no `enum:` key** — an inline enum generates a
per-DTO nested type that cannot be a shared interface getter's return type) and the `ResponseBodyAdvice`
mechanism live in [[REST-API-DESIGN]]; this guide owns only *why the field exists and what its absence
means*.

## Operating it

Four settings decide this feature's behavior, and they live in three places on purpose — the decision-side
numbers in policy data, the ingestion in application config, the ceremony in the realm.

| What | Where | Notes |
|---|---|---|
| `loa`, `required_acr`, `max_age`, `skew` | `infra/opa/policies/step_up.json` | The decision-side source of truth. The challenge's `acr_values`/`max_age` are read **from the reason**, so no Java copy exists. |
| `acr`, `auth_time`, `act_chain` ingestion | `opa.abac.subject.attribute-claims` | Pure configuration. **`auth_time` must stay numeric** — a string leaves the arithmetic undefined, which is fail-closed but silently un-elevatable. |
| The level-2 condition max age | the Keycloak realm | Mirrors `step_up.max_age` (both 300 s); the two locations cross-reference each other — see `infra/README.md`. |
| `PRIVILEGED_READ`'s vocabulary | `opa.abac.audit.privileged-read.*` | **Unset means silent.** Set *partially* **fails startup** rather than quietly disabling the event — silently disabling an audit control on a typo is how oversight stops happening. |

### The footgun: a `required_acr` the realm cannot mint

**Setting `step_up.required_acr` to a value the realm does not map does not degrade — it strands the
user.** The policy still emits a well-formed RFC 9470 challenge, the client dutifully forwards it as an
*essential* claims request, and **Keycloak then rejects the authorization request outright**
(`Invalid parameter: claims`). It does not downgrade to a lower level and it does not authenticate: the
user lands on a Keycloak error page, and the client never receives a response it could interpret or
explain. Nothing in the resource server sees this — the failure is entirely between the browser and the
IdP.

So `required_acr` must name a level the realm's acr↔loa mapping actually mints. There is still no
**startup** validation, and there should not be — it would mean the resource server reading realm
configuration it deliberately does not couple to. The check belongs where both halves are visible at
once, which is the rig's own configuration:

```bash
scripts/checks/check-step-up-acr.py     # policed in CI (job: opa-policy-tests)
```

It compares `infra/opa/policies/step_up.json` against the realm export's `acr.loa.map` and fails on the
three ways they can disagree: a `required_acr` the realm cannot mint (the footgun above), a
`required_acr` absent from OPA's own `loa` (fails closed to a plain deny, so the operator gets silence
where they expected a challenge), and a shared name carrying **different levels** on the two sides —
which elevates nothing while looking correct from either file alone. A realm that knows *more* levels
than this deployment uses is fine.

Note what the policy corpus can and cannot catch here. `category.rego` / `product.rego` already refuse
to mint a challenge unless `required_acr` maps to a numeric level in `data.step_up.loa` — but that
guard is about OPA's data being coherent **with itself**. The stranding case passes every one of those
guards; only a check that reads both files sees it. Changing `required_acr` remains a
rig-configuration change, not a policy-data tweak.

## Prove it — the whole path

Each slice's boundary has its own matrix, and they are cumulative rather than layered — a later slice
**flips** the cells whose answer it deliberately changed, annotating each in place, rather than deleting
them.

| Gate | Proves |
|---|---|
| `scripts/postman/run-supervised-scope-matrix.sh` | The list: reach by id, the CONTROL-seat rule, withdrawal on the next request |
| `scripts/postman/run-production-tier-matrix.sh` | The tier: liveness in both directions, and unstrippability by the catalog's own owner |
| `scripts/postman/run-step-up-matrix.sh` | The RFC 9470 round trip, the sole-blocker answers, both audit events, and that a refresh does not extend the window |
| `docs/to-do/implemented/SPA-CHALLENGE-UX/10-QA-TEST-CASES.md` **E10–E21** | The committed **browser** case list — the behavioural spec for the client half, run adversarially in the Browser pane |

The step-up matrix's tokens come from `scripts/postman/mint-code-flow-token.py`, a scripted PKCE code
flow, because **ROPC structurally cannot carry `auth_time`** — the mapper reads a user-session note the
direct grant never sets. See [[E2E-TESTING]].

### Run it yourself

```bash
ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2
cd scripts/postman && ./run-supervised-scope-matrix.sh
```

The headline cells: **`sup-anna`**, a member of no team, gets exactly her unit's catalogs *by id* —
including her report's report's, and excluding a report's READER-seat team; **removing a reporting edge
withdraws access on the very next request**, and the withdrawn catalog then returns `403` on a direct
`GET` rather than merely disappearing from the list.

## Related

- [[TEAM-BASED-AUTHORIZATION]] — the membership path this one sits beside, and the role resolution it reuses
- [[ABAC-AUTHORIZATION]] — the decision spine, the `deny_reason` envelope and the enrichment contract
- [[TAG-BASED-AUTHORIZATION]] — the `operatorManaged` flag and the operator tagging path
- [[REST-API-DESIGN]] — `_provenance` and `_actions` on the wire, and the `401`-vs-`403` split
- [[E2E-TESTING]] — the in-network token caveat and the code-flow miner
- [[AGENT-TOOL-AUTHORIZATION]] — the `act_chain` delegation claim whose presence closes this path
