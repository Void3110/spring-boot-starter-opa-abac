---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/security
---

# ADR 0014 — Role-source outage is error-distinct from no-role at the supplier SPI

**Status:** Accepted (planned — Slice B2, [[B2-SUPPLIER-OUTAGE]])
**Date:** 2026-06-15
**Context tags:** `RoleDefinitionSupplier`, fail-closed, realm-role fallback, outage vs no-role, SPI contract

> This ADR pins the **outage-vs-no-role fork** for **Slice B2**: how the `RoleDefinitionSupplier` SPI
> lets a consumer tell a resolve **outage** (the role source is unavailable → the result is *unknown*)
> apart from an authoritative **no-role** (the subject genuinely has no role here → a designed signal).
> The scope was settled in a planning interview (2026-06-15). It closes the one tracked
> *widening-on-failure* path — review [[PERMISSION-CATEGORIES-REVIEW]] C1/C4, aggravated by Phase 6.5.
> Slice order is pinned in [[POC-ROADMAP]]: **B2 → 6.7 → Phase 6 → B3 → Phase 7**.

## Context

`RoleDefinitionSupplier.lookup(userId, type, id)` returns `Optional<RoleDefinition>`. Today **both**
of two semantically different outcomes collapse to `Optional.empty()`:

1. **Authoritative no-role** — the subject genuinely has no role for this resource (the HTTP supplier's
   user-management service answers `204`). This is a **designed signal**: the per-type catalog policies
   keep a **JWT realm-role fallback** (`catalog-viewer` → READ, `catalog-editor` → READ+WRITE+TAG) that
   is *meant* to decide for non-members and type-level creates (5.97, ADR [[0013-attribute-rich-pre-authorization|0013]]).
2. **Outage** — the role source is unavailable (timeout, connection refused, 5xx, malformed body). The
   result is **unknown**, but `HttpRoleDefinitionSupplier` swallows every transport/parse failure into
   `Optional.empty()`.

Because both reach the policy as "no `role_definition`", an **outage** triggers `not has_role_definition`
→ the **realm fallback fires**. A subject carrying realm `catalog-editor` therefore *widens* to
READ+WRITE+TAG during a user-management outage, and any narrowing their **resolved** role carried
(`denied_actions`, `required_tags`) **evaporates** — `effective_from_categories` does no subtraction on
the fallback path. **An outage makes access wider, not narrower.** This is the single
widening-on-failure path in the codebase (every other failure is fail-closed); it was found as
[[PERMISSION-CATEGORIES-REVIEW]] C1/C4 and consciously carried as tracked follow-up B2 — Phase 6.5
**aggravated** it (pre-6.5 the resolved role had no `denied_actions`/`required_tags` to erase).

The seam, verified in code (2026-06-15):

| Consumer | On `Optional.empty()` today | Has a realm fallback? |
|---|---|---|
| `OpaPreAuthorizeAuthorizationManager` (gate) | `role_definition` absent → policy **fallback fires** | **yes** (the hole) |
| `OpaAuthorizationManager` (gate) | `role_definition` absent → policy **fallback fires** | **yes** (the hole) |
| `HierarchicalAuthorizer` | `orElse(null)` → `return false` (already denies) | no |
| `SubtreeSpecResolver` | `orElse(null)` → `Optional.empty()` (no widening) | no |
| `CategoryListAuthorizer` (example) | `null` role → deny-all residual → empty list | no (`filter` is role-def-only) |

The widening is **confined to the two gate managers**; the `filter`/inherited paths have no fallback,
so for them no-role *already* denies. B2's behavioral fix is therefore the gate; the other consumers
get hardening so the new exception never escapes uglily.

## Decision

### 1. Tri-state SPI contract: `empty` = no-role, **throw** = outage

`RoleDefinitionSupplier.lookup(...)` keeps returning `Optional<RoleDefinition>`, with the contract made
explicit:

- `Optional.of(def)` — **resolved**: decide on it.
- `Optional.empty()` — **authoritative no-role**: a designed signal; the policy *may* fall back to
  subject realm roles. Unchanged, retained.
- **throws `RoleResolutionException`** — **outage**: the source was unavailable, the result is
  *unknown*; the caller **MUST fail closed** (deny / no widening), **never** fall back.

The exception is the *minimal* SPI change — it adds the third state without disturbing the value path or
the lambda ergonomics of the `@FunctionalInterface` (see §4).

### 2. `RoleResolutionException` — unchecked, in `opa-abac-core`

A new `RoleResolutionException extends RuntimeException` in `dev.dmitriikonovalov.opaabac.core`
(`(String)` + `(String, Throwable)` constructors). **Unchecked**: an outage is an infrastructure
failure ("the world is broken"), not a recoverable business condition the caller handles locally —
the textbook unchecked case — and a checked exception on the single abstract method would force every
lambda implementation to declare/wrap it, defeating the `@FunctionalInterface` contract. Family-
consistent with the existing `AncestorResolutionException`. Spring-free (a plain `RuntimeException`),
so it lives in core alongside the SPI it belongs to and every downstream module already depends on it.
The wrapped cause is for logs only; it is never surfaced to the client.

