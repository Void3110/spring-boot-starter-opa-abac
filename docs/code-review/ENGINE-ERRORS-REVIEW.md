---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ENGINE-ERRORS — Code Review

> **Verdict**: Approved with fixes (round 1 and round 2 — see [Round 2](#round-2--three-reviewers-on-the-fix-commit))
> **Scope**: The whole slice — ADR 0037's "could not decide" family across core, the two Spring gates, the
> data layer, the starter's fallback advice, the catalog and MCP examples, the resilience decorator, the e2e
> collections and the docs. · **Branch**: `feature/void3110/engine-errors` vs `main` (10 commits, 90 files,
> +3786/−880 before the review).

## How it was reviewed — and the waiver

The standing rule (`CLAUDE.local.md`, cost posture) makes a slice's first whole-delivery review **one
multi-lens `/deep-review` workflow pass**, keeping the **Fable + Opus pair** for follow-up rounds. For this
slice the maintainer chose the pair as the first review. **Waiver recorded here.** The pair ran the same
ten-lens prompt in parallel, read-only (fail-closed · narrow typing + the rethrow invariant with a
whole-repo catch sweep · Spring routing · resilience · security · core boundary + API compatibility ·
concurrency/idempotency · wiring + completeness critic · docs truthfulness · e2e/CI/clean-room), plus the
five design calls the build session flagged. The orchestrator verified every claim it acted on against the
source; the synthesis is fail-closed (either BLOCK ⇒ BLOCK). Cost: Fable ≈ 0.31M tokens, Opus ≈ 0.40M.

## Summary

**Both reviewers: APPROVE-WITH-FIXES. No Critical, no High, no fail-open.** Every new throw ends in a 503
or a deny/empty/all-false, every catch-all on a decision path still denies, and both reviewers re-verified
the Spring Security 7.0.x / MVC 7.0.8 routing claims in the jars. The review's one substantive finding was
a **design fork the slice had accepted — and the premise it was accepted on was false**: the OPA breaker
counted deterministic faults, "accepted because every call answers 503 either way". One breaker serves
every type and every method, so a fault local to one type (a package that loads late, an enrichable type
with no `bulk` rule) opened it for **all** of them. The maintainer reversed the fork. The rest were
documentation over-claims, one unpinned compile shape, startup validation, and small hardening.

## Medium Issues

| # | Finding (who) | Fix |
|---|---|---|
| 1 | **One type's deterministic fault opened the OPA breaker for every type** (Opus; Fable flagged the `INTERRUPTED` part). `Resilience4jCallGuard` recorded every thrown failure before classifying it, and one guard wraps the single `OpaClient` bean. Five `UNDEFINED_DECISION`s from an enrichable type with no `bulk` rule — a *decoration* designed to degrade silently — opened the breaker; for 10 s every gate, list and enrichment call for every healthy type answered `CIRCUIT_OPEN`. In 1.3.0 that type merely lost `_actions`. ADR §4's acceptance ("every call answers could-not-decide either way") holds only when the fault hits every call. | **Fork reversed (maintainer decision): the OPA breaker counts exactly the faults it retries** — `TRANSPORT`, `TIMEOUT`, 5xx, 429. A fail-fast kind (`UNDEFINED_DECISION`, `MALFORMED_RESPONSE`, 4xx, `INTERRUPTED`) answers 503 for that call and neither counts nor resets the window. Mechanism: `CallGuard` gains an optional, additive fourth argument `recordableError` (a `default` that keeps counting everything); `Resilience4jCallGuard` honours it and **releases the permission** for an unrecorded throw, so a half-open probe slot is not leaked; `ResilientOpaClient` passes its retry predicate. The resolve and tag edges keep counting every throw. Tests: U34 flipped (four fail-fast kinds × 10 calls → closed), U36 (five undefined bulk calls, then a healthy decide reaches the policy), guard-level cells (unrecorded throws never open it; the half-open probe is released — **proven by removing `releasePermission()` and watching the cell fail**; the three-argument form still opens on non-retryable throws; the interface default delegates). ADR 0037 §4, `HTTP-RESILIENCE.md`, `CHANGELOG` note 6, `00-DESIGN` amended. |
| 2 | **The upgrade notes never said the OPA breaker is live for the first time** (Opus). In 1.3.0 the client never threw, so the breaker never counted. With the defaults (threshold 5, one retry → two recorded attempts per request) about three failing requests open it, and every OPA-backed call answers 503 until a half-open probe succeeds — up to 10 s after a sidecar that restarted in 300 ms is back. | **Defaults kept, documented (maintainer decision)**: `CHANGELOG` note 6 and a new `HTTP-RESILIENCE.md` callout name `opa.abac.resilience.opa.breaker.failure-threshold` / `open-duration` and the window; ADR 0037 §4 gains a "first time" bullet; **backlog item 16** — a load ceiling with OPA killed and restarted, before any default changes. |

## Low Issues

| # | Finding (who) | Fix |
|---|---|---|
| 3 | **Compile's wrong-shaped `result` was a silent `DENY_ALL`** (both). `CompileResponseParser` answers deny-all for a missing, null or non-object `result` and a non-array `queries` — `{"result":null}` on `/v1/compile` became an empty 200 while the same outage on a decide was a 503, contradicting ADR §3's table and §3a ("malformed failures on the compile path **are** indeterminate"). | `HttpOpaClient.compile` checks the shape first (`requireCompileShape`): a missing/non-object `result` or a non-array `queries` → `MALFORMED_RESPONSE`. OPA's Compile API always carries a `result` object, so nothing legitimate changes; `{"result":{}}` stays the pinned blind spot. U9 gained five cells. |
| 4 | **A bad `opa.abac.base-url` failed every request, not the startup** (both; design call b). `OpaClientConfig` only stripped slashes; `opa:8181` reached `HttpRequest.newBuilder` per call → a retried `TRANSPORT` → after this slice, a poisoned breaker hiding the root cause behind `CIRCUIT_OPEN`. | `OpaClientConfig` rejects anything but an absolute `http`/`https` URL with a host (`IllegalArgumentException`, so the context fails at startup). `TRANSPORT` stays the runtime classification. U37; `CHANGELOG` note 9. |
| 5 | **The fallback advice's precedence and opt-out were javadoc/ADR-only** (both; design call d). `@Order(HIGHEST_PRECEDENCE)` overrides the adopter's own `AccessDeniedException` handler for the two family types, also claims an adopter exception that *wraps* a family member (MVC walks the cause chain within one advice), and is turned off app-wide by any `AbstractProblemAdvice` bean, even a scoped one. | `CHANGELOG` note 2 and `REST-API-DESIGN.md` state all three and the two opt-outs (extend `AbstractProblemAdvice`, or declare your own `IndeterminateDecisionProblemAdvice` overriding `handleIndeterminate`). The ordering itself is kept — I5 proves it load-bearing. |
| 6 | **The request memos replayed only the original outage types** (Fable). `MemoizingRoleDefinitionSupplier` and `MemoizingAncestorResolver` captured `RoleResolutionException` / `AncestorResolutionException`, so an outage an SPI opts in with as its own family subtype (ADR §2) propagated correctly but re-hit the dead source on every call in the request. | Both capture `DecisionIndeterminateException` too; a cell each (one real call, the same instance replayed). |
| 7 | **A malformed decide/bulk body put the parser's message — which quotes the body — in the WARN** (Opus). 1.3.0 logged the class name only. | A malformed body is described by the parser's exception class; the full cause stays at DEBUG. Pinned by a cell asserting the body never reaches the message. |

## Docs and test gaps

| # | Finding (who) | Fix |
|---|---|---|
| 8 | **Prose the slice did not sweep, now false** (Opus): `ACTION-ENRICHMENT.md` (the all-false→omit rationale), `PERMISSION-MODEL.md` ("the gate denies"), `SUPERVISED-READ-AND-STEP-UP.md` ("plain deny at the resilient wrapper"), the `ActionEnrichmentAdvice` and `EffectiveRoleService` comments, and the README's release notes (1.4.0 sat between 1.3.0 and 1.2.0). | All rewritten for 1.4.0; README reordered. The orchestrator's sibling sweep found two more of the same class: `CallGuard`'s javadoc ("the OPA decorator maps breaker-open to `false`/`error()`/all-false") and `PARTIAL-EVALUATION-FILTERING.md`'s interface listing ("fails closed to all-false") — both fixed. |
| 9 | **The blind spots were understated** (Fable): the root-list blind spot is "an empty 200" in backlog 14 / §3a / `CHANGELOG`, but for a member who also supervises it is a silently **membership-only** 200; the `CHANGELOG`'s spring-data bullet said the built-in ancestor resolvers propagate the family — true only for an opted-in source SPI (their own SQL failures still degrade, backlog 13). QA I2 claimed "over MockMvc"; the test drives the handler and resolver directly. | All three reworded; backlog 14 notes it is the first of 13–15 to schedule (both reviewers). |
| 10 | **I6 covered a plain member, not the member-and-supervisor subject its QA row specifies** (Opus). | `CatalogListOutageIT` gained the cell — the real `SupervisedScopeClient` type, subclassed to answer a supervised id — asserting 503 and that neither catalog id leaks. |

Not changed: U2's connect-timeout variant has no dedicated cell — classification is by type
(`HttpConnectTimeoutException extends HttpTimeoutException`), so it is covered, not exercised (both).

## Design calls the build session put up for challenge

| Call | Fable | Opus | Outcome |
|---|---|---|---|
| a. Keep `PartialResult.error()` | keep | keep | **Kept.** The only value that both denies and suppresses the subtree widening; `denyAll()` would let the Java-side widening survive a refusal. |
| b. A client-rejected request (bad base URL) is `TRANSPORT` | validate at startup | validate at startup | **Startup validation added** (#4); `TRANSPORT` stays the residual runtime class. |
| c. The breaker counts every thrown fault | keep, name `INTERRUPTED` | change | **Changed** (#1) — maintainer decision. Fable's objection (the resolve/tag edges rely on counting everything) is met by making the narrowing OPA-edge-only. |
| d. Fallback advice at highest precedence, only without the base | keep + document | keep + document | **Kept, documented** (#5). |
| e. Backlog 13–15 | none blocks | none blocks; 14 first | **None blocks 1.4.0**; 14 marked first. |

## Round 2 — three reviewers on the fix commit

The loop rule makes the terminal round a no-fix round, and round 1 fixed behaviour, so round 2 reviewed `b646868`
(the round-1 fixes) — the Fable + Opus pair again, **plus OpenAI's Codex CLI as a third, independent reviewer**
(maintainer's call; run read-only in a clean clone of the branch so no gitignored local file was readable — its
capability evaluation lives outside this repo). Same brief for all three: did each fix land clean, regressions and
unswept siblings, can each new test fail, docs truthfulness, the slice invariants.

| Reviewer | Verdict | Behaviour-changing findings |
|---|---|---|
| Fable | APPROVE | 0 (one pre-existing latent defect, for the backlog) |
| Opus | APPROVE-WITH-FIXES | 1 |
| Codex | BLOCK | 4 |

**Synthesis: APPROVE-WITH-FIXES, not BLOCK.** Every Codex claim held when re-read from source, but its BLOCK rested
on two over-rated findings — a URL echoed in a startup error (rated Critical; Low: startup-only, for an already
invalid value) and a nested compile corruption (rated High; Low: nothing realistic emits it). Both were fixed
anyway. The synthesis gates on *verified, re-ranked* findings, not on a raw verdict.

| # | Finding (who) | Fix |
|---|---|---|
| R2-1 | **An OPA policy *evaluation* error still opened the shared breaker** (Opus; Medium). OPA's data API answers `eval_conflict_error` with HTTP **500** (this repo measured it before — a `false`-valued attribute triggered one), and every 5xx was retried and counted — so one product's data could refuse every type, the exact class round 1 fixed. The round-1 docs claimed it closed. | **New `Kind` `EVALUATION_ERROR` (maintainer decision)**: a 500 whose body lists only `eval_*` codes (body shape measured on OPA 1.10.1 here) is fail-fast — not retried, not counted. Only the codes are read; the messages and the policy file's location never are; codes are matched against `eval_[a-z_]+`. U38; in U34's never-opens list; ADR §3/§4, CHANGELOG note 10. |
| R2-2 | **The guard leaked a half-open probe slot on a retried *returned value*** (Fable + Codex; pre-existing, unreachable by shipped callers — all pass `result -> false`). | The value branch releases the permission too, like an unrecorded throw. U39 — proven by removing the release (the cell fails). |
| R2-3 | **A nested compile corruption was still a quiet deny-shaped result** (Codex): `{"result":{"queries":[{}]}}` passed round 1's check and became `unsupported()` — an empty page with the allowlist fallback off. | `requireCompileShape` also requires each query to be an array of expression objects; what an expression *says* stays the parser's "unsupported". Three U9 bodies. |
| R2-4 | **The base-URL check echoed the raw value** (Codex) — a user-info part would reach the startup log, and the attached `URISyntaxException` quotes the input too. | No value in either message; the syntax reason and index only, no cause attached. A credential-canary cell walks the whole cause chain. |
| R2-5 | **Two example clients put a response body in a WARN** (Codex: `RoleAssignableClient` logs `e.getMessage()`, which Spring Web 7.0.8's `StatusHandler` builds from the whole body; Opus: `ToolCallAuthorizer` logged role/capability failures with the throwable, whose parser cause quotes the body). | WARN carries the class or our own message only; the throwable moves to DEBUG. |
| R2-6 | **Docs** — "about three failing requests open it" is false for timeouts (the default 5 s timeout outlasts the 2.5 s retry ceiling: one attempt per request, so five) (Opus); stale public javadoc in `PartialResult` and `ActionEnrichmentAdvice` describing the 1.3.0 failure values (Codex — missed by round 1's sweep because the phrases wrap across lines); stale "no change" rows in `00-DESIGN` / `01-DECOMPOSITION` (Opus); ADR §3's table omitted decide's non-object `result` (Fable). | All rewritten. |
| R2-7 | **Two tests that could not fail** (Opus): the "body never reaches the WARN" cell used a body Jackson truncates anyway (it echoes identifier characters only), and the I6 supervisor cell took the same path as the plain-member cell (with the whole role source down, 1.3.0 also answered an empty 200). | The body is identifier-only (`secrettokenvalue`, which Jackson echoes in full); I6's outage now hits the membership anchor only — the case 1.3.0 answered with a supervised-only 200 — with a contrast cell proving the supervised leg runs. |

**Found while fixing R2-7, out of scope:** the contrast cell exposed a **pre-existing** defect on `main` —
`AbacQueryService.authorizedSpec` OR-s an `ALLOW_ALL` residual (`Specification.unrestricted()`, a `null`
predicate) with the subtree widening, and Spring Data JPA drops a null side, so the list collapses to the subtree
alone. Fail-closed but wrong (rows go missing). Tracked as **ENGINEERING-BACKLOG item 17** with a spun-off task;
the contrast cell asserts only what it is for and neither depends on nor pins the defect. Also to the backlog:
**item 18**, the example clients' unvalidated base URLs (Opus; example-only, pre-existing).

## Fail-closed verification

Every error/empty path re-traced by both reviewers and re-checked here for the fixes: `HttpOpaClient`
(prepare-phase refusals → deny / all-false / `error()`; transport, timeout, status, interrupt, malformed,
undefined → the family; the new compile-shape check → the family), `ResilientOpaClient` (exhausted retry
rethrows the delegate's kind; breaker-open → `CIRCUIT_OPEN`; an unrecorded fail-fast throw is still thrown,
never swallowed), both gates (the family → `AuthorizationIndeterminateException`; anything else → DENY), the
data layer (propagates), the decorations (omit / empty roster), the memos (replay, never reinterpret).
**No path returns more access on failure than on success.** The breaker change alters only *when* a call
short-circuits — every state still throws the family or returns the policy's own answer.

## Security audit

No widening by logic: no scope or ownership check was weakened; `childrenOf()` is the old loop body plus
the rethrow; the ancestor resolvers' changes are catch-clause-only (both reviewers). No authz artifact is
cached across requests (the memos are request-scoped; they now hold one more outcome type). No injection
surface touched. Leaks: the problem+json detail is a constant; the WARN carried a parser message quoting the
response body — fixed (#7); the transport WARN carries the URL and the exception, never credentials or the
request input. Authn edges unchanged. Clean-room: both reviewers' pattern scans of the added lines were
clean; no absolute paths, tokens or internal hosts.

## Concurrency & idempotency

No gate or authorizer that can now throw runs after a write (both checked the catalog controllers,
`TagDecisionGate`, `CatalogHierarchyService`, `MembershipService`); `@OpaPreAuthorize` interceptors run
outside `@Transactional`. Retries are on read-only OPA calls only. The guard's new branch calls
`releasePermission()` on the same breaker instance the permission came from; the breaker's state machine
is Resilience4j's own (thread-safe).

## Wiring & sibling sweep

New seams have callers and non-happy-path tests: `recordableError` (called by `ResilientOpaClient`; U34/U36
and four guard cells), `requireCompileShape` (U9), `requireHttpUrl` (U37, and every client built by the
starter goes through it). Sibling sweeps: the breaker narrowing — the resolve, tag and supervised edges
(`CallGuards`, the example clients) intentionally keep the three-argument form; the memo widening — both
memos changed together; the stale-prose class — the two extra hits in #8. A process note: one test file
(`MemoizingRoleDefinitionSupplierTest`, since `de4df80`) carried a **literal NUL byte** in a string literal,
so `grep` reported it as a binary file and every text sweep silently skipped it. Replaced with the
equivalent `"\0"` escape; no other tracked text file contains a NUL.

## Autonomous-run check

Not an autonomous run (collaborative build). Self-preferential bias, checked anyway: STATUS-01 stated "the
blind spot is only `{"result":{}}`" on compile while the parser's own fallbacks made four more shapes
deny-all (#3) — a claim the build's tests did not exercise. STATUS-01/ADR §4 presented the breaker fork as
safe on a premise no test challenged (#1). Both are the recurring planning-gap class — **a fail-closed /
contract semantic left unpinned** — and are recorded in the run retrospective.

## What's done right

The type split itself held under two independent whole-repo catch sweeps (74 sites): nothing swallows the
family into an allow, and the only deny-shaped swallows are the designed decorations and the three
documented backlog items. Every QA case maps to a test that fails when its guard is removed; I1 and I5
carry live controls. The routing claims were exact to the bytecode.

## Test results

- `./gradlew build`: green (1m56s) after the fixes; core and spring-security re-run green after the last
  refactor (the guard's two extracted helpers, line wraps, the NUL escape). The new cells were confirmed in
  the JUnit XML, not inferred from the exit code.
- sonar-local (changed files vs `origin/main`): the first rescan raised one real finding the fix introduced —
  **S3776** on `Resilience4jCallGuard.call` (21 > 15), fixed by extracting `acquirePermission` and
  `recordOrRelease`. Final: **15, all test-only, all the T6 baseline** (S5778 ×10, S2925 ×3, S5853 ×2 — the
  `quality-gate-sonar` FP catalog). **0 in main code.**
- PIT (`./gradlew mutationTest`, 1m15s), intersected with changed lines: **the review's own changes 25/25
  KILLED**; the whole slice vs `origin/main` **97 KILLED + 1 NO_COVERAGE** — the happy return of
  `RecursiveCteAncestorResolver.childrenOf` (extracted by T6's Sonar fix, after T6's PIT run), exercised only
  by the Docker-backed `SubtreeOfIT` that PIT's target tests exclude (the documented spring-data inflation).
  The half-open `releasePermission()` cell was additionally proven by hand-mutation (removed → the cell fails).
- Clean-room scan of the review's 690 added lines against the private patterns + token/path shapes: clean.

Round 2 (fixes over `b646868`): see the round-2 commit and the run's own gate results below it — recorded when
round 3 closes.
- newman: not re-run — the fixes do not change any rig-observable path (the breaker change narrows what
  opens the OPA breaker; E4's kill drill is a `TRANSPORT` outage, still counted). STATUS-05's live run stands.
