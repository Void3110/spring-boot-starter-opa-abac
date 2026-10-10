---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T5: the gate on all four specification types; reactive and unwrappable configurations fail startup

**Status:** ✅ DONE (2026-10-10, autonomous — part 1 runner) — one proof deferred (U24's reactive half, PKIX; see *Decisions*)

## What shipped

- **Three wrappers** in `…mcp.authz`, beside `ToolCallGate`, each with a public `gate(spec, authorizer)` (the
  direct API for hand-built specifications): `AsyncToolCallGate` (`McpServerFeatures.AsyncToolSpecification`),
  `StatelessToolCallGate` (`McpStatelessServerFeatures.SyncToolSpecification`) and `StatelessAsyncToolCallGate`
  (`McpStatelessServerFeatures.AsyncToolSpecification`). Record signatures confirmed by `javap` on
  `mcp-core-2.0.0.jar` — as the ticket named them, no deviation.
- **One invocation rule** (`ToolGateInvocation`, package-private) shared by all four wrappers: decide on
  `apply`, on the invoking thread, before any `Mono` exists; deny ⇒ the advisory (`Mono.just(…)` for the async
  ones); allow ⇒ the body (sync) or the delegate's `Mono` **decorated**: an emitted `ToolInvocationException` ⇒
  the same advisory a thrown one gives; an error result relabelled from `ToolFailureRecord` read **at emission**
  (absent request ⇒ unrelabelled); any other emitted error and an empty completion pass through unchanged.
  `ToolCallGate`'s body moved into the rule unchanged (its moved test, `ToolCallGateTest`, green untouched).
- **Starter:** `McpToolSpecifications` knows the four types (`isSupported`, `isGated` — the handler must be
  *that type's* gate — `toolName`, `gate`); `McpToolGatePostProcessor` wraps every element of all four, records
  every tool name across them (T4's union), and still fails startup on any other tool-bearing element (now the
  "not one of the four" rule, `SUPPORTED` lists all four); the verifier's gated-element assertion accepts all
  four gates.
- **WebFlux ⇒ startup failure:** a nested `@ConditionalOnWebApplication(type = REACTIVE)` configuration in
  `OpaMcpAutoConfiguration` contributes a `ReactiveWebApplication` marker, and `McpStartupVerifier` refuses it
  as its **step 0**, in its one eager callback, naming WebFlux and "not supported in v1" (see *Decisions*).
- **Non-web ⇒ deny-all:** no new code — an stdio/plain context starts and every wrapper denies
  `tool-gate-unauthenticated` (U25 pins it).
- **Build:** the starter's tests gain `spring-webflux` **only** on CI or with `-PwithWebflux` (see *Decisions*);
  PIT's starter run is restricted to `*Test` (the new `*IT` classes each boot a real server) — root build +
  root `CLAUDE.md`'s PIT paragraph.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` — new *Supported configurations* section (the four types and their
  wrappers, each proven over a real `POST /mcp`; decide-on-invocation and the async decoration contract; WebFlux
  out; stdio deny-all; `ASYNC` needs reactive-returning tools; the `immediateExecution(true)` reliance and the
  customizer trap with its fix; the post-startup `addTool` limit; the example installer is streamable-only), the
  startup table (unwrappable row restated, WebFlux row), the installing paragraph ("all four SDK types"), the
  fail-closed table's new caller-identity row. Module README (the four adapters, WebFlux). CHANGELOG line (all
  four gated, WebFlux and unwrappable types fail startup, stdio is deny-all). The auto-configuration's javadoc.

## Tests

- **U20 / U22** (`ToolGateWrappersTest`, module, one parameterized set over both async wrappers): deny ⇒ the
  advisory with `_meta` layer + code, `structuredContent` null, delegate never invoked (subscribed twice);
  allow ⇒ the delegate's result; the authorizer asked **once at `apply`**, never at subscription (a counting
  authorizer, the deferred body subscribed twice and run twice); a synchronously thrown and an emitted
  `ToolInvocationException` ⇒ the same advisory; a deferred body recording a `ToolFailureRecord` ⇒ relabelled
  at emission; no request bound at emission ⇒ unrelabelled; another emitted error ⇒ re-thrown as the same
  instance; empty ⇒ empty; a clean result with a stale record ⇒ untouched.
