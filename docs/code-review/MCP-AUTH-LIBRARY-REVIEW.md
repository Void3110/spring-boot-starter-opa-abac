---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring-security
  - area/mcp
---

# MCP-AUTH-LIBRARY — Code Review

> **Verdict**: Approved with fixes — round 1 (the multi-lens workflow) found two Low issues, both fixed; round 2
> (Fable + Opus on the fix commit) found one Low test gap and five nits, fixed; round 3 (the pair again) was dry
> and fixed nothing — the terminal round. See [Rounds](#rounds).
> **Scope**: The whole slice (layer 3 of an ORCHESTRATOR run, two parts): the new published module
> `opa-abac-mcp`, the starter's `OpaMcpAutoConfiguration` (post-processors, `McpStartupVerifier`), the
> example's shrink onto the module, the four specification-type gates, the `agent-clients` guard, input
> schema v1 (jar marker + `agent_tools.rego` guard), the Boot 4.1 compat line, the e2e runner/collection and
> the docs. · **Branch**: `feature/void3110/mcp-auth-library` vs `origin/main` (28 commits, 145 files,
> +9344/−1232 before the review; 121 code/config files, +6319/−1215).

## How it was reviewed

**Round 1 — the multi-lens `/deep-review` workflow (path 2B), one pass**, as the repo's review routing
requires for a slice's first whole-delivery review. Eight finder lenses ran in parallel (fail-closed/authz ·
core-boundary/additivity · rego/policy · persistence/concurrency (here: threading + memo isolation) ·
security-audit · API contract · conflict/CI/dead-code · infra/e2e), every finding faced an adversarial
refuter, and a completeness critic looked for unswept siblings, zero-caller seams and untested off-states.
12 agents, 0 errored; six lenses and the critic returned an explicit empty finding list (not a failure).
Cost: ≈1.48M subagent tokens, ≈10 min wall clock.

The workflow was steered at the eight highest-risk areas named by the design (ADR 0036 §13): eager startup
failure for every configuration that would serve a tool ungated; the policy-path decorator's routing; the
three new wrappers deciding on the invoking thread; memo isolation; the `agent-clients` guard's precedence;
the schema-v1 guard in `allow` and every `bulk` position; additivity/module boundary; the build's compat line
and conditional WebFlux test dependency.

## Summary

**No Critical, no Medium, no fail-open reachable from a request.** Two Low findings survived refutation:
one about how far the gate's "already gated" shortcut trusts a hand-built gate, one a stale runner/README
claim that let the first e2e cells run against a pre-T7 policy. Both fixed in the review commit.

## Critical Issues

None.

## Medium Issues

None.

## Low Issues

### L1 — "already gated" trusted any gate instance, whatever it was bound to (fail-closed edge) — FIXED

`McpToolSpecifications.isGated` was a pure `instanceof` test on the specification's handler, and
`gate(element, authorizer)` returned the element unchanged when it held. The four gate classes were public and
non-final, each with a public `(toolName, delegate, authorizer)` constructor, and `ToolGateInvocation`
authorizes with the **constructor-bound** name. So `new SyncToolSpecification(tool("delete_x"),
new ToolCallGate("list_catalogs", handler, authorizer))` was kept unwrapped, recorded as gated for
`delete_x`, and certified by the verifier's step 2 — every `delete_x` call decided against
`list_catalogs`'s declaration. Likewise a pre-gate built with an inline `ToolCallAuthorizer` silently skipped
`opa.abac.mcp.identity.agent-clients`, and a subclass could override `apply`. Not reachable by a caller —
it needs the trusted application to build a mis-bound gate — but it is a hole in the guarantee the
auto-install and verifier exist for. (The U15 fixture itself pre-gated with an inline all-null authorizer —
exactly this shape.)

