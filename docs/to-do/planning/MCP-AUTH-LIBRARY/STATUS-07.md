---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T7: input schema v1 — jar marker + policy guard, one commit

**Status:** ✅ DONE (2026-10-10, autonomous — part 1 runner)

## What shipped

- **Jar:** `ToolGateInput` (public, `…mcp.authz`) — `SCHEMA_KEY = "schema"`, `SCHEMA_V1 =
  "opa-abac.mcp.tool-gate/v1"`, `environment()` = exactly that one entry. `ToolCallAuthorizer.buildContext`
  sets `environment = ToolGateInput.environment()` (was `Map.of()`), so every single decision **and** every
  roster batch item carries it (the roster builds its contexts through the same method; `HttpOpaClient`
  serializes each item's full `AbacContext`).
- **Policy (`infra/opa/policies/agent_tools.rego`, the only policy touched):** `known_schemas :=
  {"opa-abac.mcp.tool-gate/v1"}` and `known_schema if input.environment.schema in known_schemas`, required by
  `is_tool_request` — so both `allow` rules and, through `allow with input as item`, every `bulk` position refuse
  an unknown, missing or non-string marker; the header's INPUT block gains the field. The decision rules
  themselves are unchanged; the actor stays at `input.subject.attributes.actor`.
- **Policy tests (`agent_tools_test.rego`):** a `v1_environment` constant used by the shared `request(…)` helper
  **and** the two hand-built inputs (the `missing_role_definition` cases), so every existing deny test still
  denies for its own reason; a `with_environment(req, env)` helper (`object.remove` + `object.union` — a plain
  union would merge the marker back in). New cases: positive controls (marked human + agent allowed); unknown
  version ⇒ deny for a human and for an agent; marker missing ⇒ deny for both; `environment` absent ⇒ deny for
  both; non-string / near-miss markers (`true`, a list holding it, an upper-cased copy, a string environment) ⇒
  deny; a bulk with one unmarked item ⇒ `[true, false, true]`.
- **Jar and policy land in this one commit** — splitting them would leave a commit where the rig denies every call.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` — new *The input contract (v1)* section (the field table, the rule that an
  incompatible change bumps the version, the actor's v1 location, that the guarantee holds for v1-aware policies
  only, and to deploy the v1-aware policy with the 1.5.0 jar); the fail-closed table's input-version row. CHANGELOG
  — the schema-v1 line and the **upgrade note** (deploy the v1-aware `agent_tools.rego` with the 1.5.0 jar; an
  older copy keeps working with v1 input but cannot refuse a future version). Module README — *The input contract*.

## Tests

- **U30** (`ToolGateInputTest`, module, the shipped `HttpOpaClient` against an in-process `HttpServer` recording
  each body): a human and an agent decision each POST `/v1/data/agent_tools` with
  `input.environment.schema = "opa-abac.mcp.tool-gate/v1"`; a roster `decide()` POSTs one
  `/v1/data/agent_tools/bulk` whose **every** item (3 of 3) carries it; the environment carries the marker only.
- **U31:** `opa test infra/opa/policies` ⇒ **PASS 459/459** (451 + 8 new), every pre-existing case green with the
  marker in its fixtures.
- **U32 — the guard is load-bearing:** with `known_schema` deleted from `is_tool_request`, **7 named tests fail**
  (`test_a_missing_marker_denies_a_human`, `test_a_missing_marker_denies_an_agent`,
  `test_an_unknown_schema_version_denies_a_human`, `test_an_unknown_schema_version_denies_an_agent`,
  `test_an_absent_environment_denies`, `test_a_non_string_or_near_miss_marker_denies`,
  `test_bulk_with_one_unmarked_item`) — 452/459; restored ⇒ 459/459.
- `opa check infra/opa/policies` clean. (`opa fmt --list` flags `agent_tools_test.rego` — pre-existing: the file at
  `HEAD` before this ticket is flagged too; CI runs `opa test`, not `opa fmt`.)
- Counts: `:opa-abac-mcp:test` **148** · `:opa-abac-spring-boot-starter:test` **156** (2 skipped — T5's deferred U24
  reactive half) · `:example-mcp-server:test` **52** — 0 failures.

## Architecture review + refactor

Inline ★ review (T7 is a headline ticket; the multi-lens path is unavailable inside a part-runner — layer 3
re-covers it), with the rego lens:

- **Fail-closed:** the guard is a conjunct of `is_tool_request`, which both `allow` bodies require, and `bulk` maps
  `allow` — so no path grants without a known marker. Membership (`in known_schemas`) rather than truthiness:
  `true`, a list, a near-miss string and a non-object environment are each pinned to deny (the truthiness class
  that once let `actor = false` escape, Mulch `rego-policy`).
- **No vacuous negatives:** every fixture carries the marker, so the 451 existing cases still deny/allow for
  their own reason; the new cases each have a marked positive control beside them; U32's mutation shows seven
  named tests depend on the guard.
- **Pairing:** the marker is set in the one place every context is built, so the single and batch paths cannot
  disagree; the rig run (E3) proves the jar's marker reaches the policy on both.
- **Boundary:** only `agent_tools.rego` + its test changed under `infra/opa/policies/`; `opa-abac-core` untouched
  (the marker rides the existing `AbacContext.environment`).
- **Static analysis:** local Sonar on files changed vs `origin/main` — 44 findings, all standing documented
  by-design classes; none in a T7 file. Nothing to refactor.

## Integration / e2e

**E3 — jar ↔ policy pairing live.** `ENABLE_MCP=1 ./deploy.sh build` (MCP image rebuilt with the marker),
`ENABLE_MCP=1 ./deploy.sh up --pods 2`, **`docker restart opa-abac-opa`** (the rego edit; the served document
answered `known_schemas = ["opa-abac.mcp.tool-gate/v1"]` afterwards), then `./run-agent-tool-matrix.sh` ⇒ **exit 0
— "E1-E8, E10, E11 and E12 green through the gateway"**: 10 newman runs, **62 requests, 92 assertions, 0
failures**. Every allow cell (single decisions) and every roster cut (the bulk path) passing proves the marker
reaches the policy on both paths — with an unmarked jar the v1-aware policy would have denied them all.

## Decisions

- The marker constant lives in a small public `ToolGateInput` class (the ticket's "or equivalent") with an
  `environment()` factory, so an adopter building contexts for the direct API can mark them the same way.
- An unknown marker yields `tool-gate-denied` (the policy's own "no"), not a new code: it is a policy answer,
  and the PEP cannot tell it apart without a policy round-trip — by design (ADR 0036 §7).
- No seam deviation: `object.remove` / `object.union` behave as relied on (the helper's purpose is covered by the
  missing-marker cases failing under the U32 mutation).

## Commit

This commit — `feat(mcp): tool-gate input schema v1 — the jar marks every input, the policy refuses what it does not know (MCP-AUTH-LIBRARY T7)`.
