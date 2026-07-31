---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/rego
  - area/spring
---

# STATUS — T3: Tool-gate policy `agent_tools.rego` + `AgentCapabilitySupplier` (ceiling ∩ capability, in Rego)

**Status:** ✅ DONE

## What shipped

The intersection, computed in Rego, and the tri-state capability seam that feeds it.

| File | What |
|---|---|
| `infra/opa/policies/agent_tools.rego` | **NEW** document, package `agent_tools`, `default allow := false`. `principal_actions` (the ceiling, via the shipped `permissions.effective_actions`) ∩ `agent_actions` → `effective_actions`, plus the category / tool-allow-list / risk narrowings, a human-call rule, and the `bulk` batch entrypoint |
| `infra/opa/policies/agent_tools_test.rego` | **NEW** — 31 `opa test` cases (U18–U30) |
| `identity/AgentCapabilityProfile` | record + `empty()`; wire names pinned to the policy's keys |
| `identity/AgentCapabilitySupplier` | the tri-state seam (resolved / authoritative-empty / throws) |
| `identity/AgentCapabilityUnavailableException` | the outage signal |
| `identity/AgentCapabilityProperties` | `example.mcp.agents.profiles[]` — a **list**, so actor ids survive relaxed binding intact |
| `identity/ConfigAgentCapabilitySupplier` | the demo source; unknown actor → authoritative-empty; source failure → outage |
| `identity/TurnScopedCapabilityCache` | the memo, one answer per actor per **turn** (= one MCP request), outage included |
| `config/AgentCapabilityConfiguration` | wiring — the memo decorates at **bean level**, so the gate and the roster share one answer |
| `tool/ToolDescriptor` (+ declarations) | gains `targetType` — see *Decisions* 1 |
| `application.yml` | three demo agent personas: narrower-than-ceiling, deliberately **wider**-than-ceiling, and zero-capability |

## Tests

`opa test infra/opa/policies -v` → **264/264**, of which **31 are new** `agent_tools_test` cases. Every
pre-existing policy test still passes and the pre-existing documents are **byte-identical** to `main`
(U30 — verified with `git diff --stat`, empty).
`./gradlew :example-mcp-server:test` → **84 passed, 0 failed** (up from 67).

| Case | Covered by |
|---|---|
| **U18** empty input, missing resource, non-tool resource type → deny | `agent_tools_test` |
| **U19** human call allowed inside the ceiling; denied outside it; denied with no grant on the target type | " |
| **U20** agent call allowed when the intersection is non-empty | " |
| **U21** capability omitting the category / the action → deny | " |
| **U22** **capability wider than the ceiling yields the ceiling** — plus the contrast test proving it does not pass for an unrelated reason | " |
| **U23** risk above max → deny (with the low-risk tool passing in the same test); any-of-several tags above max → deny; unknown tag; unknown max; **empty risk-tag list** → deny | " |
| **U24** tool absent from the allow-list → deny even with category, action and risk satisfied | " |
| **U25** empty capability → deny every tool | " |
| **U26** actor present, `agent_capability` absent → deny | " |
| **U27** no `role_definition` → deny (human **and** agent); subject roles alone do not grant; `denied_actions` narrows the ceiling | " |
| **U28** unknown category; action outside its declared category; unknown verb; missing `target_type` → deny | " |
| **U29** `bulk` positional and order-preserving; all-`false` for a zero-capability agent; empty items → empty | " |
| **U13–U15** supplier tri-state: resolved carries every dimension; unknown actor → authoritative-empty; source failure and a malformed profile → outage, never a silent `empty()` | `AgentCapabilitySupplierTest` |
| **U16–U17** memo: one source call per actor per turn; a fresh call next turn (so a revocation lands); no cross-actor bleed; no cross-turn bleed; the **outage replays** within a turn and is retried next turn; pass-through with no turn in scope | `TurnScopedCapabilityCacheTest` |
| wire contract | `AgentCapabilityWireContractTest` — the JSON keys, the round trip, and a cross-check that every key appears in `agent_tools.rego` itself |

