---
tags:
  - status/planned
  - type/architecture
  - area/build
---

# MAVEN-CENTRAL-PUBLISHING — design

> The settled design for [[MAVEN-CENTRAL-PUBLISHING]] — the library's first Maven Central release, the
> **sole remaining 1.0 blocker**. Grilled to six pinned forks (2026-07-13); the rationale lives in
> **[[0027-maven-central-release-engineering|ADR 0027]]** (this design references it, it is not
> repeated here). This slice is **build/release infrastructure only** — it changes no library source
> and does not touch the `opa-abac-core` Spring-free boundary.

## 1. The mechanism

Five library modules must become resolvable on **Maven Central** at a stable `1.0.0`, plus a **BOM**
platform. Publishing goes through the **Central Publisher Portal** via the
`com.vanniktech.maven.publish` plugin. The repo is already scaffolded for this: `gradle.properties`
declares `GROUP=dev.dmitriikonovalov`, `VERSION_NAME`, and the full `POM_*` metadata block **in the
vanniktech property convention** — so the plugin reads that metadata directly and little new POM wiring
is needed.

Every new artifact and integration point is **named**:

```
Root build (build.gradle.kts / gradle.properties):
    com.vanniktech.maven.publish   plugin (version-catalog alias `maven-publish`), applied to the
                                   library subprojects only (NOT the example apps)
    mavenPublishing { }            block per published module: Central Portal host, signing on,
                                   sources+javadoc jars, POM from POM_* props (already present)
    VERSION_NAME = 1.0.0           (was 0.1.0-SNAPSHOT) → after release, 1.1.0-SNAPSHOT

Per-module (NEW gradle.properties in each published module, 6 total):
    POM_NAME, POM_DESCRIPTION, POM_ARTIFACT_ID   — the per-artifact identity vanniktech needs

opa-abac-bom  (NEW MODULE — Gradle `java-platform`):
    constraints { }              declares dev.dmitriikonovalov:opa-abac-*:<VERSION_NAME> for all 5
                                 library modules; published as the 6th coordinate

.gitignore:
    + *.gpg *.key *.p12 *.jks *.keystore *.pem *.local   (broad keystore hardening, before any key)

RELEASING.md  (NEW):
    the manual, out-of-band steps (Portal account + DNS TXT, GPG keygen + keyserver + ~/.gradle creds,
    the publish command, press-Publish) — the parts that are the maintainer's, not the code's.
```

Signing is **local-only** (key in `~/.gnupg`, read via `~/.gradle/gradle.properties` — never the repo).
The **first release is manual** (`./gradlew publishAndReleaseToMavenCentral` from the maintainer's
machine). Both are ADR-0027 decisions; see there for why.

## 2. The six decisions (from ADR 0027 — summarized, not re-argued)

| # | Fork | Decision |
|---|------|----------|
| 1 | groupId | **`dev.dmitriikonovalov`** (unchanged; DNS-TXT verified — the maintainer owns the domain; matches the package root) |
| 2 | modules + BOM | **All 5 libraries + a new `opa-abac-bom`** = 6 artifacts; examples never published |
| 3 | signing key | **Local-only** (`~/.gnupg` + `~/.gradle/gradle.properties`) — smallest trust surface |
| 4 | `.gitignore` | **Broad keystore glob set**, added before any key exists (folds in the deferred keystore-glob task) |
| 5 | plugin + release | **`com.vanniktech.maven.publish`** (Portal-native; reads the props already present) + **manual** first release |
| 6 | version | **`1.0.0`**, then bump `main` → `1.1.0-SNAPSHOT`; javadoc jar with `-Xdoclint:none` |

## 3. What already exists (shrinks the work)

- `gradle.properties`: `GROUP`, `VERSION_NAME`, and the complete `POM_*` block (URL / SCM / Apache-2.0 /
  developer id `dmitriikonovalov`) — written to the vanniktech convention → **root POM metadata is done**.
- Root `LICENSE` (Apache-2.0) present.
- The version-catalog `[plugins]` block has a clean slot for the vanniktech alias.
- The example apps have **zero** publish wiring; no competing tooling (no nexus/JReleaser) anywhere.
- The inter-module graph is clean and one-directional:
  `core ← spring-security ← keycloak-directory`, `core ← spring-data`, and the starter
  `api`-aggregates core + spring-security + spring-data (`compileOnly` → keycloak-directory).

## 4. Scope boundary

**In scope (this slice):**
- vanniktech plugin wiring on the 5 library modules (via the version catalog).
- Per-module `gradle.properties` with `POM_NAME` / `POM_DESCRIPTION` / `POM_ARTIFACT_ID` (× 6, incl. BOM).
- The new `opa-abac-bom` `java-platform` module (constraints over all 5 libraries) + its `settings` include.
- sources + javadoc jars (javadoc with `-Xdoclint:none`), signing config (local-key contract).
- `VERSION_NAME` → `1.0.0` (release), then → `1.1.0-SNAPSHOT` (post-release bump).
- Broad `.gitignore` keystore hardening.
- `RELEASING.md` documenting the manual out-of-band steps.

**Out of scope (do NOT touch):**
- Any library **source** — publishing is inert release infra; behavior is byte-identical.
- The `opa-abac-core` **Spring-free** boundary (the BOM is a `java-platform`, no code, no Spring).
- `example-*` modules — they stay unpublished (no plugin applied to them).
- **CI-automated** releases (would move the key to a GitHub secret — reopens fork 3; a later follow-up).
- **Writing/fixing javadoc** content (`-Xdoclint:none` sidesteps it; a separate quality pass if wanted).
- The actual **out-of-band acts** (Portal account, DNS TXT, GPG keygen, pressing Publish) — the
  maintainer's, documented in `RELEASING.md`, not automatable in the repo.

## 5. Acceptance frame

The slice is done when, on `main` at `1.0.0`:
- `./gradlew build` stays green (no library behavior change).
- **A local dry-run** produces, for each of the 6 coordinates, a signed set — **main jar + sources jar +
  javadoc jar + POM** — with a valid `.asc` signature and a POM carrying name/description/URL/SCM/license/
  developer. (Verifiable with `publishToMavenLocal` + inspecting `~/.m2/repository/dev/dmitriikonovalov/…`.)
- The BOM POM lists all five modules at `1.0.0` in `<dependencyManagement>`.
- The example apps produce **no** publishable artifacts (their `publish*` tasks are absent).
- `RELEASING.md` is complete enough that the maintainer can execute the release from it alone.
- **Fail-closed / clean-room invariants hold:** no key material in the tree (broad `.gitignore` in place);
  no proprietary names/paths/ids in any committed file; `opa-abac-core` still has no Spring dependency.

> The **actual publish to Central** is a maintainer act gated on the out-of-band steps (namespace
> verification + key + Portal credentials); the slice delivers a repo that is *release-ready* and the
> `RELEASING.md` to drive it — it does not (and cannot) perform the live publish autonomously.

## Related
- Rationale: [[0027-maven-central-release-engineering|ADR 0027]].
- Platform baseline being published: [[0026-spring-boot-4-single-line-port|ADR 0026]].
- Milestone closed: [[POC-ROADMAP]] (Phase 7 — publish 1.0).