- **U21:** stateless sync deny (body never run) / allow / `ToolInvocationException`.
- **U25:** module — the real `ToolCallAuthorizer` (an `OpaClient` that fails the test if asked), empty
  `SecurityContextHolder`, each of the four wrappers ⇒ `tool-gate-unauthenticated`, no body run; starter — the
  plain (non-web) context with one tool of each type starts, each wrapped handler invoked ⇒
  `tool-gate-unauthenticated`, no body run, the OPA stub never asked.
- **U23** (`OpaMcpAutoConfigurationTest`; U35's three SDK-type cases **rewritten** into wrapping assertions, by
  design): async-stateful, stateless-sync, stateless-async lists now start, each element carrying its type's
  gate; a test-local `tool()` record ⇒ fails naming it, "cannot wrap", and the four supported types; one known +
  one unknown element in one list ⇒ fails naming the bean and the unknown type; a list whose elements have a
  `tool()` method not returning `McpSchema.Tool` ⇒ untouched (same instance); all four types in one context ⇒
  starts, the declarations check counting the union. U36's lazy "unsupported list nothing injects" case now uses
  the unknown type (the async type it used is supported now).
- **U24** (`OpaMcpReactiveGuardTest`): servlet half green (`WebApplicationContextRunner`, complete baseline ⇒
  starts, no marker). Reactive half (`ReactiveWebApplicationContextRunner` ⇒ fails naming "reactive (Spring
  WebFlux)" + "not supported in v1"; the same under real lazy initialization, probe uncreated) — **skipped
  locally** (no `spring-webflux`), deferred per protocol; on CI it fails rather than skips if the artifact is
  missing.
- **Mutation-checked:** moving the decision inside the `Mono` (`Mono.defer`) fails
  `theAuthorizerIsAskedOnceAtApplyNeverAtSubscription` for both async shapes (restored).
- Counts: `:opa-abac-mcp:test` **129** · `:opa-abac-spring-boot-starter:test` **154** (2 skipped — U24's reactive
  half) · `:example-mcp-server:test` **52** — 0 failures. `./gradlew --offline build` green (default line, no
  uncached artifact required).

## Architecture review + refactor

Inline ★ review (T5 is a headline ticket; the multi-lens path is unavailable inside a part-runner — layer 3
re-covers it):

