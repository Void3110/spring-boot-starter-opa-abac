---
tags:
  - status/planned
  - type/project
  - area/build
---

# Maven Central publishing — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the Maven Central publishing setup
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The
> design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/maven-central-publishing` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the Maven Central publishing setup** on branch `feature/void3110/maven-central-publishing`.

**The problem.** Every functional slice, the Spring Boot 4 port, and the whole 7.4 pre-publish gauntlet are
shipped to `main`, but the library has **no publish wiring anywhere** — it is not yet resolvable as a
dependency, which is the **sole remaining 1.0 blocker**. This slice wires `com.vanniktech.maven.publish`
(Central-Portal-native) onto the five library modules, adds a **BOM** (`opa-abac-bom`), reuses the `POM_*`
metadata the repo already declares, produces signed jar + sources + javadoc + POM for six coordinates at
`1.0.0`, hardens `.gitignore` against key material, and documents the manual out-of-band steps in
`RELEASING.md`. **Scope boundary:** this is **build/release infrastructure only** — it changes **no library
source** and does not touch the `opa-abac-core` Spring-free boundary. Explicitly **NOT** in scope: the actual
live publish to Central (a maintainer out-of-band act — Portal account + DNS-TXT namespace verification + GPG
keygen — documented in `RELEASING.md`, not automated); CI-automated releases; and writing/fixing javadoc prose.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `MAVEN-CENTRAL-PUBLISHING.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism, the six pinned forks, the scope boundary, and the fail-closed posture
   (a missing credential/verification fails the *publish*, never emits an unsigned/partial artifact).
3. `01-DECOMPOSITION.md` — the 6 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — [[0027-maven-central-release-engineering|ADR 0027]] (this slice implements it);
   [[0026-spring-boot-4-single-line-port|ADR 0026]] (the Boot 4 / Java 25 baseline being published).
5. `10-QA-TEST-CASES.md` — the build-task / generated-POM / filesystem cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5): the existing build topology —
   root `build.gradle.kts` (the `allprojects`/`subprojects` blocks + the Java-25 toolchain), each library
   module's `build.gradle.kts` (they apply `java-library` and each already declares a `description = "…"`),
   `gradle/libs.versions.toml` (the `[versions]`/`[plugins]` catalog), `gradle.properties` (the pre-scaffolded
   `GROUP` / `VERSION_NAME` / `POM_*` block), `settings.gradle.kts` (the library-vs-examples `include` split),
   and the existing `.gitignore`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (not needed for this build-only slice, but read the conventions).
9. **Prime Mulch:** `ml prime opa-abac` + the directly-relevant record ids: `mx-3df6fe` (ADR-with-decomposition
   convention), and search `ml search "gradle version catalog module"` for the build-topology records.

### Per-ticket loop (tickets T1 → T6, IN ORDER; T5 (`RELEASING.md`) is independently landable any time after T1; T6 is the proof gate and MUST run last)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact modules, the version-catalog
   alias, the `mavenPublishing { }` config, per-module `gradle.properties`, the `opa-abac-bom` `java-platform`,
   `RELEASING.md`. Match the surrounding build's naming and idioms (Kotlin DSL, the existing catalog style).
   **Clean-room:** no proprietary names anywhere (POM names, RELEASING.md, properties).

3. **Write/extend the tests** for the ticket. *This is a build/release slice — the "tests" are the
   Gradle-task / generated-POM / filesystem assertions in `10-QA-TEST-CASES.md`* (U*/I*/E*/D* run as
   `./gradlew` invocations + inspecting `~/.m2/repository/dev/dmitriikonovalov/…` + `git check-ignore`),
   **not** JUnit/Testcontainers/`opa test`/newman (there is no source, rego, service, or gateway surface in
   this slice). Where a case is review-only (D1), state the review outcome in the STATUS note.

4. **Compile + run the checks until green.** `./gradlew build` (must stay green throughout), plus the
   ticket's specific `./gradlew` acceptance commands. Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once the ticket's
   checks pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: a missing signing
     credential or a failed namespace verification makes the *publish* fail; it never emits an **unsigned**
     or **partially-formed** artifact, and `build`/`test` never depend on a secret. No path produces a
     publishable-but-broken coordinate.**
   - **Security check — name the widening that would matter for this ticket: a secret/key/token reaching a
     committed file or the artifact; the plugin leaking onto the example apps (publishing a demo); a POM
     exposing an internal/proprietary name — and state why it cannot happen (key material is user-home only +
     the broad `.gitignore`; the plugin is on an explicit library allow-list; POM strings are clean-room).**
   - **Concurrency / idempotency check — publishing is not a request-time mutation, so the locking rules
     are N/A; instead confirm the build is idempotent (re-running `publishToMavenLocal` reproduces the same
     coordinates) and configuration-cache-safe if enabled.**
   - **Wiring check** — every seam this ticket adds (the plugin application, a `gradle.properties` key, the
     BOM `include`, the javadoc option) has a **named consumer** and is exercised by a non-happy-path or
     off-target check (examples get **no** publish tasks; `build` stays green with **no** signing creds); zero
     call sites = the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (the BOM is a `java-platform`, no
     code); the change is additive build wiring; name the byte-for-byte-unchanged surfaces (all library
     source; all example modules) and the one mechanical cost (new per-module `gradle.properties`).**
   - **Module-layer separation — publish wiring lives in the root build + per-module `gradle.properties` +
     the new BOM module; no library module gains source; examples gain nothing.**
   - **Pattern-reuse check — reuse the repo's existing conventions: the version-catalog alias style, the
     `gradle.properties` POM_* convention (already vanniktech-shaped), the Kotlin-DSL idiom; do not
     hand-roll a `maven-publish` block the plugin already provides.**
   - **SOLID / decomposition** — cohesive config; anything to split/simplify (e.g. a shared publish
     convention block vs repetition)?
   - **Apply** the refactoring the review surfaces, then **re-run the checks** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - **T6 (the proof gate):** with a **throwaway** local signing key configured in
     `~/.gradle/gradle.properties`, run `./gradlew publishToMavenLocal`; assert **E1** — the six-coordinate
     signed, POM-complete artifact set in `~/.m2/repository/dev/dmitriikonovalov/…` (5 libraries with
     jar+sources+javadoc+pom+`.asc`; the BOM POM-only+`.asc`), each POM passing I1/I2/I3, and the **examples
     publish nothing**. If no signing key is available in the run environment, the CI-safe fallback is **U5**
     (`generatePomFileFor…` task-graph + POM-generation dry-run for all 6, examples excluded), with the full
     signed dry-run flagged as a maintainer step in `RELEASING.md`.
   - There is **no rig / newman / gateway** step in this slice.