### 3. The supplier classifies; each consumer maps to its own fail-closed (no wrapper)

The "do it once" safety is **not** a library wrapper. A wrapper that caught the throw and returned
`Optional.empty()` would *re-introduce the exact bug* (outage → empty → fallback); a wrapper that
re-threw a uniform signal is the exception itself with ceremony. And the consumers genuinely **diverge**
on what "deny" means (403 at a gate; an empty page / no subtree widening in the data layer) — there is
no safe unifying transform. So:

- the **supplier** classifies (throws on outage) — the *one* place classification lives;
- each **consumer** catches `RoleResolutionException` and maps it to *its own* fail-closed outcome;
- the discipline that makes this safe is the **explicit contract (§1) + a mandatory per-consumer outage
  test cell**, not a runtime wrapper.

Per-consumer mapping (the keystone scope — all five `lookup()` consumers swept, not just the named one):

| Consumer | Catch `RoleResolutionException` → | Code change |
|---|---|---|
| `OpaPreAuthorizeAuthorizationManager` | `AuthorizationDecision(false)` (403) | **explicit catch** before the existing catch-all |
| `OpaAuthorizationManager` | `AuthorizationDecision(false)` (403) | **explicit catch** before the existing catch-all |
| `HierarchicalAuthorizer` | `return false` | add a catch (the throw currently escapes uncaught) |
| `SubtreeSpecResolver` | `Optional.empty()` (no widening) | **none** — the existing `catch (RuntimeException)` already collapses it; test-only |
| `CategoryListAuthorizer` (example) | `Page.empty()` | add a catch (prevents an ugly 500 escape) |

