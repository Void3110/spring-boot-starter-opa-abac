---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T6: the `agent-clients` guard (`tool-gate-actor-required`) + matrix cell E12

**Status:** ✅ DONE (2026-10-10, autonomous — part 1 runner)

## What shipped

- **`opa.abac.mcp.identity.agent-clients`** (`IdentityProperties`, `List<String>`, default empty).
- **`ActorRequiredException`** (`…mcp.identity`, a plain `RuntimeException` — deliberately *not* a
  `DecisionIndeterminateException`: a deterministic deny, not "could not decide"), carrying the client id only.
- **The guard, in the delegation step** (`ToolCallAuthorizer.buildContext`, right after the extractor): `azp`
  (read from `subject.attributes`, a `String`) listed **and** the chain not an agent call ⇒
  `ActorRequiredException` ⇒ `authorize` denies **`tool-gate-actor-required`** (layer `tool-gate`) with its own
  WARN naming the client, before any policy call. A malformed claim still throws first
  (`tool-gate-identity-unreadable`). It runs **regardless of `agent-gate.enabled`**. An empty list never runs
  it. `ToolCallAuthorizer` gains a 7-argument constructor taking the list; the 6-argument one stays and means
  "no guard" (every existing call site unchanged). The starter's bean passes `IdentityProperties.getAgentClients()`.
- **The roster:** the same exception at list time ⇒ **unfiltered + WARN** (the identity-edge class); the call
  path denies every tool. `ToolRosterFilter.decide()`'s batch half extracted into `decideInOneBatch` (Sonar
  S3776 — the new catch tipped its complexity to 16).
- **`ActorClaimWiringCheck` extended:** `agent-clients` non-empty and `azp` not in
  `opa.abac.subject.attribute-claims` ⇒ startup failure naming `azp` and both properties.
