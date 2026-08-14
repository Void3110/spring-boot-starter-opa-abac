---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T4: adoption + wiring: the manager, the 401 emitter, audit, and the agent guards

**Status:** ✅ DONE

## What shipped

**`opa-abac-spring-security`:**

- **`StepUpRequiredDecision extends AuthorizationDecision`** — `granted` fixed `false` by the
  constructor, carrying the `DenyReason` plus three **log-only** fields (resource type + id, governing
  root id) the enforcement point cannot re-derive. Nothing reads them back into a decision; the class
  javadoc says so, because a field a denied decision supplies is not an authorization input.
- **`OpaPreAuthorizeAuthorizationManager`** now calls **`decide()`** on its single-decision path. An
  `OpaDecision` carrying a reason becomes a `StepUpRequiredDecision`; every other outcome returns
  today's `AuthorizationDecision` unchanged. A `null` from a client breaking the never-null contract
  **denies explicitly** rather than by NPE-into-the-catch. The bulk/batch and enrichment paths are
  untouched.
- **`AbstractProblemAdvice`** grows a 401 branch on the *existing* handler: an
  `AuthorizationDeniedException` whose result is a `StepUpRequiredDecision` with a **complete** reason
  maps to `401` + `WWW-Authenticate: Bearer error="…", error_description="…", acr_values="…",
  max_age="…"` + a problem body carrying the new **`LibraryErrorCode.STEP_UP_REQUIRED`** (401).
  Everything else keeps the byte-identical 403.
- **`AbacAuditLogger`** — the dedicated `opa.abac.audit` channel (named, not class-derived, so a
  consumer routes it separately). `STEP_UP_CHALLENGED` at 401-mint (no `acr`/`auth_time` — the subject
  is precisely *not* elevated); `SUPERVISED_PRODUCTION_READ` on an allowed supervised read of a
  production root, with `acr`/`auth_time` **verbatim**. Both emit paths catch and drop their own
  exceptions.

**`example-catalog-management-service`:**

- `application.yml`: `opa.abac.subject.attribute-claims: [acr, auth_time, act_chain]`, commented with
  *why* each one is there and why `auth_time` must stay numeric.
- `CatalogListAuthorizer`: the **supervised-leg agent guard** — when the subject's attributes carry the
  `act_chain` **key**, the supervised leg is skipped, reusing the existing supervised-source-degrade
  shape (agents fall back to membership-only; for a pure supervisor, the empty page).
- `catalog-api.yaml`: `STEP_UP_REQUIRED` added to the closed `errorCode` enum.

**Doc delta** — `docs/guides/TEAM-BASED-AUTHORIZATION.md` gains the *Step-up elevation* subsection: the
five-step round trip, why freshness rather than token lifetime, the sole-blocker table, the
elevation-proof unproven tier, the three-prong human-only closure, the two audit events, the
fail-closed table, and the one-window cross-reference. (T6 appends only its e2e paragraph.)

## Tests

`./gradlew build` green (all modules, Testcontainers ITs); `opa test infra/opa/policies/` **367/367**
(untouched by this ticket); local Sonar **CLEAN** on changed files.

| Case | Where | What it pins |
|---|---|---|
| **U16** | `StepUpDecisionAndChallengeTest` | a reason → `StepUpRequiredDecision` (denied, reason + all three log-only fields); a plain deny → today's shape, **not** the subclass; an allow → granted; a `null` decision → an explicit deny |
| **U17** | same | a complete reason → **401** + the exact challenge string + `STEP_UP_REQUIRED`; the parameters track the **reason** (a 60-second window renders `max_age="60"`); a plain denial → the pre-C **403** + `ACCESS_DENIED` + **no** header, from both `AuthorizationDeniedException` and a bare `AccessDeniedException` |
| **U18** | same | each of the three partial reasons → the plain 403, no header |
| — | same | a reason whose parameters cannot be safely quoted (embedded `"`, a spliced `scope=`, a CRLF) → the plain 403 — see the review note |
| **U19** | same | `SUPERVISED_PRODUCTION_READ` with the ADR 0030 §8 field list, including an **array-shaped** `env`; **not** emitted for a member, a staging tier, or a denied read; `STEP_UP_CHALLENGED` with the challenge fields and **no** `authTime`; not emitted for a plain 403 or a partial reason; and emission swallowing its own exception |
| **U20** | `StepUpClaimsIngestionTest` | with the shipped claim list: `acr` a String, `auth_time` a **Number**, `act_chain`'s array value preserved; every falsy shape (`false`, `[]`, `""`) still arrives as a **key**; absent claims stay absent; the ROPC shape (acr, no `auth_time`) |
| **I1** | `StepUpChallengeIT` | over MockMvc + Testcontainers: a stub reason becomes 401 + the header + the body code + `application/problem+json`; the parameters echo the **stub's** reason |
| **I2** | same | no reason → 403 `ACCESS_DENIED`, no header; a **partial** reason → the same 403 (the §7 guard over real HTTP) |
| **I4** | same | both audit events off `opa.abac.audit` via a log captor, and the decision input actually carries `acr` + numeric `auth_time` + the root's `env` |
| **I3** | `AgentSupervisedLegIT` | a human supervisor sees the supervised row; the **same subject** with the claim sees the empty page; `false`/`[]`/`""`/`0`/`null` all close the leg; a **member's** agent call is unchanged |

