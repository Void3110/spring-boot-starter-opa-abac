---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/rego
  - area/spring
---

# AGENT-TOOL-AUTHZ — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance* in [[01-DECOMPOSITION]]. The mechanism they
> prove is [[00-DESIGN]] §2–§4; the structural decision is [[0028-agent-tool-call-authorization|ADR 0028]].
>
> **U** = unit / slice — plain JUnit on the seams, plus the `opa test` cases for the new policy
> document (grouped separately below, same numbering series).
> **I** = integration — the new service's HTTP edges are driven by in-process
> `com.sun.net.httpserver.HttpServer` stubs (one for OPA, one for the catalog REST API) — **never
> WireMock**. `example-mcp-server` owns **no persistence**, so it has no Testcontainers of its own;
> the real-Postgres path (**never H2**) stays where it already lives — the catalog service's own ITs,
> untouched this slice — and is exercised for real end-to-end in E*.
> **E** = e2e — a **deterministic scripted MCP client** through the rig (no LLM). It asserts **the
> actual cut** — which tool names are listed, which calls are allowed, which layer denied — not
> response shape ([[E2E-TESTING]]).
>
> The four demo tools referred to throughout: `list_catalogs`, `get_catalog`, `list_categories`,
> `get_product`.

## Unit — tool metadata declaration (U1–U4, T1)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | Registration scan over all registered `@McpTool` methods | **every** tool declares a non-blank action verb, a category, and a present risk-tag set — the declared triple is the only source of tool attributes | T1 |
| U2 | A tool registered with a missing/blank category or an absent risk-tag attribute | **startup fails fast** (bean-initialization error naming the tool); an unclassifiable tool is **never exposed** — the fail-closed edge from [[00-DESIGN]] §4 | T1 |
| U3 | `ToolCallClassifier` seam | the interface exists and its contract states **most-restrictive-on-ambiguity**; assert **no** implementation bean is present in the context — the unconsumed seam is deliberate, not an omission | T1 |
| U4 | Tool descriptor → OPA input mapping for `get_product` | `input.resource.type == "tool"`, `input.resource.attributes.category` / `.risk_tags` carry the **declared** values, `input.action` is the declared verb — no derivation, no defaulting | T1 |

## Unit — delegation-chain extraction (U5–U12, T2)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U5 | Well-formed custom claim, one actor | `DelegationChain` with `principal` == token `sub`, `actor` == the agent id, chain ordered `[principal, actor]`, `isAgentCall() == true` | T2 |
| U6 | Nested chain, two hops (RFC 8693 `act` semantics) | chain **preserves order** and depth 2; the outermost actor is the immediate caller | T2 |
| U7 | **No** actor claim | `actor` absent, `isAgentCall() == false`, **no throw** — an ordinary human call, the widest the tool-gate ever goes and still bounded by the principal's ceiling | T2 |
| U8 | Malformed claim — a string where an object is expected, or an actor entry with no id | **throws** the extraction failure → **deny**; explicitly **does not** fall back to principal-only (a broken agent claim is not a human) | T2 |
| U9 | Oversized claim — chain depth above the configured max, or a serialized claim above the byte cap | **deny** (both edges asserted separately) | T2 |
| U10 | Cyclic chain — actor id equal to the principal, or a repeated actor id in the chain | **deny** | T2 |
| U11 | Wrong claim shape — an array where an object is expected, blank actor id, or a non-string id | **deny** (each shape asserted) | T2 |
| U12 | Token `sub` missing or blank | **deny** — with no principal there is no honest evaluation to fall back to | T2 |

