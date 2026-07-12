---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T3: New opa-abac-bom module (java-platform) + settings include

**Status:** ✅ DONE

## What shipped

The 6th published coordinate — a `java-platform` BOM:

- **`opa-abac-bom/build.gradle.kts`** — `plugins { java-platform; alias(libs.plugins.maven.publish) }`;
  a `dependencies { constraints { api(project(":opa-abac-…")) × 5 } }` block pinning all five library
  modules; `mavenPublishing { publishToMavenCentral(); signAllPublications(); configure(JavaPlatform()) }`.
  `allowDependencies()` deliberately NOT called → the BOM contributes only `<dependencyManagement>`,
  never transitive `<dependencies>`.
- **`opa-abac-bom/gradle.properties`** — `POM_ARTIFACT_ID=opa-abac-bom`, `POM_NAME=OPA ABAC :: BOM`,
  `POM_DESCRIPTION`, `POM_PACKAGING=pom`.
- **`settings.gradle.kts`** — `opa-abac-bom` added to the publishable **library** `include(...)` group
  (not the examples group).
- **Root `build.gradle.kts`** — the shared `subprojects { apply("java") … toolchain … tests }` block
  was rescoped to `configure(subprojects.filter { it.name !in bomModules })` (the BOM has no source,
  no toolchain, no tests, and `java-platform` cannot coexist with `java`). The BOM gets its own
  `mavenCentral()` repository (to resolve the plugin marker) via a small companion `configure(...)` block.

## Tests

- **I2 ✅** — `./gradlew :opa-abac-bom:generatePomFileForMavenPublication` → the POM has
  `<packaging>pom</packaging>`, a `<dependencyManagement>` listing **all five** `opa-abac-*` modules at
  the version, full name/description/url/scm/Apache-2.0-license/developer metadata, and **no**
  `<dependencies>` outside `<dependencyManagement>` (pure constraints platform).
- **No jar ✅** — after `./gradlew build`, `opa-abac-bom/build/libs/` does not exist (a platform has no jar).
- **U1 ✅** — `./gradlew build` → `BUILD SUCCESSFUL` (40 tasks, all up-to-date after the root-block
  rescope — the change didn't invalidate any module's compile/test).

## Architecture review + refactor

- **Plugin matrix ✅** (authoritative init-script probe of `plugins.hasPlugin(...)` per subproject):
  5 libraries = `java` + `publish`; `opa-abac-bom` = `java-platform` + `publish` (no `java`); **both
  `example-*` = `java` only, NO publish**. The fail-closed edge holds across all 6 published coordinates.
- **Fail-closed ✅** — the BOM's `signAllPublications()` wires signing into its publish path; even the
  POM-only artifact gets a `.asc` (confirmed in T6).
- **Security ✅** — the `constraints {}` block lists only the 5 `opa-abac-*` libraries; no `example-*`
  can leak into the BOM.
- **Boundary ✅** — pure `java-platform`, no code, no Spring → core-stays-Spring-free trivially held;
  `allowDependencies()` not called → no transitive deps.

**Refactor applied:** one, and necessary — the initial build FAILED with "java-platform cannot be
applied together with java" because the root `subprojects { apply("java") }` block applied `java` to
every subproject including the BOM. Fixed by rescoping that block to exclude `bomModules`, and giving
the BOM its own repository. Minimal and behavior-preserving (the "40 up-to-date" build proves no
library/example config changed). Not churn — it's the correct way to host a platform in a `java`-heavy
multi-module build.

## Integration / e2e

POM shape verified via `generatePomFileFor…`; the signed 6-coordinate proof (including the BOM's
POM+`.asc`, no jar) is the T6 gate.

## Decisions

- The BOM applies the publish plugin **in its own module build** (with `JavaPlatform()`), NOT via the
  root jar-module allow-list — the two variants (`JavaLibrary` vs `JavaPlatform`) are configured where
  each module type lives.
- Root shared-`java` block rescoped via a named `bomModules` set (symmetry with `publishableJarModules`).

## Commit

`build(publish): add opa-abac-bom java-platform as the 6th published coordinate` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
