---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/security
  - area/spring
---

# 00 — Design: Supplier-outage fix-slice (Slice B2)

> The design, written from a settled **ADR [[0014-supplier-outage-error-distinct|0014]]** (which pins
> every fork below; interview 2026-06-15). A **security** fix scoped to one SPI contract: distinguish a
> resolve **outage** (the role source is unavailable → the result is *unknown* → **deny / no widening**)
> from an authoritative **no-role** (`204` → a designed **realm-fallback** signal) at
> `RoleDefinitionSupplier`. Closes the one tracked *widening-on-failure* path ([[PERMISSION-CATEGORIES-REVIEW]]
> C1/C4, aggravated by Phase 6.5). **Zero Rego changes; `opa test` 157/157 unchanged; no kill-switch;
> NoOp/Demo and any app lambda untouched.**

## 1. The problem, precisely

`RoleDefinitionSupplier.lookup(userId, type, id)` returns `Optional<RoleDefinition>`. Today **both** an
authoritative no-role *and* an outage collapse to `Optional.empty()`. Verified in code (2026-06-15):

| Surface | Today | Consequence |
|---|---|---|
| `HttpRoleDefinitionSupplier.lookup` | `catch (Exception) → Optional.empty()` swallows **every** transport/parse failure; `204`, `200`-blank, all non-200 → `empty` | an **outage** is indistinguishable from authoritative **no-role** |
| Gate (`OpaPreAuthorizeAuthorizationManager`, `OpaAuthorizationManager`) | `role_definition` absent → policy **realm fallback** fires (`catalog-viewer`→READ, `catalog-editor`→READ+WRITE+TAG) | an **outage** rides the fallback to a grant **wider** than the resolved role; the role's `denied_actions`/`required_tags` narrowing **evaporates** (6.5 aggravation) |
| `HierarchicalAuthorizer` | `orElse(null)` → `return false` | already denies on no-role (no fallback); an outage throw would **escape uncaught** |
| `SubtreeSpecResolver` | `orElse(null)` → `Optional.empty()` (no widening); already `try/catch (RuntimeException)` | already correct; an outage throw is already collapsed |
| `CategoryListAuthorizer` (example) | `null` role → deny-all residual → empty list (`filter` is role-def-only, no fallback) | already fail-closed on no-role; an outage throw would **escape → ugly 500** |

**The widening is confined to the two gate managers** — the `filter`/inherited paths have no realm
fallback, so for them no-role *already* denies. So B2's *behavioral* fix is the gate; the other
consumers get hardening so the new exception never escapes.

This is the single widening-on-failure path in the codebase (every other failure is fail-closed). It
was found as [[PERMISSION-CATEGORIES-REVIEW]] C1/C4 and consciously carried as B2; Phase 6.5
**aggravated** it (pre-6.5 the resolved role carried no `denied_actions`/`required_tags` to erase).

## 2. The mechanism (ADR 0014 §1–§4)

### Core (`opa-abac-core` — Spring-free, additive)

```java
public class RoleResolutionException extends RuntimeException {
    public RoleResolutionException(String message) { super(message); }
    public RoleResolutionException(String message, Throwable cause) { super(message, cause); }
}
```

The `RoleDefinitionSupplier` javadoc gains the **tri-state contract**:

```
@return the role definition, or Optional.empty() if the subject AUTHORITATIVELY has no role for
        this resource (a designed signal — the policy may fall back to subject realm roles)
@throws RoleResolutionException if the role SOURCE was unavailable (an outage) so the result is
        UNKNOWN — callers MUST fail closed (deny / no widening), never fall back. An in-process,
        deterministic supplier (no remote source) never throws.
```

### The HTTP classification invariant (`HttpRoleDefinitionSupplier`, example)

Stop swallowing. One strict, defensible rule:

> **Only `204` → `Optional.empty()` (no-role → fallback). Only `200` + valid body → resolved.
> Everything else throws `RoleResolutionException` (outage → deny).**

| Boundary | Verdict |
|---|---|
| `200` + valid body | resolved |
| `204` | `Optional.empty()` (authoritative no-role → fallback) |
| `200` + blank/empty body | **throw** (contract-violating 200; only 204 is the no-role signal) |
| any `4xx` (400/401/403/404) | **throw** (the resolve protocol is not working as designed) |
| any `5xx` | **throw** |
| timeout (connect/request) | **throw** |
| connection refused / `IOException` | **throw** |
| malformed `200` body (parse failure) | **throw** |

The throw wraps the underlying status / exception as the cause (for logs, never surfaced to the client).

