---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T5: catalog-service: the two-leg partitioned list, the read-only ceiling, and the audit event

**Status:** ✅ DONE

## What shipped

`GET /catalogs` now returns membership rows **and** supervised rows, each judged by its own authority —
and every existing persona is byte-identical.

- **`CatalogListAuthorizer.readable` composes the two disjoint scopes** through the **shipped** paged
  5-arg `findAuthorized` (the ADR-0010 base-scope-widening idiom, reused rather than reinvented). `M`
  comes from `GovernedScopeResolver.governedIds`, `S` from T4's `SupervisedScopeClient`, then
  **`supervised = S \ M`** (ADR 0029 §5), order-stable and de-duplicated. Three shapes:

  | Case | `scope` | context role | `subtreeSpec` |
  |---|---|---|---|
  | both non-empty | `id IN (M ∪ supervised)` | the **membership** role | `id IN supervised` |
  | `M` empty (a pure supervisor) | `id IN supervised` | the **supervisor** role | `null` |
  | `supervised` empty (an ordinary member) | `id IN M` — **today's call, unchanged** | the membership role | `null` |

- **`governedIds.get(0)` is retired, with the Javadoc paragraph that justified it** ("every governed
  catalog is one the subject is a member of" — the assumption this slice breaks). Its replacement is the
  explicit **anchor rule**: *whenever `M` is non-empty the residual-driving role is resolved from a
  **membership** id, never from the union*; only when `M` is empty is it resolved from a supervised id.
  That is the slice's one fail-**open** surface and it is now stated, not implied.
- **The two request-time failure classes land where the design says.** A supervised-source failure →
  the client already fails closed to empty → **membership-only** (U30). A membership leg that does not
  resolve (outage, or revoked between the two calls) → that leg contributes **nothing**, dropped and
  never defaulted, and the **supervised leg still stands on its own authority** (U29); when no leg has
  authority the floor is the empty page.
- **The audit event** — one per supervised list **request**, at `INFO`, on the dedicated
  `dev.dmitriikonovalov.example.catalog.audit.SupervisedRead` logger (named explicitly, not
  class-derived, so a consumer can route it independently and I6 can assert it by name), emitted **only
  when the supervised leg contributed at least one row to the page**. Payload: subject, access path
  (`supervised` / `mixed`), and the supervised root ids **as a list**. No event on a membership read.
  Nothing persisted. **Scope pinned to the list path** — supervised single-`GET` auditing rides the
  `@OpaPreAuthorize` gate and is deferred to slice C.
- **The read-only ceiling** is emitted by the role, not by special-casing: a supervised row's `_actions`
  is `{view:true, update:false, delete:false, assign-tags:false}` and is **present, not omitted** (the
  omit-on-all-false degrade fires only when *every* verb is false, `mx-cc7262`).

## The T5 re-measurement — done BEFORE coding, as the package index requires

The index names T5's composition claims the least-verified text in the package and requires re-measuring
against the shipped code first. Four measurements, all against artifacts rather than prose:

1. **`AbacQueryService`'s paged 5-arg overload has exactly the four branches `00-DESIGN` §5 pins**, and
   `subtreeSpec` reaches the query in exactly one of them — read in the source, not inferred:
   `!settings.enabled()` → `opaClient.allow` then `scopeOnly(scope).and(notDenied())`, **`subtreeSpec`
   ignored**; `residual.fromError()` → `Page.empty` with **no repository call**; `!fullySupported() &&
   allowlistFallback()` → candidates from `scopeOnly(scope)` batch-rechecked against the **one**
   `queryContext`, **`subtreeSpec` ignored**; otherwise the pure-SQL
   `authorizedSpec(scope, residual, subtreeSpec)` = `scope.and(tagResidual.or(subtreeSpec)).and(notDenied())`.
   **The pinned prose matches the shipped code exactly — no seam deviation.**
2. **There is no two-leg overload.** `findAuthorized` compiles **one** residual from the single
   `AbacContext` it is given (`PartialResult residual = opaClient.compile(queryContext)`), and no public
   method turns a role into a residual `Specification`. So the `subtreeSpec` slot really is the only
   shipped way to say "admit these rows too", and a pre-composed `legA.or(legB)` as `scope` really would
   AND the one residual over the whole union. Confirmed.
3. **U34's precondition, measured against the real corpus and the real engine.** `opa eval` with the
   exact role T2's factory produces:

   ```console
   $ opa eval -d infra/opa/policies -i sup-filter.json 'data.catalog.filter' --format raw
   true
   $ opa eval -d infra/opa/policies -i sup-filter.json --partial --unknowns 'input.resource' \
       'data.catalog.filter'
   filter if "catalog" = input.resource.type      # ×2 disjuncts — the type-eq tautology
   ```

   and then against the **running rig's OPA** through the actual Compile API the client uses
   (`POST /v1/compile`, `query = data.catalog.filter == true`, `unknowns = ["input.resource"]`): two
   disjuncts, each a single `eq("catalog", input.resource.type)`. `CompileResponseParser` classifies an
   `eq` on `input.resource.type` against the known type as a **tautology**, so the disjunct is
   condition-free → **ALLOW_ALL, `fullySupported` true** → the pure-SQL branch. The supervisor role's
   residual is unconditional, which is exactly what makes admitting supervised rows through
   `subtreeSpec` correct.
4. **U42 — the recorded limitation, discharged as a documentation obligation** (it asserts no drop-out).
   `00-DESIGN` §5 records it; `01-DECOMPOSITION`'s T5 cites it; **no ticket claims otherwise** (checked).
   It is now also carried in `CatalogListAuthorizer`'s own Javadoc so it lives beside the code: on the
   two `subtreeSpec`-ignoring branches a **mixed** subject's supervised rows are decided by the
   **membership** role's verdict, a **pure supervisor is unaffected** (its ids ride `scope` with the
   supervisor role as context), and an ordinary member is byte-identical. No WARN and no run-time
   behavior is specified — the app cannot observe which branch ran.

