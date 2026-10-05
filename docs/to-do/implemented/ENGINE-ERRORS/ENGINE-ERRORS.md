---
tags:
  - status/done
  - type/index
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# ENGINE-ERRORS — "could not decide" is not "the policy said no"

> **Status: ✅ BUILT 2026-10-05 — mini package, collaborative build, six tickets on
> `feature/void3110/engine-errors` (PR pending); release 1.4.0.** Design settled 2026-10-04. Proven live on both
> outage classes; PIT: every mutant on a changed line killed; local Sonar: 0 findings in main code.
> An OPA outage, a broken policy deployment or a role-source outage today reaches the user as a 403 (or an
> empty list) and the operator as a stream of denials. This slice makes "could not decide" a thrown,
> typed, still fail-closed signal at every decision surface — 503 at the HTTP edge, retryable by a client
> — and stops the resilience layer retrying genuine denials. The contract is
> [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]]; the forks are in [[00-DESIGN]] §2.
> **Build: collaborative**
> **Not an autonomous run**: no implementation prompt, no orchestrator. `verify-package.sh` reads the
> declaration above and skips its two prompt arms ([1] the prompt file, [5] prompt invariants); every
> other gate must pass. Branch `feature/void3110/engine-errors`.
> **Review routing (the standing rule):** the first whole-delivery review is **one multi-lens
> `/deep-review` workflow pass** — the remaining weekly budget and its expected cost are stated before it
> launches; any follow-up round on this branch is a Fable + Opus pair.
> **Reviewed 2026-10-05:** by the maintainer's call, a **Fable + Opus pair** in place of the multi-lens pass
> (waiver recorded in [[ENGINE-ERRORS-REVIEW]]). Both APPROVE-WITH-FIXES, no Critical/High; one design fork
> reversed (the OPA breaker counts only retried faults), everything folded the same day.
> **Validated:** 2026-10-04 — mechanical gate green; then **one adversarial validation agent** (Fable,
> the TAG-GATED-CREATE measured approach; ~0.49M tokens) over six angles: code grounding, a fail-open hunt,
> framework semantics (five claims, all verified in Spring Security / MVC / Boot bytecode), build-breakers,
> consistency, clean-room. Verdict **certify with fixes**: no fork unsound, nothing fails open; **3
> run-stoppers** (T1/T2 inseparable and the MCP adapters a T1 build-breaker → merged; the agent-tool kill
> drill pins the reversed roster semantics → fork 15 keeps the empty roster, E4 added; an unpinned
> catalog-list degrade → fork 16), **8 contradictions** (the breaker records every thrown fault; T1 not
> behaviour-neutral; the empty roster was documented, not accidental; the root-list blind spot → fork 17;
> the bulk `{}` also means "no bulk rule"; the gateway shares OPA only for catalog routes; the ancestor
> resolvers' source-SPI wraps; two phantom build-breakers) and 12 nits — all folded the same day. Residual
> risk: the fold-in itself was not re-validated by a second agent (a cost decision — the fixes are wording,
> a ticket merge and three settled forks, checked against the code by the orchestrator).

## Why this slice exists

**The gap.** [[0014-supplier-outage-error-distinct|ADR 0014]] made a role-source *outage* distinct from
*no-role* at the supplier SPI — and every consumer then mapped it back to a deny. The OPA client never made
the distinction: a timeout, a 5xx, a policy package that never loaded and a policy's `allow: false` all
leave `HttpOpaClient` as the same `false`. So a service cannot answer the two states differently (403 vs
503), a durable workflow treats an outage as a final refusal, and the resilience decorator — unable to tell
the deny sentinel from a real deny — retries every genuine deny.

**The mechanism.** The core throws a `DecisionIndeterminateException` family member (`PolicyEngineException`
with a `Kind`; `RoleResolutionException` re-parented); the decorator retries thrown transient faults only;
the Spring gates throw `AuthorizationIndeterminateException extends AuthorizationServiceException` — still
an `AccessDeniedException`, so every existing handler still denies; the data layer propagates; decorations
keep their designed degradation; the advice answers 503 `DEPENDENCY_UNAVAILABLE`.

**The headline.** The same request under the same sustained role-source outage, through the real gateway:
1.3.0 answers 403, 1.4.0 answers 503 — beside the unchanged transient-recovers cell. And a genuine deny
costs exactly one OPA call and can never open the breaker; since the review, neither can a fail-fast fault
local to one type.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism (every class that changes), the fourteen forks, the fail-closed posture, the gotchas. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path + the build-breaker rule. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E*/P* cases → each ticket's Acceptance. |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | Core throws; resilience retries faults, never decisions; the MCP adapters catch the family | ✅ DONE |
| T2 | The gates throw `AuthorizationIndeterminateException`; the base advice answers 503 | ✅ DONE |
| T3 | The data layer propagates the family | ✅ DONE |
| T4 | The starter's fallback advice | ✅ DONE |
| T5 | The catalog example adopts it, and both live matrices prove it | ✅ DONE |
| T6 | Docs, changelog, proof gates | ✅ DONE |

## Related

- [[POC-ROADMAP]] — the slice line lands with T7.
- [[0037-indeterminate-decision-distinct-from-deny|ADR 0037]] — the contract; amends
  [[0014-supplier-outage-error-distinct|ADR 0014]] §3 and [[0017-cross-service-http-resilience|ADR 0017]] §2.
- [[B2-SUPPLIER-OUTAGE]] — the slice that made the role-source outage error-distinct at the SPI.
- [[B3-HTTP-RESILIENCE]] — the decorator and the fault-injection matrix this slice reuses.
