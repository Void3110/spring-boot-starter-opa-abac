---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/keycloak
  - area/catalog-service
---

# SPA-CHALLENGE-UX — the console consumes the challenge

> **Status: Planning — mini package, collaborative build.**
> Design settled 2026-08-15 (grill-me: ten forks; [[00-DESIGN]] §Considered-and-rejected).
> The **collaborative follow-up** the supervisor epic deferred from slice C
> ([[STEP-UP-ELEVATION]], shipped 2026-08-15) — Phase 10 of [[POC-ROADMAP]], after A/B/C.
> **Not an autonomous run**: no implementation prompt, no orchestrator; `verify-package.sh` gates
> [1] (the prompt file) and [5] (prompt invariants) fail by design, the rest must pass.
> **Release gating:** the 1.2.0 Maven Central cut waits for this slice, then ships the whole story.
> **Validated:** 2026-08-15 — mechanical gate at its by-design state; a three-critic adversarial pass
> (backend seam · SPA/OIDC · fixtures/registry/docs) returned 4 run-stoppers + 12 should-fixes, all
> applied (headline: an inline `enum:` would not compile against the marker; the memo write-site sat
> behind two early returns; the `id`-pin fallback contradicted its own purpose — replaced by an
> admin-API lookup; `ENABLE_SPA` and `ENABLE_MCP` must ride the same `up`; the `max_age=0` rationale
> was wrong and is now reconciled with ADR 0030 §7).

## Why this slice exists

Slice C proved the RFC 9470 round trip with a scripted token miner. The demo console — the thing a
reader actually opens — cannot follow the challenge it now receives, and has never shown which
catalogs a supervisor holds by supervision or which are production. This slice makes the console
*consume* C: the challenge explained in the server's own words, one [Verify], a fresh second factor,
back to the same place, a countdown learned from the server, amber where the client predicts and
red only where the server decided — plus the `_provenance` affordance ([[0033-catalog-provenance-affordance|ADR 0033]])
that makes the labeling server-truthful, and a demo supervisor persona so a fresh rig tells the
whole A/B/C story in the browser.

## The pins (settled 2026-08-15; full rationale in [[00-DESIGN]])

1. **Scope: challenge + visibility + wire provenance** — `_provenance: member|supervised` on catalog
   list items + the single GET, absent-when-not-computed, one semantic two derivations (ADR 0033).
2. **The 401 moment is an inline locked panel** in the drill-in contents area, showing the server's
   parameters + [Verify]; nothing modal, no auto-redirect.
3. **[Verify] = full-page redirect** with the miner's measured recipe (`acr_values` from the
   challenge, **`max_age=0`** — satisfies ADR 0030 §7's MUST as a strict superset; the loop comes
   from *omitting* it — and the essential-`acr` `claims`), the drill-in location (ids only) in the
   OIDC `state`; `prompt:'login'` moves to the initial login call.
4. **One automatic retry** (the restored view's own load); a post-callback 401 renders the panel
   **passive** with an honest notice; the UI never auto-redirects.
5. **The chip's window is learned** from the last challenge's `max_age` (sessionStorage) — never
   hardcoded; **expiry is reactive** (chip flips amber, content stays, the next fetch challenges).
6. **Amber = client prediction** (`supervised ∧ production ∧ not elevated`), red/401 = server truth;
   members' production catalogs are never amber.
7. **The demo world gets its own supervisor** — `sup-demo` (seeded TOTP; her `sub` looked up on the admin API, never minted by the seed) + `pm-demo`,
   owned by `seed-demo-data.sh`, registered beside the reserved families; `sup-anna` untouched.
8. **Validation = a committed UI case list run adversarially in the Browser pane** (packaged SPA
   through the gateway) with a ratchet, plus vitest for the pure seams; no Playwright.
9. **Ship shape = mini package + collaborative build**, `/deep-review` before PR; ADR up front.

## Tickets

| # | Ticket | Status |
|---|---|---|
| T1 | the `_provenance` affordance: spec + marker + memo + advice + tests + ADR 0033 | ✅ |
| T2 | the SPA challenge seam: parser, `StepUpRequiredError`, `request()` classification, vitest | 📋 |
| T3 | the locked panel, [Verify], the state-carried location, restoration, one retry | 📋 |
| T4 | the elevation chip (learned window, reactive expiry) + the row badges | 📋 |
| T5 | `sup-demo`/`pm-demo` in the realm, the seed's supervised block, the registry row | ✅ |
| T6 | the pane pass (E10–E21), the launch attach entry, the ratchet, close-out | 📋 |

Decomposition: [[01-DECOMPOSITION]] · QA cases: [[10-QA-TEST-CASES]] · per-ticket record: `STATUS-01…06`.

## What this slice does not do

No library change (`Enrichable`, the manager, the advice contract untouched); no `_provenance` on
categories/products; no popup/silent-renew/BFF; no client-side hiding at expiry; no scripted
browser automation; no release before it ships.
