---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T2: Dual identity: `DelegationChainExtractor` + `DelegationChain` (RFC 8693 `act` semantics)

**Status:** ✅ DONE

## What shipped

`…mcp.identity` — the dual identity behind one bearer token: principal (the human) + actor (the agent) +
the ordered delegation chain.

| File | What |
|---|---|
| `identity/DelegationChain` | record `(principal, actor, chain)` + `isAgentCall()` / `depth()`; chain immutable, **nearest actor first**, principal excluded |
| `identity/DelegationChainExtractor` | the seam — `DelegationChain extract(AbacContext.Subject)`; javadoc pins *throw, never widen* |
| `identity/ClaimDelegationChainExtractor` | reads the configured claim in **both** wire shapes: nested RFC 8693 `{"sub":…,"act":{…}}` and the flattened array the demo mapper mints |
| `identity/DelegationChainException` | the deny signal, with the absent-vs-malformed asymmetry documented on the type |
| `identity/IdentityProperties` | `example.mcp.identity`: `actor-claim` (default `act_chain`), `max-chain-depth` (4), `max-claim-length` (2048) |
| `identity/ActorClaimWiringCheck` | **new, not in the ticket list** — a startup guard against a silent widening (see *Decisions* 2) |
| `config/IdentityConfiguration` | wiring |
| `application.yml` | `example.mcp.identity.*`, `opa.abac.subject.trust-forwarded-jwt: true`, `attribute-claims: [act_chain]` |

## Tests

`./gradlew :example-mcp-server:test` — **67 passed, 0 failed** (up from 37).

| Case | Covered by |
|---|---|
| **U5** one actor → principal/actor/chain, `isAgentCall()` — in **both** wire shapes, plus the array-of-objects form | `ClaimDelegationChainExtractorTest` |
| **U6** two hops preserve order; the outermost actor is the immediate caller; both encodings normalize **identically** | same |
| **U7** no actor claim → human call, **no throw** | same |
| **U8** scalar claim, entry without an id, present-but-empty claim → deny | same |
| **U9** depth above max (flattened **and** nested); serialized size above cap; a 10 000-deep claim denies **without an `Error` escaping** | same |
| **U10** actor == principal; a repeated actor → deny | same |
| **U11** blank id, non-string id, illegal charset (space, newline, `../../etc/passwd`, 129 chars) → deny; realistic ids still accepted | same |
| **U12** blank/absent principal, null subject → deny | same |
| **I4** agent token vs plain token through the **shipped** `JwtClaimsSubjectExtractor` → distinguishable at the boundary; nested `act` survives the round trip; malformed still denies | `DelegationChainBoundaryTest` |
| **I5** three consecutive requests with different tokens → three different chains, nothing carried between them | same |
| wiring guard | `ActorClaimWiringCheckTest` (4 cases, both directions) |

## Architecture review + refactor

**Fail-closed.** Every edge and its landing:

| Edge | Failure | Lands on |
|---|---|---|
| principal | null / blank subject | deny (no honest evaluation exists without a principal) |
| actor claim | **absent** | **principal-only** — the one deliberate non-deny, still bounded by the principal's ceiling |
| actor claim | present but empty | deny — whoever minted it meant to name an agent |
| actor claim | scalar where object/array expected | deny |
| actor claim | unserializable / over `max-claim-length` | deny, **before** any traversal |
| chain | deeper than `max-chain-depth` | deny, checked **during** the walk |
| chain | >1000 nesting levels | deny via Jackson's `StreamWriteConstraints` — a `JacksonException`, **not** a `StackOverflowError` (asserted) |
| actor id | non-string / blank / outside charset / >128 chars | deny |
| chain | actor == principal, or a repeat | deny |
| config | actor claim not copied into the subject | **startup fails** |

**Security — the widenings that would matter here.** (1) A malformed claim degrading to principal-only
would silently strip the agent narrowing — every malformed shape throws, asserted across ~15 cases, and
the asymmetry is stated on `DelegationChainException` itself so nobody "helpfully" adds a fallback.
(2) **Config drift** — see *Decisions* 2, the finding this ticket's review actually produced.
(3) Actor-id injection into a policy input or a log line — an explicit ASCII class
`[A-Za-z0-9._:@-]{1,128}`, deliberately not `\w`, following the repo's recorded S6353 reasoning (an
explicit class cannot silently widen if the pattern is recompiled with different flags).
(4) Chain padding via repeats or self-delegation — the cycle check. (5) Resource exhaustion — size cap
before traversal, depth cap during, and an **iterative** walk: this repo has already shipped one
recursive-structure defect (S5998) where a `StackOverflowError` escaped a `catch (Exception)` fail-closed
handler, and a nested `act` claim is precisely that shape. (6) The extractor **describes** and never
grants — no capability, no role, no decision.

**Concurrency / idempotency.** T2 gates no mutation. `ClaimDelegationChainExtractor` is stateless apart
from immutable config; the chain is derived per call and nothing is cached (I5 asserts it) — the
turn-scoped memo arrives in T3. `extract` is a pure function of the subject, so a replay converges.