### Per-consumer mapping (the keystone — all five `lookup()` consumers swept)

The supplier **classifies** (throws); each consumer maps the throw to *its own* fail-closed outcome. No
library wrapper (it would re-introduce the bug or be ceremony; "deny" isn't uniform — ADR 0014 §3).

| Consumer | Catch `RoleResolutionException` → | Code change |
|---|---|---|
| `OpaPreAuthorizeAuthorizationManager` | `AuthorizationDecision(false)` (403) | **explicit catch** before the existing catch-all, w/ a "B2: outage → deny, never fallback" comment |
| `OpaAuthorizationManager` | `AuthorizationDecision(false)` (403) | **explicit catch** before the existing catch-all, same comment |
| `HierarchicalAuthorizer` | `return false` | add a `catch (RoleResolutionException)` around the `lookup` (the throw currently escapes uncaught) |
| `SubtreeSpecResolver` | `Optional.empty()` (no widening) | **none** — its existing `catch (RuntimeException)` already collapses it; **test-only** proof |
| `CategoryListAuthorizer` (example) | `Page.empty(pageable)` | wrap the `lookup` → on outage return empty page (prevents an ugly 500 escape; matches its fail-closed empty-list posture) |

The two gate managers already wrap their whole body in `catch (Exception) → DENY`, so the throw is
*already* caught-and-denied — the explicit catch turns that incidental property into a documented,
tested decision (the review's "make fail-closed legible at the seam" lesson).

### Implementor conformance (ADR 0014 §7)

- `NoOpRoleDefinitionSupplier`, `DemoRoleDefinitionSupplier` — **never throw** (in-process, no source).
  Untouched. (Javadoc note added: an in-process supplier never throws.)
- `HttpRoleDefinitionSupplier` — the strict classification above (the primary fix).
- `TeamRoleDefinitionSupplier` (user-mgmt, dogfooding showcase) — **minimal touch**: catch its
  `DataAccessException` → throw `RoleResolutionException`, so its outage path is legible and
  contract-conformant. Outcome unchanged (user-mgmt has no realm fallback); B2 makes the deny
  intentional, not incidental. The no-role cases (not a team / unparseable id / no user / not a member)
  stay `Optional.empty()`.

### Logging (ADR 0014 §Consequences)

Because B2 chose uniform-403 over a distinct 503, the **server log is the only operator-visible outage
signal**: **WARN at the supplier throw-site** (HTTP status or exception class — never the `userId`,
token, or body), **DEBUG at the consumer catches** (the supplier owns the alarm; no duplicate WARNs).

## 3. Behavior matrix (the cells that change — and the ones that must not)

Subject under the HTTP role source; **simulated user-management outage** unless noted.

| Caller / situation | Today | After B2 | Cell |
|---|---|---|---|
| Realm `catalog-editor`, **outage**, id'd write the *resolved* role narrows (denied/tag) | **200** via realm fallback (the hole) | **403** (outage → deny, no fallback) | **headline cut (C1/C4)** |
| Realm `catalog-viewer`/`catalog-editor`, **outage**, any catalog action | fallback grants READ / READ+WRITE+TAG | **403** | the hole closes for all fallback-eligible subjects |
| Authoritative **no-role** (`204`), source **up** | fallback decides (READ / READ+WRITE+TAG) | fallback decides — **unchanged** | the designed path is preserved |
| Resolved role (`200`+body), source **up** | decided on the role | decided on the role — **unchanged** | primary path untouched |
| List endpoint (`CategoryListAuthorizer`), **outage** | empty list (no-role → deny-all residual) | empty page (outage caught) — **no 500** | hardening (no behavior change in rows) |
| `SubtreeSpecResolver` widening, **outage** | no widening (caught) | no widening — **unchanged** | test-only |
| `HierarchicalAuthorizer` single-GET, **outage** | denies, but throw escapes uncaught | denies (throw caught) | hardening |
| user-mgmt dogfooded gate, **DB outage** | denies (incidental, via broad catch) | denies (intentional, via the throw) | legibility |

## 4. Example adoption (the diff)

- `HttpRoleDefinitionSupplier` — the classification rewrite; the class javadoc's "NOT fully fail-closed
  (tracked: B2)" section is **replaced** with the now-true posture (outage → throw → deny).
- `CategoryListAuthorizer.readable` — wrap the `lookup` → `Page.empty(pageable)` on outage.
- `TeamRoleDefinitionSupplier` — the minimal catch → throw.
- **NoOp/Demo** — javadoc-only (an in-process supplier never throws).

## 5. What this slice does NOT change

- **Zero Rego.** `has_role_definition`, the realm fallback clause, `tags_satisfied`, `inherited_grant`
  all stay byte-identical — an outage denies *before* any OPA call, so OPA only ever sees the two
  surviving inputs. **The fix is NOT to delete the fallback** (it is load-bearing for non-members and
  type-level creates per 5.97). `opa test` **157/157 unchanged**.
- **No kill-switch** — fail-closed is non-optional (ADR 0014 §5; "off" would be the vuln).
- **No retry / circuit-breaking / timeout tuning** — availability is a different axis, scoped to the
  new **Slice B3** (`B2 → 6.7 → Phase 6 → B3 → Phase 7`).
- **No new error code / wire status** — an outage is a uniform deny (403 at a gate), not a distinct 503.
- **`AbacQueryService` + the four `findAuthorized` paths; the residual model; pagination; the OPA client;
  extraction; the 5.97 resolver/cache; the gate managers' resolution path** — all untouched.

## 6. Proof obligations (QA skeleton — cases get ids in 10-QA)

**Classification (unit, `HttpRoleDefinitionSupplier` against an `HttpServer` stub — the §2 invariant):**
1. `200`+valid body → resolved · 2. `204` → `empty` · 3. `200`+blank → **throws** · 4. `5xx` → **throws**
· 5. `4xx` (400/404) → **throws** · 6. timeout → **throws** · 7. connection refused → **throws** ·
8. malformed `200` body → **throws**.

**Per-consumer fail-closed (one cell each — the keystone):**
9. `OpaPreAuthorizeAuthorizationManager`: supplier throws → `AuthorizationDecision(false)`; **sibling**:
   `204`/empty → fallback still allows (the designed path not broken).
10. `OpaAuthorizationManager`: supplier throws → deny.
11. `HierarchicalAuthorizer`: supplier throws → `false` (no escape).
12. `SubtreeSpecResolver`: supplier throws → `Optional.empty()` (no widening) — **test-only**.
13. `CategoryListAuthorizer`: supplier throws → `Page.empty()` (no 500 escape).

**Contract conformance:**
14. `TeamRoleDefinitionSupplier`: repo throws `DataAccessException` → `RoleResolutionException`;
    no-member → `Optional.empty()`.

**Headline regression (IT — the load-bearing "the hole is closed"):**
15. A subject carrying realm `catalog-editor`, **mock supplier throws** (simulated outage), id'd write
    the resolved role would narrow → **403, NOT the widened fallback grant** (the C1/C4 cut). Contrast:
    source **up** + authoritative-`204` → fallback still grants its designed reach. The IT (mock supplier
    throws → exact 403-not-fallback) is the load-bearing proof; a newman "stop the user service" matrix
    cell is a nice-to-have the decompose phase sizes (not required).

**Suite:** `opa test` **157/157 unchanged** (no policy edits — the fix is Java-side). The whole existing
build green (additivity: NoOp/Demo/lambda untouched).

## 7. Forks already closed (do not reopen during decomposition)

ADR 0014's considered-options table: tri-state sealed return · checked exception · a `FailClosed…`
wrapper · delete/narrow the fallback · a kill-switch · 4xx/200-blank as no-role · retry here. Plus from
the interview: unchecked exception; `RoleResolutionException` name + core home; the strict 204-only
invariant; uniform-403 (no 503); WARN-at-supplier/DEBUG-at-consumer logging; the per-consumer mapping
(gate→403, hierarchy→false, subtree→test-only, list→empty page); `TeamRoleDefinitionSupplier` minimal
touch; resilience → Slice B3 before publish; IT is the load-bearing proof.

## Related

- ADR [[0014-supplier-outage-error-distinct|0014]] — every fork above, with rejections.
- [[PERMISSION-CATEGORIES-REVIEW]] — C1/C4, the finding this closes (and the 6.5 aggravation).
- ADR [[0013-attribute-rich-pre-authorization|0013]] — the realm-fallback semantics B2 protects (its
  §3 split-fail-closed posture is the precedent); ADR [[0007-coarse-grained-permission-categories|0007]]
  (the narrowing an outage erased).
- [[POC-ROADMAP]] — the route box (B2 → 6.7 → Phase 6 → **B3** → Phase 7) and the new B3 resilience row.
- Guides to touch (decompose phase): the `HttpRoleDefinitionSupplier` class javadoc (replace the B2 note),
  the `RoleDefinitionSupplier` SPI javadoc, [[PERMISSION-MODEL]] (the fail-closed posture line),
  [[ABAC-AUTHORIZATION]] (the fallback description).
