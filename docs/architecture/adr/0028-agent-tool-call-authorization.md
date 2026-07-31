---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring
---

# ADR 0028 — Agent tool-call authorization: two decision layers, enforcement by composition

**Status:** Accepted (planned — Phase 9, [[AGENT-TOOL-AUTHZ]])
**Date:** 2026-07-28
**Context tags:** MCP tool-call PEP, dual identity (principal ≠ actor), RFC 8693 `act`, narrowing-only capability, no role propagation, example-first, fail-closed

> Pins the **structural forks** for **Phase 9** — an example MCP server whose tool calls are authorized by
> the shipped starter with OPA as the PDP. Settled in a planning interview (2026-07-27) and its
> decomposition fork (2026-07-28). Baseline pins: Spring AI 2.0.0, MCP Java SDK 2.0.0, MCP spec revision
> 2025-11-25, on the repo's Java 25 / Spring Boot 4.0 / Gradle 9.x line.

## Context

Every gate in this repo assumes **one** identity: a validated token names a subject, and
`@OpaPreAuthorize`, the list filter, and the enrichment advice all decide for that subject. An AI agent
invoking tools breaks the assumption in one specific way — **two** identities are involved, the human on
whose behalf the call happens and the agent making it, and only the first has ever appeared in
`input.subject`.

The smallest honest demo is a new example service: an **MCP server** exposing a handful of catalog tools,
each of which executes by calling the **existing catalog REST API with the caller's own bearer token** —
no new data path, no shared database, no duplicated domain logic. That leaves three forks that decide
whether the demo is real or theatre: *where* the decision is made, *how* the agent's narrower authority
reaches the resource decision, and whether the agent is a grant holder at all.

MCP **transport** authorization (resource-server metadata, token audience, consent) is deliberately out of
scope — the ecosystem's MCP-security work covers it and the gateway already terminates OIDC. This ADR is
about everything **above** the validated token: which tools, and with which arguments.

The second fork is constrained by precedent. [[0018-team-scoped-resource-isolation|ADR 0018]] removed the
realm-role fallback because *a caller-supplied attribute deciding access is a fail-open shape*. An MCP
server that reads a role out of a token and re-asserts it downstream is that same shape wearing a
different label.

## Decision

### 1. Two decision layers with disjoint ownership

- **Tool-gate** — a **new** Rego document (explicit `default allow := false`) evaluated **in the MCP
  server**: given the subject (principal + actor + chain) and the tool's **statically declared** metadata
  (action, category, risk tags), may this call happen at all? Cheap — no target resolution, no database
  round-trip. The same document powers roster filtering through the existing batch `allowAll` primitive.
- **Target-gate** — the shipped per-type catalog policies (`catalog` / `category` / `product`), evaluated
  **by the catalog service** on the resource the tool actually touches, **completely unchanged**: not one
  line of existing Rego, Java, or config in the catalog or user services moves in this slice.

Topology is a **two-step short-circuit**: the tool-gate always runs first; the target-gate happens
naturally when the tool executes its REST call. A deny from either layer surfaces as a **structured
advisory tool error that names the denying layer**, so the model can distinguish "you may not use this
tool" from "you may use this tool, but not on that catalog". Never silent, never a raw stack trace.

### 2. Enforcement by composition — the MCP server never asserts a role

Effective authority is **principal ceiling ∩ agent capability**, and that intersection is enforced
**across** the layers, never carried between them:

- agent narrowing lives **only** in the tool-gate;
- the principal ceiling is enforced **independently** by the catalog service, from the same token it
  would have received from a browser.

The MCP server forwards the caller's token **unmodified** and adds nothing to the downstream request that
could influence a decision — no role header, no resolved-role claim, no impersonation. The net effect is
provably the intersection: the agent can never exceed its principal (the catalog service enforces that),
and is further narrowed (the tool-gate enforces that). Bypass the tool-gate entirely and the worst
outcome is *the human's own authority* — a failure to restrict, never a grant.

Propagation is rejected for the ADR-0018 reason: a downstream service that trusts an authority assertion
made by its caller is the fail-open shape B4 deleted. Under propagation a buggy or compromised MCP server
could **widen**; under composition it can only fail to narrow.