**Fix:** the four gates are `final`; each gains `isBoundTo(toolName, authorizer)` (name equality + the same
authorizer **instance**). `McpToolSpecifications.isGated(element, authorizer)` holds only when the
specification's gate is bound to its **own** tool name and the **context's** `ToolCallAuthorizer`; anything
else is wrapped again (a second gate can only narrow). The verifier's step 2 asks the post-processor
(`McpToolGatePostProcessor.isGated`), so the two never disagree on what "gated" means; its failure message
names the new cause. Tests: U15's fixture now pre-gates with the context's authorizer (kept, not wrapped
twice); new `wrapsAgainAPreGatedElementBoundToAnotherToolOrAuthorizer` (wrong name + foreign authorizer →
the outer gate is bound correctly) and `refusesAListWhoseGateIsBoundToAnotherAuthorizer` (a
`registerSingleton` list carrying a foreign-bound gate fails startup). **Mutation-checked:** reverting the
sync case to the name-only rule fails exactly the two new cases. Sonar's S6878 on the new switch → record
patterns.

**Sibling sweep:** all four gate types changed together (same rule, same accessor); both readers of
"gated" (post-processor, verifier) now share one method. No other `isGated`/`instanceof *ToolCallGate`
reader exists (`git grep`).

### L2 — the agent-tool runner and README still said "no Rego change → no OPA restart" (e2e truthfulness) — FIXED

T7 added the schema-v1 guard to `agent_tools.rego`, but `run-agent-tool-matrix.sh` never reloaded OPA before
its main pass (OPA runs without `--watch`; the first reload was the E6 drill), and the README row rewritten in
the same diff kept the "no Rego change" sentence. On a rig brought up before T7, E1–E5 ran against the
pre-guard policy and passed — the old policy simply ignores the marker — so the jar↔policy handshake was only
exercised live from E6-restore on. **Fix:** the runner restarts OPA before minting tokens and polls
`agent_tools.known_schemas` until it holds the v1 marker (the guard itself, not `/health`, which answers
before the bundle loads); the README row says so. Sibling runners that own a Rego change already restart
first (`run-production-tier-matrix.sh` and others) — this one now matches.

## Fail-closed verification

The fail-closed lens and its critic reported nothing beyond L1. The design's two failure classes, as built:

- **Startup class** — no `OpaClient` (also under `opa.abac.enabled=false` + `allow-ungated-methods=true`), no
  `PrincipalCeilingSupplier`, no `ToolRegistry`, declarations ≠ gated (both directions), a tool-bearing type
  the gate cannot wrap, WebFlux, a singleton list that escaped the post-processor (incl., after L1, a
  mis-bound gate), the `agent-clients` wiring check — each fails the context in the one ordered
  `McpStartupVerifier` callback, eagerly under lazy initialization (U36/U37).
- **Request class** — each undecidable call denies with its own code in `_meta`; the roster lands empty
  (engine could not decide) or unfiltered + WARN (identity/capability/ceiling/actor-required edges) per the
  design table; schema v1 makes an unknown or missing marker refuse in both `allow` rules and every `bulk`
  position (deleting the guard fails seven named policy tests).

## Security audit