- **E12 (matrix cell):** `infra/compose.mcp.yaml` gains `OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS:
  "${MCP_AGENT_CLIENTS:-}"` and `OPA_ABAC_SUBJECT_ATTRIBUTE_CLAIMS: "${MCP_SUBJECT_ATTRIBUTE_CLAIMS:-act_chain}"`
  (defaults = today's pod: an empty list and the claim `application.yml` already copies);
  `run-agent-tool-matrix.sh` runs the drill after E11 (recreate with `MCP_AGENT_CLIENTS=catalog-gateway
  MCP_SUBJECT_ATTRIBUTE_CLAIMS=act_chain,azp`, folder `E12 — …`, then an explicit recreate on the default profile
  and folder `E12-restore — …`; the EXIT trap still restores the rig); the collection gains the two folders
  (`mcpDenied(…, 'tool-gate', 'tool-gate-actor-required')` by value; the agent-readonly allow also asserts the
  granted catalog id came back; the restore allow likewise). No realm change.
- **Docs:** `AGENT-TOOL-AUTHORIZATION.md` — the claim-state table's "`azp` listed, no actor" row; a paragraph on
  the guard (precedence, independent of the agent gate, the `azp` wiring requirement, the roster outcome, the
  residual "a user handing their own token to an agent is a human call"); the fail-closed table row; a new
  **codes table** under *Denials are structured* (all eight tool-gate codes incl. `tool-gate-actor-required`,
  carried in `_meta`); the matrix section's drills paragraph gains E12. `scripts/postman/README.md` registers the
  drill (four drills now). Module README property row. CHANGELOG line for `opa.abac.mcp.identity.agent-clients`.

## Tests

- **U26** (`AgentClientsGuardTest`): an empty list builds a context **equal** to the 6-argument (pre-guard)
  authorizer's, `subject.attributes` = only `azp` (human path, no new key).
- **U27:** listed + no actor ⇒ `tool-gate-actor-required`, layer `tool-gate`, no OPA call, a WARN naming the
  client, `agent-clients` and "no actor claim" (logback `ListAppender`; logback moved to the module's test
  compile path for it); the same with **agent-gate OFF**; listed + a valid actor ⇒ the agent path (actor +
  capability in the input); unlisted + no actor ⇒ the human path (allowed, no `actor`); listed + a **malformed**
  actor ⇒ `tool-gate-identity-unreadable`. Extra: a non-string `azp` is never "listed"; the exception carries the
  client id and no token content.
- **U28:** `ActorClaimWiringCheckTest` — listed without `azp` ⇒ fails naming `'azp'` and both properties; with
  `azp` ⇒ passes; empty list ⇒ `azp` not required. `McpPropertiesBindingTest` — an **empty env var**
  (`SystemEnvironmentPropertySource`, `OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS=""`) binds to an **empty list** (never
  `[""]`); `catalog-gateway,other-client` binds both; default empty. Starter (`OpaMcpAutoConfigurationTest`) —
  the context fails startup without `azp` copied; with it, the context's `ToolCallAuthorizer` denies a listed
  client without an actor (`tool-gate-actor-required`, no OPA request) — the property really reaches the bean.
- **U29:** roster ⇒ `isUnfiltered()`, no batch spent, a WARN starting "Roster unfiltered" naming the client,
  while `authorize` denies `tool-gate-actor-required` — and the same with the agent gate OFF.
- Counts: `:opa-abac-mcp:test` **145** · `:opa-abac-spring-boot-starter:test` **156** (2 skipped — T5's deferred
  U24 reactive half) · `:example-mcp-server:test` **52** — 0 failures. `check-shell-guards.py` (28 clean),
  `check-collection-conformance.py` (18 clean), `bash -n` on the runner.

## Architecture review + refactor

Inline ★ review:

- **Fail-closed / precedence:** the guard sits after the extractor (malformed stays identity-unreadable, pinned)
  and before the capability and ceiling lookups and the OPA call, so a deterministic deny spends nothing. With the
  list non-empty and `azp` not copied, startup fails — a guard that could never fire is refused, not shipped.
- **Every reader of the switch agrees:** `agent-clients` is read from the one `IdentityProperties` bean by the
  authorizer (the call path **and** the roster — the roster builds its contexts through the same `buildContext`)
  and by the wiring check; U27/U29 cover the agent-gate OFF case for both paths.
- **Security:** no widening — the guard only adds a deny; a non-string `azp` never matches; the WARN and the
  exception carry the client id only. Residual (documented): a token from a listed client that somehow lacks
  `azp` entirely is not matched (an IdP always sets `azp`; the gateway validates the token). Note for adopters:
  an adopter-defined `ToolCallAuthorizer` bean built with the 6-argument constructor has no guard — the wiring
  check still runs; the javadoc says the 6-argument form means "no guard".
- **Refactor:** `ToolRosterFilter.decide()` split (S3776); two test-only Sonar items fixed (S4144 identical test
  bodies, S5778 lambda). Nothing else substantive.
- **Static analysis:** local Sonar on files changed vs `origin/main` — 44 findings, all the standing documented
  by-design classes (S5778, S5853, S1168, S2925, S3011, S125, S1075, S5976 — Mulch `quality-gate-sonar`); none
  new in a T6 file.

## Integration / e2e

**E2 / matrix cell E12 green on the live rig.** `ENABLE_MCP=1 ./deploy.sh build` (MCP + catalog images rebuilt
from this tree), `ENABLE_MCP=1 ./deploy.sh up --pods 2`, then `cd scripts/postman && ./run-agent-tool-matrix.sh`
⇒ **exit 0 — "E1-E8, E10, E11 and E12 green through the gateway"**: 10 newman runs, **62 requests, 92
assertions, 0 failures** (part 0's 53/78 plus E12's 6/9 and E12-restore's 3/5). E12: the human token
(`catalog-gateway`, no actor mapper) on `tools/call get_catalog` ⇒ `tool-gate-actor-required` by value; an
`agent-readonly` token on the same pod ⇒ allowed, the granted catalog returned; after the default recreate the
human token is allowed again. The default pod (empty `agent-clients` from the empty interpolated env var) booted
and served E1–E11 unchanged — the empty value bound to an empty list, not `[""]` (which would have failed the
wiring check at boot). The EXIT trap restored the rig.

## Decisions

- **`ToolCallAuthorizer` keeps its 6-argument constructor** (= no guard) beside the new 7-argument one, so every
  existing call site and adopter code compiles unchanged; only the starter's bean passes the list.
- **E12 has an explicit restore folder** (`E12-restore`) rather than relying on the EXIT trap alone: the QA case
  asserts "after the trap the default pod answers the human token normally", and an assertion needs a request.
- The guard reads `azp` from the subject attributes (where the starter copies it) as a `String`; any other type is
  "not listed".
- Seams verified, no deviation: Boot's env-var binding of `OPA_ABAC_MCP_IDENTITY_AGENT_CLIENTS` /
  `OPA_ABAC_SUBJECT_ATTRIBUTE_CLAIMS` (binder test + the live pod); the realm's `catalog-gateway` token carries
  `azp` and no `act_chain` (the runner's preflight + E12's live deny).

## Commit

This commit — `feat(mcp): the agent-clients guard — a listed client without an actor is refused (MCP-AUTH-LIBRARY T6)`.
