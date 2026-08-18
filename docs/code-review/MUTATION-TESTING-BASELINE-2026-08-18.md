---
tags:
  - status/active
  - type/review
  - area/methodology
---

# Mutation-testing baseline (PIT) — 2026-08-18

**ENGINEERING-BACKLOG item 1, shipped.** PIT now runs over six JVM modules, **report-only, no
threshold**. This note is the baseline the first gate will be argued from, and it records two things
the backlog got wrong — both measured, not reasoned.

> **Read `SURVIVED`, not the score.** A survivor means *a test executed that line and did not notice
> the code changed underneath it*. That is the signal. `NO_COVERAGE` mostly measures which suites this
> wiring deliberately excludes (below), and the raw mutation score inherits that distortion.

## How to run it

```bash
./gradlew mutationTest                          # every wired module (~1m20s, warm)
./gradlew :opa-abac-core:mutationTest           # one module
open opa-abac-core/build/reports/pitest/index.html
```

## The baseline

| Module | Mutations | Killed | Survived | No coverage | Score | **Test strength** |
|---|---:|---:|---:|---:|---:|---:|
| `opa-abac-core` | 275 | 222 | 17 | 36 | 80% | **92%** |
| `opa-abac-spring-security` | 466 | 412 | 26 | 28 | 88% | **94%** |
| `opa-abac-spring-data` | 293 | 179 | 18 | 96 | 61% | **90%** |
| `opa-abac-keycloak-directory` | 20 | 15 | 3 | 2 | 75% | **83%** |
| `opa-abac-spring-boot-starter` | 130 | 106 | 17 | 7 | 81% | **86%** |
| `example-catalog-management-service` | 484 | 189 | 17 | 278 | 39% | **91%** |
| **Total** | **1668** | **1123** | **98** | **447** | **67%** | **91%** |

Test strength (killed ÷ mutants a test actually reached) is **83–94% everywhere**, including in the
two modules whose headline score looks alarming. Those two — `opa-abac-spring-data` (61%) and
`example-catalog-management-service` (39%) — mix Docker-backed `*IT` classes with plain unit tests,
and PIT re-runs the selected tests **once per mutant**: leaving a Testcontainers IT in that loop turns
a one-minute analysis into an unusable one. `--targetTests` is therefore restricted to `*Test` in those
two modules, so a class exercised **only** by an IT reports `NO_COVERAGE` rather than as genuinely
untested. That is a scoping artifact, not a finding.

## What the backlog got wrong

### 1. The Gradle plugin does not work here

Item 1 said "a Gradle plugin + a threshold. Effort: low". `info.solidsoft.pitest` **last shipped
2023-09** and fails to even apply on this repo's Gradle 9.6.1 wrapper:

```
Could not get unknown property 'baseDir' for extension 'reporting'
```

`ReportingExtension.baseDir` was removed in Gradle 9. Every alternative on the plugin portal is a
staler fork or Android-specific. PIT is therefore wired as a plain `JavaExec` against its own
command-line entry point (`org.pitest.mutationtest.commandline.MutationCoverageReport`) in the root
build, using the repo's existing allow-list idiom. That is *better* than the plugin here: no
third-party plugin to age out, and the engine tracks its own current line — which matters, because
only PIT ≥ 1.20 with **ASM 9.10.1** reads our Java 25 class files at all.

**One load-bearing detail.** `pitestRuntime` must come **first** on the classpath. Boot's test starter
drags **ASM 9.7.1** in transitively (`spring-boot-starter-test` → `json-path` → `json-smart` →
`accessors-smart`); 9.7.1 reads class files only to Java 24, so whenever it won the classpath race PIT
died on our own output with `Unsupported class file major version 69`. It is a silent, order-dependent
failure — `opa-abac-core` and `opa-abac-spring-security` never hit it because neither pulls Boot's test
starter.

### 2. PIT does **not** catch the defect that motivated ranking it #1 — measured

Item 1's case rested on one defect: `CatalogProvenanceAdviceTest` asserted on the **input** page rather
than on what `beforeBodyWrite` **returned**, so a rebuilt-body implementation would have passed. A
reviewer caught it by mutating the production code by hand. The backlog concluded "PIT automates
precisely that."

**It does not.** Reverting that assertion to its exact pre-fix vacuous form and re-running PIT produces
a **byte-identical report** — 25 of 26 mutants on `CatalogProvenanceAdvice` still killed, no new
survivor. Two independent reasons:

- **A sibling test already covers the return value.** The mutant that asks "does anything check what
  this method returned?" (`NullReturnValsMutator` on `beforeBodyWrite:112`) is killed by
  `u7_aBodyThatIsNotACatalogShapeIsUntouched()`, which asserts `isSameAs(body)`. PIT scores the
  **suite**, so it cannot attribute a gap to the one test that has it.
- **No mutator generates the actual defect.** "Return a rebuilt-but-equivalent object" is not in PIT's
  repertoire; its object-return mutators are `null` / empty-object, which any identity assertion
  anywhere kills.

This does not make item 1 waste — the run surfaced 98 real survivors, several on the security spine.
But the specific claim that justified its rank is false, and **the manual mutation discipline it was
meant to replace must stay**: *prove a test by mutating the code it guards* remains a review step, not
an automated one.

## The survivors worth acting on

Not defects — the code reads correct in each case. These are guards nothing currently proves.

| Where | Survivor | What it means |
|---|---|---|
| `HttpOpaClient.isSafePath` (core) | **7**, incl. *replaced boolean return with `true`* | The hand-rolled policy-path validator — written deliberately to avoid a `StackOverflowError` the regex form would throw past the `catch (Exception)` fail-closed handlers. Making it accept **everything** fails no test; its length cap and character-class boundaries are unproven. Highest-value gap in the baseline. |
| `AbstractProblemAdvice.isSafeParameter` (security) | 5 boundary mutants | Same shape: a safety predicate whose boundaries no test pins. |
| `PrivilegedReadAuditPolicy.matches:65` | *replaced boolean return with `true`* | The `null`-input guard. Making null inputs **match** fires the privileged-read audit on nulls, untested. |
| `ResilientOpaClient.lambda$allow$1:82` | *replaced boolean return with `true`* | The retry predicate `denied -> denied == Boolean.FALSE`. Always-retry passes; nothing pins that a genuine **allow** is not retried. |
| `ActionEnrichmentAdvice.beforeBodyWrite:133,153` | 2 null-return mutants | The advice's return value is unchecked on two branches — the *same shape* as the `CatalogProvenanceAdvice` defect, and here PIT **does** see it. |

That last row is the honest counterweight to §2: PIT catches this defect class when no sibling test
happens to cover the return value.

## What NOT to do next

**Do not gate on a mutation score.** The baseline's spread (83–94% strength) is narrow enough that any
repo-wide threshold either sits below every module — proving nothing — or fails a module for scoping
artifacts. A noisy gate gets ignored, which is worse than none.

The useful next step is to **close the five survivor rows above** and re-baseline. A threshold, if one
ever lands, should be per-module, on `opa-abac-core` and `opa-abac-spring-security` only, set just
under their then-current strength.

## Related

- `docs/to-do/planning/ENGINEERING-BACKLOG/ENGINEERING-BACKLOG.md` — item 1
- `docs/code-review/SPA-CHALLENGE-UX-REVIEW.md` — the three-round review this item came out of, and the
  manual mutation discipline §2 says must stay