**Wiring.** `ActorClaimWiringCheck` has the context as consumer and is tested through three distinct
failure paths. `IdentityProperties` is consumed by both. The `DelegationChainExtractor` bean's only T2
consumer is its tests — that is the decomposition's explicit instruction ("the bean is registered so T4
can inject it unchanged"), not an accidental unconsumed seam; T4 and T5 are named in its javadoc.

**Boundary / additivity.** `git diff --stat` outside `example-mcp-server/` is **empty** for this ticket.
Nothing under `opa-abac-*/`, the two existing services, or `infra/opa/policies/` is touched. No schema
change.

**Module-layer separation.** `…mcp.identity` holds identity only: no policy decision, no capability, no
resource resolution. It does not import anything from `…mcp.tool`.

**Pattern reuse.** The seam mirrors the starter's own pluggable-extractor shape — nothing about the IdP
token layout is hardcoded, the claim name is configuration — so a real `act` claim later is an
implementation change, not a caller change.

**What the review found — a defect in T1's already-committed code.** `ToolRegistry.all()` documented
"declaration order" but returned `List.copyOf(byName.values())` over a `Map.copyOf` map, whose iteration
order is **unspecified and randomized per JVM run**. A T2 run drew a different order and
`ToolRegistryTest` failed. This is not cosmetic: **T5's roster pre-flight pairs the tool list with the
`allowAll` boolean vector by index**, and an unstable order is exactly the index-shift T5's acceptance
case exists to forbid. **Refactor applied:** the registry now keeps an immutable declaration-ordered
`List` alongside the lookup map; `names()` returns an ordered `List` positionally aligned with `all()`;
a regression test asserts the alignment and repeat-read stability. Fixed here rather than deferred,
because T5 would have consumed the broken contract.

**Static analysis.** 36 findings on the changed files, **0 real** — all in documented by-design classes:
S5778×30 (test lambdas), S112 + S4502 (`SecurityFilterChain … throws Exception`; CSRF off behind the
gateway), S2925 (`Thread.sleep` in a mock handler), S5976 (separately-named fail-closed cases), S1075
(the class adjudicated in T1), and S125 — a prose javadoc comment containing `catch(Exception)` and a
JSON snippet, the documented "code-like glyphs in prose" class. Nothing re-fixed.

## Integration / e2e

`./gradlew :example-mcp-server:test` green. `DelegationChainBoundaryTest` is the meaningful integration
here: it drives real gateway-shaped tokens through the **shipped** `JwtClaimsSubjectExtractor` and then
through ours, so the claim path is proven end to end rather than only inside the parser. Rig work is T6.

## Decisions

1. **The seam takes `AbacContext.Subject`, not a `Jwt`.** The decomposition wrote
   `DelegationChain extract(Jwt token)`, but this repo has **no Spring Security `Jwt`**: it is not an
   OAuth2 resource server. The starter's `AbacFilter` + `JwtClaimsSubjectExtractor` decode the
   gateway-forwarded payload themselves (the deliberate gateway-trust posture behind
   `trust-forwarded-jwt`). Taking the already-resolved `Subject` keeps **one** place deciding what a
   token means, satisfies the ticket's own requirement that the principal be "resolved exactly as the
   rest of the repo resolves it", and avoids a second, subtly different trust posture. Adding
   `spring-boot-starter-oauth2-resource-server` purely to obtain a type would have introduced exactly that.
2. **`ActorClaimWiringCheck` — the review's own finding, added as a deliverable.** Decision 1 makes the
   actor claim arrive via `opa.abac.subject.attribute-claims`. If that list and
   `example.mcp.identity.actor-claim` ever drift, the claim never reaches the subject, the extractor
   honestly reports "no actor claim", and **every agent call evaluates as an ordinary human call with the
   narrowing silently gone** — nothing fails, nothing logs, nothing looks wrong. That is the exact
   failure this slice exists to prevent, arriving through a config typo. The check fails startup on
   drift, and `DelegationChainBoundaryTest#showsWhyTheWiringCheckExists` demonstrates the widening it
   prevents.
3. **`chain` excludes the principal** (decomposition: "nearest actor first"). [[10-QA-TEST-CASES]] U5
   describes it as `[principal, actor]`; that ordering also contradicts its own U6 ("the outermost actor
   is the immediate caller"). Resolved toward the decomposition: `chain` means *agents*, so a depth limit
   counts agents and a cycle check has one kind of element to compare. The principal is already
   `principal`. Noted rather than silently reconciled.
4. **A present-but-empty claim denies.** Only an **absent** claim means "human". A claim that is there
   but names nobody is a broken agent identity, and the design's whole asymmetry is that broken ≠ human.
5. **The nested walk is iterative, not recursive** — see the security note above.

## Commit

`feat(mcp): normalize principal + actor + delegation chain, fail-closed (T2)`
