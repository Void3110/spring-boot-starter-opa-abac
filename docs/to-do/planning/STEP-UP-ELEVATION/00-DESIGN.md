---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/spring
---

# STEP-UP-ELEVATION — 00-DESIGN

> Slice **C** of the supervisor epic: implements [[0030-step-up-decision-contract|ADR 0030]] **§5–9**
> as amended (§Amendments, 2026-08-13). A supervisor's **production** child read stops being a plain
> 403 and becomes a **step-up round trip**: a structured deny → an RFC 9470 challenge → a fresh
> second factor → the read opens for a bounded window. Members are untouched; **agents are closed
> out of the supervised path entirely**. Settled 2026-08-13 (grill-me: nine forks, all recorded in
> §Considered-and-rejected).

## The feature in one paragraph

Slice B closed a supervisor's **production** contents with a plain 403 and pinned *why* (the
operator-managed `env` tier, carried to child decisions as `root_attributes`). This slice adds the
**elevation** that opens them deliberately: the resource server reads `acr` and `auth_time` from the
subject's token (pure configuration — the ingestion seam already exists), the policy adds an
`elevated` predicate (`loa[acr] >= 2` and `now − auth_time ≤ max_age + skew`), and the production
tier deny gains a `not elevated` conjunct. When elevation is the **only** thing standing between the
supervisor and the read, the decision carries a structured `deny_reason`, which the library's advice
maps to a `401` with an RFC 9470 `WWW-Authenticate` challenge; the client re-authenticates with
`max_age` + an essential `acr` claim, Keycloak's conditional level-2 flow demands TOTP, and the very
next read is 200 — until the freshness window closes. The headline: `sup-anna` hits her report's
production catalog, answers one TOTP prompt, and reads it for five minutes; her refresh tokens
cannot stretch that window, an out-of-unit supervisor probing the same catalog learns nothing (plain
403, no challenge), and an AI agent holding her freshest token is refused outright.

## What this builds on, and what it must not break

- **B's tier machinery is the substrate**: `root_attributes` enrichment (ADR 0032), the two
  provenance-scoped denies per leaf policy, the widened supervisor role. C **amends the production
  clause only** — the absent/unproven clause stays elevation-proof (an enrichment outage is a closed
  tier for *everyone*, elevated or not: elevation proves who is present, never what the tier is).
- **The subject-attribute ingestion seam exists**: `opa.abac.subject.attribute-claims`
  (`SubjectClaimsConfig` + `JwtClaimsSubjectExtractor`) copies top-level claims type-preserved into
  `input.subject.attributes`. C ships **no library ingestion code** — only the examples' YAML gains
  `[acr, auth_time]`. (Verified against source at design time; the extractor's number-type
  preservation is a decompose-time seam check — the policy does arithmetic on `auth_time`.)
- **The decision envelope is boolean everywhere** (`OpaClient.allow`, both managers, every stub).
  C evolves it **additively** (§3): a new `decide()` default method; `allow()` stays byte-identical;
  every existing implementor and test compiles unmodified.
- **The advice is the PEP**: `AbstractProblemAdvice` already owns the
  `AuthorizationDeniedException → 403 problem+json` mapping both example services extend. The 401
  branch lands there — no filter, no new exception type.
- **The agent surface** (ADR 0028): the tool-gate narrows, the target-gate decides. C adds a
  provenance-scoped agent deny at the target-gate policies (§6); the tool-gate and the MCP module
  are untouched.
- **The e2e harness is ROPC everywhere** — structurally unable to carry `auth_time` (ADR 0030
  §Context). The scripted code-flow token miner (§7) is therefore a first-class deliverable, not
  test plumbing. Existing runners and `mint_token()` are untouched.

## The epic and this slice's boundary