No caller-reachable widening found. The ceiling memo keys on `(subject, resourceType)` and the capability memo
on the actor, both request-scoped and (after part 0's own review) per decorator instance. The policy-path
decorator falls through to the application's resolver for every non-`tool` type; `tool` is reserved. Denial
results and WARNs carry codes and names, never a source's response body. The `agent-clients` guard keeps a
malformed actor on `tool-gate-identity-unreadable`. L1 was the one place the gate trusted something it had not
installed itself.

## Concurrency & idempotency

No mutation is gated in this slice; the risk is threading. All four wrappers share `ToolGateInvocation`:
decide on the invoking thread when the handler is called, decorate (never re-decide inside) the async `Mono`;
a resubscription does not call the authorizer again (a `Mono.defer` mutation fails the once-only test). The
measured spike (all four server configurations run the handler on the request thread with the
authentication bound) is the premise, and the customizer trap (I6) is pinned.

## Wiring & sibling sweep

Every new seam has a consumer and a non-happy-path test (the part-runners' wiring checks, re-confirmed by the
critic's empty result). The two fixes were swept as described under L1/L2.

## Autonomous-run check

ORCHESTRATOR run, two parts, both collected from disk by the orchestrator (commits, gates re-run, clean tree,
STATUS filled, layer-2 records present, no escalation marker). No laziness found: every ticket's acceptance
cases exist as tests; the two PKIX-deferred proofs (U24 `spring-webflux`, U33 Boot 4.1.1) are recorded with
their commands and the build is wired so CI fails rather than skips them. No self-preferential gloss: both
layer-2 reviews named real findings and fixes (part 0: a name-only scoped-proxy exemption — the same
*trust-by-name* class as L1, one level up — and per-class memo keys). No goal drift: `opa-abac-core` and
every pre-existing module untouched; the module declares no beans and its main scope depends on neither the
starter, the example nor Spring AI.

## What's done right

- One ordered startup verifier instead of independent callbacks — the ordering bug class the decomposition
  spent five delta checks on did not reappear.
- The structural tool-bearing test (any public `McpSchema.Tool tool()`), so a future fifth SDK type is refused
  rather than passed through.
- Schema v1 lands jar and policy in one commit, with the policy refusing the unknown rather than ignoring it.
- The compat line resolves the default line byte-identically (18/18 dependency trees) and fails on an unknown
  value.

## Test results

- `./gradlew build` (offline, default line): green after the fixes.
- `:opa-abac-mcp:test` / `:opa-abac-spring-boot-starter:test` / `:example-mcp-server:test`: green (starter +2
  cases).
- `opa test infra/opa/policies`: 459/459 (no policy change in the review).
- sonar-local: no new finding; the remainder are the standing by-design classes recorded in Mulch
  `quality-gate-sonar` (moved-test S5778/S5853/S2925/S125/S5976 — mx-302e78; S1168 tri-state memo, S3011 the
  example's reflective installer, S1075).
- Agent-tool matrix (E1–E12): green on images rebuilt from the round-1 fix **and again from the round-2 fix
  (`013e295`, the reviewed state)** — each time 10 newman runs, 92 assertions, 0 failures; the up-front OPA reload
  passed its `known_schemas` poll before any token was minted.
- After round 2: `./gradlew build` green; `:opa-abac-mcp:test` 152, `:opa-abac-spring-boot-starter:test` 159
  (2 skipped — the PKIX-deferred reactive half), 0 failures; sonar-local back to the standing by-design set.
- Deferred (PKIX on this host — the maintainer's command or CI on the PR): U24 `spring-webflux`, U33 Boot 4.1.1
  (commands in STATUS-05/STATUS-08).

## Round 2 — Fable + Opus on the fix commit (`17a70bc`)

The repo's routing for every follow-up review on a slice branch: the same read-only prompt to a Fable and an
Opus reviewer in parallel, synthesized here, verdict folded fail-closed. **Both: APPROVE-WITH-FIXES** — no
Critical or Medium, and both independently confirmed the fix cannot fail open (the keep-set is strictly
narrower; anything not kept is wrapped; the outer gate decides first and an inner mis-bound gate can only deny
again; both provider lookups resolve one cached singleton or both throw `NoUniqueBeanDefinitionException`).
Cost: Fable ≈0.21M, Opus ≈0.22M subagent tokens; the weekly all-models meter did not move a whole percent
across the pair, so the Fable multiplier was not observable at this resolution.

| # | Sev | Finding | Who | Disposition |
|---|-----|---------|-----|-------------|
| R2-1 | Low | `isBoundTo` on the async / stateless / stateless-async gates had no negative test — a `return true` or name-only mutant in any of them survived and re-opened L1 for three of four types; and the round-1 "foreign authorizer" fixture was **also** bound to the wrong name, so the authorizer half was pinned only through the verifier | both | **Fixed.** `ToolGateWrappersTest.aGateIsBoundOnlyToItsOwnToolNameAndItsOwnAuthorizer` over all four gates (own/other name × same/other/null authorizer); the starter's re-wrap case now covers all four types with each half pinned separately (`<type>-name`: context authorizer, wrong name; `<type>-auth`: own name, foreign authorizer). Mutation-checked: async name-only, stateless `return true` — each fails one module and one starter case. |
| R2-2 | Nit | A **prototype-scoped** `ToolCallAuthorizer` now failed startup: the post-processor wrapped with `getObject()` and the verifier compared against a fresh `getIfAvailable()` — never the same instance. Fail-closed, but a regression from the `instanceof` rule | Opus | **Fixed.** The post-processor resolves the authorizer once and keeps it (`AtomicReference`); the verifier compares against that instance (falling back to a lookup only when no list carried tools). New case `aPrototypeAuthorizerBindsEveryGateToTheInstanceTheGateInstalled`; mutation-checked. |
| R2-3 | Nit | ADR 0036 §13, the decomposition's T3 text, the U35 row and the guide still described the weaker rule | both | **Fixed** — one clause each (STATUS notes left as the historical ticket record). |
| R2-4 | Nit | A mis-bound pre-gate was re-wrapped silently | Opus | **Fixed** — a WARN naming the tool and list. |
| R2-5 | Nit | The runner derived the probe URL by stripping `/health` (a silent no-op on an override without that suffix), grepped the marker unanchored, had no request timeout, and its comment said OPA's health answers before "the bundle" loads (this rig loads plain files) | both | **Fixed** — an `OPA_URL` (default unchanged) feeds both the health and probe URLs; `grep -qF` on the JSON-quoted marker; `--max-time 5`; comment reworded. Verified against the live rig: the probe matches `{"result":["opa-abac.mcp.tool-gate/v1"]}` and not an undefined rule's `{}`. |
| R2-6 | Nit | The record patterns call `tool.name()` on a possibly-null `tool` component — an NPE instead of the verifier's named message | both | **Follow-up, not fixed** — fail-closed either way, and the post-processor already NPEs one line earlier on the same input (pre-existing, `toolName(element)`). |

Two new Sonar findings the fixes introduced (S7475 unnamed-pattern type, S3077 `volatile` reference) were fixed
before commit; the scan is back to the standing by-design set.

**Follow-ups (pre-existing, out of scope — proportionality clamp):** the null-`tool` NPE above; `wait_opa` /
`wait_mcp` in the runner call `curl` without `--max-time` (a half-open service hangs the loop); `restore_rig`
recreates the MCP pod on every exit, even one before any drill (slow, harmless); STATUS-07 still tells the
operator to restart OPA by hand before the matrix (historical — the runner now does it).

## Round 3 — Fable + Opus on `013e295` (terminal)

Scoped to the round-2 commit (proportionality clamp from round 3 on). **Opus: APPROVE. Fable: APPROVE-WITH-FIXES.**
Folded fail-closed: no BLOCK. **Zero behavior-changing findings** — both re-verified that the pinned authorizer
cannot certify anything the post-processor did not install (per-context post-processor, `updateAndGet` returns the
winner, `@Primary`/ambiguity resolve identically in both lookups, the fallback can only refuse), and that every new
test kills the mutant it targets. What they did report:

| # | Sev | Finding | Who | Disposition |
|---|-----|---------|-----|-------------|
| R3-1 | Low (test strength) | The prototype-authorizer case declares one list, so a "re-resolve on every list" mutant (fails *closed*) survives | Fable | Backlog **24** |
| R3-2 | Nit | The docs do not say that, with a non-singleton authorizer, a pre-gate can never match the installed instance (always wrapped again with a WARN) | both | Backlog **24** |
| R3-3 | Nit | Overriding only `OPA_HEALTH` no longer moves the schema probe, and its timeout message then blames the policy | Opus | Backlog **24** |

Pre-existing follow-ups the round surfaced (step 2 certifies the binding, not the passage, for a hand-bound
`registerSingleton` list of an undeclared tool — call-time deny; a `@Lazy`-proxied authorizer is never "the same
instance"; the `curl | grep -q` SIGPIPE edge under `pipefail`) are in backlog 24 or recorded here.

**Termination.** Round 3 had all its reviewers run, zero behavior-changing findings, and **fixed nothing** — the
loop rule's terminal round. Its test-strength and doc nits went to the backlog rather than into the branch, so the
terminal round stays a no-fix round (a fix there would need a round 4 to verify it).

## Rounds

**Layer 3 round 1:** multi-lens workflow (2B) — 2 Low, both fixed.
**Layer 3 round 2:** Fable+Opus pair — 1 Low + 5 nits; 5 fixed, 1 follow-up.
**Layer 3 round 3:** Fable+Opus pair — dry (0 behavior-changing), no fix; terminal. Nits → backlog 24.

Observed cost (subagent tokens): round 1 ≈1.48M (12 agents); round 2 Fable ≈0.21M + Opus ≈0.22M; round 3 Fable
≈0.16M + Opus ≈0.17M. The weekly meters moved 86→87 % (all models) and 63→64 % (Fable) across the whole review — too
coarse to read a Fable multiplier off.
