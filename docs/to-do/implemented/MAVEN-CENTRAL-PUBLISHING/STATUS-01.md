---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T1: Root publish wiring: vanniktech plugin + signing + sources/javadoc jars (library modules only)

**Status:** ✅ DONE

## What shipped

- **`gradle/libs.versions.toml`** — pinned `vanniktechMavenPublish = "0.37.0"` (current stable line;
  `publishToMavenCentral()` is argument-less — Central Portal is the default host, no `SonatypeHost`)
  and a `[plugins]` alias `maven-publish = { id = "com.vanniktech.maven.publish", version.ref = … }`.
- **Root `build.gradle.kts`** —
  - Declared the plugin `apply false` in the root `plugins {}` block.
  - Added an **explicit library allow-list** `val publishableJarModules = setOf("opa-abac-core",
    "opa-abac-spring-security", "opa-abac-spring-data", "opa-abac-keycloak-directory",
    "opa-abac-spring-boot-starter")` — **never** a blanket `subprojects {}`.
  - `configure(subprojects.filter { it.name in publishableJarModules }) { apply(plugin = …); … }`
    applies the plugin only to those five, then — **deferred via `plugins.withId("java-library")`**
    (evaluation-order-safe: the root block runs before the module scripts apply `java-library`) —
    configures `mavenPublishing { publishToMavenCentral(); signAllPublications();
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true)) }`.
  - Javadoc `-Xdoclint:none -quiet` on the same allow-list (T4's deliverable, co-located here because
    the javadoc jar cannot build without it and T4 depends on T1 for exactly this — not premature).

## Tests

Build/release slice — the "tests" are Gradle-task/filesystem assertions ([[10-QA-TEST-CASES]]):

- **U1 ✅** — `./gradlew build` → `BUILD SUCCESSFUL` with **no signing credentials present**. The
  publishing wiring is inert to the normal build/test graph (tests stayed UP-TO-DATE after the config
  change — the wiring never touches compile/test).
- **U2 ✅** — `:opa-abac-core:tasks --group publishing` lists the full set (`publishToMavenLocal`,
  `publishAllPublicationsToMavenCentralRepository`, `publishToMavenCentral`, …);
  `:example-catalog-management-service:tasks --group publishing` lists **none**.
- **Clean per-module `--dry-run` audit:** `publishToMavenLocal` EXISTS on all 5 libraries, ABSENT on
  both example apps.

## Architecture review + refactor

Focused self-review against the runbook's gate:

- **Fail-closed ✅** — a `publishToMavenLocal` dry-run task graph **includes `signMavenPublication`**:
  signing is wired *into* the publish path, so a run with no key **fails at signing** rather than
  emitting an unsigned artifact. `build`/`test` never depend on a secret (U1). No path yields a
  publishable-but-unsigned coordinate.
- **Security (the widening that matters here) ✅** — the plugin leaking onto the example apps (which
  carry demo fixtures) would publish a demo to Central. Prevented by the **explicit allow-list** — the
  per-module `--dry-run` audit proves the two `example-*` modules have zero publish tasks. No key
  material in any committed file (keys are `~/.gradle` only; broad `.gitignore` lands in T4 before any
  key exists).
- **Idempotency ✅** — publishing is not a request-time mutation; re-running produces the same
  coordinates. Config-cache is intentionally OFF at the repo level (openapi-generator), unchanged here.
- **Wiring ✅** — every seam has a consumer exercised by an off-target check: the plugin application →
  the 5 libraries have publish tasks (present) and the 2 examples do not (absent); `build` stays green
  with no creds (off-state). Zero orphan seams.
- **Boundary / additivity ✅** — purely additive build wiring; **no library source touched**;
  `opa-abac-core` stays Spring-free (the plugin is build-only). Byte-unchanged: all library source, all
  example modules. Mechanical cost: none yet (per-module `gradle.properties` is T2).
- **Pattern reuse ✅** — reused the repo's version-catalog alias style and the Kotlin-DSL idiom; used
  the plugin's own `mavenPublishing {}` rather than hand-rolling a `maven-publish` block.

**Refactor applied:** one — the initial `configure(JavaLibrary(...))` failed at root evaluation
(`requires the java-library plugin to be applied`, because the root block evaluates before the module
scripts). Fixed by deferring the whole publishing config inside `plugins.withId("java-library") { … }`,
which fires exactly once per module when `java-library` arrives. Nothing else substantive — did not
invent additional structure (the single named allow-list `val` is the right shape; T3's BOM extends the
pattern in its own module).

## Integration / e2e

No rig / newman / gateway in this slice. The T6 `publishToMavenLocal` dry-run is the e2e proof gate
(runs last, after T1–T4). T1's own e2e surface is the U1/U2 task-graph assertions above.

## Decisions

- Plugin version **0.37.0** (latest stable; confirmed via Central `maven-metadata.xml`, not the stale
  Plugin-Portal HTML which mislabels an old release).
- `sourcesJar = true` (boolean form) in `JavaLibrary(...)` — the version-appropriate signature for
  0.37.0; the docs' `SourcesJar.Sources()` form is a different/older API surface. Build resolves clean.
- Javadoc `-Xdoclint:none` co-located with the publish wiring (see What-shipped rationale).

## Commit

`build(publish): apply vanniktech maven-publish to the 5 library modules (allow-list)` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
