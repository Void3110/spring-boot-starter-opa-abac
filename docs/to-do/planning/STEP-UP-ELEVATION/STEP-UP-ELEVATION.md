---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# STEP-UP-ELEVATION — a fresh second factor opens production, briefly

> **Status: Planning — design settled, decomposition pending.**
> Design settled 2026-08-13 (grill-me: nine forks; [[00-DESIGN]] §Considered-and-rejected).
>
> Slice **C of three** in the supervisor epic (Phase 10 of [[POC-ROADMAP]]).
> Implements [[0030-step-up-decision-contract|ADR 0030]] **§5–9** as amended (§Amendments,
> 2026-08-13): resource-server-side `auth_time` freshness as the whole control, the additive
> `deny_reason` envelope, the RFC 9470 challenge, the audit emission points, TOTP-first factor
> policy — plus the grill-me refinements (sole-blocker `deny_reason`; the unproven tier stays
> elevation-proof; the Keycloak level-2 max age mirrors the policy window; **the supervised path is
> human-only — closed to agent calls, any tier, for now**).

## Why this slice exists

Slice B closed a supervisor's production contents with a **plain 403** and made the tier
unstrippable — but oversight that can never open production is a wall, not a gate. The consumer's
requirement is that production-level view be possible **behind a fresh second factor**: one TOTP
opens the contents for a bounded window, refresh tokens cannot stretch the window (refresh preserves
`auth_time` — measured), and every privileged read leaves an audit trail. Spring Security has no
resource-server step-up; the RFC 9470 emitter this slice ships in the starter is a genuine
differentiator, not a re-implementation ([[0030-step-up-decision-contract|ADR 0030]] §Context).

## The pins (settled 2026-08-13; full rationale in [[00-DESIGN]] + ADR 0030 §5–9 + §Amendments)

1. **SPA out; two declared parts** — the scripted code-flow token miner proves the round trip; the
   SPA's challenge UX is a collaborative follow-up.
2. **`not elevated` on the production clauses only**; the absent/unproven clause is
   elevation-proof; `loa`/`max_age: 300`/`skew: 30` in a `step_up` data JSON; OPA's own clock.
3. **`deny_reason` only when step-up is the sole blocker** (granted, and no other deny fires) —
   fingerprinting, the write ceiling, and the agent challenge-loop all fall out of this one rule.
4. **Typed `DenyReason` + additive `OpaClient.decide()` default method**; malformed reason → plain
   deny; resilient fail-closed results → plain deny; bulk/filter stay boolean;
   `OpaPreAuthorizeAuthorizationManager` only.
5. **Advice-based emitter, library-owned**: `StepUpRequiredDecision` → the `AbstractProblemAdvice`
   401 branch + `WWW-Authenticate` (params from the reason, never local config) +
   `LibraryErrorCode.STEP_UP_REQUIRED` (`ApiErrorCode` is the interface); partial reason → plain 403.
6. **Realm declarative**: `basic`+`acr` scopes on both clients (the ADR-diagnosed fix), the
   conditional level-2 TOTP subflow with max age 300 mirroring the policy window, `sup-anna`'s
   seeded fixture OTP secret; runner documents down-first re-import.
7. **Audit = two events on `opa.abac.audit`** (`STEP_UP_CHALLENGED` in the advice,
   `SUPERVISED_PRODUCTION_READ` in the manager); emission never affects the decision.
8. **The supervised path is human-only**: `provenance == "supervised"` + the `act_chain`
   presence-test → deny, any tier, always a plain 403 (sole-blocker suppresses the challenge); the
   wire claim is `act_chain` — `actor` is MCP-internal and never travels downstream.
9. **The e2e token path is the scripted PKCE code flow** (stdlib python3 + RFC 6238 TOTP) — ROPC
   structurally cannot carry `auth_time`; existing runners untouched.

## Headline proof

`sup-anna` reads her report's production catalog's contents: **401** with a well-formed RFC 9470
challenge → one TOTP → **200**, for five minutes; re-auth *without* `max_age` reuses the SSO session
and stays 401 (the loop-prevention negative, measured); the freshness drill shows the elevation
expiring on the wire; an out-of-unit supervisor and an elevated `PUT` get plain 403s with no
challenge; a member never sees any of it; and an agent-marked MCP call (the `act_chain`
delegation claim) is refused outright, any tier — the "elevated agent" combination is unmintable
on this rig, and its contract is pinned by constructed-input `opa test`.

## Tickets (status table)

| # | Title | Status |
|---|---|---|
| T1 | realm: `basic`+`acr` scopes, ACR-to-LoA map, the conditional level-2 TOTP subflow (max age 300), anna's seeded OTP credential | 📋 TODO |
| T2 | policy: the `step_up` data JSON, `elevated`, the amended production denies, `stepup_denied` + sole-blocker `deny_reason`, the agent deny (all three leaf policies), mutation guards | 📋 TODO |
| T3 | library envelope: `OpaDecision`/`DenyReason`, `OpaClient.decide()` default, `HttpOpaClient` parse, `ResilientOpaClient` **overridden** passthrough (the default-method trap) | 📋 TODO |
| T4 | manager + emitter + audit + wiring: `decide()` adoption, `StepUpRequiredDecision`, the advice 401 branch + `STEP_UP_REQUIRED`, both audit events, the catalog service's `attribute-claims` yaml (`acr`, `auth_time`, `act_chain`), the supervised-leg agent guard in `CatalogListAuthorizer` | 📋 TODO |
| T5 | the code-flow token miner (`mint-code-flow-token.py`, stdlib + TOTP) | 📋 TODO |
| T6 | e2e: the step-up matrix (E1–E7 incl. the freshness drill + the log-grep cell), the seven enumerated production-tier C-flips, non-regression, the runner | 📋 TODO |

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The settled design: mechanism, nine forks, fail-closed posture, execution parts. *(written at phase ①)* |
| `01-DECOMPOSITION.md` | The ordered work list T1…T6 + the critical path. *(written by /decompose)* |
| `10-QA-TEST-CASES.md` | The U*/I*/E* cases each ticket's Acceptance cites. *(written by /decompose)* |
| `AUTONOMOUS-IMPLEMENTATION-PROMPT.md` | The verbatim phase-③ prompt. *(written by /decompose)* |
| `STATUS-01…06.md` | Per-ticket run records. *(scaffolded by /decompose)* |

## Conventions

Branch `feature/void3110/step-up-elevation` off clean `main` · identity
`Void3110 <void31102025@gmail.com>` · clean-room (no consumer names) · do-NOT-push (the maintainer
pushes) · the package validates through both phase-② gates before any run.

## Related

[[PRODUCTION-TIER]] (slice B — the tier this slice opens) ·
[[SUPERVISED-SCOPE]] (slice A — the reach) ·
[[0030-step-up-decision-contract|ADR 0030]] · [[0032-root-attribute-enrichment-input-contract|ADR 0032]] ·
[[0031-inheritance-confined-to-membership-roles|ADR 0031]] · [[0029-supervised-read-scope|ADR 0029]] ·
[[0028-agent-tool-call-authorization|ADR 0028]] (the agent surface §6 closes)