The two gate managers already wrap their whole body in `catch (Exception) → DENY`, so the throw is
*already* caught-and-denied — but relying on the broad catch-all is exactly the silent-coupling
anti-pattern the review warned against. The **explicit** catch (with a "B2: outage → deny, never
fallback" comment) turns an incidental property into a documented, tested decision.

### 4. The HTTP classification invariant (strict)

`HttpRoleDefinitionSupplier` stops swallowing failures. The classification is a single, defensible
invariant:

> **Only `204` → `Optional.empty()` (no-role → fallback). Only `200` + a valid body → resolved.
> Everything else throws `RoleResolutionException` (outage → deny).**

Concretely: `200` + blank/empty body → throw (a contract-violating 200 — the only legitimate no-role
is 204 — is untrustworthy, not "no role"); **all** 4xx → throw (a 4xx is "the resolve protocol is not
working as designed", never the designed 204 no-role signal); 5xx → throw; timeout / connection refused
/ `IOException` → throw; malformed-200 body (parse failure) → throw. Treating any of these as no-role
would let a misrouted / misconfigured / corrupt response silently widen — the whole bug.

### 5. No kill-switch — fail-closed is non-optional

Unlike the Phase-5 (`partialEval.enabled`) and Phase-5.97 (`opa.abac.resource-resolution.enabled`)
slices, B2 ships **no property to revert to the old behavior**. Those switches revert to a *safe
baseline* (they turn an enrichment off); B2's "old behavior" **is the security hole** (outage →
widening). A documented "make me vulnerable again" toggle is an attractive nuisance — flipped under
outage pressure ("just stop the 403s"), it silently re-opens the hole. The legitimate "I'd rather
degrade than 403" need is served by the realm fallback for the *no-role* case and by fixing/scaling the
source for the *outage* case (Slice B3, §Consequences), **not** by re-enabling silent widening. This
consciously breaks the house "every mechanism has an off-ramp" pattern; the off-ramp here is the vuln.

### 6. Zero Rego changes — the fallback clause is *retained*

The fix is entirely Java-side: on outage a consumer **denies before any OPA call** (the gate returns
`DENY` without building/sending a context; the data consumers return empty/no-widening without
compiling). OPA therefore only ever sees the two surviving inputs — resolved-role, or
authoritative-`204`-no-role — both decided exactly as before. `has_role_definition` and the realm
fallback clause stay **byte-identical**. **The fix is NOT to delete the fallback**: authoritative
no-role → fallback is a *designed* path (non-members, type-level creates per 5.97); B2 only stops
*outages* from reaching it. `opa test` is unchanged (157/157).

### 7. Implementor conformance

- `NoOpRoleDefinitionSupplier`, `DemoRoleDefinitionSupplier` — **in-process, deterministic; never
  throw** (no source to be unavailable). Untouched. Any app-provided lambda is likewise free not to
  throw.
- `HttpRoleDefinitionSupplier` (example) — the primary fix: the strict classification of §4.
- `TeamRoleDefinitionSupplier` (user-mgmt, the dogfooding showcase) — **minimal touch**: catches its
  data-access failure (`DataAccessException`) → throws `RoleResolutionException`, so its outage path is
  *legible* and contract-conformant. Outcome is unchanged (user-mgmt has no realm fallback — a DB error
  already denied via the gate's broad catch-all); B2 makes the deny intentional, not incidental. The
  authoritative no-role cases (not a team / unparseable id / no user / not a member) stay
  `Optional.empty()`.

## Considered options

| Option | Why not |
|--------|---------|
| **Tri-state sealed return** (`Resolved \| NoRole \| Unavailable`) | Encodes the same three states with a compiler-checked exhaustive `switch` at every consumer — but a larger refactor across all 5 consumers + 4 implementors, and it breaks the `@FunctionalInterface` lambda ergonomics. The unchecked exception is lighter and matches the existing `AncestorResolutionException` / ADR-0013-§3 "throws → deny" precedent. |
| **Checked exception** | Would *force* compile-time acknowledgement at every consumer (a real safety property for a security fix) — but breaks `@FunctionalInterface` and is un-idiomatic for an infrastructure failure. The acknowledgement discipline is recovered via the mandatory per-consumer test cell (§Decision 3). |
| **A library `FailClosedRoleDefinitionSupplier` wrapper** | Catch-and-return-`empty` re-introduces the bug; catch-and-rethrow is the exception with ceremony; and "deny" is not uniform across consumers (403 vs empty-page vs no-widening) so no single wrapper can map it. Classification belongs in the supplier; mapping belongs in each consumer. |
| **Delete / narrow the realm fallback** | The fallback is load-bearing for non-members and type-level creates (5.97). The bug is *outages reaching* the fallback, not the fallback itself. Deleting it would break designed paths and over-scope a security fix. |
| **A kill-switch to restore the old swallow-all** | Its "off" position is the vulnerability (§Decision 5). |
| **Treat 4xx / 200-blank as no-role → fallback** | Lets a misrouted / misconfigured / contract-violating response silently widen — the exact failure mode B2 exists to remove. Only `204` is the designed no-role signal. |
| **Add retry / circuit-breaking here** | An *availability* concern on a different axis; getting retry right (transient-5xx/timeout vs permanent-4xx, latency under request-handling paths) is real design work that would entangle a security fix. Scoped to **Slice B3** (§Consequences). |

## Consequences

- **Good:** the one widening-on-failure path closes — an outage can no longer ride the realm fallback to
  a grant wider than the resolved role. The SPI contract is now legible at the seam (the review's
  recurring "the sweep stopped at the surface the ticket named" lesson applied pre-emptively: all five
  consumers swept, not the one named). Fail-closed becomes a *documented, tested* property rather than
  an incidental consequence of a broad catch-all.
- **Cost:** an outage now produces a **hard deny wall** (every fallback-eligible request denies for the
  outage's duration) where it previously served the fallback. That "availability" *was the bug* (it was
  serving wider-than-authorized access), but the operational sharpness is real and motivates **Slice
  B3** — a cross-service-HTTP resilience pass (retry/backoff/circuit-break for
  `HttpRoleDefinitionSupplier`, `TagDefinitionClient`, `OpaClient`), sequenced **before publish**
  (`B2 → 6.7 → Phase 6 → B3 → Phase 7`). Because B2 chose uniform-403 over a distinct 503, the **server
  log is the only operator-visible outage signal** — hence WARN at the supplier throw-site (status /
  exception class, no PII), DEBUG at the consumer catches.
- **Additivity:** `opa-abac-core` gains one Spring-free exception type + a javadoc-only SPI contract
  change. NoOp/Demo and any app lambda see **zero** behavior change (they never throw). Zero Rego;
  `opa test` 157/157 unchanged. The only behavior changes are in the example `HttpRoleDefinitionSupplier`
  (classification) and the two gate managers (the fix); the data consumers are hardening/test-only.
- **Follow-on:** Slice B3 (resilience) softens the deny wall; Phase 6.7 (control-plane vocabulary) and
  Phase 6 (enrichment) are unaffected (they don't touch the role-source seam).

## Related

- [[PERMISSION-CATEGORIES-REVIEW]] — C1/C4, the finding this closes (and the 6.5 aggravation).
- ADR [[0013-attribute-rich-pre-authorization|0013]] — the realm-fallback semantics B2 protects (its
  §3 split-fail-closed posture is the precedent mirrored here); the 5.97 design where the fallback is
  load-bearing for non-members / type-level creates.
- ADR [[0007-coarse-grained-permission-categories|0007]] — the `denied_actions`/`required_tags`
  narrowing an outage erased (the aggravation).
- ADR [[0011-error-contract-problem-json|0011]] — the error contract (B2 adds no new error code; an
  outage is a uniform deny, not a distinct wire status).
- [[B2-SUPPLIER-OUTAGE]] — the slice (00-DESIGN) · [[POC-ROADMAP]] (order; the new B3 row).
