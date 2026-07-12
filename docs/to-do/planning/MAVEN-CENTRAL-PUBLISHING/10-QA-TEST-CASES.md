---
tags:
  - status/planned
  - type/project
  - area/build
---

# MAVEN-CENTRAL-PUBLISHING — QA test cases

> Concrete cases that back each ticket's *Acceptance* in [[01-DECOMPOSITION]]. **This is a build/release
> infra slice** — there is no rego, no service, no gateway e2e. The "tests" are therefore **Gradle-task
> assertions, generated-POM inspections, and filesystem checks** (not JUnit/Testcontainers/newman). Each
> case names the exact command and the exact thing to assert. The headline gate is **E1** (the six-coordinate
> signed dry-run).

## Conventions for these cases
- "the local repo" = `~/.m2/repository/dev/dmitriikonovalov/`.
- A **throwaway** GPG key in `~/.gradle/gradle.properties` is an operator prerequisite for the signed cases
  (E1); the CI-safe substitute is the task-graph/POM-generation dry-run (U5). No key material is ever committed.
- "examples" = `example-catalog-management-service`, `example-user-management-service`.

---

## U — build-level (no publish network, no key)

**U1 — publishing config does not break the normal build.**
`./gradlew build` → **BUILD SUCCESSFUL** with **no** signing credentials present. Proves signing is required
only for publish/release tasks, not for `build`/`test`. *(T1)*

**U2 — plugin applied to libraries only, never examples.**
- `./gradlew :opa-abac-core:tasks --group publishing` lists `publishToMavenLocal` and a
  `publish…ToMavenCentral…` task.
- `./gradlew :example-catalog-management-service:tasks --group publishing` and
  `:example-user-management-service:tasks --group publishing` list **no** publish tasks (plugin not applied).
*(T1, T3 — repeat for `opa-abac-bom` once it exists: it has publish tasks; examples still have none.)*

**U3 — javadoc jar builds on the un-linted surface.**
`./gradlew :opa-abac-core:javadocJar` (and each library) → **SUCCESS**; no doclint error aborts the build.
Proves `-Xdoclint:none` is wired. *(T4)*

**U4 — `.gitignore` broad key globs match; no key file tracked.**
- `git check-ignore secring.gpg signing.p12 my.keystore key.pem release.jks config.local` → each path echoed
  (all ignored).
- `git status --porcelain` after touching any of those in the tree → they do **not** appear as untracked.
- `git check-ignore CLAUDE.local.md` → still ignored (no regression from the generic `*.local`). *(T4)*

**U5 — POM/metadata generation dry-run (CI-safe substitute for E1 when no key).**
`./gradlew generatePomFileForMavenPublication` (across the 6 publishable modules) → generates a `.pom` for
each of the 5 libraries **and** the BOM, and **none** for the examples. Inspect the generated POMs for the
same completeness E1/I1/I2 assert. Proves the task graph + POM shape without needing a signing key. *(T6 fallback)*

## I — generated-POM completeness (after `publishToMavenLocal`)

**I1 — each library POM is metadata-complete.**
For each of the 5 libraries, the `.pom` in the local repo has non-empty: `<name>`, `<description>`, `<url>`,
`<scm>` (connection + url), `<licenses>` = Apache-2.0, `<developers>` (id `dmitriikonovalov`). Proves T2's
per-module props + the shared root POM fields compose. *(T2)*

**I2 — the BOM POM manages all five modules.**
`opa-abac-bom`'s `.pom` has `<packaging>pom</packaging>`, a `<dependencyManagement>` block listing **all five**
`dev.dmitriikonovalov:opa-abac-{core,spring-security,spring-data,keycloak-directory,spring-boot-starter}` at the
release version, and **no** jar artifact alongside it. Proves the `java-platform` constraints. *(T3)*

**I3 — starter POM references its api-deps at the release coordinates.**
`opa-abac-spring-boot-starter`'s `.pom` lists `opa-abac-core` + `opa-abac-spring-security` + `opa-abac-spring-data`
as `<dependency>` entries at `dev.dmitriikonovalov:…:1.0.0` (the `api` edges), and `opa-abac-keycloak-directory`
is **absent** from `compile`/`runtime` scope (it is `compileOnly` → optional, not a transitive). Proves the
published coordinates resolve against each other (no dangling reference) and the opt-in module stays opt-in. *(T1/T2/T3 compose)*

## E — the dry-run proof gate

**E1 — six signed, POM-complete coordinates; examples publish nothing. (HEADLINE)**
With a throwaway signing key configured, `./gradlew publishToMavenLocal`, then inspect the local repo:
- **5 libraries** — each has `…-<ver>.jar`, `…-<ver>-sources.jar`, `…-<ver>-javadoc.jar`, `…-<ver>.pom`, and a
  `.asc` signature for **each** of those files.
- **`opa-abac-bom`** — a POM-only artifact (`.pom` + `.asc`), no jar/sources/javadoc.
- Every POM passes I1 (libraries) / I2 (BOM) / I3 (starter deps).
- `./gradlew :example-catalog-management-service:publishToMavenLocal :example-user-management-service:publishToMavenLocal`
  publishes **nothing** to the local repo (no such tasks / no-op) — grep the local repo shows no example
  artifacts.
The presence of the full signed six-coordinate set is the slice's headline proof. *(T6)*

## D — the release runbook (review, not a runner)

**D1 — `RELEASING.md` is executable from the doc alone.**
A fresh reader can follow it end-to-end: (a) Central Portal account + namespace claim + the DNS **TXT** step on
`dmitriikonovalov.dev` are explicit; (b) GPG keygen + keyserver upload + the exact `~/.gradle/gradle.properties`
property names (signing key/passphrase + Central token) are named, with the "user-home, never the repo" note;
(c) the local dry-run and the `publishAndReleaseToMavenCentral` + press-Publish steps are copy-pasteable; (d)
the post-release `VERSION_NAME` → `1.1.0-SNAPSHOT` bump + `v1.0.0` tag is listed. **No real key/token/TXT value
appears** — only placeholders. Verified by review. *(T5)*

---

## Out of scope (proven elsewhere / not this slice)
- **The actual Central publish** — a maintainer out-of-band act gated on the Portal account + DNS-TXT
  namespace verification + the real signing key; `RELEASING.md` drives it. Not automatable here.
- **Library behavior** — unchanged; the full test suite (unit + Testcontainers ITs + `opa test` + newman)
  already proves it and is not re-run for a build-only slice beyond the cross-cutting `./gradlew build` green.
- **CI-automated release** — a later follow-up (would move the key to a GitHub secret; reopens ADR-0027 fork 3).
- **Javadoc content quality** — `-Xdoclint:none` ships a real jar; authoring prose is a separate pass.