**Conclusion: reality agreed with the pinned prose in all four checks. No seam deviation to escalate.**

## Tests

`./gradlew :example-catalog-management-service:test` green · `./gradlew build` green (all modules,
Testcontainers ITs against real Postgres) · `opa test infra/opa/policies/` **274/274**, and
`git diff --stat main -- 'infra/**' 'opa-abac-*'` is still exactly T3's four policy files — T5 touches no
policy and no library module.

- **`CatalogListAuthorizerTest`** (rewritten; the old single-role assertions were the expected
  build-breakers and were updated in this same commit). Each composition case captures **what actually
  reached each slot** — the `scope` ids, the anchor the role was resolved on, and the `subtreeSpec` ids —
  by invoking the captured Specifications against a mocked criteria API:
  **U25** (membership-only is today's call, anchored on the first *membership* id, `subtreeSpec` null;
  plus the no-`SupervisedScopeClient`-bean variant), **U26** (a pure supervisor scopes to the supervised
  set with the **supervisor** role and no widening arm), **U27** (**the fail-open edge**: `C1` reachable
  both ways appears **once** in the union, is **absent** from the widening arm, and the role is resolved
  on `C1` as a membership id — `C3` is never consulted; plus the S ⊆ M case collapsing to the single-leg
  call), **U28** (both scopes empty → empty page, no query, no role lookup), **U29** (a membership-role
  outage with supervised ids keeps the supervised leg and drops `C1` from the scope entirely; and both
  legs unresolvable → empty page), **U30** (a supervised-source failure is membership-only, never wider),
  **U32** (unauthenticated / no `AbacQueryService` / no `GovernedScopeResolver`), and **U34** (the
  supervisor role's shape — no `requiredTags`, no `deniedActions`, exactly the coarse `READ` token — the
  precondition whose loss must break this test).
