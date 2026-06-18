---
tags:
  - status/planned
  - type/index
  - area/abac
  - area/architecture
  - area/spring
---

# B3 — Cross-service HTTP resilience

> **Status: 🔜 PLANNED — design settled (grill-me 2026-06-18), ready for `/decompose`.** The availability
> pass that **softens the outage → hard-deny wall** Slice B2 introduced — a uniform retry/backoff/
> circuit-break posture across all three cross-service HTTP edges — **without** re-opening the realm-role
> fallback B2 closed. Slice **B3** of [[POC-ROADMAP]] (route step 4: **B2 → 6.7 → Phase 6 → B3 →
> Phase 7**; B3 is now the front of the queue, last slice before publish). Pinned by **ADR
> [[0017-cross-service-http-resilience|0017]]**; the full design is [[00-DESIGN]].

## What it is

A resilience layer over the three cross-service HTTP edges so **transient** failures (pod restarts, GC
pauses, brief network weather) recovering within a bounded budget no longer surface as user-visible
denials, while **sustained** outages still fail closed exactly as B2 mandates. Resilience makes outages
**rarer**, never **wider** — it never re-opens B2's realm-role fallback.

The three edges and the failure contracts B3 must preserve:

| Edge | Path | On (exhausted) failure — unchanged by B3 | Module |
|---|---|---|---|
| `HttpOpaClient` (`allow`/`compile`/`allowAll`) | the gate, **every request** | `false` / `PartialResult.error()` / n×`false` | `opa-abac-core` (Spring-free, zero-dep) |
| `HttpRoleDefinitionSupplier` | role resolve, on the gate path | **throws `RoleResolutionException`** → deny (B2) | example app (catalog) |
| `TagDefinitionClient` | tag-assignment validation | **throws `TagDefinitionFetchException`** → 503 | example app (catalog) |

## The shape in one line

> A thin **`CallGuard`** seam (retry + breaker + injectable clock), **R4j-backed**, wraps each edge: the
> OPA edge as a **decorator over the existing `OpaClient` interface** (auto-configured by the starter,
> `@ConditionalOnClass` Resilience4j); the resolve/tag edges as app-side wrappers. **Uniform posture =
> uniform classification + config shape + fail-closed contract — NOT uniform numbers.**

## The pins (from the grill-me; full rationale in [[00-DESIGN]] + ADR 0017)

1. **OPA resilience = decorator over `OpaClient`** (Spring layer), **not** a pluggable transport in core —
   the `OpaClient` interface already provides the only benefit a lower seam would. Core untouched, zero-dep.
2. **Fail-closed identity, every state.** Exhausted-retry **and** breaker-open yield the delegate's
   fail-closed value: `allow`→`false`, `compile`→**`PartialResult.error()`** (`fromError=true` — never
   `denyAll()`/`allowAll()`, so no 5.5-B hierarchy widening outlives an OPA outage), `allowAll`→n×`false`.
   Pinned by a **contract test** (decorator == delegate), not shared code.
3. **Retry classification** {connect-refused, connect-timeout, read-timeout, 5xx, 429} retry; {4xx≠429,
   malformed-200} fail fast. **Side-effect-free invariant:** all three edges are read-only — retrying
   (incl. read-timeout) can't double-execute; any future *mutating* edge MUST opt out. Retry slots
   **before** B2's throw — only an *exhausted* 5xx throws; 4xx throws immediately, no retry.
4. **Asymmetric per-edge budgets** — OPA gate **1 retry / ~50ms / ~2-3s ceiling**; resolve & tag
   **2 retries / ~50ms / ~6s ceiling**; exponential backoff + full jitter; named configurable ceiling.
   **No-lock invariant:** no resilience-wrapped call runs under the team-row `FOR UPDATE` (it's
   server-side in user-mgmt, makes no outbound edge); future violations flagged in review.
5. **Three breakers, one per edge** (per-endpoint, not per-host — resolve & tag independent). **Breaker
   outcome-invariance:** the breaker is latency/load only, **never a decision input**; every state yields
   an already-reachable fail-closed outcome; open = strictly *more* fail-closed.
6. **Library ships OPA resilience via optional/conditional R4j** (`@ConditionalOnClass`) — adopter without
   R4j gets today's plain client. Resolve/tag resilience is necessarily app-side. Same R4j, same knobs.
7. **`CallGuard` seam — backend-agnostic, R4j-backed in B3**; the Boot-4 / Java-25-26 native-resilience
   backend is a one-impl swap later. The seam **exposes an injectable clock** (deterministic tests).
8. **Two baselines kept** (Java 21 / Boot 3 R4j · Java 25-26 / Boot 4 native), probably as **two
   separately-compiled artifact lines** — but B3 **builds only the Java-21/Boot-3.4 seam + R4j impl**; the
   second line is a later (Boot-4) slice.
9. **Per-edge kill-switch** — the principled inverse of B2's no-switch (B2's off = the vuln; **B3's off =
   a safe baseline**, like Phase-5/5.97). `enabled=false` ⟺ byte-identical to pre-B3; the switch governs
   retry/breaker only, **never** the fail-closed contract.

## Proof (the headline)

- **Transient outage recovering within budget → request SUCCEEDS** (B3 does its job) — and **sustained
  outage → still DENIES** (B2 not re-opened). Fault-injecting stub through the gateway (P9).
- Fail-closed identity / `compile`→`error()` on breaker-open / B2-preserved (resolve→`RoleResolutionException`,
  tag→503) / classification / breaker outcome-invariance / latency bound / kill-switch identity /
  optional-R4j conditional — P1–P8, all **virtual-time, zero `Thread.sleep`**. Full matrix in [[00-DESIGN]].

## Scope boundaries

- **In:** the `CallGuard` seam + R4j impl; the OPA decorator (starter, conditional); resolve/tag app-side
  wrappers; per-edge config + kill-switch; the proof matrix. Java 21 / Spring Boot 3.4 baseline.
- **Out → later slices:** the Boot-4 / Java-25-26 native-resilience backend + the second artifact line
  (**Boot-4 slice**); the **load-testing rig** + empirical budget/breaker-threshold tuning (p99 under a
  partial outage) → **Phase 7 polish** (with OPA-restart hygiene + CI-runs-e2e).
- **Untouched:** `opa-abac-core` (zero new types, zero-dep); **zero Rego**; B2's contract; the realm fallback.

## Related

- ADR [[0017-cross-service-http-resilience|0017]] — the structural decisions (this slice).
- ADR [[0014-supplier-outage-error-distinct|0014]] + [[B2-SUPPLIER-OUTAGE]] — the B2 fix B3 softens;
  B3's kill-switch is the principled inverse of B2's.
- ADR [[0010-hierarchy-aware-list-filter|0010]] — the `subtreeSpec` widening `compile`'s `fromError` flag
  suppresses on an OPA outage.
- ADR [[0005-partial-eval-to-jpa-specification|0005]] — `PartialResult.error()` vs `denyAll()`/`allowAll()`.
- [[POC-ROADMAP]] — the route box and the B3 row.
