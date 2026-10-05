---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T6: Docs, changelog, proof gates

**Status:** ✅ DONE (2026-10-05, collaborative)

## What shipped

- **`CHANGELOG.md`** (new) — 1.4.0: what changed per module, and eight **upgrade notes** (direct `OpaClient`
  callers, the method gate, the request-level gate's default 403, list/hierarchy/subtree propagation, `{}` and the
  `bulk`-rule caveat, retries + the breaker, `PartialResult.error()`'s narrower meaning, the log wording) plus the
  two documented blind spots. The README's release notes gain a 1.4.0 paragraph that points at it.
- **Guides** — `HTTP-RESILIENCE.md`: a 1.4.0 banner; the edge table; the breaker invariance and "what opens a
  breaker" sections (the OPA breaker now counts real faults, the accepted deterministic-fault consequence); the
  fail-closed contract table; "how the decorator decides what to retry" (kinds, never the cause chain, never a
  value); the two-predicate note. `AGENT-TOOL-AUTHORIZATION.md`: the failure table (`tool-gate-policy-unavailable`
  vs `tool-gate-denied`) and "Why an empty roster is the honest answer" (premise gone, conclusion kept).
  `ABAC-AUTHORIZATION.md`: the client's fail-closed rule rewritten, the gates' translation, the request-level
  gate's `AccessDeniedHandler` type check (the one shown snippet), the deny-reason table row, the tri-state
  contract's outage bullet. `PARTIAL-EVALUATION-FILTERING.md`: the safety property (+ the compile blind spot), the
  paged-path table (a new "could not decide" row; `fromError` re-scoped), the resilience cross-reference.
  `REST-API-DESIGN.md`: the 503 row, a "could not decide is a 503" paragraph, the exception→status row.
- **ADRs** — 0014 and 0017 carry an "Amended 2026-10-05 by ADR 0037" note under their status lines; ADR 0037's
  status → implemented; its index row → "Accepted (implemented 2026-10-05)".
- **`ENGINEERING-BACKLOG.md`** — items **13** (the built-in ancestor resolvers' own SQL failures), **14** (the
  root-list scope-resolver blind spot), **15** (the user-service's out-of-mechanism `RoleAssignableClient`).
- **`POC-ROADMAP.md`** — the slice's line, after TAG-GATED-CREATE's.
- **This package** → `docs/to-do/implemented/ENGINE-ERRORS/`.
- **Mulch** (worktree store, hand-appended — `ml` targets the primary checkout; validated in an isolated copy,
  417 records / 0 errors): the two-rig-configuration rule (`opa-abac-rig-deploy-ops`), the gitignored files a
  fresh worktree lacks (`opa-abac-methodology`), the JDK-truststore / Let's Encrypt `YE1` PKIX failure
  (`quality-gate-mutation`).

## Tests

- **P1 — PIT** (`./gradlew mutationTest`, six modules, 1m39s): **every mutant on a line this slice changed is
  KILLED** — 74/74 across core (50), spring-security (19), starter (3), spring-data (1), catalog (1); 0 SURVIVED,
  0 NO_COVERAGE (measured by intersecting each module's `mutations.xml` with `git diff -U0 origin/main`). The first
  pass left **3 survivors** — `HttpOpaClient.describe` ×2 (no test asserted the message an operator reads) and
  the fallback advice's `instance` conditional (no test asserted the request URI) — each killed by a new assertion
  (`indeterminateMessage_namesOperationPathAndStatus`, the TRANSPORT message, the I4 `instance` + null-request
  cells) and re-verified by a second PIT run. PIT has no mutator for a bare `throw e`, so the rethrow guards are
  proven by their dedicated tests (U23, U29, U33), not by mutation.
- **P2 — local Sonar** (changed files vs `origin/main`): first scan **26** findings; the five real ones fixed —
  **S1192** (`HttpOpaClient`'s operation literals → `OP_DECIDE` / `OP_COMPILE` / `OP_BULK`), **S2147** (two catches
  with the same body → `IOException | RuntimeException`), **S2139** (`HierarchicalAuthorizer` logged and
  rethrew → rethrow only, the unused logger removed), **S3776** (`RecursiveCteAncestorResolver.collectSubtreeIds`
  pushed to 18 by the added catch → the guarded lookup extracted into `childrenOf`), **S1130** ×6 (leftover
  `throws Exception` on new tests). Rescan: **15, all test-only and all in the by-design FP catalog** (Mulch
  `quality-gate-sonar`): S5778 ×10 (`assertThatThrownBy` lambdas whose second call is a trivial test factory),
  S2925 ×3 (`Thread.sleep` inside timeout / interrupt HTTP stubs — one pre-existing), S5853 ×2 (pre-existing,
  `ToolRosterFilterTest` lines this slice did not touch). **0 findings in main code.**
- **P3** — `./gradlew build` green (1m56s) after the fixes.

## Architecture review + refactor

The Sonar fixes are the refactor. Nothing else substantive.

## Integration / e2e

None at T6 (proven at T5). Collection conformance (18 clean) and shell guards (27 clean) unchanged since T5.

## Decisions

- **PIT's dependency download** needed two workarounds, recorded in Mulch. The pin is 1.25.9 and only 1.25.7 was
  cached, and the JDK truststore rejects Maven Central's new Let's Encrypt `YE1` chain (`curl` accepts it through
  the macOS keychain). The resolving run went unsandboxed with
  `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=KeychainStore-ROOT -Djavax.net.ssl.trustStore=NONE --no-daemon`,
  so TLS was still verified, against the OS roots. Later runs work sandboxed from the cache.
- The local Sonar stack was started for P2 (`.sonar-local/docker-compose.yml`, its two-week-old volume intact)
  with the gitignored token copied from the primary checkout.

## Commit

The T6 commit — "docs: ENGINE-ERRORS T6 — changelog, guides, ADR amendments, backlog; PIT + Sonar gates" — which
also moves this package to `docs/to-do/implemented/`; the Mulch records ride a separate `.mulch`-only commit.
