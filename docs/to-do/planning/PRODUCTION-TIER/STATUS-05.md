---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T5: catalog-service ITs: the four child endpoints' tier behavior below the rig

**Status:** ✅ DONE

## What shipped

- **One new IT, `ProductionTierEnrichmentIT`** (catalog service, Testcontainers Postgres — never H2; an
  in-process recording OPA stub — no WireMock), **9 cases covering I5–I8**. It proves the *seam* between
  what T4 decides and what T6 shows: that each of the four gated child endpoints ships the pinned ADR 0032
  input shape and honors the answer it gets back.
- **The stub OPA client is a miniature of the shipped corpus, not a programmable allow/deny.** It carries
  the same two provenance-scoped `denied` clauses T4 added (supervised + absent `root_attributes`,
  supervised + `env == "production"`) on top of the coarse token check — written as two clauses in Java for
  the same reason the Rego is, since `!"production".equals(root.get("env"))` would pass for an absent root
  and fail **open**. A suite that passed against a hand-waved allow-all stub would prove nothing about the
  tier.
- **The three states are asserted on the serialized bytes, not only on the object**: `{}` must appear as
  `"root_attributes":{}` (untagged, fetched → open) while absent must be a **missing key** — not `null`,
  not `{}`. That pairing is the whole contract, so both directions are pinned in the same suite.
- **Test scaffolding only — no production code changed** (the decomposition's expectation held; nothing to
  record as a deviation). Two test doubles: a `RecordingResourceResolver` that wraps the app's own resolver
  to count resolutions per type and make a type's fetch throw, and a header-driven subject extractor +
  role supplier giving the two populations (`sup-anna` → the widened synthesized supervisor role,
  `plain-member` → an ordinary membership role).

## Tests

`./gradlew build` (**all modules**, Testcontainers ITs) — **BUILD SUCCESSFUL**.
`opa test infra/opa/policies/` — **301/301**, the corpus untouched as this ticket requires.

| Case | Cases | Asserts |
|---|---|---|
| **I5** | 4 | both child GETs carry `root_attributes` equal to the **governing catalog's** tag map (`getCategory` with a one-link chain, `getProduct` two levels down — the ancestors asserted alongside, so "the root" is visibly the catalog and not the leaf's parent); an **untagged** catalog arrives as `{}` on the object **and** on the wire, and the supervised child read **opens**; a **production** catalog denies both child GETs with a **plain 403** (`ACCESS_DENIED`, and the body asserted to contain no `deny_reason` — that envelope field is slice C's); a **member** reads the same production children unchanged (200) |
| **I6** | 2 | the child LIST **coarse gate** (type-level, `resource.id == null`) carries `root_attributes` from the `roleResource` **override target**, for both `category:list` and `product:list`; the Compile/filter request that follows carries **none** — asserted on the object *and* on the raw JSON (invariant 4). And the decision visibly lands **at the gate**: on a production root both lists 403 and **partial evaluation is never reached at all** (zero compile calls) |
| **I7** | 2 | with a root fetch that **throws**: the recorded input has **no `root_attributes` key** (absent — not `{}`, not a serialized `null`), the supervised read is **403**, and the member's identical read is **200** — on the instance path *and* on the list gate. Both populations were still **asked**: an enrichment outage never becomes an exception out of the manager, never a 5xx |
| **I8** | 1 | a category PUT changing **both** content and tags asks two decisions (`category:update`, then `category:assign-tags`), and across them the governing root is resolved **exactly once** while the **decided leaf is resolved twice** (fresh per decision) — the memo's contract and the "no decision reads its own cached answer" rule, measured in one request. Both decisions carry the **same** root snapshot |

## Architecture review + refactor

Inline ★ review over the ticket's diff. T5 adds **no production code**, so the review's weight falls where
a test-only ticket can actually go wrong: an assertion that passes for the wrong reason.

- **Fail-closed.** The suite's job is to prove the floor, and it does so from both sides: the two closed
  states (absent, production) close, and the one open state (`{}`) opens. The direction is what a
  regression would flip, so `anUntaggedGoverningCatalogIsAnEmptyMapOnTheWireAndOpensTheChild` and
  `aThrowingRootFetchClosesTheSupervisedPathAndLeavesTheMemberUntouched` deliberately assert **opposite**
  outcomes on inputs that differ only in absent-vs-`{}` — reverting T3's null-preserving copy would turn
  the second into `{}` and break it. And the member half is asserted in the same test as the supervised
  half, so "narrows the supervisor, never touches the member" cannot silently become "narrows both".
- **Security — the widening that would matter for a test ticket is a vacuous cell.** Three guards were
  applied deliberately: (i) the tier assertions are on **exact tag maps and raw JSON**, not on
  `isNotNull()`; (ii) the stub reproduces the real deny shape rather than a boolean the test controls, so a
  policy-shaped mistake in the app's input would show up here; (iii) the compile-call assertions are
  **negative** (`isEmpty()` on a denied list) — proving where the decision landed, not merely that it was
  made.
- **Concurrency / idempotency.** I8 is the coherence cell: one root snapshot per request, and both
  decisions carrying it. The measured leaf count (2) is the other half of the same contract and is now
  asserted rather than left implicit.
- **Wiring.** Every case enters through the **real REST endpoint** via MockMvc, so the seam under test is
  the shipped one (controller annotation → manager → resolver → stub), never a hand-built context.
- **Boundary.** `git status` for this ticket is a **single untracked test file**: no production code, no
  library module, no policy file, no `scripts/postman/`. That is the ticket's own additivity proof.
- **Pattern reuse.** `ResourceResolutionGateIT`'s shape (Testcontainers singleton + `@DynamicPropertySource`
  + a `@TestConfiguration` of doubles + a capturing OPA stub) and `SupervisedListIT`'s header-driven
  persona idiom, reused rather than reinvented — including its reason for turning action enrichment off.
