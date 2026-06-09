---
tags:
  - status/done
  - type/review
  - area/abac
  - area/spring-security
  - area/spring-data
  - area/docs
---

# Full-repo review (2026-06-10) — multi-agent deep review + remediation

> **Verdict**: **Strong foundation, approved after remediation.** The fail-closed posture is genuinely
> engineered (23 error paths traced; 19 closed by construction), the partial-eval translator handles the
> classic widening traps correctly, and docs are accurate at the identifier/config-key level. The review
> found **no Critical leak in the shipped decision path**, but four Medium fail-open *seams*, one
> clean-room slip in a committed doc, and a front-door (README) two slices stale. All four seams + the
> doc issues are **fixed in this remediation** (same branch); the larger items are dispositioned below.
>
> **Scope**: the **whole repo** (all 4 library modules, both example services, Rego corpus, infra,
> docs/ADRs/guides), not a slice diff. Run as a 4-lens multi-agent fan-out (core+starter ·
> security+data, adversarial fail-closed audit · examples+Rego+infra · docs accuracy), findings
> spot-verified in source before acceptance.
> **Branch**: `fix/void3110/full-repo-review-remediation` vs `main` (post-PR #23 / Phase 5.9).

## Summary

This was the first **whole-repo** review (prior reviews were per-slice). The four lenses confirmed the
load-bearing invariants hold across slices: `opa-abac-core` is verifiably Spring-free; unsupported
residual nodes poison the whole residual (never skip a conjunct); the empty-Compile-result ambiguity
maps to DENY_ALL; the `member_2` operand-side subtlety is handled; no SQL value is ever concatenated;
the `scope.and(tagResidual.or(subtreeSpec)).and(notDenied)` composition is placed correctly. The
findings cluster at the *seams* — places where an error or misconfiguration silently **widened** a
decision instead of denying — and in documentation staleness concentrated in the three files a
stranger reads first.

## Findings & dispositions

Severity · finding · disposition. **Fixed** = in this branch; **Deferred** = tracked follow-up below;
**Accepted** = deliberate, now documented.

### Critical / clean-room

1. **Clean-room slip in a committed doc** — `docs/to-do/implemented/LIBRARY-SPINE/10-QA-TEST-CASES.md`
   case X3 enumerated the literal proprietary scan strings (employer name, internal project names, a
   token prefix) in the public repo. **Fixed twice over**: the tip was reworded to a neutral
   description (the concrete scan list is maintainer-local), and the **git history was rewritten**
   (2026-06-10, `git filter-repo --replace-text`, all 242 commits) so the strings appear nowhere in
   any reachable blob — historic versions of the line read `[redacted]` placeholders (the offending
   commit is now `2d7b0a9`). A pre-rewrite backup bundle is kept maintainer-local. Note: GitHub may
   serve old, now-unreachable commit SHAs from cache until its garbage collection runs; support can
   purge sooner if ever needed.

### Medium — fail-open seams (all fixed)

2. **Interface-annotated methods dodged enforcement.** The `@OpaPreAuthorize` pointcut used
   `forMethodAnnotation` (no inherited lookup), so the annotation on an *interface* method was not
   matched under class-based (CGLIB) proxies — the method ran with no enforcement and no error.
   **Fixed**: `AnnotationMatchingPointcut(null, OpaPreAuthorize.class, checkInherited=true)` (same
   posture as Spring Security's own `@PreAuthorize`), dead class-level pointcut half + class-level
   annotation fallback removed (the annotation is METHOD-only by design). Regression test: interface
   annotation + CGLIB proxy → enforced (deny and allow paths).

3. **A declared `resourceId` that evaluated to `null` silently widened to a type-level check** (typo'd
   `#param`, or parameter names unavailable) — skipping per-id deny rules and per-resource role scoping.
   **Fixed**: declared-but-null/blank id → deny, same posture as the unresolvable `resource()` branch.
   Test: `declaredResourceIdEvaluatesNull_deny` (OPA never even consulted).

4. **The subtree widening could outlive an OPA outage.** A Compile *transport failure* and a
   policy-unsatisfiable answer both arrived as the same `DENY_ALL`, and the Java-built `subtreeSpec`
   was OR-ed in regardless — so hierarchy-inherited rows stayed listable while OPA was down.
   **Fixed**: `PartialResult` gained a `fromError` flag (`PartialResult.error()`); `HttpOpaClient`
   returns it on every failed Compile call; `AbacQueryService` fails the **whole list** closed on it
   (no repo query, no batch, no widening). A *policy* DENY_ALL still composes with the subtree
   widening — that's a real policy answer (test updated to pin the distinction).
   *Residual risk (Accepted + documented)*: the Java `SubtreeSpecResolver` gate mirrors the Rego
   `inherited_grant` clause; if the policy's inherited-grant ever gains conditions beyond
   role-grants-verb + inheritable + deny, the SQL path can drift wider than the policy. Documented in
   the class javadoc contract; revisit if inherited-grant conditions grow.

5. **The kill-switch dropped the deny-tag filter.** `partial-eval.enabled=false` returned all scoped
   rows — making `abac_deny == true` rows listable that a single-GET 403s. **Fixed**: `notDenied()` is
   AND-ed on the kill-switch path too.

6. **Unverified-JWT trust was documented but not guarded.** The default `JwtClaimsSubjectExtractor`
   does no signature verification (gateway-trust by design); structurally, any 3-segment blob with a
   `sub` became an authenticated subject — a total auth bypass if deployed gateway-less. **Fixed**:
   the extractor is now gated on an explicit `opa.abac.subject.trust-forwarded-jwt=true`
   acknowledgment; without it a refusing extractor is wired (every request anonymous → deny,
   fail-closed) and a startup warning explains exactly what to do. The dead `verify-signature` no-op
   property is removed. Both examples opt in via yml with a "never enable gateway-less" comment.

### Medium — correctness/robustness (fixed)

7. **`InterruptedException` swallowed** in all three `HttpOpaClient` decision methods — the interrupt
   flag was lost on shutdown/cancellation. **Fixed**: separate catch, `Thread.currentThread().interrupt()`,
   deny.
8. **Mixed-resource-type `allowAll` batch evaluated everything against the first item's policy.**
   **Fixed**: same-type precondition documented on the `OpaClient` interface and enforced fail-closed
   (mixed batch → all-false, no HTTP call). Test added.
9. **Manager abstained (`null`) when the annotation wasn't found** — a latent proceed-unenforced seam
   if pointcut and lookup ever diverged. **Fixed**: deny + warn (the manager is bound to an
   `@OpaPreAuthorize` pointcut; "matched but not found" is a wiring inconsistency, not a valid state).
10. **The OPA wire-protocol `ObjectMapper` was borrowed from the application** — app-level Jackson
    customizations (naming strategies, inclusion rules) would silently change the serialized `input`
    the policies match on; two non-primary mappers would crash wiring. **Fixed**: the starter builds a
    private mapper for the OPA client and the JWT extractor; Boot's Jackson auto-config untouched.
11. **Diagnosing "why is everything denied" was needlessly hard** — warn logs carried only the
    exception class name. **Fixed**: warn now includes the message (`{e}`), full stack at DEBUG;
    still never the token.

### Docs / CI (fixed)

12. **Root README was the stalest doc in the repo** — phantom `example/` tree, user-service
    "introduced later", "Next" two shipped slices behind, and a **standalone quick start that 401s**
    (the example became secured-by-default in Phase 3 but the README still promised bare CRUD).
    **Fixed**: tree/diagram/shipped/next rewritten; the quick start now honestly says standalone =
    Swagger browsing, the rig = the real path.
13. **`opa test` never ran in CI** — the 91 (now 95) Rego tests are the authz proof; a policy
    regression merged green. **Fixed**: new `opa-policy-tests` CI job (pinned `opa:1.10.1` via
    docker), plus a **drift check** for finding 14.
14. **Two diverged copies of `team.rego`** (infra runtime vs user-service resources): the infra copy
    lacked the `define-tags` capability comments and 6 tests. **Fixed**: synced from the canonical
    user-service copy (81/81 + 14/14 green); CI now fails on any future drift.
15. **Stale statuses & links**: 4 shipped ADRs said "(planned)" (flipped to implemented, slice-linked);
    postman README's status paragraph denied the matrices listed above it (rewritten); POC-ROADMAP's
    broken `infra/README.md` link (fixed, `../` count) and the "rename not yet executed" contradiction
    (fixed); the "Current state" section is now explicitly an append-style chronological log with
    superseded markers; AUTONOMOUS-IMPLEMENTATION-FLOW's shipped-slice list (+3 slices);
    E2E-TESTING's runner list (+`run-team-matrix.sh`, +`run-hierarchy-matrix.sh`, + the 5.9
    problem+json note); `docs/api/` is no longer an empty promise (points at the OpenAPI specs);
    the gitignored mulch-skill link in code-review/README de-linked.

### Deferred (tracked follow-ups, in rough priority order)

- **Maven Central publishing scaffolding** — entirely absent (no `maven-publish`/signing/sources/javadoc
  jars; the `POM_*` keys in `gradle.properties` are dead config that looks written for the vanniktech
  plugin). Slice-sized; deserves its own plan (plugin choice, Central Portal vs OSSRH, release flow).
- **Starter dependency-scope restructure** — the starter's `@ConditionalOnClass` back-off can't fire
  because `opa-abac-spring-security` declares web/security as `implementation` and the starter
  `api`-depends on it. Real design fork (a starter conventionally *does* bring its stack) → ADR
  material. The misleading build-script comment is fixed to state reality honestly.
- **Absorb the `AbacFilter` double-registration workaround into the starter** — both example
  `SecurityConfig`s hand-copy it (two different ways); it's the main adopter boilerplate.
- **Standalone demo profile** — a true bare-CRUD `bootRun` profile would need profile-gating the
  example's authorizer beans (they constructor-inject starter beans); nice-to-have for first-touch UX.
- **Properties validation** — `@Validated` + an enum-typed `hierarchy.resolver` (a typo'd resolver
  silently wires nothing today), positive `maxDepth`, non-blank `baseUrl`.
- **Smaller library items**: split connect-vs-request timeout; `HttpOpaClient` as `AutoCloseable`;
  `baseUrl` validation at startup; node-count bound in `RecursiveCteAncestorResolver.collectSubtreeIds`;
  `AbacAuthentication` transient-subject serialization; ltree charset validation for *types* at encode
  time; document the negated-eq narrower-than-Rego translation; 401-vs-403 contract note on
  `AbstractProblemAdvice`; URI normalization in the request-level `OpaAuthorizationManager`.
- **Docs**: wikilinks render dead on GitHub (~85 of them) — decide dual-linking vs a rendered-docs
  pointer; TAG-SYSTEM vocabulary drift (`audience/developer`, `area/api`, `area/catalog` vs
  `area/catalog-service`); "the source platform" comparative phrasing in two guides reads
  inside-baseball for OSS visitors.
- **`/internal/**` bootstrap API in the user-service is permitAll and host-published** (port 28090) —
  acceptable as in-network test scaffolding for a local demo, but gate it behind a profile/flag before
  anyone treats the rig as more than a demo.

## What's done right (unchanged by this review)

- **Fail-closed engineering, not fail-closed marketing**: deny-on-error at every decision shape,
  unsupported-poisons-residual, empty-Compile→DENY_ALL, throw-don't-truncate hierarchy walks, fully
  bound SQL, and the composition invariants (AND-never-replace; widening inside scope; deny outside
  the OR) — all pinned by tests, most now *more* tightly after this remediation.
- **The Rego corpus** — explicit default deny everywhere, the no-fallback `filter` entrypoint,
  malformed-input deny, filter↔allow consistency tests; `category.rego` is a teaching-grade policy.
- **Process artifacts are real**: ADRs with honest rejected-alternatives, per-slice planning packages
  with verbatim prompts, e2e matrices that assert the *cut* (exact row sets), and guides accurate to
  the code at the identifier level (~25 spot-checks, zero misses).

## Test results

- Library modules (`:opa-abac-core:test`, `:opa-abac-spring-security:test`, `:opa-abac-spring-data:test`,
  `:opa-abac-spring-boot-starter:test`): **green** after the seam fixes, including the new regression
  tests (interface-annotation enforcement under CGLIB, declared-null-id deny, error-residual
  suppresses subtree + batch, mixed-type batch rejection, `fromError` pinning, trust-gate wiring).
- `opa test infra/opa/policies`: **81/81**; user-service copy: **14/14** (post-sync).
- `./gradlew build` (all modules + both examples + Testcontainers ITs against real Postgres):
  **green** (see the remediation branch CI run).

## Review-method note (for the AI-assisted-engineering record)

The find phase was a 4-lens parallel fan-out (one reviewer per surface pair), with the synthesis pass
spot-verifying every High/Medium finding in source before acceptance — the same
fan-out → verify → synthesize shape as `/deep-review`, applied repo-wide. The highest-value lens was
the **adversarial fail-closed audit** (instructed to enumerate error paths and try to find an open
one): all four Medium seams came from it. Worth keeping as a periodic whole-repo complement to the
per-slice reviews; the per-slice gates had each seam *locally* invisible (each lives at a boundary
between slices).