- **`SupervisedListIT`** (new, real Postgres via Testcontainers, entered through the real
  `GET /api/v1/catalogs`): **I4** (a mixed page at `perPage=2`: no row twice, none skipped at the
  boundary), **U31** (the wire `count` is the authorized total across both legs), **I5** (a supervised
  row's `_actions` present and read-only), **I6** ×4 (exactly one INFO event on the named logger carrying
  subject / `accessPath=supervised` / both root ids; `accessPath=mixed` for a dual-hatted page; **no**
  event on an ordinary membership read; **no** event when the supervised leg contributed no row to *this*
  page). It uses the **real** `SupervisedScopeClient` against an in-process stub user-service, not a mock
  of it.

  **The stub OPA returns a real DNF, not ALLOW_ALL** — deliberately. With an ALLOW_ALL membership
  residual the widening arm would be redundant and the IT would pass even if the composition were wrong.
  Instead `compile` is **role-aware, mirroring the measurement above**: a tag-gated membership role →
  `tags.region = 'emea'` (real SQL); the supervisor role → ALLOW_ALL. That makes the cut load-bearing: a
  membership `emea` row survives the residual, a supervised `apac` row survives **only** through
  `subtreeSpec`, and a membership `apac` row that the subject **also** supervises stays **excluded** —
  because `S \ M` keeps it off the vacuous arm. That last assertion is the fail-open edge, proven over
  real SQL rather than argued.

## Architecture review + refactor

Ran the ★ gate inline after unit-green, before the ITs. **Two substantive findings; the first is a real
widening the ticket's own design did not anticipate.**

1. **A mid-request membership revocation could let the supervisor role judge membership rows (real,
   fixed).** The anchor rule guarantees the *id* is a membership id, but not that the *role resolved on
   it* is membership-derived. The two are read at different instants: if the membership on the anchor is
   revoked between `governedIds` and the role resolve, the user-service's ordered fallthrough (membership
   first, supervision second — ADR 0029 §2) answers with the **synthesized supervisor role**, whose
   **vacuous** tag requirement would then drive the residual over **every other membership row in the
   scope**. That is precisely the fail-open `supervised := S \ M` exists to prevent, arriving by a race
   rather than by arithmetic. It is narrow — with the shipped ADR-0022 root-read tag exemption on, a
   tag-gated membership role's list residual is *also* unconditional, so the difference only materialises
   in strict mode — but it is real, and it is the one direction that widens.
   **Fixed** by checking the anchor role's **provenance stamp** (ADR 0031's, already shipped in part 0
   and already on the wire): when `M` is non-empty and the anchor resolves a `provenance == "supervised"`
   role, the membership leg is **dropped as stale** and the request falls through to the supervised-only
   shape, or to the empty page when there is no supervised leg. Absence of the stamp reads as
   "not supervised", so a role source that does not stamp behaves exactly as before — the check can only
   ever *drop* a leg, never add one. Three regression tests added
   (`membershipAnchorResolvingASupervisedRole_dropsTheMembershipLeg`, the no-supervised-leg variant, and
   the unstamped-role no-op).
2. **Static analysis: 3 findings.** `S1192` (the `"catalog"` type token repeated 4×) was **fixed** with a
   `CATALOG_TYPE` constant rather than claimed under the `example-*` FP class — it is not a switch label,
   and naming the coordinate every seam is keyed by reads better anyway. The two `S5853` are the
   **documented by-design class** (`mx-302e78`: consecutive `assertThat(sameSubject)` calls left unchained
   because each line carries its own explanatory comment — here the fail-open-edge assertion and the
   audit-payload field assertions). Chaining them would delete exactly the per-line rationale the
   exemption exists for, so they were **marked false-positive in the local instance with a comment naming
   the record**, not rewritten.

**Static-analysis gate: `CLEAN — 0 open findings` on the changed files.**

The rest of the checklist, with what it found:

- **Fail-closed.** Every branch lands on the empty page, one test each: unauthenticated, no
  `AbacQueryService`, no `GovernedScopeResolver`, both scopes empty, a membership-role outage with no
  supervised leg, an absent membership role, both legs unresolvable, and the new stale-anchor case. A
  supervised-source failure degrades to **membership-only**. **No branch can return a partial supervised
  set**: the client returns all-or-nothing (T4), and the set difference is applied to whatever it returns
  in one place.
- **Security — the widening that would matter here** is the set difference being skipped or inverted, so
  that a doubly-reachable row is judged by the vacuous-tag supervisor role instead of its tag-gated
  membership role. It cannot happen: `supervised = S \ M` is computed once, in one method, and asserted
  both at the unit level (U27 — `C1` absent from the widening arm) and over **real SQL** (the IT's
  `memberApac` exclusion, which only passes because the reduction happened). The complementary widening —
  the *anchor* resolving a non-membership authority — was the review's finding 1 and is now closed by the
  provenance check. The realm marker is read nowhere in this class.