- **Refactor applied (one, from the static-analysis gate).** Sonar's **S125** flagged a two-line trailing
  `@Test // …;` comment as commented-out code — the trailing semicolon is what makes it read as a
  statement. Rewritten as a plain comment block above the annotation. A real fix, not a suppression.
- **Static-analysis gate** — `./.sonar-local/sonar-local.sh`: after that fix, **16 findings, all documented
  by-design FP classes** (Mulch `quality-gate-sonar` mx-302e78): S5778×8, S1186×5, S1168×2, S107×1 — every
  one carried from T1–T3 and untouched by this ticket. **No finding on this ticket's file.**

## Integration / e2e

This ticket **is** the integration proof — the nine cases above, against real Postgres through the real
endpoints. No e2e here: the rig matrix (E1–E8, the tier-flip liveness cell and the E6 flip) is **T6's**.

What T5 changes about the slice's state: after T4 the corpus closed supervised contents by **absence**,
with nothing exercising the population code in anger. T5 exercises it — the field now provably reaches OPA
with the right value on all four gated endpoints, and provably does **not** reach the residual. The rig is
the only thing left to prove.

## Decisions

- **Seam deviation — none in the app; one measured *runtime* behavior the QA cases implicitly assumed
  away.** A **denied** check reaches the OPA client **more than once**: `ResilientOpaClient.allow` treats
  the fail-closed sentinel `false` as retryable, so a genuine deny costs one extra sidecar hop before the
  guard's budget is spent (B3 / ADR 0017's deliberate posture — the sentinel is retried but, since the
  review fix, never recorded on the breaker). A first draft of these cells asserted "exactly one decision
  per action" and failed on the denies. **Measured, not guessed**: identical stack traces through
  `Resilience4jCallGuard.call`, the same `DispatcherType=REQUEST` and the same request object, and — the
  decisive datum — **zero** additional resolver calls between the two attempts, which places the repeat at
  the client wrapper and nowhere else. The suite now asserts something stronger and honest instead: every
  attempt at one decision carried **byte-identical input**. Recorded here because a future IT that counts
  OPA calls on a deny will hit exactly this.
- **The stub reproduces the tier clauses rather than being programmable.** A programmable
  `Predicate<AbacContext>` (the `ResourceResolutionGateIT` idiom) would have let each test assert its own
  expected outcome, which is precisely the vacuity risk here — the tier's whole content is *which* input
  states close. Reproducing the two clauses makes the endpoint's input shape load-bearing for the result.
- **I8 uses the category PUT with both deltas** because it is the only single request that asks **two**
  manager decisions on the same governing root without the rig (the delta-aware `TagDecisionGate`
  dispatch). It needs a `TagDefinitionClient` stub, since T2 made a tags-carrying write consult the
  dictionary — the same stub `ResourceResolutionGateIT` gained for the same reason.
- **Documentation delta: none, as the decomposition specified.** T3's *Root-attribute enrichment* section
  in `docs/guides/ABAC-AUTHORIZATION.md` already documents the mechanism these ITs verify, and T4's
  *Production tier* subsection documents the decision; this ticket adds proof, not mechanism. Nothing in
  the guides is falsified by it.

## Commit

`feat(production-tier): prove the child endpoints' tier input shapes below the rig (T5)` — one IT with
nine cases (I5–I8) and its test doubles, on `feature/void3110/production-tier`.
