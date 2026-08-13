---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T3: library (additive): `Resource.root_attributes` + manager-side governing-target enrichment

**Status:** ✅ DONE

## What shipped

- **`opa-abac-core` — `AbacContext.Resource` gains a fifth component**
  `@JsonInclude(NON_NULL) @JsonProperty("root_attributes") Map<String, Object> rootAttributes`, plus
  **two compat constructors** (3-arg and 4-arg) so every existing caller compiles and serializes
  byte-for-byte as before. The compact constructor's defensive copy is **null-preserving for this one
  component** — `rootAttributes == null ? null : Map.copyOf(...)` — deliberately unlike `attributes` and
  `ancestors`, whose null → empty normalization would turn an enrichment failure into a confident "the
  root has no tags" and open the tier. The record's javadoc carries the three states, the
  NON_NULL-not-NON_EMPTY rationale, and the naive-negation trap.
- **`opa-abac-spring-security` — the manager populates it**, `enrichWithRootAttributes` +
  `resolveRootAttributes`, applied to the resolved check **once, after the role-resource override has
  been applied** (see *Decisions*): when the governing target is distinct from the decided leaf, resolve
  it through the app's existing `AbacResourceResolver` and thread its `abacAttributes()` into the
  `Resource`. The resolve is **read-through-memoized** in `RequestAttributesResourceCache`
  (get-before-resolve, put-after), so a request pays at most one extra resolver call and every check in
  it sees one coherent root snapshot.
- **Absent on every failure** — no resolution support, a type-level check with no override, a leaf that
  *is* its own governing root, a resolver returning empty, a resolver **throwing**, or a target reporting
  null attributes. Nothing propagates: `RuntimeException` is caught and logged at debug.
- **The cache-contract amendment is documented where it lives**: the manager's class javadoc gains a
  *Root-attribute enrichment* section stating that the root memo's `put` is **decision-independent**, so
  an entry is a *resolved* snapshot and no longer necessarily an *authorized* one — and the `:130`
  write-through comment is rewritten to say precisely what still holds (the **decided leaf** is always
  resolved fresh and never read back, so no decision can read its own cached answer).
- **Guide delta** — the *Root-attribute enrichment* section in `docs/guides/ABAC-AUTHORIZATION.md`: the
  mechanism, the three-state table, the NON_NULL rationale, the failure-is-narrow rule, the Rego
  naive-negation trap with the two-clause reference shape, the nothing-enters-`filter` note, and the
  cache consequence.

## Tests

`./gradlew build` (**all modules**) — **BUILD SUCCESSFUL**. `opa test infra/opa/policies/` — **276/276**,
the corpus untouched as this ticket requires.

| Case | Where | Count | Asserts |
|---|---|---|---|
| **U12** | `AbacContextRootAttributesTest` (core) | 8 | the 3-arg and 4-arg constructors serialize **byte-identically** (exact strings, and no `root_attributes` key); both leave the field null; absent is omitted; `{}` **survives to the wire**; a tagged map serializes; the copy is null-preserving yet still defensive; the field appears under `resource` in a whole context |
| **U13** | `OpaPreAuthorizeRootAttributeEnrichmentTest` (7 of 11) | | root distinct from leaf → the root's attributes; untagged root → **`{}`, not absent**; leaf-is-root → absent (and only one resolve); resolver empty → absent; resolver **throws** → absent, **no exception escapes, and the decision still grants** (the member half); null attributes → absent, not `{}`; no resolution support → absent and the resolver is never engaged |
| **U14** | same (3) | | a resolvable override threads the **override target's** attributes into a still-type-level input (`resource.id == null`); no override → absent + resolver never engaged; **declared-but-unresolvable override still denies before enrichment is attempted** — OPA never asked, the resolver never called (the pre-existing fail-closed branch, asserted not regressed) |
| **U15** | same (1) | | gate + instance in one request → **exactly one** root resolve, and **both** contexts carry the same root map (one coherent snapshot) |

**The additivity proof, mechanically:** `git status --porcelain` for this ticket shows exactly **two
modified files** (`AbacContext.java`, `OpaPreAuthorizeAuthorizationManager.java`) and **two added test
files** — **zero existing tests modified, in any module**, with the whole-repo build green. `AbacResource`,
`AbacResourceResolver`, `ParentRef`, both ancestor resolvers and all of `opa-abac-spring-data` are
byte-for-byte untouched.

## Architecture review + refactor

Inline ★ review over the ticket's diff. This is a **headline ticket** (the input contract every adopter
sees; the additivity proof is the whole risk), so the checks below were run against source rather than
recollection.

- **Fail-closed — the failure-to-absent discipline, enumerated.** Five ways enrichment can fail and one
  way it can be skipped; all six land on `null` (absent), each with a test. The direction is what
  matters: absent means *unproven* (the supervised path closes), while `{}` means *untagged* (it opens),
  so **every** uncertain case must fall on absent. The null-preserving copy is the structural half of
  this; the `catch (RuntimeException)` returning `null` is the behavioral half. And the member half is
  asserted explicitly — `rootResolverThrows_absentAndNoExceptionEscapes` checks the decision is still
  **granted**, so a tag-lookup failure never becomes a member-facing outage or a 5xx.