## Unit — capability supplier tri-state + memo (U13–U17, T3)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U13 | `AgentCapabilitySupplier` returns a **resolved** profile | allowed categories, max risk tag and tool allow-list are carried into the OPA input verbatim | T3 |
| U14 | **Authoritative-empty** — agent known, zero capability | a real answer (empty profile, distinct from "unresolved"): the gate **denies every tool**, and the denial is attributed to policy, not to an outage ([[0014-supplier-outage-error-distinct|ADR 0014]]) | T3 |
| U15 | **Outage** — timeout / transport failure (supplier throws) | **deny**; a **distinct** log message and error code from U14, while the caller-facing message is **identical** (no oracle) | T3 |
| U16 | Two capability lookups **within one turn** | the supplier is called **exactly once** (assert call count 1); a **new turn** triggers a fresh call (count 2) — the memo is **turn-scoped, never session-lifetime**, which is what lets a mid-session revocation land on the next turn ([[0023-request-scoped-resolution-memoization|ADR 0023]], narrowed) | T3 |
| U17 | Two turns with **different** actors (and different principals) in the same JVM | separate lookups, no cross-actor memo bleed; a profile is never served to an identity it was not resolved for | T3 |

## Unit — the tool-gate policy, `opa test` (U18–U30, T3)

All against the new `agent_tools.rego` document. `default allow := false` is the floor; the
intersection (principal ceiling ∩ agent capability) is computed **in Rego**, so these cases are the
audit of it.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U18 | Empty input `{}` | `allow == false` — the explicit default deny | T3 |
| U19 | **Human** call (no `actor`), ceiling grants the tool's category | **allow** — principal-only evaluation is honest, not a bypass | T3 |
| U20 | Agent call: ceiling grants the category **and** the capability lists it | **allow** — the intersection is non-empty | T3 |
| U21 | Agent call: ceiling grants the category, capability **omits** it | **deny** — the narrowing bites | T3 |
| U22 | Agent call: capability lists a category the ceiling does **not** grant | **deny** — capability can only ever **narrow**; an agent can never do what its principal cannot. The load-bearing policy case | T3 |
| U23 | Tool risk-tag above the profile's **max risk**, category otherwise allowed | **deny** — the risk condition is independent of the category condition | T3 |
| U24 | Profile carries an explicit tool allow-list; the requested tool is absent from it | **deny**, even with category and risk satisfied (an allow-list is a further narrowing, never a widening) | T3 |
| U25 | `agent_capability` present but **empty** (the authoritative-empty shape) | **deny every tool** — matches U14 at the policy layer | T3 |
| U26 | `actor` present, `agent_capability` **absent** from the input | **deny** — no capability means no agent authority, never "unrestricted" | T3 |
| U27 | No `role_definition` / no resolved ceiling (subject roles only) | **deny** — no subject-roles fallback, consistent with the boundary [[MULTI-TENANT-ISOLATION|slice B4]] set | T3 |
| U28 | Unknown tool category, or an action verb outside the vocabulary | **deny** (both asserted) | T3 |
| U29 | Batch `allowAll` over the full roster (the [[0024-batch-role-resolution|ADR 0024]] primitive) | one boolean **per tool, order-preserving**; a zero-capability agent gets all-`false`; a mixed profile gets exactly the expected true/false vector | T3 |
| U30 | Whole policy suite | `opa test` **all green**, count updated for `agent_tools_test.rego`; **no existing policy file changed** (the per-type documents are byte-identical) | T3 |

## Integration — tools over the catalog REST edge (I1–I3, T1)

In-process `HttpServer` standing in for the catalog service. **No authorization yet at T1.**

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | `list_catalogs` invoked with a caller bearer | the stub receives **exactly one** request, `Authorization: Bearer <caller token>` forwarded **verbatim**, and **no** role / capability / acting-as header of any kind; the response maps to the tool's structured result | T1 |
| I2 | Catalog stub returns **403** `problem+json` | the tool surfaces a **structured** tool-error preserving the upstream error code — never a 500, never a stack trace | T1 |
| I3 | Catalog stub unreachable / times out | a structured tool-error bounded by the configured client timeout — the call does not hang, and the failure does not leak the transport exception | T1 |

