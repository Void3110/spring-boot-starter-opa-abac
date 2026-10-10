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

Recorded in the part-review commit that follows T4 (see below — this section is completed there).

## Commit

This commit — `feat(mcp): declarations are checked against what the gate wrapped; part 0 closes on the rig (MCP-AUTH-LIBRARY T4)`.
