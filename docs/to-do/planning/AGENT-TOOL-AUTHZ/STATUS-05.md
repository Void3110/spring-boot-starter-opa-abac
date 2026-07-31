---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T5: Roster filtering: `tools/list` via batch `allowAll`, omit-never-fabricate

**Status:** ✅ DONE

## What shipped

| File | What it is |
|---|---|
| `…mcp.authz.ToolRosterFilter` | **the durable core** — `decide()` (one `AbacContext` per registered tool → one batch `allowAll` → an allow-set) and a static, pure `apply(decision, delegate)`. Survives SDK 2.1; owns every semantic and every test. |
| `…mcp.authz.RosterDecision` | the decision value type — an allow-set, **or** `unfiltered()`. Two opposite outcomes that a bare `Set` would have to encode as `null`/"everything". |
| `…mcp.authz.RosterFilterInstaller` | **the disposable adapter** — a `SmartInitializingSingleton` that resolves the transport provider **by interface type**, walks the two pinned fields, and wraps the `"tools/list"` entry. Deleted when java-sdk #578 ships. |
| `ToolAuthorizationProperties.rosterFilter.enabled` | the kill-switch (default true) — OFF skips the installer **and its smoke check** via `@ConditionalOnProperty`. |
| `ToolCallAuthorizer.buildContext(descriptor, boolean)` | the gate-off seam the amended package added (see *Decisions* §1). |
| `application.yml` | `spring.ai.mcp.server.protocol: STREAMABLE`, with the trap documented in place. |
| `SecurityConfig` | `DispatcherType.ASYNC` permitted — a defect the protocol flip surfaced (*Decisions* §2). |
| `docs/guides/AGENT-TOOL-AUTHORIZATION.md` | **created** (T6 extends it), closing the coverage hole T1–T4 left. |

The wrapped handler is a **named class** (`RosterFilteringHandler`), not a lambda, so I23 can assert
against the real context that the entry the SDK invokes *is* the wrapper — the `ToolGateInstallationTest`
invariant, for the list path.

## Tests

`./gradlew :example-mcp-server:test` → **127 passing** (99 at T4; +28). `./gradlew build` green.
`opa test infra/opa/policies` → **264/264** (unchanged — T5 touches no policy).

| Case | Where |
|---|---|
| I16 listed == callable, by name · I17 one batch round-trip | `ToolRosterFilterTest` |
| I18 dead PDP (5xx / timeout / connection-refused) ⇒ **empty** roster **and** every call denies · I19 zero-capability agent ⇒ the same empty roster | ″ |
| I20 kill-switch OFF ⇒ unfiltered, gate untouched · I21 listed-then-revoked ⇒ denied at call time | ″ + `RosterFilterSmokeCheckTest` |
| I25 two concurrent callers, different identities ⇒ different rosters, no leak | ″ |
| I26 human token ⇒ ceiling-only from the same rule · I29 gate-OFF ⇒ ceiling-only, asserted **wider** than I16's | ″ |
| I27 wrong-length vector (stub `OpaClient`) ⇒ **empty**; empty registry ⇒ empty, **no** OPA call | ″ |
| I28 unreadable identity / capability outage / unresolvable ceiling ⇒ **unfiltered** + WARN | ″ |
| I30 `nextCursor` + `meta` survive byte-identically · I31 divergent delegate order ⇒ same cut | ″ |
| I22 `POST /mcp` handshake: JSON + `Mcp-Session-Id` header; a follow-up request SSE-framed; a **notification is 202 with an empty body** | `RosterFilterInstallationTest` |
| I23 the map's `"tools/list"` entry IS the wrapping handler, against the real context | ″ |
| I24 smoke-check failure branch ×3 (missing `sessionFactory`, missing `requestHandlers`, missing entry) + the no-streamable-provider case ⇒ startup fails naming the pins | `RosterFilterSmokeCheckTest` |

The OPA stub answers **both** endpoints from one rule set, so I16 is an assertion about the code rather
than a tautology about the fixture.

## Architecture review + refactor

Self-review (no sub-agent). It found **three** things; all fixed in this commit.

1. **Wiring gap — the kill-switch had no test that it actually skips the smoke check.** I20 covered the
   runtime equivalence but nothing proved the escape hatch works, which is the entire reason the switch
   exists. Added `theKillSwitchOffSkipsTheInstallerAndItsSmokeCheckEntirely`: the real
   `ToolAuthorizationConfiguration` + a provider whose pins are gone + `roster-filter.enabled=false` ⇒
   the context **starts** and no installer bean exists.
2. **A dead branch (Sonar S2583).** `requestHandlers()` tested `handlers == null` after `read()`, which
   never returns null — it throws on an absent or null-valued field. Removed, with the invariant stated
   in a comment.
3. **A reachable NPE (Sonar S2259).** `read()` built its final message from a `Class<?>` the loop had
   already walked to `null`. Resolved the type name once, up front.