- **Security — the widenings that would matter.**
  1. *A `{}`-on-failure silently opening the tier* — prevented by the null-preserving copy plus the six
     absent paths; `anUntaggedRootIsAnEmptyMapNotAbsent` and `aRootReportingNullAttributes_absentNotEmpty`
     pin both sides of the distinction.
  2. *A `root_attributes` predicate reaching the SQL residual* (invariant 4) — **structurally impossible
     from this change**: `opa-abac-spring-data` is untouched, and `AbacQueryService`'s filter input is
     built with the 4-arg constructor, so the field is `null` and omitted. Verified by diff, not by
     intention.
  3. *The memo becoming a decision input* — the decided leaf is **never** read from the cache
     (`resolveInstance` calls the resolver directly, unchanged), so a decision can never read its own
     cached answer. The new read is for the governing **root** only, and the entry it reads was written
     in the same request by the manager itself; `RequestAttributesResourceCache` is request-scoped, so
     nothing crosses requests.
  4. *Enrichment turning a deny into an allow* — the unresolvable-override branch denies **before**
     enrichment is reachable (enrichment takes a `null` check and returns it unchanged);
     `declaredButUnresolvableOverride_deniesBeforeEnrichmentIsAttempted` asserts OPA is never called.
- **Concurrency / idempotency** — a root-tag change mid-request cannot produce a mixed gate/instance
  view: the memo pins one snapshot for the request, asserted in U15 by checking **both** captured
  contexts carry the same map. Enrichment itself is a pure read; nothing is written outside the memo.
- **Wiring** — the new component's consumers are the manager (this ticket) and T4's `denied` clauses
  (next); the enrichment's non-happy paths are six of the eleven manager tests. Zero call sites would
  have meant the ticket was not done — instead the field reaches OPA on both gated paths.
- **Boundary / additivity** — `opa-abac-core` stays Spring-free (the change is pure Jackson/Java; no
  import added — `@JsonProperty` was already in use). The two-modified-files diff is the boundary proof.
- **Module-layer separation** — core *carries* the field, the manager *populates* it from the target it
  already computes, the app's resolver *fetches*, policy *decides*. No new SPI, no new seam — ADR 0032's
  rejected alternatives stay rejected.
- **Pattern reuse** — the `ancestors` record-evolution pattern (compat constructors + `@JsonInclude`, the
  mx-9c901e shape) and the `RequestAttributesResourceCache` get-before-resolve/put-after idiom, both
  reused rather than reinvented.
- **Refactor applied (two).** (1) The enrichment was consolidated to **one** call site instead of the
  two the decomposition sketched — see *Decisions*; the two-site version left the instance-path-plus-
  override case ambiguous, and one site makes "the enriched attributes always describe the resource the
  role was resolved on" true by construction. (2) The null-attributes case was made **explicit** in
  `resolveRootAttributes`'s contract rather than left to emerge from the record's constructor: it is a
  fail-closed edge in the slice's riskiest ticket, and a reader should not have to infer its direction.
  A test was added for it.
- **Static-analysis gate** — `./.sonar-local/sonar-local.sh`: **16 findings, all documented by-design FP
  classes** (Mulch `quality-gate-sonar` **mx-302e78**): S5778×8 (T2's, the assertion-lambda class),
  S1186×5 (the test `SampleController`'s empty annotation-carrier methods — the FP text names this exact
  fixture shape in this exact module), S1168×2 (`resolveRootAttributes` returning `null` as a deliberate
  tri-state sentinel the caller branches on with `== null` — **and here "return an empty map instead" is
  precisely the fail-open the slice exists to prevent**, so the FP classification is load-bearing rather
  than cosmetic), S107×1 (T1's entity constructor). **Two findings were real and were fixed in this
  ticket, not filed**: S5853 (unchained AssertJ assertions with no explanatory comment → chained) and
  S6068 (useless `eq(...)` matchers in a `verify` with no other matcher → plain values).

## Integration / e2e

None in this ticket by design — T5 (part 1) proves the end-to-end input shape against the real app, and
T6 proves it through the rig. What this ticket owes is the **unit-level contract plus the additivity
proof**, and both are above. The whole-repo `./gradlew build` (every module's ITs, Testcontainers
included) is the integration-level signal that the record change broke nothing.

## Decisions

- **Seam deviation — none; every seam matched.** `AbacContext.Resource` at the quoted location with the
  four components; the manager's `resolveInstance`, `withRoleResourceOverride`, and type-level branch
  exactly as described; `RequestAttributesResourceCache`'s get/put shape; the write-through at `:129-134`;
  all eleven `Resource` construction sites across the repo compiling unchanged through the compat
  constructors.
- **Implementation consolidation: one enrichment point, not two.** The decomposition sketched insertion
  in `resolveInstance` *and* in the type-level/override branch. Both compute the same thing — the
  `(type, id)` the role is looked up on — so the enrichment is applied **once, in `check()`, to the
  fully-resolved check** (after the override has been applied). This is not a behavior change against the
  sketch for any case the decomposition enumerated; it *resolves* a case the sketch left ambiguous — an
  instance check that **also** declares a `roleResource` override, where two insertion points would have
  raced to decide whether `root_attributes` describes the ancestor root or the override target. With one
  point the answer is always "the target the role was resolved on", which is the only reading a policy
  author can rely on. ADR 0032's own wording ("one rule, both paths") is what this implements.
- **The null-attributes case is absent, not `{}`.** An `AbacResource` whose `abacAttributes()` returns
  null is treated as *unproven*, not *untagged*. No shipped entity does this (they return
  `getTags().asMap()`), but the direction is the fail-closed one and is now stated in the method contract
  and pinned by a test.
- **The cache-contract amendment was taken as pinned, not re-litigated.** A namespaced memo key would
  have preserved the old "entries are authorized snapshots" wording, but the decomposition explicitly
  decided the shared cache plus a documented amendment, and `AbacResourceCache.get` has no namespace
  parameter to use honestly. Both required doc updates landed in this commit.

## Commit

`feat(production-tier): add Resource.root_attributes and manager-side enrichment (T3)` — the core record
component + compat constructors, the manager's enrichment and memoized root resolve, the two
cache-contract doc seams, the ABAC guide section, and 19 new cases, on
`feature/void3110/production-tier`.