## Integration — identity + input document at the boundary (I4–I7, T2/T3)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I4 | Inbound request with an agent-claim token vs. a plain token | the tool layer sees `principal + actor + chain` in the first case and `principal` only in the second — the two paths are distinguishable at the boundary, not only inside the extractor | T2 |
| I5 | The `DelegationChain` is derived **per request** | two consecutive requests with different tokens produce different chains; nothing is cached across requests | T2 |
| I6 | `AgentCapabilitySupplier` over its real HTTP edge (`HttpServer` stub): 200-with-profile / 200-empty / 5xx / timeout / malformed body | the tri-state holds **at the wire**: resolved → narrow; authoritative-empty → deny-all; the last three → **deny** with the outage code (the [[HTTP-RESILIENCE]] contract) | T3 |
| I7 | The exact JSON sent to the OPA stub for one agent tool call | the body carries `subject.attributes.actor`, `.chain`, `.agent_capability`, `resource.type == "tool"` with the declared category/risk-tags, and the principal's ceiling in the **shipped** fields — proving the shape is **additive** ([[00-DESIGN]] §2.2), with no key removed or renamed | T3 |

## Integration — the PEP (I8–I15, T4)

OPA stub + catalog stub, both in-process. Every case asserts **request counts on both stubs** — that
is how "the tool body never ran" is proven rather than assumed.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I8 | Happy path: OPA allows | the tool body runs; **exactly one** tool-gate OPA call; the OPA call **precedes** the catalog call (ordering asserted, not inferred) | T4 |
| I9 | Tool-gate **deny** | the tool body never runs — **catalog stub request count == 0**; the caller gets a structured advisory error naming the denying layer **`tool-gate`** | T4 |
| I10 | OPA stub 5xx · timeout · connection-refused (three edges, asserted separately) | **deny** on each; catalog stub count 0; the deny is attributed to the tool-gate, not misreported as a policy decision | T4 |
| I11 | OPA returns a malformed body, or a body with **no** `allow` binding | **deny** on both | T4 |
| I12 | A malformed / oversized / cyclic identity claim (the U8–U11 shapes) reaching the PEP | **deny before any OPA call** — **OPA stub request count == 0**; nothing is spent on an unreadable identity | T4 |
| I13 | Capability-supplier outage vs. authoritative-empty | both **deny**; **distinct** internal error codes and log lines; **identical** caller-facing advisory error | T4 |
| I14 | Tool-gate allows, catalog stub returns **403** (the target-gate denying) | a structured advisory error naming layer **`target-gate`**, upstream error code preserved — the two layers are **distinguishable** by the caller, which is what lets a model react instead of retrying blindly | T4 |
| I15 | Kill-switch `agent-gate` **OFF** | the tool-gate is skipped; the call is evaluated exactly as a human principal's would be; the catalog stub **still 403s** for a resource the principal may not touch → **OFF is never wider than ON**. Asserted alongside: **no** property disables call-time enforcement — with every switch off, the authoritative gate is still installed | T4 |

## Integration — roster filtering (I16–I21, T5)

Fixture: a persona whose capability covers **exactly 2 of the 4** tools.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I16 | `tools/list`, then invoke **all four** tools | the listed set equals the callable set **exactly, by name** — the same 2 are listed and the same 2 pass the call-time gate; the other 2 are neither listed nor callable | T5 |
| I17 | `tools/list` with N registered tools | **one** batch `allowAll` round-trip (OPA stub request count == 1), N booleans back — not N calls | T5 |
| I18 | Roster batch fails (stub 5xx / timeout) | the server degrades to the **unfiltered** list — all 4 names present, **never empty, never fabricated** — and the call-time gate **still denies** the 2 non-callable tools (asserted by actually calling them) | T5 |
| I19 | Roster batch **succeeds** with all-`false` (a zero-capability agent) | an **empty** roster is the correct answer here — the contrast with I18 proves degradation keys on *failure*, not on emptiness | T5 |
| I20 | Kill-switch `roster-filter` **OFF** | unfiltered list, call-time enforcement unchanged — externally identical to I18's degradation, and no wider | T5 |
| I21 | Listed-but-revoked: turn 1 lists a tool; the capability profile is then revoked; turn 2 calls it | **denied at call time** — the list was a hint, never a grant, and the turn-scoped memo (U16) is what makes the revocation visible on the next turn | T5 |

## E2E — deterministic scripted MCP client through the rig (E1–E9, T6)

Rig personas: a human with catalog access; an **agent client** whose token carries the custom claim
minted by the demo realm's protocol mapper, acting for that human; and a low-privilege human plus an
agent acting for **them**.