### 3. An additive dual-identity subject shape (RFC 8693 `act` semantics)

`input.subject` gains **`actor`** (the agent) and **`chain`** (the ordered delegation chain) alongside the
existing fields, whose meaning is unchanged: `subject.id`, roles and tags remain **the human**. The shape
follows **RFC 8693 `act` semantics** and is normalized by a **`DelegationChainExtractor`** seam from a
**plain custom claim** minted by a **stock Keycloak protocol mapper** in the demo realm — no nightly
build, no experimental token exchange.

**A token with no actor claim is an ordinary human call**: the chain is empty and the tool-gate evaluates
the principal only. That is honest rather than a bypass precisely because agent narrowing *only ever
restricts* — a human already sits at the ceiling.

The shape is **additive**: only the tool-gate reads `actor`/`chain` this slice.

### 4. Agent capability narrows only — never grants

There are **no standing agent grants**. The agent carries a **capability profile** supplied by a new
**`AgentCapabilitySupplier`** seam following the repo's tri-state doctrine (resolved / authoritative-empty
/ outage **throws** → deny, per [[0014-supplier-outage-error-distinct|ADR 0014]]) with a **turn-scoped
memo** — the [[0023-request-scoped-resolution-memoization|ADR 0023]] "one request, one answer" discipline
moved to the turn boundary, never session-lifetime. The intersection is **computed in Rego**, so the
narrowing is auditable in the decision log rather than buried in Java.

The consequence is the point: **an agent is never a principal.** No credential represents "the agent"
alone, so no request reaches the tool-gate with authority but no human behind it.

### 5. Example-first packaging; library extraction is the exit criterion

Everything ships inside the new `example-mcp-server` module (packages
`dev.dmitriikonovalov.example.mcp.*`). **No library module is created or changed.** Extracting an optional
`opa-abac-agent` module is the slice's **exit criterion and a follow-up**, deliberately not a ticket: the
seams (extractor, capability supplier, the tool-call PEP) must be exercised by a running demo before being
frozen into published API — 1.0 is on Maven Central, where a wrong seam is expensive.

### 6. Fail-closed edges; kill-switches that cannot widen