**In**: the realm's claims fix + level-2 TOTP flow + seeded OTP credential (§1); the `elevated`
predicate + `deny_reason` emission with the sole-blocker rule (§2); the additive decision envelope
(§3); the RFC 9470 challenge emitter + `STEP_UP_REQUIRED` (§4); the two audit events (§5); the
supervised path closed to agents (§6); the code-flow token miner + the step-up matrix incl. the
freshness drill (§7).

**Out, deliberately**:
- **The SPA challenge UX** — catching the 401 in `example-demo-ui`, running the `max_age` re-auth,
  retrying. Ships as a small **collaborative** follow-up after C (roadmap note stands); the scripted
  miner proves the round trip end to end without it.
- **Any second factor beyond TOTP** — pluggability is demonstrated by the flow shape (ADR 0030 §9);
  no SMS plugin ships (NIST-restricted; inform, don't block).
- **A "supervised agent read-out"** — §6 closes agents out *for now*, as a recorded, revisitable
  decision. Re-opening it is its own designed feature (own audit story, own capability tier), never
  a default.
- **Audit persistence/retention** — the emission point only (ADR 0030 §8).

## The design

### 1. The realm — scopes, the level-2 flow, deterministic TOTP

The claims' absence is self-inflicted realm configuration (ADR 0030 §Context, re-confirmed on the
committed export 2026-08-13): `defaultClientScopes` pins a literal four-name list, which *replaces*
Keycloak's built-in assignment and leaves the `basic` scope (carrying the built-in `auth_time`
session-note mapper) and the `acr` scope assigned to no client. The fix **restores built-in
behavior**: add `basic` + `acr` to both clients (`catalog-spa`, `catalog-gateway`). Known,
harmless side effect: ROPC tokens gain `acr` (the `acr=1`-vs-SSO-`acr=0` trap is recorded — `acr`
alone is never a control here); no matrix asserts token claim sets, and the non-regression set
proves it.

The browser flow gains Keycloak's **conditional Level-2 subflow** with TOTP as the required
authenticator, realm ACR-to-LoA mapping `{"aal1": 1, "aal2": 2}`, and the level-2 condition's
**max age set to 300 — mirroring the policy's window** so exactly one freshness number exists,
stated in two places that cross-reference each other. The flow-definition JSON is fiddly: it is
written from a **live-exported realm**, never from memory (decompose-time seam line).