## Architecture review + refactor

**Fail-closed.** Every new edge:

| Edge | Failure | Lands on |
|---|---|---|
| tool-gate policy | any input the rules do not satisfy | `default allow := false` |
| `principal_actions` | missing/malformed `role_definition` | empty set (inherited from the shipped expansion — no subject-roles fallback) |
| `principal_actions` | missing `target_type` | undefined → deny |
| `capability` | absent on an agent call | undefined → `effective_actions` undefined → deny |
| capability | authoritative-empty | every dimension empty → deny through the ordinary rules |
| `allowed_tools` | empty | deny **every** tool (an empty allow-list is not "unrestricted") |
| risk | unknown tool tag or unknown `max_risk_tag` | no rank → comparison fails → deny |
| risk | tool declares **no** risk tags | the `count > 0` guard → deny (`every` over an empty array is vacuously true) |
| category | unknown, or action outside its declared category | no expansion → deny |
| supplier | unknown actor | authoritative-empty → deny |
| supplier | source failure | `AgentCapabilityUnavailableException` → caller denies; never a silent `empty()` |
| memo | no turn in scope | pass-through; bookkeeping failures degrade to an extra lookup, never a decision |

**Security — the widenings that would matter here.**
(1) **A capability adding an action the ceiling lacks** — the whole point. It cannot: `effective_actions`
is a set intersection, and U22 asserts an over-broad capability yields the ceiling, with a paired
contrast test proving that assertion is not passing vacuously.
(2) **A Java-side pre-filter drifting from the policy** — there is none; the intersection exists only in
Rego, and the risk *ordering* deliberately lives there too rather than in Java, so there is one place
that decides and it is the auditable one.
(3) **An empty allow-list read as "unrestricted"** — it denies instead, so a profile someone trimmed to
nothing cannot become a profile that permits everything.
(4) **A vacuous `every`** — the empty-risk-tags guard; without it an unclassified tool would sail past
the risk condition. This is the registration-time rule enforced *independently* at the policy layer, so
neither layer is load-bearing alone.
(5) **Cross-turn or cross-actor memo bleed** — keyed by actor inside request-scoped attributes, asserted
both ways; a session-lifetime memo would have made "the roster said I could" outlive a revocation.
(6) **An outage masquerading as zero capability** — distinct signal, distinct code, replayed for the
turn so a turn cannot degrade halfway.
(7) **Extra keys in a policy input** — see the refactor below.

**Concurrency / idempotency.** This ticket gates no mutation. The only shared state is the turn-scoped
memo, keyed by `(actorId, turn)` where the turn *is* the servlet request attributes — so it cannot
outlive its turn or be observed by a concurrent one, and a decision made in turn *n−1* is never reused
to authorize turn *n* (the memo equivalent of `CONCURRENCY-AND-LOCKING` Rules 1–2). `agent_tools.allow`
is a pure function of its input, so a replayed identical call converges.

**Wiring.** `AgentCapabilitySupplier` → consumed by the memo and (T4) the PEP; non-happy paths tested.
`TurnScopedCapabilityCache` → the registered bean, seven cases including two failure modes.
`AgentCapabilityProperties` → consumed by the supplier; malformed config tested. The `bulk` rule's
consumer is T5, named in its comment — it is exercised now by U29 rather than left unproven.

**Boundary / additivity.** `git status infra/` shows exactly two **new** files. `git diff --stat main`
against `catalog.rego`, `category.rego`, `product.rego`, `role.rego`, `team.rego`, `permissions.rego`
and `permission_categories.json` is **empty** — the expansion table is consumed, not changed. Outside
`example-mcp-server/` and `infra/`, nothing.

**Module-layer separation.** The policy is the only place the intersection exists. `…mcp.identity`
gained capability but still resolves no target and makes no decision.