| ID | Flow | Asserts (the actual cut) | → Ticket |
|---|---|---|---|
| E1 | Human token (no actor claim), `list_catalogs` through the MCP server | succeeds, and returns **the same catalogs** the same token gets from the catalog REST API directly — the tool surface adds no access and removes none | T6 |
| E2 | Agent acting for that human, read-only capability: `list_catalogs` **and** a write / high-risk tool | **allow / deny contrast in one run** — the read tool succeeds; the write tool is denied at **`tool-gate`** although the *principal* is permitted it. The narrowing is visible end-to-end | T6 |
| E3 | The agent's `tools/list` | equals the expected set **by exact tool name** (name-by-name equality, not a count), and every listed tool is then successfully callable | T6 |
| E4 | Agent whose tool-gate allows the tool, acting for a principal who may not reach the **target** | denied at **`target-gate`** — the catalog service's own unchanged policy, exercised as shipped; the layers are distinguishable in the error | T6 |
| E5 | The same tool + target that E2's agent was allowed, replayed by an agent acting for the **low-privilege** principal | **denied** — the agent never exceeds its principal. Asserted with it: the catalog service received **no** role, capability or acting-as header on any request in the run (the fail-open shape [[MULTI-TENANT-ISOLATION|slice B4]] removed is not reintroduced) | T6 |
| E6 | **Mid-run PDP-kill drill** — stop OPA mid-suite | every subsequent tool call **denies** at `tool-gate`; `tools/list` degrades to the unfiltered list while call-time **still denies everything**; assert **zero widening** — no call denied before the kill succeeds after it. Restart OPA → the pre-kill cut returns **exactly** (same tool names, same allow/deny vector) | T6 |
| E7 | Kill-switch drill — `agent-gate` OFF, replay E2's denied write tool | the tool-gate no longer denies, and the **catalog target-gate still denies** it for a principal without the permission → the OFF state is **not wider** than ON, proven on the rig rather than argued | T6 |
| E8 | Every deny produced in E2 / E4 / E6 | a **structured** tool-error carrying a stable error code **and** the denying layer; no stack trace, no bare 5xx, never a silent empty result | T6 |
| E9 | Every existing e2e matrix (catalog / hierarchy / tags / pagination / permission-categories / team / user-service) | re-runs **green** — this slice changed nothing they depend on | T6 |

## Headline proof

**E5 + E6**, backed by **U22**. E5 is the claim itself: an agent acting for a low-privilege principal
is denied the very call the same agent made successfully for a higher-privilege one, with **nothing
asserted downstream** — the intersection is enforced *across* the two layers, not passed between
them. E6 is the fail-closed proof: killing the PDP mid-run can only ever **shrink** the cut, and the
degraded roster carries no authority. **U22** is the same invariant at the policy level — a
capability naming a category the ceiling does not grant grants nothing.

**I15 + I18** are the second-order proofs: an OFF kill-switch is never wider than ON, and a failed
roster batch degrades a *hint* while the authoritative gate keeps enforcing.

## Suite-level

- `./gradlew build` green — all library modules, both existing example services, and the new
  `example-mcp-server`.
- `opa test` green with the new count; the existing per-type policy documents are **byte-identical**
  (U30).
- **Zero diff under `opa-abac-*/`, `example-catalog-management-service/`, and
  `example-user-management-service/`** — asserted mechanically on the branch (`git diff --stat`
  against `main` touches none of those paths). The slice's whole claim is that the shipped starter
  and the shipped services carry an agent front door **unchanged**.
- The local Sonar gate scans **CLEAN** on the changed `.java` files before push.
- The new service comes up on the rig via `deploy.sh` with every other service still green.

## Related

- [[AGENT-TOOL-AUTHZ]] · [[00-DESIGN]] · [[01-DECOMPOSITION]]
- [[0028-agent-tool-call-authorization|ADR 0028]] · [[0014-supplier-outage-error-distinct|ADR 0014]] ·
  [[0024-batch-role-resolution|ADR 0024]]
- [[E2E-TESTING]] · [[HTTP-RESILIENCE]] · [[ABAC-AUTHORIZATION]] · [[MULTI-TENANT-ISOLATION]]