- **Concurrency / idempotency.** The two legs are fetched **once each per request**, so both read a
  consistent id set within the request. The interesting race is the revocation one above, now handled;
  the opposite race (a membership *added* between the reads) leaves the row on the supervised arm, which
  grants no more than the supervised authority the subject already held a moment earlier — smaller-or-
  equal, never a mixed snapshot that widens. The union is `LinkedHashSet`-based, so a duplicate id can
  never be listed once and counted twice.
- **Wiring.** T4's client finally has its production call site here — and the `_actions` ceiling and the
  audit logger each have a named consumer with a test through the non-happy path (no event when the
  supervised leg contributed nothing; no event on a membership read).
- **Boundary / additivity.** No library module touched — `AbacQueryService`, the residual compiler and the
  `notDenied()` AND are consumed exactly as shipped; this ticket composes Specifications, it does not
  change how residuals are built. `CategoryListAuthorizer` and `ProductListAuthorizer` untouched (child
  lists stay closed). No Rego. No decision-envelope change. `opa-abac-core` untouched and still
  Spring-free.
- **Module-layer separation.** The derivation stays in the user-service; the set difference and the
  composition live here, on the catalog side, and neither reaches across.
- **Pattern reuse.** The 5-arg call, the `scope`/`subtreeSpec` slots, the `ObjectProvider` optional-bean
  idiom, and the empty-page floor are all the shipped shapes; nothing was reinvented.
- **SOLID.** `readable` reads as its own decision table (scope → anchor → compose → audit), with the set
  difference, the union, the `id IN` builder and the audit emission each factored out and separately
  testable.

## Integration / e2e

ITs in this ticket; the rig-level proof is **T6's E1/E4/E5/E9**. `SupervisedListIT` runs against real
Postgres via Testcontainers and, uniquely among this ticket's tests, exercises the **real** partial-eval →
JPA translation, so the composition is proven as SQL rather than as a mock interaction.

## Decisions

- **A leg with no resolvable role is dropped, and when no leg has authority the method returns the empty
  page directly** instead of calling `findAuthorized` with a `null` role. The outcome is identical — a
  `null` role makes `catalog.rego`'s `filter` fail `has_role_definition`, compiling to `DENY_ALL` → the
  empty page, and on the kill-switch branch `allow` is false for the same reason — so this is
  behaviour-preserving, and it drops a needless OPA compile plus a repository round-trip on a path that
  can only ever return nothing. B4 already short-circuited the *outage* half of this; the change makes
  the two halves consistent.
- **The membership leg is dropped when its anchor resolves a supervised-provenance role.** See review
  finding 1. Recorded as a decision because it is a *behavioural* rule the design did not state, it is
  observable (a revoked-mid-request member sees only their supervised rows for that request rather than a
  stale membership page), and it consumes ADR 0031's stamp for a second purpose — which is a coupling a
  later slice should know about: **if the stamp is ever removed or renamed, this check silently becomes a
  no-op and the race reopens.** It fails safe in the sense that an unstamped role is treated as
  membership-derived, matching pre-slice behaviour exactly.
- **The audit event fires on "the supervised leg contributed ≥ 1 row to this page", not "the subject has
  a supervised leg".** The decomposition pins the former; the difference shows on a paged mixed list
  whose first page happens to hold only membership rows, which emits nothing. That case is pinned by a
  test so the rule is not re-derived as "one event per request with a non-empty supervised set".
- **The audit logger is obtained by name, not by class.** `LoggerFactory.getLogger("…audit.SupervisedRead")`
  rather than a class-derived logger, so the channel is stable, independently routable by a consumer, and
  assertable by name (I6) without coupling the name to a class's package.
- **No seam deviation to report.** Everything named was verified against the artifact before being built
  on — see the four measurements above, plus `Condition`/`Conjunction`/`PartialResult.conditional` (the
  IT's real DNF), `RoleDefinition`'s `attributes()` accessor and its defensive copying, and the shipped
  `CatalogEnrichable` verb registry `{view, update, delete, assign-tags}`, which I5 asserts **because the
  registry says so** (`mx-3446c4`: verified against the real endpoints, never assumed).

## Commit

`feat(supervised-scope): T5 the two-leg partitioned list, the read-only ceiling, and the audit event`
— on `feature/void3110/supervised-scope`.
