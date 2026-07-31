---
tags:
  - status/active
  - type/review
  - area/abac
  - area/opa
  - area/spring
  - area/security
---

# AGENT-TOOL-AUTHZ (Phase 9) — Code Review

> **Verdict**: **Approved with fixes** — 14 findings confirmed, 0 Critical; 12 fixed on the branch,
> 2 accepted-and-documented.
> **Scope**: the whole slice T1–T6 — a new `example-mcp-server` (Spring AI 2.0.0, streamable HTTP), the
> `agent_tools.rego` tool-gate, dual identity, the roster filter and its reflective SDK adapter, the rig
> and the E1–E11 matrix — plus the follow-up commit making the lifecycle and abac e2e suites B4-aware.
> **Branch**: `feature/void3110/agent-tool-authz` vs `origin/main` (110 files, ~14.9k insertions).

## Summary

Path 2B (multi-lens adversarial workflow): 8 lenses → per-finding refutation → completeness critic →
synthesis. 34 agents, ~3.8M tokens. **24 candidates, 14 confirmed, 10 refuted.** No Critical, and
nothing that widens access on an error path — the fail-closed spine held everywhere it was traced.

Two findings were worth the whole pass. The first is a **coherence** defect the slice's own gates could
not see, because each half was individually defensible: `agent-gate.enabled=false` short-circuited to
`permitted()` without asking OPA, while `ToolRosterFilter` — reading the *same* switch — kept evaluating
ceiling-only contexts. So the roster was **narrower than the call path**, which is the one direction
this design says a hint must never fail in, and three shipped statements about the switch were false.
The second is a genuine **fail-open** in the policy: `is_agent_call` was a bare truthiness test, and
Rego's only falsy value is `false` — so an actor of `false` made the rule undefined and the call was
decided by the *human* branch, skipping the capability conjunct entirely. Unreachable through the
shipped PEP, but this document asserts it enforces the narrowing independently of the PEP.

**Process notes, recorded because they change how to read this run.** (1) The `args` object was passed
to the workflow as a JSON *string*, so `args.base` and `args.focus` were undefined: the review ran
against local `main` rather than `origin/main` — a two-commit `mulch:` difference, immaterial — and
**without the focus steer**. The eight lenses are failure-mode-based rather than focus-driven, so
coverage was unaffected; the prioritised surfaces were verified by hand instead (below). (2) Local
`main` is stale, pointing at `c92e54e`, a commit *on this branch*. Any tool defaulting to `main` as the
diff base reviews the wrong range — worth `git branch -f main origin/main` before the merge.

## Critical Issues

**None.**

## Medium Issues

| # | Issue | Status |
|---|---|---|
| 1 | `agent-gate.enabled=false` skipped the OPA call entirely, so the principal's ceiling was not enforced at call time — inverting the roster/call-path invariant and falsifying three shipped statements | **Fixed** |
| 2 | The type-level ceiling built one unbounded GET from the principal's entire governed-target set — no cap, no chunking; an over-long request line reads as an outage and denies every tool call | **Fixed** |
| 3 | The e2e runner aborted on **repository** state (a `git diff` invariant), so any later branch touching the catalog service would fail the run for a reason unrelated to the rig | **Fixed** |
| 4 | No rebuild path for the MCP image — `./deploy.sh build` covered only the catalog, so `up` silently reused a stale `opa-abac-mcp:local` | **Fixed** |
| 5 | `TypeLevelRoleDefinitionSupplier` — one of the tool-gate's two inputs — had **zero** tests | **Fixed** (7 cases) |
| 6 | QA cases I6 and I7 were cited as T3 acceptance but never implemented and never waived | **Fixed** |

## Low Issues