- **Fail-closed:** every tool-bearing element is either one of the four types (wrapped, recorded, asserted
  gated *by its own type's gate*) or a startup failure — checked on every element; the verifier still asserts
  every resolved list. A reactive application fails first (step 0). A configuration that runs handlers off the
  request thread (I6) denies every call — never an allow without identity. A `null` `Mono` from a delegate is
  passed through as-is (what the server would get without the gate); the decision was already a permit.
- **Security:** no new widening — the wrappers call the unchanged `ToolCallAuthorizer`. Residual noted for
  layer 3: the async relabel reads `ToolFailureRecord` from the thread that *emits*; a body that completed on a
  thread bound to a different request could mislabel (or consume) that request's record — labelling only,
  error-to-error, never a success. The design pins "read at emission"; not changed.
- **Concurrency:** decide-on-invocation proven (counting authorizer, double subscription; mutation-checked);
  the decoration reads no identity. Startup guards stay eager (verifier callback; U36 lazy cases green).
- **Refactor from the review + Sonar:** the four wrappers' duplicated decide/relabel/advisory logic extracted
  into `ToolGateInvocation` (one rule, four shapes); Sonar's three real findings fixed — the log category is now
  `ToolGateInvocation` (S3416; nothing in the repo keys on the old one), the reactive marker an `enum` instead of
  an empty record (S2094), a needless `throws Exception` in the IT app (S1130).
- **Module-layer separation:** the module still declares no beans; the starter adds a nested detection
  configuration only; its public API is unchanged (`OpaMcpAutoConfiguration` + the `.imports` line).
- **Static analysis:** local Sonar on files changed vs `origin/main` — after the fixes 44 findings, only the standing
  documented by-design classes listed in STATUS-02/04 (S5778, S5853, S1168, S2925, S3011, S125, S1075, S5976 —
  Mulch `quality-gate-sonar` mx-302e78 / mx-6fd6b3 / the S1075 record); none in a T5 file.

## Integration / e2e

**I2–I6 green over a real `POST /mcp`** (`opa-abac-spring-boot-starter/src/test/…/autoconfigure/mcp/`,
`@SpringBootTest(RANDOM_PORT)`, a minimal app with the starter's `AbacFilter` in its chain and an in-process
OPA `HttpServer` recording every input): `SyncStreamableToolGateIT` (I2), `AsyncStreamableToolGateIT` (I3),
`SyncStatelessToolGateIT` (I4), `AsyncStatelessToolGateIT` (I5) — each: the server is built from that
configuration's type with its gate on the handler (non-vacuity case); deny ⇒ `isError` with
`_meta.layer = tool-gate`, `_meta.code = tool-gate-denied`, body counter 0; allow ⇒ body counter 1; the
recorded OPA input's `subject.id` is the bearer's `sub` (the identity was on the deciding thread).
**All four types are supported** — no type moved to the startup-failure row, so ADR 0036 §13 needs no
amendment. `OffThreadCustomizerToolGateIT` (I6): a `@Primary McpSyncServerCustomizer` without
`immediateExecution` ⇒ `tool-gate-unauthenticated`, body 0, OPA never asked (measured fact re-confirmed).
No rig run in this ticket (T6/T7).

## Decisions

- **The reactive guard is verifier step 0, fed by a nested-configuration marker** — not a separate throwing
  `SmartInitializingSingleton` as the ticket sketched. The orchestrator's brief places the WebFlux guard "in the
  same ordered `McpStartupVerifier` callback"; this satisfies both: the detection is still Boot's own
  `@ConditionalOnWebApplication(type = REACTIVE)` (measure item 2), and the failure runs first in the one eager
  callback, so no less relevant step can pre-empt it and no registration order is relied on.
- **Seam deviation — `spring-webflux` is conditional, not plain `testImplementation`.** The ticket's
  unconditional `testImplementation("org.springframework:spring-webflux")` would make the default
  `./gradlew build` **require** an artifact this host cannot resolve (verified:
  `dependencyInsight … -PwithWebflux` ⇒ `spring-webflux:7.0.9 … PKIX path building failed`). It is added when
  `CI` is set (GitHub Actions always sets it — so the PR build and T8's compat step run U24) or with
  `-PwithWebflux`; `OpaMcpReactiveGuardTest` skips without it locally and **fails** without it on CI.
- **LOCAL PROOF DEFERRED:** PKIX — spring-webflux. The maintainer runs, outside the sandbox:
  `JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=KeychainStore-ROOT -Djavax.net.ssl.trustStore=NONE" ./gradlew :opa-abac-spring-boot-starter:test -PwithWebflux --no-daemon`
  (the whole starter suite, so `spring-webflux` on the classpath is also proven not to disturb the other
  cases); expected: `OpaMcpReactiveGuardTest` 3/3, 0 skipped. Otherwise CI on the PR to `main` is the gate.
- **Per-test tool beans are top-level classes** (`SyncProbeTool`, `AsyncProbeTool`): a `@Configuration` nested
  inside the `@SpringBootConfiguration` test app is always registered as a member class (measured:
  `BeanDefinitionOverrideException` on `probe`).
- **Log category:** the four wrappers log under `…mcp.authz.ToolGateInvocation` (was `…ToolCallGate` for the
  sync wrapper's lines) — Sonar S3416; nothing in the repo (rig, scripts, configs) keys on the class name.
- **PIT:** the starter joins `mutationUnitTestOnlyModules` (`--targetTests …Test`), so the five real-server
  `*IT` classes stay out of the per-mutant loop.
- Seams verified, no deviation: the four record signatures (`javap`), `McpSyncServerCustomizer.customize(
  McpServer.SyncSpecification<?>)` and `SyncSpecification.immediateExecution(boolean)` (`javap`), the
  stateless transport answering on `/mcp` by default, the `ASYNC` scanner serving the `Mono` tool.

## Commit

This commit — `feat(mcp): the gate on all four tool-specification types; WebFlux and unwrappable types fail startup (MCP-AUTH-LIBRARY T5)`.