**Fail-closed — every new edge, named:** roster identity absent ⇒ unfiltered + WARN (the call gate denies
on the same condition, asserted); `DelegationChainException` / `AgentCapabilityUnavailableException` /
`RoleResolutionException` at list time ⇒ unfiltered + WARN; batch all-`false` ⇒ **empty** (authoritative);
wrong-length vector ⇒ **empty**; a `null` vector ⇒ **empty**; an unexpected `RuntimeException` in the
handler ⇒ unfiltered + WARN (runtime posture — a filter bug must not take `tools/list` down); any missing
pin at startup ⇒ **context failure**. No edge returns a *larger* result than success except the three
documented outside-the-batch degradations, where the hint carries no authority.

**Security — the widening that would matter here, and why it cannot happen.** The roster becoming a grant:
`ToolRosterFilter` holds no mutable state, `RosterDecision` is immutable and never stored, and
`ToolCallGate` is untouched — I16 and I21 assert the gate still decides. Index-shift widening: decisions
are applied **by name**, and I31 drives a delegate whose order diverges. Cross-caller leak: the memo is
turn-scoped request state; I25 runs two identities concurrently. Gate-OFF making the roster *narrower*
than calls: fixed by §1 below, asserted by I29. The `ASYNC` permit adds no surface — an async dispatch
only exists for a request that already passed the chain. `git diff --stat` touches only
`example-mcp-server/`; the diff carries no role/capability/acting-as header.

**Concurrency / idempotency.** This ticket gates **no mutation** — the tools are reads over the catalog
REST API and this service has no persistence — so there is nothing to make idempotent. The one shared
mutable structure is the SDK's handler map: written **exactly once**, at startup, before traffic, and
never per session (I23 pins the result). `RosterFilteringHandler` is shared across all sessions and holds
only immutable collaborators. A replayed identical `tools/list` converges on the same roster; a decision
made in one turn is never reused to authorize the next, because the memo dies with the request.

**Static analysis.** `./.sonar-local/sonar-local.sh` → **41 findings on changed files, 0 real.** Baseline
was 37. The delta is one **new by-design class** — S3011 `setAccessible` on
`RosterFilterInstaller`, which *is* the class's documented purpose — plus three test-style instances of
classes already standing (S5778, S5853, S2925). The two findings that were real (S2583, S2259) are fixed
above rather than filed. S3011-on-the-adapter recorded to `quality-gate-sonar`.

## Integration / e2e

`./gradlew :example-mcp-server:test` 127/127. No rig work in this ticket — T6 owns it.

## Decisions

1. **`buildContext` takes `applyAgentNarrowing` as a parameter.** The shipped T4 builder attached the
   agent attributes unconditionally and consulted `agent-gate.enabled` only afterwards, inside
   `authorize()`. Reused as-is by the roster, that would have made the gate-OFF roster **narrower** than
   the gate-OFF call path — hiding tools the caller can successfully call, the one direction a hint must
   never fail in. Both callers now pass the switch, so it is read in exactly one place. (Found by the
   adversarial gate before implementation, not during it.)

2. **`DispatcherType.ASYNC` is permitted in the security chain — a real defect the protocol flip
   surfaced.** The streamable transport answers `tools/list` by *streaming* it, which puts the request
   into async mode and re-dispatches it. This chain is `STATELESS`, so on the ASYNC dispatch there is no
   authentication left and `anyRequest().authenticated()` denied a response that had **already started** —
   aborting a half-written chunked body, which reaches the client as `IOException: chunked transfer
   encoding` rather than a clean 403. The sibling services stream nothing and need no such rule; this is
   transport-shaped, not a posture change, and permitting ASYNC widens nothing because an async dispatch
   can only exist for a request that already passed the chain. **This is exactly what I22 was written to
   catch** — the decomposition's note that "a 401 arrives before routing, so `McpServerSecurityTest` will
   not catch it" was right, and the first real `POST /mcp` in the repo found it within a minute.

3. **The eager-decide / lazy-apply split.** `decide()` runs on the request thread; `apply()` is pure. The
   SDK's handler returns a `Mono` and `map`'s lambda runs at subscription time — assuming that is the
   same thread would make identity resolution depend on an unwritten scheduling detail. Deciding first
   and applying a plain value removes the assumption. (The lab spike measured that the handler *does* run
   inline on the servlet thread; this makes the code independent of that measurement holding at 2.1.)

4. **The defensive wrong-length guard lands on the empty roster, not the unfiltered list.** `OpaClient` is
   an adopter-implementable SPI that deliberately refuses a default implementation so nobody inherits a
   fail-open filter — so the filter does not *assume* the shipped client's totality. Landing on the
   smaller result keeps "no edge widens" true even when the contract below is violated. Reconciled with
   the §4 table by the same amendment that rewrote I27.

## Commit

`feat(mcp): filter tools/list by policy — the roster core, its SDK adapter, and the guide (T5)`