| # | Issue | Status |
|---|---|---|
| 7 | `is_agent_call` truthiness: an actor of `false` escaped to the unnarrowed human branch | **Fixed** |
| 8 | "OFF is never wider than ON" was only tested where the downstream denied for an independent reason | **Fixed** |
| 9 | I29's gate-off assertion ran against a stub allowing every tool, so it could not fail | **Fixed** |
| 10 | A committed Mulch record leaked a maintainer machine-local `~/Workspace/...` path into the public repo | **Fixed** |
| 11 | The `catalog-agent-revoked` realm client and its capability profile were never exercised | **Fixed** (E11-b) |
| 12 | `AgentCapabilityProfile`'s javadoc pinned the wire contract to a test class that does not exist | **Fixed** |
| 13 | A write-only `team_id` collection variable in the catalog-e2e collection | **Fixed** |
| 14 | The two list tools proxy paged endpoints without forwarding `page`/`perPage` | **Accepted + documented** |

### On #1, and why the fix went to the code rather than the docs

Both directions were defensible, and the choice matters. Making the *documentation* match the code
would have meant writing down that a config flag disables call-time authorization — and would have left
the roster narrower than the call path, hiding tools a caller could successfully invoke. Making the
*code* match the documentation restores a single coherent claim: **OFF drops the agent conjunct, not the
gate.** The context was already built; only the early return was removed.

The counter-argument considered and rejected: OFF as an escape hatch from a *broken PDP*. Nothing in the
ADR, guide or properties claims that, and it could not work anyway — the fail-closed client denies on
outage, so the switch would be useless exactly when reached for. The stated purpose is "removes the
narrowing", which the fix implements exactly. One doc statement (`ToolAuthorizationProperties`, "skips
the tool-gate") was inverted by the fix and has been corrected.

## Fail-closed verification

Every error/empty branch traced to deny or the smaller result:

| Edge | Lands on | Verified |
|---|---|---|
| tool-gate OPA call (outage/timeout/malformed/no binding) | deny | contract + `ToolCallGateTest` |
| ceiling resolve non-200 / unreachable | `RoleResolutionException` → deny, **distinct from empty** | new `TypeLevelRoleDefinitionSupplierTest` |
| ceiling: no governed targets anywhere | authoritative empty (not an outage) | new test |
| capability supplier: resolved / authoritative-empty / outage | narrow / deny-all / deny | `AgentCapabilitySupplierTest` |
| delegation chain absent / malformed / oversized / cyclic | human / **deny** ×3 | `ClaimDelegationChainExtractorTest` |
| roster batch all-`false` (dead PDP *or* zero capability) | empty roster, treated as authoritative | `ToolRosterFilterTest` + live E6 |
| roster wrong-length vector | **empty**, never unfiltered | I27 |
| roster edges *outside* the batch | unfiltered + WARN, gate still denying per call | I18/I19 |
| roster adapter pins moved by an SDK upgrade | **startup failure**, naming the pins | I24 |
| `agent-gate` OFF | ceiling-only evaluation — **now** genuinely evaluated | fixed, + a new test |
| actor of any malformed shape (`false`, `""`, `0`, `null`, `[]`) | agent branch → deny | fixed, + 2 rego tests |

## Security audit

- **Nothing asserted downstream** — `CatalogApiClient` sets exactly `Authorization` and `Accept`; no
  role, capability or acting-as header appears anywhere in the module. The bearer never reaches a log.
- **No cache serves an authz artifact across subjects.** `TurnScopedCapabilityCache` and
  `ToolFailureRecord` both key into **per-request** servlet attributes and degrade to a pass-through
  outside a request — no static state, no unmanaged `ThreadLocal`. Verified by hand, since a bug here
  would have been Critical.
- **The reflective adapter is safe by ordering, not by luck.** `RosterFilterInstaller` mutates a
  HashMap the SDK shares across sessions, but does so from `afterSingletonsInstantiated()`, which
  completes before `SmartLifecycle` starts the connector — one write, strictly before any traffic, with
  no later mutation path. Every pin miss throws, failing startup by design.
- **Clean-room**: one real leak found and fixed (a machine-local path in a committed Mulch record). The
  realm export, compose file, collection and runner carry only obvious demo values.
