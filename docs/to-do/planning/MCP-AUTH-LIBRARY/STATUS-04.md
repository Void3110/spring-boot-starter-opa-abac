---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T4: declarations validated against what was wrapped; part 0 closes on the rig

**Status:** ✅ DONE (2026-10-10, autonomous — part 0 runner; part 0 closed)

## What shipped

- **Wrap-time validation.** `McpToolGatePostProcessor` records the name of every tool it gates (wrapped, or
  found already gated) across **all** specification lists — the union. `McpStartupVerifier` gains **step 3** in
  the same callback, after step 2 resolved every singleton list (so a lazily-defined list is wrapped and counted,
  never missed, and no callback ordering is relied on): no `ToolRegistry` bean ⇒ startup failure naming it;
  advertised-but-undeclared ⇒ failure; declared-but-never-wrapped ⇒ failure; the message carries both sorted
  lists plus the full declared and wrapped sets. An empty registry with no tools starts. T3's verifier was
  extended — no second startup bean.
- The call-time `tool-undeclared` deny stays (the moved `ToolCallGateTest#deniesAnUndeclaredTool`); the
  `ToolCallAuthorizer` comment now says the startup check (not the deleted validator) makes it unreachable for
  wrapped tools.
- **Deleted:** the example's `ToolRegistryValidator` + `ToolRegistryValidatorTest` (replaced by U17);
  `ToolCallClassifier` and `McpToolSurfaceTest#shipsTheClassifierSeamWithNoImplementation` — retired, not
  replaced (ADR 0036 §11). `ToolRegistrationConfiguration` keeps only the registry; `CatalogTools`,
  `McpToolSurfaceTest` and `RosterFilterSmokeCheckTest` javadoc no longer name the removed types.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` — the declarations paragraph (registry, both-directions startup check,
  the call-time backstop, the `addTool` / customizer limit, the unsupported registrations: prototype- or
  scope-bound lists, `FactoryBean`-produced lists, `registerSingleton` lists — the last caught), the startup
  table's registry row, and the fail-closed table's tool-metadata row ("…checked against the specifications
  actually wrapped, both directions"). `CHANGELOG.md` — the declarations check and the unshipped classifier.

## Tests

- **U17** (`OpaMcpAutoConfigurationTest`, baseline-paired fixtures): wrapped ⊋ registry ⇒ fails listing `[b]`
  as undeclared; registry ⊋ wrapped ⇒ fails listing `[phantom]`; names spread over two lists equal to the
  registry ⇒ starts; no `ToolRegistry` ⇒ fails naming it; empty registry + no tools ⇒ starts.
- **U37** (real lazy initialization, probe asserted uncreated): a two-tool registry and a matching list nothing
  injects ⇒ starts, both handlers gated; a third declaration ⇒ fails naming `[third]`.
- **Mutation-checked:** removing the step-3 call fails exactly the four declaration-mismatch / missing-registry
  cases (restored).
- **U18:** the moved call-time case green. **U19:** `git grep -n 'ToolCallClassifier\|ToolRegistryValidator' -- '*.java'`
  returns nothing; `McpToolSurfaceTest`'s remaining three cases green.
- Build-breakers: none — every T3 fixture already followed the QA baseline (lists only with their declarations).
- Counts: `:opa-abac-mcp:test` 103 · `:opa-abac-spring-boot-starter:test` 133 · `:example-mcp-server:test` 52
  (57 − the validator test's cases − the retired classifier case) — 0 failures. `./gradlew build` green.
  `check-shell-guards.py` (28 clean) and `check-collection-conformance.py` (18 clean) green.
- The slice's retired-name grep (`01-DECOMPOSITION` cross-cutting acceptance) returns nothing.

## Architecture review + refactor

Inline ★ review of the ticket's diff: the declarations check lives in the one verifier callback, after list
resolution, so lazy lists are counted (U37) and a registry without a tool behind it cannot pass; the recorded set
holds only names that passed the post-processor, so a specification registered outside the singleton list beans
(`addTool`, a customizer) can never satisfy a declaration — it fails startup instead of serving ungated under a
"declared" name. Message carries both lists, no source data. Nothing to refactor. Static analysis: local Sonar on
changed files — 44, all documented by-design classes (STATUS-02's list, plus S1075 on `CatalogTools`' downstream
API path constant, mx-ccd85c — the file is "changed" only by its javadoc).

## Integration / e2e

**E1 — part 0 on the rig, nothing changed.** `ENABLE_MCP=1 ./deploy.sh build` (catalog + MCP images rebuilt
from this tree), `./profile.sh up`, `ENABLE_MCP=1 ./deploy.sh up --pods 2`, then
`cd scripts/postman && ./run-agent-tool-matrix.sh` ⇒ **exit 0 — "E1-E8, E10 and E11 green through the gateway"**
(matrix cell E9 is the suite-level "pre-existing runners green" acceptance, not a cell of this runner):
8 newman runs, **53 requests, 78 assertions, 0 failures**, including the E6 (OPA stopped mid-suite), E7 (agent
gate OFF) and E11 (readonly capability revoked) drills; the EXIT trap restored the rig. The MCP pod's log shows
`Tool-gate installed on 4 tool specification(s) in 'toolSpecs'` from the starter's post-processor and the
example's roster installer installed after it.
`git diff --exit-code "$(git merge-base main HEAD)" HEAD -- scripts/postman/agent-tool-matrix.postman_collection.json`
⇒ exit 0 (the collection is unchanged since the slice began).
The runner's advisory NOTE ("this branch changes code the tool surface treats as 'the shipped target layer'")
listed only `opa-abac-spring-boot-starter` files — **expected from T3 on**: the change is additive (a new
auto-configuration, one `.imports` line, test-only dependencies).

## Decisions

- **Rig left up** after part 0 (catalog ×2, user-service ×2, MCP, Keycloak, APISIX, OPA, Jaeger, Postgres): part 1's
  T6/T7 run the same matrix on a rebuilt image, and `deploy.sh build` + `up` rebuild/refresh it anyway. Tear down
  with `./deploy.sh down && ./profile.sh down` if not needed.
- E1 ran against an image built from this ticket's working tree (the commit content), before the commit itself.

## Part review (layer 2)

**Path:** the 2A lens set applied **inline** in this part-runner's own context (the deep-review path table's
inside-a-subagent row — no review sub-agent, 2B unreachable). **Downgrade recorded:** T3 is a headline ticket
and got inline-2A instead of the multi-lens path; the whole-delivery layer-3 `/deep-review` re-covers it.
**Scope:** part 0 as one diff, `338baa8..` (T1–T4 + the Mulch sync) — the new module, the starter's
auto-configuration, the example's shrink, rig/config renames, docs.

**Lenses run:** fail-closed · security (widening, cross-subject caches, routing, log/body leaks) · concurrency /
threading · wiring (every new seam has a consumer and a non-happy-path test) · core boundary · additivity ·
rego (none touched) · autonomous-run (laziness, self-preferential bias, goal drift).

**Findings — two, both fixed in the part-review commit (re-tested: the three modules' tests, `./gradlew build`,
Sonar, and the agent-tool matrix again on a rebuilt image — 8 runs, 53 requests, 78 assertions, 0 failures):**

1. **Medium (fail-closed edge) — the scoped-proxy exemption trusted the bean *name*.** Both the gate
   post-processor and the verifier's step 2 skipped any `List` bean that had a `scopedTarget.<name>` bean
   definition. A *singleton* tool list whose name merely had such a namesake would have been passed through
   un-iterated and skipped by the verifier — served without the gate (the declarations check would then fail
   only if the tool was declared). Fix: `McpToolSpecifications.isScopedProxy` now requires the structure
   `ScopedProxyUtils` registers — the bean's merged definition must *decorate* the `scopedTarget.<name>`
   definition — shared by both sites (sibling sweep: both readers of the rule changed together). New case
   `aScopedTargetNamesakeDoesNotExemptASingletonToolList` (mutation-checked: a name-only rule fails it); the
   real `@RequestScope` proxy case (U36) still starts.
2. **Low (cache isolation) — the two request memos keyed their request attribute per class, not per
   instance.** Two decorated ceiling (or capability) sources in one application would have read each other's
   answers for the same `(subject, type)` / actor within a request — cross-*supplier*, never cross-caller. Fix:
   a per-instance attribute in `MemoizingPrincipalCeilingSupplier` and (sibling sweep) in
   `TurnScopedCapabilityCache`; one bean's consumers (the roster and the gate) still share one memo. New
   cases `twoDecoratorsInOneRequestKeepSeparateMemos` / `twoCachesInOneTurnKeepSeparateMemos`. The same
   per-class key exists in `opa-abac-spring-security`'s `MemoizingRoleDefinitionSupplier` — pre-existing
   library code outside this slice's boundary (no existing module changes), left as is and noted here for
   layer 3.

**Checked and clean:** `opa-abac-core`, `opa-abac-spring-security`, `opa-abac-spring-data`,
`opa-abac-keycloak-directory` and `infra/opa/policies/` untouched since the part began; the starter's main
diff is additive only (four new package-private/public classes + one `.imports` line); the module's POM has
`mcp-core:2.0.0` compile and no Spring AI; the package index and STATUS-05..08 untouched; the CHANGELOG entry
sits under the existing `[Unreleased] — 1.5.0` heading. Fail-closed: every request-time path keeps its
code (call-site attribution pinned by an adopter-subtype case), every misconfiguration that could serve a
tool ungated in part 0 fails startup, eagerly (U36/U37, three verifier steps mutation-checked). Threading:
no new code decides off the calling thread (the gate is the moved `ToolCallGate`). Wiring: every new seam
(`PrincipalCeilingSupplier`, the memo, the decorator, the verifier, the post-processors, `ExampleAuthzProperties`,
the `opa.abac.mcp.*` properties) has a named consumer and a non-happy-path test. Autonomous-run lens: each
STATUS note's review section names what it changed (T2: two Sonar-driven reverts; T3: the switch-reader
alignment) — no ritual "nothing found" over a diff that says otherwise; no ticket's acceptance left partial.
No finding reaches an earlier part (none exists — part 0).

## Commit

`5464c21` — `feat(mcp): declarations are checked against what the gate wrapped; part 0 closes on the rig (MCP-AUTH-LIBRARY T4)`;
the layer-2 fixes and this record: `fix(mcp): part-0 review — scoped-proxy exemption by structure, per-instance memos (MCP-AUTH-LIBRARY part 0)`.
