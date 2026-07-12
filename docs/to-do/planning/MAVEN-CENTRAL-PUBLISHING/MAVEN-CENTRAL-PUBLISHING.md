---
tags:
  - status/planned
  - type/index
  - area/build
---

# MAVEN-CENTRAL-PUBLISHING — first Maven Central release (Phase 7, the 1.0 blocker)

> **🚧 Planning (design settled 2026-07-13).** The library's **first Maven Central publish** — the
> **sole remaining 1.0 blocker**. Design grilled to six pinned forks; rationale in
> **[[0027-maven-central-release-engineering|ADR 0027]]**, the mechanism in [[00-DESIGN]]. This slice is
> **build/release infrastructure only** — it changes no library source and does not touch the
> `opa-abac-core` Spring-free boundary. Phase 7 of [[POC-ROADMAP]].

## Why this slice exists

**The gap.** Every functional slice, the Boot 4 port ([[0026-spring-boot-4-single-line-port|ADR 0026]]),
and the entire 7.4 pre-publish gauntlet are done — but the library has **no publish wiring anywhere**.
It is not yet resolvable as a dependency. The 1.0 milestone and the portfolio artifact's public face both
hinge on it being `dev.dmitriikonovalov:opa-abac-*:1.0.0` on Maven Central.

**The mechanism.** Wire `com.vanniktech.maven.publish` (Central-Portal-native) onto the five library
modules, add a **BOM** (`opa-abac-bom`), reuse the `POM_*` metadata the repo already declares, generate
signed jar + sources + javadoc + POM per coordinate (local GPG key), set the version to `1.0.0`, harden
`.gitignore` against key material, and document the manual out-of-band steps in `RELEASING.md`.

**The headline.** A repo that is **release-ready** — a local dry-run yields six fully-signed, POM-complete
coordinates — plus the `RELEASING.md` that drives the maintainer's actual (out-of-band) publish.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the six pinned forks (→ ADR 0027), the scope boundary, the acceptance frame. |
| [[01-DECOMPOSITION]] | The ordered work list + the critical path. *(produced by `/decompose`)* |
| [[10-QA-TEST-CASES]] | Concrete cases → each ticket's Acceptance. *(produced by `/decompose`)* |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. *(produced by `/decompose`)* |
| STATUS-0N | One stub per ticket, filled at each checkpoint. *(produced by `/decompose`)* |

## Ticket status at a glance

> Decomposed 2026-07-13 — the full package ([[01-DECOMPOSITION]], [[10-QA-TEST-CASES]],
> `AUTONOMOUS-IMPLEMENTATION-PROMPT`, STATUS-01…06) is written; tickets are unstarted.

| # | Title | Status |
|---|---|---|
| T1 | Root publish wiring: vanniktech plugin + signing + sources/javadoc jars (library modules only) | ✅ done |
| T2 | Per-module POM identity (`POM_NAME`/`POM_DESCRIPTION`/`POM_ARTIFACT_ID`) for the 5 libraries | ✅ done |
| T3 | New `opa-abac-bom` module (`java-platform`) + settings include | ✅ done |
| T4 | Release version `1.0.0` + broad `.gitignore` hardening + `-Xdoclint:none` javadoc | ✅ done |
| T5 | `RELEASING.md` — the manual out-of-band release runbook | ✅ done |
| T6 | Local dry-run verification (`publishToMavenLocal`) — the proof gate (6 signed, POM-complete coordinates; examples none) | ⬜ planned |

## The manual, out-of-band steps (the maintainer's — see [[00-DESIGN]] §1 / ADR 0027)

Not automatable in the repo; documented in `RELEASING.md`:
1. Central Portal account + claim `dev.dmitriikonovalov` + DNS **TXT** on `dmitriikonovalov.dev`.
2. GPG keygen → public half to a keyserver → private key/passphrase + Central token in `~/.gradle/gradle.properties`.
3. Run the publish and press **Publish** on the Portal.

## Related
- Rationale: [[0027-maven-central-release-engineering|ADR 0027]].
- Platform baseline being published: [[0026-spring-boot-4-single-line-port|ADR 0026]].
- Roadmap milestone: [[POC-ROADMAP]] (Phase 7 — publish 1.0).
