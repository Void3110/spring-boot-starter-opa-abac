---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T6: Rig + scripted-client e2e + guide + folder move

**Status:** ✅ DONE

## What shipped

Two halves, committed separately: the **rig** (which is what surfaced the two defects below), then
the **e2e + docs**.

### Rig

| File | What it is |
|---|---|
| `example-mcp-server/Dockerfile` | multi-stage build, mirroring the user-service's. |
| `infra/compose.mcp.yaml` | the `mcp` service on the shared network (host port 28093, diagnosis only). **All three outbound base-URLs overridden in-network**, the three demo agent profiles, and the two kill-switches + the readonly profile's four dimensions interpolated for the drills. |
| `deploy.sh` | `ENABLE_MCP=1` (force-enables `ENABLE_OIDC` + `ENABLE_OPA` + `ENABLE_USER_SERVICE`), the image build, the health wait, the status line, and the flag passed through to `init-routes.sh`. Default rig unchanged. |
| `infra/apisix/init-routes.sh` | the `mcp-pool` upstream + the `mcp` route (`/mcp*`, **priority 65**) with the **user-service** plugin set and deliberately **not** the catalog `opa` plugin. |
| `infra/keycloak/realm-export.json` | three clients — `catalog-agent-{readonly,overreach,revoked}` — each with a hardcoded-claim mapper minting `act_chain` for that actor. |

### e2e + docs

| File | What it is |
|---|---|
| `scripts/postman/agent-tool-matrix.postman_collection.json` | **the deterministic scripted MCP client** — eight folders (E1/E10, E2/E3/E8, E4, E5, E6, E6-restore, E7, E11), real streamable framing, shared parse helpers in a collection variable. |
| `scripts/postman/run-agent-tool-matrix.sh` | the standalone runner: preflight, `bbbb…` fixtures, the four tokens, the E5 **operator check**, the main pass, then the three rig **drills** — with an EXIT trap that restores the rig however the run ends. |
| `scripts/postman/README.md` | the runner-table row + **two** fixture-registry rows (`bbbb…bbbb` granted, `bbbb…bbbc` foreign). |
| `docs/guides/AGENT-TOOL-AUTHORIZATION.md` | **extended** (T5 created it): the rig section, the proven-cut table, and *Two things only the rig could show*. |
| [[POC-ROADMAP]] · ADR index · `0028` · root `CLAUDE.md` | Phase 9 ticked; ADR 0028 `Accepted (planned)` → `Accepted — shipped`; the `ENABLE_MCP` rig line added. |

## Tests