## Architecture review + refactor

Path: **inline self-review** (the ★ gate).

- **Fail-closed.** Five classes, each landing where it arises and each with a test: a null decision
  (explicit deny), a plain deny (unchanged 403), a partial reason (403, no challenge), an unquotable
  parameter (403), and audit failure (nothing). Nothing on any path can widen: the only new *allow* in
  this ticket is the one OPA already returned.
- **The widenings named for this ticket, and why they cannot happen.** (a) *A challenge for a write or
  an agent* — the manager only ever forwards a reason OPA emitted, and T2's sole-blocker rule is what
  decides that; the advice adds no policy of its own. (b) *A half-formed challenge* — `isComplete()` is
  checked before the header is built, and all three partial shapes are tested. (c) *The `act_chain`
  presence-test regressing to truthiness* — the guard is `containsKey`, with five falsy shapes asserted
  through the real endpoint. (d) *The supervised list leg reachable by an agent* — I3's control cell
  proves the leg is there without the claim, so the empty page is the guard's doing and not the
  fixture's.
- **Refactor applied — one, and it is a security fix the review surfaced rather than a tidy-up.** The
  challenge parameters (`error`, `acr_values`) originate in **policy data**, and the first draft
  interpolated them straight into a quoted header string. A value carrying a `"` — or worse a CRLF —
  would break out of the quoted-string and, in the worst case, out of the header: a data document
  turning a deny into a header-injection primitive. `challengeFor` now runs both values through an
  unreserved-character allowlist and falls back to the plain 403 when either fails, with three hostile
  shapes tested. Trusted-but-not-blindly is the right posture for a value that reaches a response
  header.
- **Wiring.** Every seam has a named consumer and a non-happy-path test: `decide()` → the manager (U16);
  `StepUpRequiredDecision` → the advice (U17/U18) and the challenge audit (U19); `STEP_UP_REQUIRED` →
  the body assertion **and** the OpenAPI enum; the audit logger → four emission cells plus the swallow;
  the leg guard → I3's four cells; the yaml claim list → U20 and I4's input assertion.
- **Boundary.** `opa-abac-core` untouched by this ticket. The request-pattern `OpaAuthorizationManager`,
  `opa-abac-spring-data`, `HierarchicalAuthorizer`, `agent_tools.rego`, the SPA, and every
  `filter`/`bulk` entrypoint are unchanged — `git status` shows no file from any of them.
- **Pattern reuse.** The advice's existing `@ExceptionHandler` grew a branch rather than gaining a
  sibling handler or a filter; the audit logger mirrors the *dedicated named logger* shape
  `CatalogListAuthorizer` already uses for supervised list reads; the leg guard reuses the
  supervised-source-outage degrade rather than inventing a second one; the `env` normalization mirrors
  the policy's `root_env_values` cardinality handling.
- **Static analysis.** 14 findings on the first run, all triaged: 12 fixed here (an unused import, an
  `S5838` assertion form, four `S5853` assertion chains, six `S1186` empty pointcut-target methods —
  the last resolved by documenting the class rather than adding bodies). The remaining **two `S1168`**
  ("return an empty map instead of null") are on **pre-existing** lines of `resolveRootAttributes` and
  are a **by-design false positive**: an empty map is ADR 0032's *fetched-and-untagged* state, which
  **opens** the tier, while `null` is *unproven*, which closes it. Taking Sonar's advice would turn
  every enrichment outage into "the root is untagged". Marked false-positive in the local Sonar with
  that rationale and recorded in the Mulch `quality-gate-sonar` domain. Gate: **CLEAN**.

## Integration / e2e

**I1–I4 green** (`./gradlew build`, Testcontainers Postgres — never H2, in-process stubs — no WireMock):

- **I1/I2** — `StepUpChallengeIT`, 4 cells: the 401 + exact `WWW-Authenticate` + `STEP_UP_REQUIRED`
  body; the parameters tracking a different reason; the plain 403 contrast; the partial-reason 403.