Every new edge lands on **deny, or the smaller result**: malformed / oversized / missing delegation claim
→ deny; capability outage → deny; tool-gate OPA unreachable or timing out → deny. The **roster is a hint,
never a grant** — `tools/list` filtering is an affordance, call-time enforcement is authoritative and
always runs (which is also how mid-session revocation is caught; an MCP `listChanged` notification is a
documented follow-up). **Roster failure semantics (revised 2026-07-31 — see the [[AGENT-TOOL-AUTHZ]]
slice's `00-DESIGN` §3.2):** the shipped `OpaClient.allowAll` is contractually **total and fail-closed** — it
never throws and normalises outage, timeout, non-200, malformed body and length mismatch alike into an
all-`false` vector — so "the roster batch failed" is not a signal this seam can emit. An all-`false`
result is therefore taken as **authoritative ⇒ an empty roster**, which is the honest report: during a PDP
outage every `tools/call` denies too, so a roster advertising four unusable tools would be the misleading
one. Degradation to the **unfiltered** list survives only for the edges *outside* the batch — an
unreadable roster identity, and the capability/ceiling lookups preceding it. In every mode:
**omit-never-fabricate** — a tool is never invented. *(The original text here read "if the roster batch
fails, the server degrades to the unfiltered list… never present 'no tools'"; that rule was written
against a failure signal the batch cannot express.)*

Kill-switches are per-layer and **their OFF state is never wider than ON**: switching the agent gate off
leaves the catalog service enforcing the principal ceiling, so the worst case is "this agent is as capable
as its human", never more.

## Consequences

- **A worked agent-authorization example on the shipped starter, with zero library change** — the claim
  "the same ABAC building blocks answer *may this agent invoke this tool on behalf of this user*" is
  demonstrated rather than asserted, and every existing matrix keeps passing by construction.
- **The honest cost: the per-type policies never see the actor this slice.** A tool needing *agent-aware
  row filtering* (the agent may read only a subset of the rows its human may read) cannot be expressed —
  that needs the deferred propagation design (a verifiable narrowing assertion the catalog checks, **not**
  a caller-supplied role). Today the cut is at **tool granularity**, composed with the human's existing
  row cut.
- **Two policy documents are consulted per agent call**, producing **two decisions in the audit trail**
  (tool-gate + target-gate) — richer forensics, and one more bundle to keep tested (`opa test` covers the
  new document, including every deny edge).
- **A second example deployable** enters the rig (compose + `deploy.sh` + a demo-realm agent client and
  claim mapper). The e2e driver is a **deterministic scripted MCP client**: the authorization path cannot
  tell a model from a script, so a live LLM would add cost and nondeterminism, not coverage.
- **Deferred, explicitly:** the host-side `ToolCallingManager` leg, human-in-the-loop approval, policy-
  filtered retrieval/RAG, `listChanged`, and a `ToolCallClassifier` implementation (the SPI ships
  **contract-only** — most-restrictive-on-ambiguity — because the demo's tools all declare their metadata
  statically, so an implementation would be an unconsumed seam).

## Considered & rejected

| Option | Why rejected |
|---|---|
| **Propagate the role (or a narrowed role) to the catalog service** as a header/claim | The catalog service would trust an authority assertion made by its caller — precisely the fail-open shape [[0018-team-scoped-resource-isolation\|ADR 0018]] removed. It also converts the MCP server from "can only fail to narrow" into "can widen". Composition gets the same intersection with none of that (§2). |
| **The MCP server evaluates the target-gate itself** (resolve the resource, ask the per-type policy) | Duplicates enforcement in a second deployable that drifts the day a policy changes, and needs domain data access it should not have. The catalog service must enforce anyway (it is directly reachable), so the copy buys nothing but a second thing to keep in sync (§1). |
| **Embedded domain access** (shared database or a copy of the entities in the MCP server) | Bypasses the target-gate — the layer that enforces the principal ceiling — leaving the tool-gate as the *only* control, and puts a second writer on the catalog schema. Calling the existing REST API with the caller's token keeps one enforcement point (§1, §2). |
| **The agent as a first-class grant holder** (its own service account or standing role definition) | A grant that outlives any human's authority: revoke the human and the agent keeps working. It makes the agent a principal and re-opens the confused-deputy hole the narrowing-only model closes (§4). |
| **Extend the per-type catalog policies with actor conditions now** | Touches shipped, published-behavior policies for a demo, and has no actor to read without the propagation design rejected above. Additive-first: the tool-gate is a **new** document (§1, §3). |
| **Host-side `ToolCallingManager` decorator as the first PEP leg** | Enforces inside the agent's own process — trivially bypassed by any other client of the same MCP server. Server-side interception is the boundary that actually holds; the host-side leg is a later optimization on top, not the foundation (§1). |
| **Ship the seams as a library module in this slice** | Freezes the extractor and capability contracts into the published 1.0 artifact line before a single demo has exercised them. Example-first, with extraction as the exit criterion (§5). |
| **Full MCP transport/OAuth authorization in scope** | A solved ecosystem concern, and the gateway already terminates OIDC; folding it in would triple the slice while adding nothing to the *authorization above the token* question this phase exists to answer (Context). |

## Related

- Slice: [[AGENT-TOOL-AUTHZ]] (00-DESIGN) · [[POC-ROADMAP]] — Phase 9.
- Precedents: [[0018-team-scoped-resource-isolation|0018]] (why a caller-asserted role is fail-open) ·
  [[0014-supplier-outage-error-distinct|0014]] (the tri-state supplier doctrine the capability seam
  follows) · [[0023-request-scoped-resolution-memoization|0023]] (the memo discipline, at the turn
  boundary) · [[0006-three-layer-enforcement-model|0006]] (the layered enforcement model this extends
  outward) · [[0016-action-enrichment-affordance-metadata|0016]] (the `allowAll` batch the roster reuses,
  and the omit-never-fabricate degrade contract).