| Gate | Result |
|---|---|
| `./gradlew build` | ✅ green (T6 part 2 adds no Java — the suite is T5's, unchanged) |
| `:example-mcp-server:test` | **129**, 0 failures |
| `opa test infra/opa/policies` | **264/264** |
| `scripts/postman/run-agent-tool-matrix.sh` | ✅ **49 requests / 73 assertions, 0 failures** |
| Local Sonar (changed files vs `origin/main`) | unchanged from T6 part 1 — 41 findings, **0 real** |

## Architecture review + refactor

**Nothing substantive.** T6's second half adds no production Java: the two code fixes it *did* need
(*Decisions* §1–2) landed in part 1 with their own regression tests, at the moment the rig exposed
them. The review energy went into the operator check instead — see *Decisions* §3.

## Integration / e2e

**E1–E8, E10 and E11 green through the gateway**, on the first full run. The cut, live:

| Persona (same principal unless noted) | `tools/list` | `get_product` |
|---|---|---|
| human — no actor claim | all four | allowed |
| `agent-readonly` — capped below `medium` | exactly `list_catalogs`, `get_catalog` | **denied `tool-gate`** |
| `agent-overreach` — WRITE, GRANT, every verb | the human's four, **never more** | allowed |
| `agent-readonly` for a **low-privilege** principal | `[]` | denied |

Plus: REST parity by id (E1), a foreign catalog denying at `target-gate` / `ACCESS_DENIED` (E4), an
unlisted tool denied rather than merely hidden, the PDP-kill pair (empty roster **and** every call
denied, zero widening, the pre-kill vector restored **exactly** — same allows *and* same denies), the
gate-OFF drill (the tool-gate leaves the path, the catalog's own gate still denies), and revocation
removing the tool from the roster *and* the call path.

**E9 — the pre-existing fleet: 14/14 green on their documented rig flavours.** Eleven runners plus
the isolation matrix ran on the full `ENABLE_MCP=1 ENABLE_DIRECTORY=1 ENABLE_SPA=1` rig; the
resilience matrix on its own fault-stub rig (it repoints role resolution at the stub, so it is
rig-exclusive by construction). `run-tests.sh` and `run-matrix.sh` are red on the *user-service* rig
and **green (19 + 19 assertions) on the OIDC-only rig that is their documented prerequisite** — a
Slice-B4 consequence already recorded in `DIRECTORY-QUERY-FILTERS/STATUS-05` (a fresh, team-less
catalog 403s by design once membership is the sole access path), re-verified here rather than
inherited. This branch changed no tracked file under `scripts/postman`, no library module, no example
service and no pre-existing `.rego`.

## Decisions

### 1. The type-level ceiling under-approximated — found only by driving the rig

`TypeLevelRoleDefinitionSupplier` asked the user-service for governed targets of the **requested**
type. But membership lives on the **governing root** (ADR 0018) while the role it resolves to carries
the whole hierarchy's permissions — so `?resourceType=product` is legitimately empty for a principal
who may read every product under a catalog they govern. The tool surface therefore *removed* access
the caller had over REST, and denied the human and the agent for the same reason, **erasing the
slice's headline contrast**. Fixed by enumerating the requested type **plus**
`example.mcp.authz.role-source.grant-scope-types` (default `[catalog]`).

*The tell, recorded for next time: a human roster identical to a narrowed agent's is a ceiling bug,
not a policy bug.*

### 2. A target-gate denial arrived unlabelled

Spring AI's annotation-scanned call handler **catches** whatever an `@McpTool` method throws and
flattens it to a plain-text error result, so `ToolCallGate`'s own `catch` never fires in the real
invocation path — and the layer distinction this slice exists to make was silently gone.
`ToolFailureRecord` (request-scoped, read-once) now carries layer + code across that seam, and the
gate relabels an **already-failed** result; it can never turn an error into a success. **I14 passed
throughout**, because its fixture builds the specification itself with a handler that throws straight
through — the call-path analogue of the `tools/list` routing gap T5 hit. The regression test
(`namesTheTargetGateEvenWhenTheDelegateSwallowsTheException`) reproduces the *swallowing* shape.

Both defects are the same class: **a third-party seam behaving differently from the mental model of
it.** That is the class the slice's earlier two pauses were in, and the class the 2026-07-31 gates
were built for — and the gates did work, catching the missing APISIX route and the `buildContext`
gate-off seam *before* implementation.

### 3. E5's wire-level half is an operator check, and it is a real one

A client speaking to the gateway cannot see the MCP-server → catalog hop, so "nothing is asserted
downstream" is unprovable from inside the collection ([[10-QA-TEST-CASES]] scopes it here
deliberately; the wire-level proof stays I1). The runner checks three things instead, any of which
failing would mean propagation had crept back: the outbound client sets **exactly** `Authorization` +
`Accept`; no source in the module names an agent/role/acting-as header; and the branch changed **no**
library module, existing example service, or pre-existing `.rego` — i.e. the target-gate really is
being exercised as shipped. `SKIP_OPERATOR_CHECK=1` exists for a detached checkout.

### 4. Three cells are rig drills, and the rig is restored by a trap

E6 (PDP kill), E7 (`agent-gate` OFF) and E11 (revocation) cannot be collection steps — they need the
rig mutated mid-suite. The runner owns them, and an **EXIT trap** restores OPA and recreates the MCP
pod on its default profile however the run ends. Without it a failing drill would leave the PDP
stopped or the gate disabled, and the *next* run — or the demo UI — would behave differently for a
reason nobody would connect back to this script.

E7 needed a principal the **target-gate** refuses, not merely a denied tool: with `agent-gate` OFF the
authorizer returns permitted *without consulting OPA at all*, so a ceiling-based deny could never be
the thing that fires. The foreign catalog's product is that target.

### 5. Fixture discipline: two ids, and one preflight assertion

`bbbb…bbbb` is granted (a role covering catalog + category + product READ, so the **type-level**
ceiling reaches all four tools) and `bbbb…bbbc` is foreign, with only the seeder bound. The
low-privilege principal's ceiling being **empty** is asserted in the runner before newman — a stray
membership from a demo click would turn the headline cell green for the wrong reason, which is
exactly the failure mode the id registry exists to prevent.

## Commit

`test(e2e)+docs(agent-tool-authz): the E1–E11 agent matrix, the guide's rig story, and the folder
move (T6, part 2)` — see git log on `feature/void3110/agent-tool-authz`.
