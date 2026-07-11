---
tags:
  - status/planned
  - type/index
  - area/architecture
  - area/spring
---

# SPRING-BOOT-4-PORT — target Boot 4 first-class (the publish line)

> **Status: Planning — decomposed, ready for the autonomous run.** The Spring Boot 3.4 → 4.0 port:
> the whole 3.x line is out of OSS support, and a library publishing its 1.0 in late 2026 must target
> the current line — Boot 4.0 (Framework 7 / Security 7 / Jakarta EE 11 / Hibernate 7 / Jackson 3) on
> **Java 25 / Gradle 9.x**, as a **single-line artifact** (no dual 3.5/4.0). Deliberately mechanical:
> the acceptance frame for every ticket is *byte-identical observable behavior on the new line*.
> Pre-publish sequence per [[POC-ROADMAP]]: this port → the 7.4 delta security review → publish 1.0.

## Why this slice exists

The 3.4-built artifact already *runs* on Boot 4 (proven by an external consumer, 2026-07-08 — Java 25
+ SB4, clean); the port is about **targeting** it first-class: compile against Boot 4, retire the
already-deprecated APIs we sit on (`AuthorizationManager#check`, `Specification.where(null)`, the R4j
internal constructor), and publish the right story. Research: [[RESEARCH]]; settled design (forks
F1–F9, grill-me 2026-07-11): [[00-DESIGN]]; packaging/Java-25/Jackson-3/R4j decisions pinned in ADR
[[0026-spring-boot-4-single-line-port|0026]].

## Files in this folder

| File | What it is |
|---|---|
| [[RESEARCH]] | The 2026-07-08 research pass — per-module port inventory (pre-grill input). |
| [[00-DESIGN]] | The settled design: forks F1–F9, scope fence, acceptance, decompose-level to-dos. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T7 + critical path + sizing-gate verdict. |
| [[10-QA-TEST-CASES]] | Concrete B/S/R/H/C/W/D/E/P cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-07 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | Boot 3.4.13 → 3.5.x + the deprecation map | ✅ DONE |
| T2 | Security 7 pre-migration: covariant `authorize()` (on 3.5) | ✅ DONE |
| T3 | Resilience4j 2.4.0: delete the internal coupling | ✅ DONE |
| T4 | THE BUMP: Boot 4.0.x + Gradle 9 + JDK 25 + renames + test churn | ✅ DONE |
| T5 | Core Jackson 3 + the three wire-format parity pins | ✅ DONE |
| T6 | Data JPA 4 idiom + deprecation zero-out | 📋 TODO |
| T7 | Rig rebuild + e2e fleet + PERFORMANCE.md re-baseline + docs sweep | 📋 TODO |

Critical path: **T1 → (T2 ∥ T3) → T4 → (T5 ∥ T6) → T7**; T1–T3 are independently landable (still a
3.5 repo with two deprecation classes retired). T4 is the flagged build-breaker commit *by design*.
See [[01-DECOMPOSITION]] for per-ticket Goal/Deliverables/Acceptance/What-NOT-to-touch.

## Conventions

- **Clean-room:** original neutral names only; no proprietary names/paths/ids in any committed file.
- **Commit identity:** `Void3110 <void31102025@gmail.com>` (repo-local; verify before committing).
- **Branch:** `feature/void3110/spring-boot-4-port` off a clean `main`; the maintainer tags
  `pre-sb4-port` on the last 3.4 commit at merge time.

## Related

- [[POC-ROADMAP]] — the pre-publish sequence (this port sits between 7.3 and the 7.4 delta review).
- ADR [[0026-spring-boot-4-single-line-port|0026]] — packaging, Java 25, Jackson 3, R4j 2.4.0.
- ADR [[0017-cross-service-http-resilience|0017]] — the CallGuard seam T3 amends (internal pin
  eliminated as of R4j 2.4.0).
- ADR [[0021-load-testing-methodology|0021]] — governs T7's re-baseline (validity gates,
  report-only).
- [[RESOLVE-COALESCING]] — the predecessor slice; its ledger is the "before" T7 re-baselines against.
- [[USER-STORIES]] Epic F (F6 — the port story).
