---
tags:
  - status/active
  - type/guide
  - area/abac
  - area/opa
  - area/rego
  - area/security
  - area/spring
---

# Agent tool-call authorization — two decision layers, no propagation

> **Phase 9** (ADR [[0028-agent-tool-call-authorization|0028]]). The starter answers *"may this
> **human** act on this resource?"*. An AI agent calling tools on a person's behalf collapses two
> identities into one bearer token, so the agent inherits the **whole** human ceiling — and the tool
> surface itself has no gate at all. This guide is the shipped contract for the gate that closes
> that: a new example service, `example-mcp-server`, whose `@McpTool` methods proxy the catalog REST
> API and are authorized **before** they run, by a policy that computes **principal ceiling ∩ agent
> capability in Rego**.
>
> **Packaged as a library module since 1.5.0 (incubating).** Phase 9 shipped the gate inside the
> example and changed no library module. ADR [[0036-mcp-auth-library-module|0036]] (slice
> MCP-AUTH-LIBRARY) moves its reusable half into the new **`opa-abac-mcp`** module — the identity
> kernel, the capability seam and the tool declarations first — under `opa.abac.mcp.*` properties;
> the example keeps its catalog tools, its ceiling source and the reflective roster installer. The
> catalog service and its per-type policies are still the shipped ones, byte-for-byte, which is what
> makes [[#On the local rig|the e2e]] meaningful: the target layer it denies at is unchanged.

## Why the MCP specification does not cover this

The spec stops at the transport edge, deliberately:

- It specifies how a server **validates a bearer token** and nothing above it. There is **no per-tool
  authorization** concept: a token that passes validation may call **every** tool the server exposes.
- There is **no mechanism for filtering the advertised tool list**. The 2026-07-28 revision
  *sanctions* the set varying by the authorization presented — and forbids per-connection variation —
  but ships no mechanism.
- The identity reaching the tool body is a **single** identity, the token's `sub`. Nothing
  distinguishes *the human on whose behalf the call is made* from *the agent making it*, so a policy
  cannot bound the agent separately from the person.

## The mechanism: two layers, nothing asserted downstream

```
  agent ──MCP──▶  example-mcp-server              ──REST (the caller's own bearer)──▶  catalog service
                  ┌───────────────────────────┐                                       ┌──────────────────┐
                  │ LAYER 1 — the TOOL-GATE   │                                       │ LAYER 2 —        │
                  │  may this (principal,     │                                       │  the TARGET-GATE │
                  │  actor) invoke THIS TOOL? │                                       │  may this        │
                  │  → agent_tools.rego (NEW) │                                       │  PRINCIPAL touch │
                  │  → ceiling ∩ capability   │                                       │  THIS RESOURCE?  │
                  └───────────────────────────┘                                       │  → + act_chain   │
                                                                                      └──────────────────┘
        principal ceiling ∩ agent capability                ∩            principal ceiling on the row
```

The load-bearing property is what is **not** in that picture: the MCP server sends the catalog
service the caller's bearer **and nothing else** — never a role, a capability, a delegation chain, or
an acting-as header, and never a minted, exchanged or rewritten token. The catalog service therefore
re-derives the principal and applies the same ceiling it would for a direct REST call. The
intersection holds **across** the two layers rather than being passed between them, which is why the
MCP server can only ever fail to narrow — it can never widen. (Asserting a caller-supplied role is
exactly the fail-open shape [[MULTI-TENANT-ISOLATION|slice B4]] removed; it is not reintroduced.)

The honest cost: the per-type policies never *see* the actor — `actor` is the MCP server's internal
tool-gate attribute and never travels downstream. **What they do see, since slice C (ADR 0030
Amendment 4), is the `act_chain` wire claim** the `catalog-agent-*` clients' protocol mapper mints:
all three per-type policies carry a provenance-scoped agent deny (`is_agent_call`, a presence-test
on the claim key) that closes the *supervised* path to delegated calls, and the catalog service's
list authorizer drops the supervised leg on the same claim. That is a **narrowing** input — it can
only remove rows/decisions an agent call would otherwise get — and it is scoped to supervised
provenance; the membership-derived ceiling is untouched. General agent-aware row filtering ("this
agent may see only *some* of the rows its principal may see") still has no propagation design and
stays out of scope.

## Dual identity: principal, actor, chain

`DelegationChainExtractor` normalizes the validated token into a `DelegationChain` — the principal
(the token `sub`), the actor (the agent), and an ordered chain — read from a **plain custom claim**
carrying RFC 8693 `act` semantics, minted by a stock Keycloak protocol mapper. No token exchange, no
nightly build.

| Claim state | Reading | Why |
|---|---|---|
| **absent** | an ordinary **human** call — principal-only evaluation | Honest, not a bypass: principal-only is the widest the tool-gate ever goes, and it is still exactly the principal's own ceiling. Agent narrowing only ever restricts. |
| **present, well-formed** | an agent call — the intersection applies | |
| **malformed / oversized / cyclic** | **deny** | A broken agent claim is not a human. Never downgrade malformed to principal-only. |
| **absent, but `azp` is listed in `agent-clients`** | **deny** — `tool-gate-actor-required` | The token comes from a client the operator declared an agent, yet carries no actor: a misconfigured agent client (its IdP mapper is missing), never a human call. Opt-in — see below. |

The claim is configured under **`opa.abac.mcp.identity.*`** — `actor-claim` (default `act_chain`),
`max-chain-depth` (4), `max-claim-length` (2048) — and the claim name must also be listed in
`opa.abac.subject.attribute-claims`, or startup fails (`ActorClaimWiringCheck`): a claim that never
reaches the subject would read every agent call as a human one.

**The `agent-clients` guard** (ADR 0036 §8). `opa.abac.mcp.identity.agent-clients` (default empty) lists the
OAuth client ids that are agents. A token whose `azp` is listed but which carries no valid actor claim is
**denied `tool-gate-actor-required`** (layer `tool-gate`, its own WARN naming the client) instead of being
evaluated as a human call — and before any policy call is spent. Three rules pin it:

- **Precedence.** The claim is read first, so a listed client with a *malformed* actor is still
  `tool-gate-identity-unreadable`; the guard only ever sees a well-formed absent actor.
- **It runs whether or not `opa.abac.mcp.agent-gate.enabled` is on.** It asks whether this client's token
  carries the actor it must — an identity check, like the malformed-claim deny — not an agent-narrowing
  conjunct.
- **`azp` must reach the subject.** A non-empty list requires `azp` in `opa.abac.subject.attribute-claims`,
  or startup fails naming both properties (the same wiring check as the actor claim): without it the guard
  could never fire. An empty list never runs the check — the policy input is byte-identical to before.

At list time the guard is an identity edge outside the batch: the roster is **unfiltered** (+ WARN) while the
call-time gate denies every tool. **Residual, by design:** a user handing their *own* token to an agent is a
human call — the token is the human's, bounded by the human's ceiling; nothing in it says an agent holds it.

## The capability seam is tri-state

`AgentCapabilitySupplier` resolves the actor's profile — allowed categories, an allowed-tool list,
allowed actions, a max risk tag. It follows the repo's supplier doctrine (ADR
[[0014-supplier-outage-error-distinct|0014]]) exactly:

| Outcome | Decision | Distinguishable? |
|---|---|---|
| **resolved** | narrow by the profile | |
| **authoritative-empty** (agent known, zero capability) | **deny every tool** — a real answer | distinct **internal** code + log line |
| **outage** (throws) | **deny** | distinct internal code + log line, **identical** caller-facing error (no oracle) |

The profile's `allowed-tools` list is **mandatory**: an empty list denies every tool. Reading it as
"unrestricted" would turn a profile someone trimmed to nothing into a profile that permits
everything.

The YAML default (`ConfigAgentCapabilitySupplier`) reads a **list** of profiles under
**`opa.abac.mcp.agents.profiles[]`** (`actor-id`, `allowed-categories`, `allowed-tools`,
`allowed-actions`, `max-risk-tag`) — a list rather than a map keyed by actor id, because relaxed
binding would mangle an id containing dots. An actor with no profile is authoritative-empty. The outage
signal, `AgentCapabilityUnavailableException`, is a member of the core library's "could not decide"
family (`DecisionIndeterminateException`, ADR [[0037-indeterminate-decision-distinct-from-deny|0037]]);
the gate catches it before the family, so its denial code stays distinct.

The lookup is memoized **per turn** — a turn is *one MCP request*, never a session. Within a turn the
roster and the gate must see one capability answer or they could disagree; across turns the memo must
die, or revoking an agent's capability mid-session would not take effect until the client reconnected
and "the list said I could" would quietly become an authorization.

## The principal ceiling

The human half of the intersection arrives through a dedicated SPI, **`PrincipalCeilingSupplier`** —
`Optional<RoleDefinition> lookup(subject, resourceType)`: what the principal may do to *some* resource
of a type. The tool-gate resolves no target (it asks "may this caller invoke this tool at all?" before
any row is known), so it cannot use the per-resource question the core `RoleDefinitionSupplier` answers.
The contract:

- **grants are unioned** across every resource the principal governs; a **denial is kept only if it
  applies to all of them**;
- **over-approximation is allowed, under-approximation forbidden** — the gate is not the authority on
  resources (the target service decides each one with the caller's own bearer), while a ceiling that is
  too narrow removes access the caller has over REST and hides the agent narrowing behind a principal
  denial;
- **empty = an authoritative no-role** (the policy default-denies); an **outage throws** a member of the
  "could not decide" family (`DecisionIndeterminateException` — the core `RoleResolutionException`, or
  the implementation's own subtype), and the gate denies with `tool-gate-ceiling-unavailable` — any
  family member thrown by the ceiling lookup gets that code, whichever subtype it is;
- **no default implementation** — the gate has no honest fallback;
- an implementer's note from the rig: when roles are recorded on a **governing root**, include the root's
  type when the asked type is a descendant (the example's `TypeLevelPrincipalCeilingSupplier` does this via
  `example.mcp.authz.role-source.grant-scope-types`).

**Why not `RoleDefinitionSupplier` with a `null` id?** That would silently reinterpret every adopter's
existing supplier: one that resolves exactly the role on exactly one resource would be read as a type-level
union it never computed. A separate SPI is something an implementation opts into. The request memo of
ADR [[0023-request-scoped-resolution-memoization|0023]] decorates it (`MemoizingPrincipalCeilingSupplier`,
keyed `(subject, resourceType)`, under the same `opa.abac.resolve-memo.enabled` switch), so the roster and the
call in one request see one ceiling.

## The intersection is computed in Rego

`infra/opa/policies/agent_tools.rego` (package `agent_tools`, `default allow := false`) consumes the
shipped `permission_categories` expansion, so a principal's ceiling here is derived exactly the way
every other enforced path derives it:

```rego
principal_actions := permissions.effective_actions(input.role_definition, input.resource.attributes.target_type)
agent_actions     := {action | some action in capability.allowed_actions}
effective_actions := principal_actions & agent_actions   # set intersection can only SHRINK
```

That one line is the whole agent model: **a capability naming an action the ceiling does not grant
contributes nothing**. An agent can never do what its principal cannot. It lives in the policy, not
in Java, so it is auditable and `opa test`-able rather than drifting from the rule that is supposed
to own it.

Category, tool allow-list and risk-tag checks are independent `AND`s on top, so satisfying one never
compensates for failing another. The risk ranking is a table **in the policy** for the same reason.

## The input contract (v1)

Every tool-gate input — each single decision and **every item** of a roster batch — is versioned:
`input.environment.schema = "opa-abac.mcp.tool-gate/v1"` (`ToolGateInput.SCHEMA_V1`, set by the PEP). The
shipped `agent_tools.rego` grants **nothing** for a version it does not know (`known_schema`, required by
`is_tool_request`, so both `allow` rules and every `bulk` position refuse it) — human calls included. An
unknown version, a missing marker, an absent `environment`, a non-string marker: all deny.

| Field | v1 content |
|---|---|
| `subject.id` | the principal (the token `sub`) |
| `subject.roles` | the principal's roles, as the starter extracts them |
| `subject.attributes.actor` | the agent id — **absent for a human call**; its *presence* is what makes a call an agent call |
| `subject.attributes.chain` | the ordered delegation chain (audit only, never a grant) |
| `subject.attributes.agent_capability` | `{allowed_categories, allowed_tools, allowed_actions, max_risk_tag}` — absent on an agent call ⇒ deny |
| `subject.attributes.*` | any other claim copied by `opa.abac.subject.attribute-claims` (e.g. `azp`) |
| `role_definition` | the principal's type-level ceiling (`PrincipalCeilingSupplier`) |
| `action` | the tool's declared fine verb (`view`, `list`) — a bare verb |
| `resource.type` | `tool` (reserved) |
| `resource.id` | the MCP tool name |
| `resource.attributes.{category, risk_tags, target_type}` | the tool's declaration in the `ToolRegistry` |
| `environment.schema` | `opa-abac.mcp.tool-gate/v1` |

**The rule:** any incompatible change to this layout ships as a **new version**, never under v1. In
particular the actor stays at `input.subject.attributes.actor` in v1 — the policy recognizes an agent call by
that key's presence, so moving it without a version bump would turn every agent call into an unnarrowed human
call with every test still green. With the version bumped, a v1-aware policy meeting a later jar **denies**
instead of misreading.

**What it cannot do:** a policy written *before* v1 ignores the marker — the guarantee starts with v1-aware
policies. v1 *is* the pre-existing field layout (nothing moved), so an older copy of the policy keeps working
with v1 input; it just cannot refuse a future version. Deploy the v1-aware `agent_tools.rego` together with the
1.5.0 jar.

## The roster: a hint, never a grant

`tools/list` advertises only the tools the caller may actually call — one batch `allowAll`
round-trip (the ADR [[0016-action-enrichment-affordance-metadata|0016]] primitive) over one
`AbacContext` per registered tool, against the same `bulk` rule the call path asks.

**Uniform for every caller, with no code branch:** a human (no chain) is evaluated ceiling-only, an
agent gets ceiling ∩ capability. The contrast comes from the policy.

Three rules make the hint safe:

1. **Call-time enforcement is authoritative and always runs.** A listed tool never skips the gate.
   That is how mid-session revocation is caught — and it is why the roster may be degraded at all.
   There is deliberately **no** roster-derived cache and no "already checked in the pre-flight"
   shortcut; the moment one appears, the hint has silently become an authorization.
2. **The roster answers the same question the call gate will.** Whatever the call path would decide
   *right now*, the roster predicts. This settles the switch interaction rather than leaving it to be
   discovered: with `agent-gate` **off** the call path evaluates an ordinary human principal, so the
   roster builds its contexts **without** the agent attributes and shows the ceiling-only cut. A
   roster that kept narrowing while calls did not would hide tools the caller can successfully call —
   the one direction a hint must never fail in.
3. **Omit, never fabricate.** A tool is only ever removed from the advertised list. Nothing is added,
   in any failure mode.

Decisions are paired with the registry's declaration order **by index**, then applied to the
delegate's list **by name** — the two orders are different lists and nothing contracts them to agree.

## Fail-closed posture

Every edge lands on **deny, or the smaller result**.

| Edge | Failure | Lands on |
|---|---|---|
| caller identity | no authenticated caller on the thread the gate decides on (a non-web server; a customizer that moved handlers off the request thread) | **deny** every call (`tool-gate-unauthenticated`) |
| caller identity | a client listed in `opa.abac.mcp.identity.agent-clients` with no actor claim | **deny** (`tool-gate-actor-required`); the roster is **unfiltered** + WARN |
| delegation chain | claim absent | principal-only (an ordinary human call) |
| delegation chain | malformed / oversized / cyclic | **deny** |
| capability supplier | authoritative-empty | **deny** every tool |
| capability supplier | outage | **deny** (distinct code, identical caller-facing error) |
| principal ceiling | outage at **call** time | **deny** (`tool-gate-ceiling-unavailable`) |
| tool-gate OPA call | down / timeout / non-200 / malformed / package not loaded | **deny** with `tool-gate-policy-unavailable` — "could not decide", retryable later (since 1.4.0, ADR 0037; it was `tool-gate-denied` before) |
| tool-gate OPA call | the package is loaded but `allow` is undefined for this input | **deny** with `tool-gate-denied` (undefined-means-deny is a policy answer) |
| input version | `input.environment.schema` unknown, missing or not a string (a jar and a policy that disagree on the input layout) | **deny** with `tool-gate-denied`, human calls included; the roster position is `false` |
| tool metadata | a tool with no declared action/category/risk/target-type | **deny at registration time** — startup fails, the tool is never exposed; checked against the specifications actually wrapped, both directions |
| roster batch | dead PDP, **or** a genuinely zero-capability agent | the **empty roster**, treated as authoritative (see below) |
| roster identity / capability / ceiling at **list** time | unreadable or throwing | the **unfiltered** list + WARN, with the gate still denying per call |
| roster adapter | pinned SDK internals moved by an upgrade | **startup failure**, naming the pins |

**Why an empty roster is the honest answer.** Since 1.4.0 ([[0037-indeterminate-decision-distinct-from-deny|ADR
0037]]) `OpaClient.allowAll` **can** report failure: an outage, a timeout, a non-200, a malformed body or a
wrong-length result throws `PolicyEngineException` instead of padding an all-`false` vector. The roster
filter catches it and still answers the **empty roster** — now chosen on a distinguishable signal, and logged
as the outage it is, where before a dead PDP was indistinguishable from a zero-capability agent. The reason
it is the right answer did not change: during that outage every `tools/call` is denied too (with
`tool-gate-policy-unavailable`), so a roster advertising four unusable tools would be the misleading one.
Degradation to the *unfiltered* list survives only for the edges **outside** the batch — identity,
capability, ceiling — where showing more is safe because the gate keeps denying.

### Kill-switches — and what OFF means

| Switch | OFF behaviour |
|---|---|
| `opa.abac.mcp.agent-gate.enabled` | The tool-gate stops **narrowing**. The call is evaluated exactly as an ordinary human principal's, and the catalog service still enforces that principal's ceiling on every resource — so OFF **cannot grant an agent more than its principal already has**. The roster reads the same switch and shows the same ceiling-only cut. |
| `example.mcp.authz.roster-filter.enabled` | The adapter **and its smoke check** are not installed at all — the deliberate escape hatch for the day an SDK bump moves the pinned internals. The served list is the unfiltered one, byte-identical to the outside-the-batch degradation path, with call-time enforcement unchanged. |

There is **no** switch that disables call-time enforcement. The authoritative gate is not optional.

## Denials are structured, and name the layer

A deny is a `CallToolResult` with `isError` set and structured content carrying a stable code **and
the denying layer** — `tool-gate` or `target-gate`. That is the protocol's own way of telling a model
something went wrong in a way it can act on: pick another tool, ask the human to escalate. Never a
stack trace, never a bare 5xx, never a silent empty result. The two layers being distinguishable is
what lets a model react instead of retrying blindly.

On the wire the layer and code ride in the result's **`_meta`** (`{"layer": …, "code": …}`); the text
content is one safe, uniform message. The tool-gate's codes are documented API:

| Code (layer `tool-gate`) | Meaning |
|---|---|
| `tool-gate-denied` | the policy said no (including an undefined `allow` for this input) |
| `tool-undeclared` | the tool is missing from the `ToolRegistry` — unclassifiable is never unrestricted |
| `tool-gate-unauthenticated` | no authenticated caller on the thread the gate decided on |
| `tool-gate-identity-unreadable` | the actor claim is malformed, oversized or cyclic |
| `tool-gate-actor-required` | the client (`azp`) is listed in `opa.abac.mcp.identity.agent-clients` but the token carries no actor claim |
| `tool-gate-capability-unavailable` | the agent-capability source could not answer |
| `tool-gate-ceiling-unavailable` | the principal's ceiling could not be resolved |
| `tool-gate-policy-unavailable` | the policy engine could not decide (ADR 0037) — retryable later |

Layer `target-gate` carries the target service's own codes (e.g. `ACCESS_DENIED`), relabelled from what the
tool body recorded.

> **What a tool body's exception shows the model (Spring AI 2.0.1+).** Spring AI turns a `RuntimeException`
> thrown by an `@McpTool` method into an error result carrying **that exception's own message** (2.0.0 masked
> every one as `Error invoking method: <name>`; spring-ai #6534), and propagates a checked exception, an `Error`
> or an `McpError` as a protocol failure instead. The gate relabels only a failure the body **recorded**
> (`ToolFailureRecord.capture` of a `ToolInvocationException`); any other exception's message reaches the model
> as written. So on every expected failure path, throw a `ToolInvocationException` with a message meant for a
> model, and keep response bodies, URLs and internal detail out of exception messages —
> `CatalogApiErrorTranslator` in the example is the pattern.

## Installing the module

`opa-abac-mcp` is auto-configured by the starter (`OpaMcpAutoConfiguration`) as soon as it sits next to
Spring AI's MCP server — adding the dependency is the whole installation:

```kotlin
implementation(platform("dev.dmitriikonovalov:opa-abac-bom:<version>"))
implementation("dev.dmitriikonovalov:opa-abac-spring-boot-starter")
implementation("dev.dmitriikonovalov:opa-abac-mcp")          // incubating
implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
```

**You provide:** a `PrincipalCeilingSupplier` bean (no default — [[#The principal ceiling]]) and a
`ToolRegistry` bean declaring every tool's action, category, target type and risk tags.
**Defaulted** (each `@ConditionalOnMissingBean` — your bean wins): the `ToolCallAuthorizer` and
`ToolRosterFilter`; the claim-based `DelegationChainExtractor` (over a **private** JSON mapper, so a claim
parse never changes with your Jackson customizations); the YAML `AgentCapabilitySupplier`
(`opa.abac.mcp.agents.profiles[]`). The starter decorates, bean-level, whatever ended up in the context:
the ceiling memo (under `opa.abac.resolve-memo.enabled`), the turn-scoped capability memo (always), the
gate on every tool specification of all four SDK types (every element of every specification list), and
your `PolicyPathResolver` — **decorated, not replaced**: resource type `tool` goes to
`opa.abac.mcp.policy-path` (default `agent_tools`), every other type keeps your resolver's answer, so the
application's other gates are unaffected. The resource type **`tool` is reserved** for the tool-gate.

It is **not** gated on `opa.abac.enabled`, and it never starts half-installed. Startup fails, with a
message naming the fix, when:

| Condition at startup | Outcome |
|---|---|
| no `OpaClient` bean (incl. `opa.abac.enabled=false`) | fails, naming `opa.abac.enabled` and `OpaClient` — **even with** `opa.abac.allow-ungated-methods=true`: that acknowledgment covers `@OpaPreAuthorize` methods, never an ungated tool surface |
| no `PrincipalCeilingSupplier` bean | fails (there is no default) |
| a tool specification the module cannot wrap — any element exposing an `McpSchema.Tool tool()` accessor that is not one of the four SDK specification types (e.g. a type a future SDK adds) | fails, naming the type |
| a reactive (Spring WebFlux) web application | fails — not supported in v1 ([[#Supported configurations]]) |
| a specification list that never passed the gate (e.g. added with `registerSingleton`) | fails, naming the bean |
| `opa.abac.mcp.identity.actor-claim` not listed in `opa.abac.subject.attribute-claims` | fails (the wiring check — read from the environment, so it holds with the starter off) |
| no `ToolRegistry` bean, or its declarations ≠ the tools the gate wrapped (either direction) | fails, with both sorted lists |

Every check runs from one startup callback that resolves every singleton `List` bean first, so
`spring.main.lazy-initialization=true` cannot skip a guard. No property turns the gate off;
`opa.abac.mcp.agent-gate.enabled=false` drops the agent conjunct only.

**Declarations are checked against what was actually wrapped.** Every tool is declared in the
`ToolRegistry`. At startup the names the gate wrapped — the union across every specification list — are
compared with the registry **in both directions**: a tool that is advertised but undeclared (nothing to
authorize it against) and a declaration the gate never wrapped (a phantom, or a tool registered some way
the gate never sees) both fail startup, with both sorted lists in the message; a missing `ToolRegistry`
fails too, naming it; an empty registry with no tools starts. At call time a tool missing from the
registry is still denied `tool-undeclared` — the backstop for a wrapped specification whose name the
registry lost.

**Supported registrations** are the singleton specification-list **bean definitions** (`@Bean` methods or
registered definitions — Spring AI's own lists are), checked element by element; a hand-built specification
is supported by contributing it as such a bean (un-gated — the starter wraps and records it; one already
gated is kept only when its gate is bound to its own tool name and the application's `ToolCallAuthorizer`,
and is otherwise wrapped again with a WARN). **Unsupported,
and therefore never wrapped:** a specification added at runtime with `addTool`, one a server customizer adds
to the builder, a prototype- or scope-bound list, a list produced by a `FactoryBean`, and a list added with
`registerSingleton` (that one is caught: it fails startup if it carries a tool without the gate). Declaring
an unwrapped tool fails the startup check, so an unsupported registration cannot pass silently as declared.
The direct API (`ToolCallGate.gate` and its three siblings, `ToolCallAuthorizer`) stays public for wiring
without the auto-configuration.

## Supported versions

MCP Java SDK **2.0.x** and Spring AI **2.0.x**, on Spring Boot **4.0.x and 4.1.x** — both tested: the repo
builds on its 4.0.x pin, and the CI `build` job re-runs the module's, the starter's and the example's tests with
every Boot-BOM import switched to the newest 4.1.x (`-PspringBootLine=compat`, the catalog's `springBootCompat`).
The module pins `mcp-core` to the version Spring AI 2.0.x depends on; your own dependency management still wins
in your build. Spring AI 2.1 is out of scope until it is GA. Spring MVC only (see below).

## Supported configurations

The gate wraps **all four** MCP SDK tool-specification types, each with its own wrapper in
`…mcp.authz`:

| `spring.ai.mcp.server.type` × `protocol` | Specification type | Wrapper | Status |
|---|---|---|---|
| `SYNC` × `STREAMABLE` | `McpServerFeatures.SyncToolSpecification` | `ToolCallGate` | supported — proven over a real `POST /mcp` |
| `ASYNC` × `STREAMABLE` | `McpServerFeatures.AsyncToolSpecification` | `AsyncToolCallGate` | supported — proven over a real `POST /mcp` |
| `SYNC` × `STATELESS` | `McpStatelessServerFeatures.SyncToolSpecification` | `StatelessToolCallGate` | supported — proven over a real `POST /mcp` |
| `ASYNC` × `STATELESS` | `McpStatelessServerFeatures.AsyncToolSpecification` | `StatelessAsyncToolCallGate` | supported — proven over a real `POST /mcp` |

"Proven" is a test per row in the starter (`*ToolGateIT`): on Spring MVC, a real HTTP `tools/call` with a
forwarded bearer reaches the gate with the caller's identity on the deciding thread, a policy deny returns
the tool-gate advisory without running the body, and a policy allow runs it once. A type that could not show
the identity where the gate decides would not be shipped as supported — it would fail startup instead.

**Every wrapper decides when its handler is invoked**, on the transport's calling thread, where the caller's
`SecurityContextHolder` and request attributes are bound — never inside a `Mono`. An async wrapper then
returns the denial, or the delegate's `Mono` *decorated, not replaced*: an emitted `ToolInvocationException`
becomes the same advisory a thrown one does; an error result is relabelled from the tool body's recorded
failure, read when the result is emitted (an async tool body runs at subscription); any other emitted error,
and an empty completion, pass through unchanged. A resubscription never asks the policy again.

- **Spring WebFlux is out of scope for v1** ([[0036-mcp-auth-library-module|ADR 0036]] §4): a reactive web
  application **fails at startup**. The gate reads the caller from the thread a handler is invoked on, which a
  reactive server does not keep; the planned successor is identity carried in the SDK's transport context.
- **A non-web application** (e.g. an stdio server) starts, and **every call is denied**
  `tool-gate-unauthenticated` — there is no request identity to read. Deny-all, never inert.
- **An `ASYNC` server only scans reactive-returning tools.** Spring AI's annotation scanner registers only
  `@McpTool` methods returning `Mono`, `Flux` or a `Publisher` on an `ASYNC` server; a synchronous method is
  silently not served (and, if declared, fails the declarations check above).
- **The synchronous servers rely on Spring AI's servlet-only `immediateExecution(true)`** to invoke handlers
  on the request thread: the stateful one through `servletMcpSyncServerCustomizer`, the stateless one inside
  its server bean whenever the environment is a servlet one. **The customizer trap:** the stateful server
  injects a *single* `McpSyncServerCustomizer`, and Spring AI's is **not** `@ConditionalOnMissingBean` — a second
  customizer fails startup with `NoUniqueBeanDefinitionException`, and a `@Primary` one that does not itself set
  `immediateExecution(true)` moves every handler to `boundedElastic`, where no caller is bound: **every call is
  then denied** `tool-gate-unauthenticated` (pinned by a test). If you need your own customizer, make it
  `@Primary` and call `immediateExecution(true)` in it.
- **Registered after startup is ungated.** A tool added with `addTool` (or by a customizer) never passes the
  gate — see *Supported registrations* above.
- The example's reflective `tools/list` installer (`RosterFilterInstaller`) needs the streamable transport and
  fails startup on `STATELESS`; it stays an example-only adapter.

## Running it

The tool-gate wraps **every** advertised tool's call handler by post-processing the specification
lists Spring AI's auto-configuration produces (the starter's `OpaMcpAutoConfiguration`), so a tool added
later cannot be forgotten — nothing enumerates tool names.

```bash
./gradlew :example-mcp-server:test
opa test infra/opa/policies -v
```

> **One configuration pin you must not lose.** `spring.ai.mcp.server.protocol: STREAMABLE` is set
> **explicitly** in `application.yml`. Spring AI 2.0.x's auto-configuration matches the property
> *string* and ignores the properties class's own field default: with the property absent, the SSE
> condition (`matchIfMissing = true`) wins and the server silently serves the legacy transport
> (`GET /sse` + `POST /mcp/message`) while `POST /mcp` 404s. The roster adapter's seam exists only on
> the streamable transport, and its smoke check fails startup naming this property if it is missing.

> **The transport's own refusals must not leak the exception.** Spring AI 2.0.1's streamable transport answers
> an `Accept` without `text/event-stream` (`400`) or an unknown session (`404`) by handing its `McpError` to the
> HTTP message converters. A Throwable serialized as a bean is ~29 KB of `stackTrace`, `cause` and `suppressed`.
> The example's `McpErrorJsonMixin` (a Boot `@JacksonMixin`) keeps the JSON-RPC error and its message. A real
> client meets this — Claude Code's first post-login request is that `400`.

> **The streamable transport streams, so the security chain must permit `ASYNC`.** A `tools/list`
> response is written as SSE via an async dispatch; on that re-dispatch a stateless chain has no
> authentication left, and `authenticated()` would deny a response that has *already started* —
> aborting a half-written chunked body rather than returning a clean 403. Permitting `ASYNC` (as this
> module's `SecurityConfig` does, alongside `ERROR`) widens nothing: an async dispatch can only exist
> for a request that already passed the chain on its initial dispatch.

## On the local rig

```bash
ENABLE_MCP=1 ./deploy.sh up --pods 2      # force-enables OIDC + OPA + the user-service
scripts/postman/run-agent-tool-matrix.sh  # the E1-E17 matrix, through the gateway
```

The tool surface is **gateway-fronted like every other service here** — the scripted client enters at
`http://localhost:9085/mcp`, never a published pod port. Three details are load-bearing:

- **The route.** `ENABLE_MCP` seeds an `mcp` route (`/mcp*`, **priority 65** — above the `/*`
  catch-all and the user-service's 60, below `internal-blocked`'s 70) carrying the *user-service*
  plugin set: `openid-connect` + `response-rewrite` + tracing/CORS, and deliberately **not** the
  catalog `opa` gateway plugin. This server authorizes itself, with its own tool-gate. Without the
  route, `catalog-all` (`/*`, priority 0) already matches `/mcp` and the whole matrix would quietly
  hit the catalog pods instead.
- **Three outbound base-URLs, all overridden in-network.** The shipped defaults are `localhost`,
  which inside a container is the container. The role-source one is the trap: point it wrong and
  *every* tool call denies with `tool-gate-ceiling-unavailable`, which reads like a broken policy and
  is a broken URL.
- **The actor comes from which client minted the token.** The realm carries one client per demo
  actor — `catalog-agent-{readonly,overreach,revoked}`, plus the MCP host's public `catalog-mcp-host`
  (§MCP OAuth discovery) — each with a hardcoded-claim protocol mapper minting `act_chain` with that
  actor's id. So the same human can *also* obtain an ordinary no-actor
  token through `catalog-gateway`, which is exactly what the human-parity cells need. One agent
  client could not mint three actors, and a user-attribute mapper would bind the actor to the human.

### What the matrix proves

`agent-tool-matrix.postman_collection.json` **is** the MCP client — a deterministic scripted one that
speaks the real streamable framing (`initialize` answers plain `application/json` and assigns
`Mcp-Session-Id` as a *response header*; a notification answers `202` with an empty body, **not** SSE;
every later request is SSE-framed). Every assertion is on the actual cut — which tool names, which
denials, which **layer** — never on response shape alone.

| Persona (same principal unless noted) | `tools/list` | `get_product` on its own catalog |
|---|---|---|
| human — no actor claim | all four tools | allowed |
| `agent-readonly` — capped below `medium` risk | exactly `list_catalogs`, `get_catalog` | **denied `tool-gate`** |
| `agent-overreach` — capability lists WRITE, GRANT, every verb | the human's four, **never more** | allowed |
| the same `agent-readonly`, acting for a **low-privilege** principal | `[]` | denied |
| `agent-host` — the MCP host, logged in by discovery, capped at `low` | exactly `list_catalogs`, `get_catalog`, `list_categories` | **denied `tool-gate`** (listed; the risk cap) |

The middle two rows are the whole argument: the capability **narrows and never grants**, and the
contrast is drawn on *one* token — the same human is permitted `get_product` directly. Alongside
them: a foreign catalog answers `target-gate` / `ACCESS_DENIED`, so a caller can tell the two layers
apart end to end; a tool omitted from the roster is still *denied*, not merely hidden; and killing
OPA mid-suite empties the roster **and** denies every call, with the pre-kill vector — same allows,
same denies — returning exactly on restart.

Four cells are rig **drills** the runner orchestrates rather than collection steps: it stops and
restarts the PDP around the outage folders, recreates the pod with `agent-gate` OFF (proving the
catalog's own gate still refuses what the tool-gate no longer does), recreates it with the
readonly actor's capability emptied — a revocation that removes the tool from the roster *and* denies
it at call time — and, for **E12**, recreates it with `catalog-gateway` **added** to the four agent clients
`opa.abac.mcp.identity.agent-clients` lists by default: the human token, minted by that actor-less
client, is refused `tool-gate-actor-required` by value, an `agent-readonly` token on the same pod keeps
its normal outcome, and the default pod, where `catalog-gateway` is no longer listed, answers the human
token normally again.

### Two things only the rig could show

Both were found driving the live rig, after the unit and integration suites were green — and both are
the same shape: **a third-party seam behaving differently from the mental model of it.**

1. **The type-level ceiling under-approximated.** It asked the user-service for governed targets of
   the *requested* type, but membership lives on the **governing root** ([[HIERARCHICAL-AUTHORIZATION|ADR 0018]])
   while the role it resolves to carries the whole hierarchy's permissions — so
   `?resourceType=product` is legitimately empty for someone who may read every product under a
   catalog they govern. The tool surface *removed* access the caller had over REST, and denied the
   human and the agent for the same reason, erasing the contrast. Now enumerated over the requested
   type **plus** `example.mcp.authz.role-source.grant-scope-types` (default `[catalog]`).
   *Diagnostic tell: a human roster identical to a narrowed agent's is a ceiling bug, not a policy bug.*
2. **A target-gate denial arrived unlabelled.** Spring AI's annotation-scanned call handler *catches*
   whatever an `@McpTool` method throws and flattens it to a plain-text error result, so the gate's
   own `catch` never fires in the real path — and the layer distinction this slice exists to make was
   silently gone. A request-scoped, read-once record now carries layer and code across that seam. The
   unit tests could not see it: they build the specification themselves, with a handler that throws
   straight through.

### Known limits of the demo tool surface

Not defects — demo scope, named so a reader does not mistake them for the design:

- **The two list tools expose no paging.** `list_catalogs` and `list_categories` proxy paged REST
  endpoints without forwarding `page`/`perPage`, so a caller silently gets the API's default first
  page. A real tool surface would either forward the parameters or state the cap in the tool
  description; four read tools over a demo catalog do not need it, and adding it would put a
  pagination contract in a slice about authorization.
- **The ceiling is resolved per turn, not cached across them.** That is deliberate (a revocation lands
  on the next turn), and it costs one fan-out per turn per target type — bounded by the starter's
  resolve memo within a turn, and chunked so a principal governing many roots cannot build a request
  line the servlet container refuses.

## MCP OAuth discovery

A real MCP host — Claude Code, an IDE agent — starts from nothing but the server's URL. The MCP authorization
specification (2025-11-25) has it discover where to log the user in, and the example implements that path
([[0035-audience-bound-gateway-tokens|ADR 0035]] §5–§7). Logging Claude Code in by hand:
[[MCP-HOST-LOGIN-RUNBOOK]].

### The protected-resource document

`example-mcp-server` serves the RFC 9728 document on **exactly two** paths: `/.well-known/oauth-protected-resource/mcp`,
which clients try first, and `/.well-known/oauth-protected-resource`, the fallback.

```json
{"resource":"http://localhost:9085/mcp","authorization_servers":["http://localhost:9085/realms/catalog-demo"],"bearer_methods_supported":["header"]}
```

It is built **once, from configuration** — `example.mcp.oauth.protected-resource.resource` and
`…authorization-server` — and never from the request, so a forged `Host` or `X-Forwarded-*` cannot point a
client's login somewhere else:

| Configuration | Outcome |
|---|---|
| both unset | **Discovery off**: both paths answer `404`; one startup WARN names both keys. |
| exactly one set | **Startup failure**, naming the missing key. A half-written document would advertise a guess. |
| a value that is not an absolute `http(s)` URL, carries userinfo or a fragment, or (`resource`) carries a query or names a path other than `/mcp` | **Startup failure**, naming the key and the reason. |
| both valid | The document, `200`, `application/json`. |

The shipped `application.yml` leaves discovery off; the rig's `infra/compose.mcp.yaml` sets both values to the
gateway origin.

**It is not Spring Security's `OAuth2ProtectedResourceMetadataFilter`.** That filter matches every sub-path of
the well-known prefix, presets `resource` from the request URL (its `Host`) and answers whenever installed —
three breaches of the contract above (`javap -c`, Spring Security 7.0.7). A two-mapping controller does the
job. The security chain permits exactly those two `GET` paths and nothing else under `/.well-known`.

### At the gateway

- **The documents have their own route**: the two paths, `GET` only, **no auth plugin**, priority 66. A client
  reads them before it has a token. A `POST`, or any other well-known path, falls to the catch-all and its bearer
  check.
- **`/mcp` is always bearer-only**, whatever `ENABLE_SPA` says. A token-less MCP client must get `401` — its cue
  to discover — never a `302` to a login page it cannot render.
- **The `/realms/*` passthrough is wired whenever `ENABLE_MCP=1`**, not only for the SPA. The document names the
  realm at the gateway origin, so the host's whole code flow runs through it, and Keycloak stamps that origin as
  the token's `iss`, one of the three the gateway admits.
- **Keycloak ignores RFC 8707 `resource`.** The spec has clients send it on the authorize and token requests;
  Keycloak 26.3.2 leaves `aud` to the mappers either way (measured, ADR 0035 T2 probe I1). So the binding is on
  the mapper-stamped audience, and a Keycloak upgrade should re-measure it.

### The host is an agent, not the person

The realm's `catalog-mcp-host` is a **public** client: Authorization Code + PKCE S256, loopback redirect URIs on
port `33418`. Like the three demo agents, it mints `act_chain` (`["agent-host"]`) and carries the
`gateway-audience` mapper. On the server:

- its profile, `agent-host`, is `allowed-categories: [READ]`, the four tools, `allowed-actions: [view, list]`,
  `max-risk-tag: low`. Its roster is the three low-risk reads; `get_product` is listed in the profile and still
  denied, the risk cap being the only reason;
- it is one of the four `opa.abac.mcp.identity.agent-clients`, so a host token whose actor went missing is refused
  `tool-gate-actor-required` rather than read as the person's own call. `azp` is in
  `opa.abac.subject.attribute-claims` for that reason.

On the tool surface, then, a person logging an MCP host in hands it **less** than their own reach. **On the REST
API the same token is the person's own.** It carries the gateway's one audience, so the catalog and user-service
routes admit it, and their policies decide it at the principal's ceiling, writes included. That is ADR 0028 §2's
accepted bound: bypassing the tool-gate yields the human's own authority, never more. A host that replays its
token against the REST API is therefore not narrowed. Closing that needs per-resource audiences with RFC 8693
exchange at the MCP server (backlog 25).

The four tools also carry MCP tool annotations — `readOnlyHint` true, `destructiveHint` and `openWorldHint` false —
so a host labels them as the reads they are. `@McpTool`'s defaults would have them advertise as destructive. The
hints are **advisory**: the gate never reads them, and what it enforces is each tool's declared category and risk
tier.

### Refresh tokens rotate

The realm sets `revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`, because the MCP spec requires rotation for
public clients, and it holds realm-wide. A refresh answers a new refresh token. Presenting a spent one answers
`invalid_grant` "Maximum allowed refresh token reuse exceeded", and Keycloak detaches the session.

That second effect is why the demo SPA's `freshUser()` refreshes **single-flight**: a catalog view fans out
several reads at once, and a refresh per caller would replay the spent token and sign the user out.

Two limits remain, recorded rather than engineered around (backlog 29):
- **The single flight is per tab.** Two console tabs share one Keycloak client session but hold separate tokens.
  Once one tab refreshes, the other's older refresh token answers `invalid_grant` ("Stale token"), and that tab
  is signed out.
- **The gateway's own browser-flow session renews on its own.** On a browser-flow rig (`ENABLE_SPA=0`), two
  parallel requests on an expired session can race the same refresh token.

### The documented deviation: the forwarded bearer

The tools forward the caller's own bearer to the catalog API, in-network. The MCP spec says a server *"MUST NOT
pass through the token it received"*. Here the token was issued for the gateway's single audience, which the
catalog shares, so the catalog is not a third party holding a token meant for someone else — but a strict reading
still forbids it. The spec-aligned successor is **RFC 8693 token exchange** at the MCP server (a catalog-audience
token minted per call), which needs per-resource audiences. It is in the backlog as its own slice.

### Proven on the rig

The agent matrix carries five cells for this path:
- **E13**: both documents through the gateway with no token, a forged `Host` leaving the document byte-identical,
  and the catch-all's `401` for a `POST` or a third path;
- **E14**: the miner's `--discover` login — it walks the documents to the realm the way a host does, and checks
  the token's `aud`, `azp`, `act_chain` and `iss`;
- **E15**: the host's narrowed roster and its risk-cap denial, against a REST read of the same product on the same
  token;
- **E16**: rotation;
- **E17**: the transport's own `400`/`404` answer the JSON-RPC error and nothing else. E10's roster cell also reads
  every tool's read-only hints back.

The step-up matrix's E9k proves rotation for the SPA client too.

## Scope boundary — explicitly not in this slice

MCP transport / OAuth authorization in the library (the scope fence sits **above** the validated token; the
example's discovery path, §MCP OAuth discovery, is example code, and RFC 8693 token exchange is backlog) · the host-side `ToolCallingManager` leg · HITL / approval flows ·
policy-filtered retrieval · `listChanged` notifications (the SDK offers only a *global* broadcast;
revocation bites at call time immediately, and at the next re-list for the view) · a real LLM in the
loop. (The slice's exit criterion — a reusable library module — has since shipped as the incubating
`opa-abac-mcp`, [[0036-mcp-auth-library-module|ADR 0036]].)

**The existence oracle is accepted and documented:** a roster-hidden tool still answers `tools/call`
with the layer-naming advisory deny, revealing that it exists. All demo tools are publicly
documented, and hiding existence was never a goal.

## Related

- ADR [[0028-agent-tool-call-authorization|0028]] — the two-layer decision model, enforcement by
  composition, the additive dual-identity subject shape, example-first packaging.
- ADR [[0014-supplier-outage-error-distinct|0014]] — the tri-state supplier contract the capability
  seam copies · ADR [[0023-request-scoped-resolution-memoization|0023]] — the memo scope this slice
  narrows to a turn · ADR [[0016-action-enrichment-affordance-metadata|0016]] — the batch `allowAll`
  primitive the roster reuses.
- [[ABAC-AUTHORIZATION]] — how an `AbacContext` is built and asked · [[PERMISSION-MODEL]] — the
  `permission_categories` expansion the ceiling is derived through · [[HTTP-RESILIENCE]] — the
  edge-wrapping discipline every outbound call here follows.
- [[MULTI-TENANT-ISOLATION]] — where asserting a caller-supplied role was removed as fail-open.