7. **Update documentation (after each ticket).** Tick the ticket in the `MAVEN-CENTRAL-PUBLISHING.md` status
   table; record real values/decisions in `STATUS-0N.md` (the resolved plugin version, the actual artifact
   list from the dry-run, any surprise). The ticket that finalizes the runbook writes `RELEASING.md` (T5) and
   points `README.md` at it. Root/project `CLAUDE.md` only if a new build/run step matters (a "Releasing" or
   "publish" note likely does).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (config + the acceptance-check evidence + docs + the
   `STATUS-0N.md` note together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `build(publish): …` (or `docs(releasing): …` for T5). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the `./gradlew` acceptance-check summary
    (+ the dry-run artifact list for T6), **summarize the review findings + the refactoring you applied**
    (step 5), list docs updated, and note any open question you resolved. Then proceed to the next ticket.
    **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the **root and per-module Gradle build files, the version catalog, `settings.gradle.kts`,
  `gradle.properties`, `.gitignore`, the new `opa-abac-bom` module, `RELEASING.md`, `README.md`, the docs in
  this folder**, and Mulch — all on this branch.
- Configure a **throwaway** local signing key in `~/.gradle/gradle.properties` for the T6 dry-run (never
  committed); run `./gradlew publishToMavenLocal` and inspect `~/.m2/…`.
- Fix any issue your own validation reveals (build, task-graph, POM shape, gitignore). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE the T6 dry-run
  validation** — checks green → review → refactor → re-test → then the dry-run. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant:** no path emits an **unsigned or partially-formed**
  publishable artifact; a missing credential/verification fails the publish, it does not silently ship a
  broken coordinate — and `build`/`test` never require a secret.
- **Slice-specific invariants — never trade these away:** the plugin is applied to the **5 library modules
  + the BOM only, NEVER the example apps**; **no library source is touched** (additive build wiring only);
  **no key/token/credential in any committed file** (user-home + broad `.gitignore` only); `GROUP` stays
  `dev.dmitriikonovalov` and the existing `POM_*` values are reused, not rewritten; **`VERSION_NAME` is
  `1.0.0` in this branch** (the `1.1.0-SNAPSHOT` bump is a post-publish *operator* step in `RELEASING.md`,
  not a commit here).
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source
  (POM names + RELEASING.md are public).
- **`opa-abac-core` stays Spring-free** — trivially (build-only slice; the BOM is a `java-platform`).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes. **Do NOT run
  `publishToMavenCentral` / the live release** — the dry-run is `publishToMavenLocal` only; the real publish
  is the maintainer's out-of-band act.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T1** (the plugin/signing/jars wiring — everything hangs on it) and **T6** (the
  six-coordinate signed dry-run — the proof that justifies the whole slice). T3 (the BOM) is the second
  visible deliverable.
- **The fail-closed edge to eyeball** — the plugin must be on an **explicit library allow-list**, never a
  blanket `subprojects` — a careless `subprojects { apply(...) }` would silently publish the **example demo
  apps** (secrets, demo fixtures, the whole rig story) to Central. That is the one place this slice leaks if
  done carelessly. Second edge: a signing key or Central token landing in a committed file — the broad
  `.gitignore` (T4) must be in place before any key is generated.
- **Standalone-value subset** — T1 + T2 + T6 (the 5 libraries publishing, signed + POM-complete) is a
  shippable core if the window is short; the BOM (T3) and RELEASING.md (T5) are the polish/completeness layer.
  The design commits to all six, so land them all.
- **Rig / e2e specifics** — **none.** This slice touches no rig, no services, no gateway, no rego. The only
  "e2e" is the local `publishToMavenLocal` dry-run against `~/.m2`.
- **CI does not run the rig yet** — irrelevant here (no rig); but note the T6 dry-run needs a signing key, so
  a CI variant uses the U5 POM-generation fallback (no key) and the full signed dry-run is a maintainer step.
- **The live publish is out-of-band** — the slice delivers a *release-ready* repo + `RELEASING.md`; it does
  **not** (and cannot) perform the Central publish autonomously (namespace verification + key + Portal
  credentials are the maintainer's).
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