- **I4** — 3 cells: `STEP_UP_CHALLENGED` on the challenge flow (asserting the *absence* of `authTime`),
  `SUPERVISED_PRODUCTION_READ` on the elevated read, and the decision input carrying `acr` + **numeric**
  `auth_time` + the governing root's `env`.
- **I3** — `AgentSupervisedLegIT`, 4 cells, against the real `SupervisedScopeClient` and an in-process
  stub user-service.

**One near-miss worth recording:** I3's "the agent sees the empty page" cell passed on its first run for
the wrong reason — the list endpoint's `page` parameter is **0-based**, and the suite asked for page 1,
which is empty whatever the cut did. The control cell (a human supervisor *does* see the row) is what
exposed it; both were re-run against page 0. A vacuous green in the exact cell that proves a closure is
the failure mode this repo's e2e conventions already warn about, and it arrived here through
pagination rather than through an assertion.

## Decisions

- **Seam deviation — adopting `decide()` breaks mock-based tests, and "every pre-existing test
  unmodified-green" cannot survive it.** Mockito mocks an interface's `default` methods like any other,
  so `mock(OpaClient.class)` returns `null` from `decide()`: **19 pre-existing tests across 4 files**
  failed the moment the manager switched. Three resolutions were considered and two rejected:
  (a) *fall back to `allow()` when `decide()` returns null* — rejected, it is production code shaped by
  a test artifact, it doubles the round-trip on a contract violation, and it hides a broken client;
  (b) *keep calling `allow()`* — rejected, no reason would ever reach the advice and the slice would not
  exist; (c) **restub the four affected test files** (`when(opaClient.decide(any()))
  .thenReturn(OpaDecision.of(…))`, `verify(...).decide(...)`) — taken. The change is mechanical, alters
  no assertion, and leaves those tests asserting the call the manager **actually makes**, which is more
  accurate than before. The planning claim is precisely *"no behaviour changed and no assertion
  changed"*; **an interface-method adoption always costs its mocks a restub**, and the decomposition
  should say so next time.
- **`STEP_UP_REQUIRED` is declared in the catalog spec only.** Per the recorded closed-enum discipline
  the sibling spec was checked (`user-mgmt-api.yaml`): the code is **not reachable** there — only
  `category.rego`/`product.rego` emit `deny_reason`, and the user-service queries `team`/`role`
  policies. Declaring an unemittable code in a closed "union of codes this service can emit" enum would
  document a falsehood. **The coupling to watch:** if a later slice teaches any user-service-facing
  policy to emit `deny_reason`, that spec must gain the code in the same commit.
- **The challenge's `error_description` and the problem body's `detail` are one constant.** They are two
  renderings of the same sentence, and a client comparing them must never see drift.
- **Elevation is never re-derived app-side.** The `SUPERVISED_PRODUCTION_READ` event fires on
  *granted ∧ supervised ∧ production* and logs `acr`/`auth_time` verbatim; elevation is implied by the
  allow. A Java copy of the LoA map or the window would be a second source of truth for the one number
  Amendment 3 says exists once.
- **`AuthorizationDecision` subclassing and result propagation were verified, not assumed** (`javap -p`
  on `spring-security-core-7.0.6`): the class is non-final with a public `(boolean)` constructor, and
  `AuthorizationManager.verify` throws `new AuthorizationDeniedException("Access Denied", result)` with
  the manager's own result object — so the subclass reaches the advice intact.

## Part review (layer 2)

**Scope:** part 0 = **T1–T4** as one diff (`4bf3e3b..HEAD` on `feature/void3110/step-up-elevation`) —
the realm export, the three leaf policies + `step_up.json`, the core envelope, and the manager/advice/
audit/example wiring.

**Path — and the downgrade, recorded.** Applied **inline, in this context, as the 2A lens set**. The
deep-review skill's own path table routes an inside-a-subagent review to *2A inline, any size, any
risk*; the multi-lens 2B path is unreachable where a part-runner runs. **T2 and T4 are headline
tickets** in this range (the sole-blocker factoring, and the emitter every adopter sees), so both are
covered here by the inline pass rather than by the multi-agent fan-out they would otherwise get —
**layer 3 re-covers them** over the whole delivery.

**Lenses applied and what they found:**