- **`DispatcherType.ASYNC` permitted** — widens nothing: an async dispatch can only exist for a request
  that already passed the chain on its initial dispatch.

## Concurrency & idempotency

No mutation path in this slice — the tool surface is four read-only proxies, so Rules 1–2 have no
subject here. The one shared-mutable-state question (the SDK handler map) is answered above by
lifecycle ordering. The e2e runner is idempotent across reruns: fixtures are re-seeded from scratch and
torn down on green, and the rig drills are restored by an EXIT trap however the run ends.

## Wiring & sibling sweep

- Every new seam has a non-test caller: the roster filter (installed by `RosterFilterInstaller`), the
  ceiling supplier (`ToolAuthorizationConfiguration`), `ToolFailureRecord` (five throw sites in
  `CatalogApiClient` + the gate), the `grant-scope-types` property (compose + defaults).
- **One seam was inert and is now wired**: the `catalog-agent-revoked` realm client and its
  zero-capability profile had no runner or collection reference — E11-b now mints that token and
  asserts a *declared* zero-capability actor answers with an authoritative empty roster and a
  `tool-gate` deny, which is a genuinely different shape from the emptied-profile drill next to it.
- **Sibling sweep on the two Sonar main-source findings**: `S112` (`throws Exception` on the
  `SecurityFilterChain` bean) and `S4502` (CSRF disabled on a stateless bearer API) are **identical in
  both sibling example services** — a standing repo-wide pattern, not a new instance. Siblings clean.
- **Sibling sweep on the gate-off fix**: the two readers of `agentGate.isEnabled()` are
  `ToolCallAuthorizer.authorize` and `ToolRosterFilter.decide`; the fix aligned the former with the
  latter, which is what the defect was. No third reader exists.

## Autonomous-run check

- **Agentic laziness** — one instance: I6/I7 cited as T3 acceptance, delivered as neither a test nor a
  waiver. I7 is now implemented; I6 is waived in the QA doc with the reason (the shipped capability
  supplier has no HTTP edge to exercise) and a re-instatement trigger.
- **Self-preferential bias** — none found. The STATUS notes' claims match the diff, including the two
  self-reported rig defects, which are described accurately and unflatteringly.
- **Goal drift** — the load-bearing invariant held: **no library module, no existing example service,
  and no pre-existing `.rego` document was changed**, verified by diff. Core stays Spring-free. The one
  drift found is #1 — the gate-off seam, added late by the adversarial gate, was written against a
  call-path behaviour that did not exist.

## What's done right

- The fail-closed spine is genuinely uniform, and the *honest* choices are the notable ones: an empty
  roster during a PDP outage rather than a comforting stale list, and an explicit acknowledgement that
  `allowAll` cannot signal failure so both causes are answered identically.
- Enforcement by **composition**, not propagation: the intersection is enforced across two independent
  layers and computed in Rego, so it is `opa test`-able and cannot drift from a Java pre-filter.
- The reflective adapter is quarantined behind a durable core that owns every semantic and every test,
  with a named deletion trigger (java-sdk #578) and a kill-switch for the SDK bump that moves the pins.
- The e2e asserts the **actual cut** — tool names and denying layer — never response shape, and the
  three rig drills exercise states no unit test can reach.

## Test results

| Gate | Before | After |
|---|---|---|
| `./gradlew build` | green | **green** |
| `:example-mcp-server:test` | 129 | **139** (+10) |
| `opa test infra/opa/policies` | 264/264 | **266/266** (+2) |
| `scripts/postman/run-agent-tool-matrix.sh` | 49 req / 73 assertions | **53 req / 78 assertions**, 0 failures |
| `run-tests.sh` · `run-matrix.sh` (both flavours) | 22/21 · 19/19 | **22/21 · 19/19** |
| local Sonar (changed files) | 41 findings, 0 real | **42, 0 real** (+1 `S5778`, the standing test-style class) |

The rego and the MCP sources both changed, so the re-run rebuilt the image (via the `deploy.sh build`
path this review added), recreated the pod, and restarted OPA before the matrix.
