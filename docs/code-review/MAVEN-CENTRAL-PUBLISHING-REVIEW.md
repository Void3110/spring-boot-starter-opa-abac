---
tags:
  - status/active
  - type/review
  - area/build
---

# Maven Central Publishing — Code Review

> **Verdict**: **Approved, no fixes** — zero findings across a multi-lens adversarial review + independent spot-verification.
> **Scope**: The build/release-infra slice (T1–T6, ADR 0027) that wires `com.vanniktech.maven.publish`
> onto the 5 library modules + a new `opa-abac-bom` platform, adds per-module POM identity, sets
> `VERSION_NAME=1.0.0`, hardens `.gitignore`, and documents the manual release in `RELEASING.md`.
> **Branch**: `feature/void3110/maven-central-publishing` vs `main` (9 commits, +647/−37, 24 files).

## Summary

A packaging/release-only slice — **no `src/main`, `*.rego`, controller, entity, DTO, OpenAPI, infra, or
example-app change**. Reviewed via the 2B multi-lens adversarial workflow (8 lenses + a completeness
critic, adversarial refutation) **and** an independent hand spot-check of the seven maintainer-flagged
risks. Both passes returned **zero issues**. The T6 signed `publishToMavenLocal` dry-run had already
proven the 6-coordinate signed set with the examples publishing nothing; this review confirms the wiring
that produces it is correct, clean-room, and secret-free.

## Critical Issues

None.

## Medium Issues

None.

## Low / notes (non-blocking, no change made)

- **Generic `*.local` gitignore glob is broad.** It correctly covers `CLAUDE.local.md` and any future
  local override, and no tracked file ends in `.local` today (verified: `git ls-files | grep '\.local$'`
  empty). If the repo ever needs a committed `*.local` file, it would be silently ignored — a theoretical
  footgun, not a present defect. Left as-is (the breadth is the intended hardening).

## Fail-closed verification (this slice's form: no demo app can publish)

The load-bearing edge — the plugin must **never** reach the two `example-*` apps — verified three
independent ways, all agreeing:

1. **Static:** `build.gradle.kts` applies the plugin via `configure(subprojects.filter { it.name in
   publishableJarModules })` — an explicit 5-name allow-list, never a blanket `subprojects {}`. The only
   `example-` token in the file is a guardrail **comment** (`// NEVER add an example-* module here`).
2. **Runtime plugin matrix** (init-script `plugins.hasPlugin` probe): 5 libraries = `java`+`publish`;
   `opa-abac-bom` = `java-platform`+`publish`; **both `example-*` = `java` only, NO publish**.
3. **Artifact-level** (the T6 dry-run): no `example-*` coordinate under `dev.dmitriikonovalov`; exactly 6
   coordinates, each signed.

The BOM `constraints {}` lists only the 5 `opa-abac-*` libraries — no `example-*`. Signing is wired into
every publish task (a no-key run fails at signing, never emits an unsigned artifact — confirmed live in T6:
`signMavenPublication` executed, `gpg --verify` → "Good signature").

## Security audit (release-artifact hygiene — no app-source surface in this diff)

- **No secret in any committed file.** `git diff main...HEAD -- ':(exclude)*.md'` scanned for
  `glpat-`/`squ_`/`BEGIN…PRIVATE`/populated `signingInMemoryKey=`/`mavenCentralPassword=`/the DNS-TXT
  token → **none**. `RELEASING.md` carries only `<placeholders>`; the only matches in `.md` files are
  STATUS/RELEASING prose *describing* the property names, not values.
- **Key material sourced from `~/.gradle` only** (as commented in the wiring); the T6 throwaway key lived
  in `~/.gnupg` + `~/.gradle` and was deleted after — the working tree is clean of key-like files.
- **`.gitignore` hardening verified:** `git check-ignore` matches all 8 key globs
  (`*.gpg *.key *.p12 *.pfx *.jks *.keystore *.pem` + `*.asc`); no key file is tracked.

## Concurrency & idempotency

**N/A** — no entity, changelog, `@Version`, lock, or mutating handler touched. Gradle publishing is not a
request-time mutation; re-running `publishToMavenLocal` reproduces the same coordinates (idempotent).

## Wiring & sibling sweep

- **New seams all have consumers:** the plugin application (5 libs have publish tasks, examples don't —
  proven); the BOM `include` (library group, produces a POM-only coordinate — proven); each per-module
  `POM_*` property (flows into the generated POM — I1/I2). No orphan seam.
- **Sibling sweep:** the "publish wiring" pattern's siblings are the 5 library modules + the BOM — all
  carry the same allow-list/POM treatment consistently; the mirrored non-members (2 example apps) are
  uniformly excluded. Siblings clean.

## Autonomous-run check

This branch came from a ticket-by-ticket run (STATUS-01..06). Lens check:
- **Laziness:** no — every ticket's acceptance was actually exercised (U1/U2/I1/I2/U3/U4/E1 run, not just
  asserted), and STATUS notes match the diff.
- **Self-preferential bias:** no — the STATUS "review found X" entries are truthful: T1 and T3 each
  **honestly record a real refactor** (the `plugins.withId` deferral; the `java-platform`⊥`java` rescope),
  not ritual "nothing found" notes.
- **Goal drift:** no — `VERSION_NAME` held at `1.0.0` (not bumped to `1.1.0-SNAPSHOT`); `opa-abac-core`
  gained 0 Java lines (Spring-free intact); the change stayed additive build-wiring across all 6 tickets;
  the fail-closed edge held from T1 through T6.

## What's done right

- The allow-list is the correct fail-closed shape; the guardrail comment makes the invariant explicit.
- The `plainJavadocJar` task-name correction (T4) was fed back into the QA-case doc, not left stale.
- `RELEASING.md`'s DNS section is grounded in the *actual* namespace-verification flow (reg.ru `@`-apex,
  multi-resolver `dig` check), so it's a repeatable runbook rather than a generic instruction.
- The BOM is a pure constraints platform (`allowDependencies()` not called) — no transitive leakage.

## Test results

- `./gradlew build`: **green** (run at T3 and T4; the publishing wiring never broke the normal build).
- `./gradlew publishToMavenLocal` (T6, throwaway signed): **6 signed, POM-complete coordinates; examples
  none; `gpg --verify` → Good signature**.
- `opa test` / newman matrix: **N/A** — no policy or runtime path touched.
- Multi-lens workflow: **8 lenses + critic, 0 confirmed, 0 refuted** (417K tokens, 83 tool calls).