1. **Fail-closed spine (the slice's load-bearing invariant).** Traced every failure class end to end
   across the four tickets: missing/unmapped/non-numeric claims → `elevated` undefined → deny (U1–U3 +
   the string-`auth_time` case); a malformed wire reason → dropped at the parse (U14, eight shapes); an
   outage/breaker → `deny()` with a null reason (U15); a partial reason → 403 (U18, I2); an unquotable
   parameter → 403; audit failure → nothing. **No path produces a reason the policy did not emit**, and
   the only synthesized decision values in the library are deny-shaped. No finding.
2. **The seven slice invariants, checked mechanically rather than by memory.** (1) sole-blocker — the
   `denied_other` enumeration in STATUS-02, plus U8's seven "absent" cases. (2) elevation-proof unproven
   tier — guarded in *both* directions (deleting the clause fails 3 tests; **adding** `not elevated` to
   it fails 1). (3) additive envelope — `allow`/`allowAll` unchanged, `ResilientOpaClient.decide`
   overridden and pinned by an identity test. (4) exactly one policy ticket — `git log --stat` shows
   `infra/opa/policies/` touched **only** by T2's commit. (5) nothing elevation- or agent-related in
   `filter`/`bulk` — the `filter`+`bulk` tails of all three policies are **byte-identical to `HEAD`**
   (scripted comparison), and `filter` is asserted *true* on the very inputs the gate denies. (6) the
   three prongs — config (U20), policy (U9, all three files), app-side leg (I3). (7) one window — 300 in
   the realm export and in `step_up.json`, cross-referenced in `infra/README.md`; the advice holds no
   copy. No finding.
3. **Cross-ticket composition (what only a part-wide view sees).** The `acr`/`auth_time`/`act_chain`
   names agree across all four tickets — the realm mints them (T1), the policy reads
   `input.subject.attributes.<name>` (T2), and the yaml copies exactly those three claims (T4); `act_chain`
   is the **wire** name everywhere; a grep for `actor` as a claim (word-boundary, excluding
   *factor*/*factory*/*refactor*) finds it **only** inside comments that say it is MCP-internal and never
   travels downstream — it is used as a key in no file of this part. The window value 300 lives in
   exactly two **data** files (`step_up.json`, the realm export's `loa-max-age`); its two other
   occurrences are the policy tests asserting the reason's contents. The `provenance == "supervised"`
   stamp is the same string in the policy, the manager's audit predicate and the leg guard. No finding.
4. **Boundary / additivity.** Verified by file list rather than by intent: no `opa-abac-spring-data`,
   no `HierarchicalAuthorizer`, no `agent_tools.rego`, no `example-mcp-server`, no `example-demo-ui`, no
   `OpaAuthorizationManager` in the part's diff. Two pre-existing test files were touched for a helper
   rename (T3) and four for a mock restub (T4) — both recorded above with their reasoning; **no
   assertion was altered in either**.
5. **Test honesty.** Re-read the new cells for vacuity, since a passing test that proves nothing is this
   slice's characteristic failure. One real instance found and fixed during T4 (the 0-based pagination
   near-miss, above). Re-checked the policy side the same way: every new "allow" cell is guarded by a
   deletion mutation that makes it fail, which is what makes it non-vacuous.
6. **Docs vs code.** The three doc deltas (T1's realm note, T3's envelope section, T4's guide
   subsection) were re-read against the shipped code: the challenge string in the guide matches the one
   `AbstractProblemAdvice` builds character for character; the claim list matches the yaml; the
   `deny_reason` wire shapes match `HttpOpaClient`'s parse; the level-1/level-2 flow description matches
   the exported JSON. No drift.

**Fixes applied in this review:** none — the two substantive findings of the part (the header-injection
surface and the pagination near-miss) were caught and fixed inside T4's own ★ gate, and this pass
confirmed them rather than adding to them. **Nothing was refactored for its own sake.**

**Escalations:** none. This is part 0, so no completed part exists to reach.

**Left for part 1 / layer 3, stated rather than silently assumed:**

- Nothing in part 0 has been proven **on the rig**. The corpus is `opa test`-green and OPA has **not**
  been restarted, so the deployed decision is still slice B's; T6 restarts it and polls a real decision.
- The safe intermediate state holds as designed: the realm *can* mint `aal2`, but nothing deployed
  requests it (no client sends `acr_values`), so a supervised production read stays a deny — 401-shaped
  once part 0's images are deployed, 403 on pre-C images. Contents never open wider than B shipped.
- T6 must rewrite exactly the seven enumerated production-tier C-flip cells (E2a–E2d, E4b, E4c, E5d),
  because those cells assert the plain 403 this part deliberately turns into a 401.

## Commit

`feat(step-up-elevation): the manager's structured deny, the RFC 9470 emitter, audit, and the agent
guards (T4)` — `StepUpRequiredDecision` + `AbacAuditLogger` + the manager's `decide()` adoption + the
advice's 401 branch + `LibraryErrorCode.STEP_UP_REQUIRED`, the catalog service's claim config, leg guard
and OpenAPI enum, the TEAM-BASED-AUTHORIZATION subsection, four new test classes and the four mock
restubs, on `feature/void3110/step-up-elevation`.