**Pattern reuse.** The supplier copies `RoleDefinitionSupplier`'s tri-state doctrine (ADR 0014) in
spirit and in wording. The memo copies `MemoizingRoleDefinitionSupplier` (ADR 0023) — including
memoizing the outage marker and degrading to pass-through with no request scope — narrowed from request
to turn. `bulk` mirrors the per-type documents' batch primitive byte-for-byte in shape (ADR 0024).
The policy follows the corpus idiom: package-per-document, explicit default deny, `import data.permissions`.

**What the review's own tests found.** `AgentCapabilityWireContractTest` caught Jackson serializing the
`isEmpty()` accessor as a bean property, so every policy input carried a stray `"empty"` key. The policy
ignores it, so nothing was unsafe — but a policy input should contain exactly what the policy reads and
nothing more, or decision logs grow fields nobody can account for. **Refactor applied:** `@JsonIgnore`,
with the reason on the method. Also applied: S6878's record pattern in the memo's outage branch.

**Static analysis.** 38 findings on the changed files; **1 real, fixed** (S6878 — record pattern). The
other 37 are documented by-design classes: S5778×30, S5853 (unchained assertions each carrying their own
explanatory comment), S125, S1075, S112, S4502, S5976, S2925.

## Integration / e2e

`opa test infra/opa/policies -v` is the integration surface for this ticket and it is green at 264/264.
The Java↔Rego contract is covered by `AgentCapabilityWireContractTest`, which reads
`agent_tools.rego` off disk and asserts every wire key appears in it — so a rename on either side fails
a test rather than silently producing "no capability". Live-rig proof is T6.

## Decisions

1. **`ToolDescriptor` gains `targetType`** — a refinement to T1's pinned record shape, and the one
   substantive design change in this ticket. The decomposition says the ceiling is "derived through the
   shipped `permission_categories` expansion", but that expansion is keyed by **resource type**
   (`permissions.effective_actions(role_def, type)`), and a real role definition has `catalog` /
   `category` / `product` keys — no `tool` key. Without a target type the lookup either falls through to
   the `"*"` wildcard or resolves empty, which would make the ceiling either accidental or always-deny.
   Declaring the type each tool reads keeps the derivation on the **shipped** role model with **zero**
   change to it, and has a second payoff: it is the same type the catalog service will gate downstream,
   which is what makes the two-layer composition provable rather than asserted.
2. **`input.action` is a bare verb**, not the `"<type>:<verb>"` form the per-type documents parse. A tool
   declares a verb directly; it carries no resource-qualified action. Stated at the top of the policy so
   the difference from the sibling documents is deliberate and visible.
3. **`allowed_tools` is mandatory; empty denies every tool.** The alternative reading — empty means "no
   tool-level narrowing" — would turn a profile someone trimmed to nothing into one that permits
   everything. There is deliberately no "unrestricted" state to misconfigure.
4. **The risk ordering lives in the policy, not in Java.** A Java-side ordering would be a second source
   of truth free to drift from the one that actually decides. Java declares tags; Rego ranks them.
5. **The tool-declaration integrity check** (`declared_action_in_category`) is new relative to the
   decomposition: the declared verb must belong to the declared category per the shipped table. It is
   what makes an unknown category or a self-inconsistent declaration *unauthorizable* rather than
   accidentally authorized by whichever rule happens to match.
6. **The policy path is `agent_tools`, not `agent_tools/allow`.** The decomposition's T4 default is wrong
   for the shipped client: `HttpOpaClient.allow` POSTs to `/v1/data/<path>` and reads
   `result.<decisionField>`, and `allowAll` POSTs to `/v1/data/<path>/bulk`. With `agent_tools/allow` the
   batch call would hit `/v1/data/agent_tools/allow/bulk`, which does not exist. Recorded here; T4 sets
   the property accordingly.
7. **The demo registry is a list, not a map.** Spring's relaxed binding rewrites map keys, and an actor
   id must round-trip byte-for-byte; a list keeps the id an ordinary value.

## Commit

`feat(policy): add the agent tool-gate — ceiling ∩ capability in Rego (T3)`