`sup-anna` gets a **seeded OTP credential with a fixed, fixture-only secret** in the realm export
(declarative, like every other fixture identity), so the e2e computes codes offline. Other personas
stay TOTP-less: a supervisor who cannot satisfy level 2 simply never elevates — fail-closed, and
not a C e2e cell. The realm changed ⇒ the step-up runner documents the **`./deploy.sh down` first**
re-import discipline (slice A's precedent).

### 2. The policy — `elevated`, the amended production deny, and the sole-blocker `deny_reason`

The `step_up` knobs live in a **data JSON** next to `permission_categories.json` — one auditable
place, no literals in rules:

```json
{"loa": {"aal1": 1, "aal2": 2}, "max_age": 300, "skew": 30}
```

```rego
elevated if {
    not is_agent_call                       # §6 — elevation is a human ceremony
    data.step_up.loa[input.subject.attributes.acr] >= 2
    (time.now_ns() / 1000000000) - input.subject.attributes.auth_time
        <= data.step_up.max_age + data.step_up.skew
}
```

Fail-closed by construction: a missing/unmapped `acr` leaves the LoA lookup undefined; a missing
`auth_time` leaves the arithmetic undefined — either way `elevated` is undefined and the deny
holds. The clock is **OPA's own** (`time.now_ns()`), skew explicit.

The **production clause alone** gains the conjunct, in both leaf policies:

```rego
denied if {
    input.role_definition.attributes.provenance == "supervised"
    "production" in root_env_values
    not elevated
}
```

The absent/unproven clause is **untouched and elevation-proof**. B's per-clause mutation guards get
siblings for the new conjunct (deleting `not elevated` must fail the elevation-allows tests;
deleting the production conjunct must fail B's pre-existing guards — re-measured, not assumed).

**`deny_reason` is emitted only when step-up is the *sole* blocker**: the subject is `granted` and
no deny other than the step-up clause fires. Structurally: the production+supervised+`not elevated`
body is factored into its own `stepup_denied` rule (consumed by `denied`), and:

```rego
deny_reason := {"type": "insufficient_user_authentication",
                "required_acr": "aal2", "max_age": data.step_up.max_age} if {
    stepup_denied
    granted
    not denied_other          # every deny EXCEPT the step-up clause
}
```

This makes ADR 0030 §7's fingerprinting stance structural: an out-of-unit supervisor gets a plain
403 (no "this is production" leak — in practice their role never synthesizes, but the rule holds
even if it did); an elevated supervisor's **write** gets a plain 403 (the read-only ceiling is not
an elevation problem); an **agent** call never sees a challenge (§6's deny is "another deny", so
sole-blocker suppresses the reason — an unfulfillable TOTP prompt would recreate the §7 loop).
The window arithmetic (boundaries, skew, both undefined-input directions) is pinned by `opa test`
with explicit clocks.

### 3. The decision envelope — additive `decide()`

`OpaClient` gains a **default method** `decide(AbacContext)` returning a core record:

```java
OpaDecision(boolean allow, DenyReason denyReason)          // denyReason null = plain decision
DenyReason(String type, String requiredAcr, Integer maxAge) // §6's three fields, typed
```

The default delegates to `allow()` with a null reason — every existing implementor and stub
compiles unmodified (the T3/`root_attributes` additivity discipline repeated). `HttpOpaClient`
overrides it to parse both fields from the same response; `allow()` keeps its byte-identical
contract. Three fail-closed rules:

1. A **malformed** `deny_reason` on the wire (wrong types, missing fields) → plain deny, reason
   dropped — parse trouble never throws and never widens, matching `allow()`.
2. `ResilientOpaClient`'s fail-closed results (breaker open, transport failure, retries exhausted)
   → **plain deny, never a fabricated reason** — a challenge promises "re-auth fixes this"; during
   an OPA outage that promise would be a lie and a TOTP treadmill.
3. **Bulk and filter stay boolean-only** — `allowAll` (the `_actions` feed) and the partial-eval
   path never carry reasons. The step-up challenge is a single-decision concern; the supervised
   *list* is covered because B put the tier decision at the coarse type-level gate, which is a
   single decision through the same manager.

Scope: **`OpaPreAuthorizeAuthorizationManager` only** adopts `decide()`. The request-pattern
`OpaAuthorizationManager` stays boolean — a stated boundary, not an omission.

### 4. The challenge emitter — advice-based, library-owned

The manager returns a **`StepUpRequiredDecision extends AuthorizationDecision`** (`granted=false`,
carrying the typed reason) when `decide()` yields one. Spring's method security propagates it inside
`AuthorizationDeniedException` untouched. `AbstractProblemAdvice`'s existing handler grows one
branch: a `StepUpRequiredDecision` maps to

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer error="insufficient_user_authentication",
  error_description="A second factor is required to read production content",
  acr_values="aal2", max_age="300"
```

with a problem+json body carrying the **additive** `ApiErrorCode.STEP_UP_REQUIRED`; every other
denied result keeps the existing 403 path byte-identically. `acr_values`/`max_age` come **from the
`DenyReason` fields** — the policy data is the single source; the advice holds no local copy.
Fail-closed edge: a reason with null/partial fields → the plain 403 — never a half-formed challenge
(a challenge missing `max_age` is the §7 infinite loop). Both example services inherit the emitter
through the advice they already extend: **zero example-side code**.

### 5. Audit — the emission points

One dedicated logger, **`opa.abac.audit`** (plain SLF4J, structured key-value payload), named so a
consumer routes it separately. Two events:

- **`STEP_UP_CHALLENGED`** — in the advice, when the 401 is minted: subject, resource type+id,
  governing root, the challenge params.
- **`SUPERVISED_PRODUCTION_READ`** — in the manager, when a decision is **allowed** with
  `provenance == "supervised"` on a production-tier target and an elevated subject: subject,
  governing root, resource, access path, `acr`, `auth_time` (ADR 0030 §8's list verbatim).

No token-level "elevation happened" event — the library never sees the Keycloak ceremony, only
tokens; the elevated *read* is the elevation in use, which is the auditable fact. **Audit never
affects the decision**: the emit path catches and drops its own exceptions (log-of-last-resort);
an audit bug must not become an authorization outage. Nothing is persisted (ADR 0030 §8).

### 6. Agents — the supervised path is human-only

The supervised path — **all of it, any tier** — closes to agent calls at the target-gate policies:

```rego
denied if {
    input.role_definition.attributes.provenance == "supervised"
    is_agent_call        # presence-test: "actor" in object.keys(input.subject.attributes)
}
```

Supervision and elevation are human ceremonies: the reporting relation is between people, and the
second factor proves a person is present. The discriminator is the **presence-test** (the recorded
`actor=false` escape: a bare truthiness test lets `actor: false` route to the wider branch). The
clause lands in every leaf policy the supervised path traverses — `category` + `product`, and
`catalog` **if** slice A's supervised catalog read traverses the policy on the agent path (a
decompose-time seam check against the two-leg list implementation; the app-side leg may make the
catalog clause moot — verified, not assumed, and the tool-gate roster's behavior for a memberless
supervisor principal is checked the same way). Consequences, both automatic: a borrowed `aal2`
token in an agent call still denies (this clause is not the step-up clause, so sole-blocker
suppresses the challenge → plain 403, no TOTP treadmill for a thing that cannot TOTP); and B's
"members structurally unaffected" discipline repeats — the clause is provenance-scoped, so a
member's agent call cannot reach it (the agent surface's existing behavior for members is
untouched).

### 7. E2e ownership — the miner, the matrix, the drill

**The token miner** — `scripts/postman/mint-code-flow-token.py`, **stdlib-only** python3 (urllib +
cookie jar + the login-form dance + RFC 6238 TOTP via `hmac`): mints `aal1` tokens (plain code
flow) and `aal2` tokens (code flow with `acr_values=aal2` essential + `max_age`, answering the TOTP
prompt from the seeded secret). The 2026-08-13 probe script is the prototype, including its
hard-won gotcha: Keycloak's cookies are `Secure`-flagged, and an http client that silently
withholds them over plain http produces the misleading "Restart login cookie not found" 400 — the
miner forces the cookie header. Existing runners and ROPC `mint_token()` are untouched.

**The matrix** (runs in order; the runner restarts OPA and documents the down-first re-import):

- **S1** — anna at `aal1` reads a production child → **401**, asserted on the `WWW-Authenticate`
  params (`error`, `acr_values`, `max_age`) *and* the `STEP_UP_REQUIRED` body code.
- **S2** — re-auth with `max_age` + essential `acr` → TOTP → same read → **200**; then the
  **log-grep cell**: both audit events present in the pod log.
- **S3** — **the loop-prevention negative**: re-auth *without* `max_age` → SSO reuse → same stale
  `auth_time` → still 401. ADR 0030 §7's "the client MUST forward max_age" as measured behavior.
- **S4** — fingerprinting negatives: an out-of-unit supervisor on the same production catalog →
  plain 403, no challenge header; elevated anna's `PUT` → plain 403.
- **S5** — members unaffected: the owner reads production contents at plain `aal1`, no challenge.
- **S6** — **the agent cell**: an MCP tool call carrying anna's freshest `aal2` token against
  production (and non-production) supervised content → plain 403, no challenge (§6).
- **The freshness drill** (agent-matrix PDP-kill style): push a temporary `step_up` data override
  (`max_age: 5`) to OPA, wait past the window, assert the previously-elevated token now answers
  401 again, restore via EXIT trap. Honest expiry on the wire without touching shipped defaults;
  the window *arithmetic* (boundaries, skew) is `opa test`'s job with pinned clocks.

## Fail-closed posture

The floor is deny, and elevation can only *narrow* what deny covers — never widen anything else:

- Missing/unmapped `acr`, missing `auth_time`, malformed claims → `elevated` undefined → the
  production deny holds. **No token state opens the unproven tier** — the absent clause has no
  elevation conjunct.
- A malformed wire `deny_reason`, a resilient-wrapper fail-closed result, a reason with partial
  fields → **plain deny / plain 403** at each layer. A challenge is only ever emitted from a
  well-formed, sole-blocker reason.
- The agent deny is provenance-scoped and presence-tested: no membership decision can reach it, and
  `actor: false` cannot slip past it.
- Audit emission failure changes nothing about the decision.
- The one request-time failure class B pinned (enrichment failure ⇒ absent ⇒ supervised closes,
  members proceed) is inherited unchanged — C adds no new failure class to that path.

## Considered and rejected

- **SPA challenge UX in-slice** — rejected (Q1): collaborative follow-up; the miner proves the
  round trip.
- **An allow-side elevation exception** — structurally impossible under deny-overrides (Q2); the
  conjunct lives in the deny clause.
- **Elevation bypassing the unproven tier** — rejected (Q2): outage ≠ non-production, whoever asks.
- **`deny_reason` on every step-up-shaped deny** — rejected for the sole-blocker rule (Q2):
  fingerprinting + the write-ceiling + the agent loop all fall out of one rule.
- **An untyped `Map<String,Object>` reason** — rejected (Q3): the typed record keeps the
  fail-closed check at parse time and the emitter dumb.
- **A servlet-filter challenge emitter** — rejected (Q4): duplicates the problem+json machinery;
  the advice is the repo's PEP convention.
- **A token-level "elevation" audit event** — rejected (Q7): the library would be faking a signal
  it cannot see.
- **Admin-API TOTP enrollment at deploy time** — rejected (Q5): the export stays the declarative
  source of truth for fixture identities.
- **Deferring the agent question** — rejected (Q9): C installs the lock on the back door; leaving
  the front door open by default is the wrong default. Revisitable as its own feature.
- **A new ADR** — not needed (Q8): ADR 0030 §5–9 is the contract; the four grill-me refinements
  ride a dated §Amendments note on 0030 (B's §Population precedent on 0032).

## Knowledge destination

Guides, subsection-owned (the B lesson): T4 owns TEAM-BASED-AUTHORIZATION's *Step-up elevation*
subsection (T6 appends only its e2e paragraph); T3 owns ABAC-AUTHORIZATION's envelope section; T5
owns E2E-TESTING's code-flow token path section; T1 owns infra/README's realm note (T6 its matrix
section + the postman registry row). Mulch: the probe + correction records already exist
(`opa-abac-rig-deploy-ops`); per-ticket records per the domain table.

## Execution parts

**Parts:** part 0 = T1–T4 · part 1 = T5–T6

Part 0 is the mechanism, provable without the full rig — the realm (T1, provable against Keycloak
alone: boot + the probe script through TOTP), the policy (T2, `opa test`-provable incl. the window
arithmetic and the agent deny), the envelope (T3, unit-provable + old-tests-unchanged), and the
manager + emitter + audit + example config (T4, unit/IT-provable). Part 1 is the proof: the token
miner (T5) and the step-up matrix with the freshness drill and the agent cell (T6, rig). **Every
fail-open code edge lands in part 0** — the `elevated` undefined-input discipline, the sole-blocker
emission, the malformed-reason and resilient-passthrough rules, the half-formed-challenge guard —
each covered by part 0's inline review; part 1 carries no new code edge: its review checks the
proofs. The boundary is the deployable handoff: after part 0 the corpus and both services are green
while a supervisor's production read is *still* a plain 403 on the rig (nothing mints an `aal2`
token yet — the safe intermediate state); part 1 proves the round trip end to end.
