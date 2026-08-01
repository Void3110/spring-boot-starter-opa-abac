---
tags:
  - status/planned
  - type/project
  - area/build
---

# MAVEN-CENTRAL-PUBLISHING — decomposition

> The ordered work list for [[MAVEN-CENTRAL-PUBLISHING]]. Design: [[00-DESIGN]]. Rationale:
> [[0027-maven-central-release-engineering|ADR 0027]]. Each ticket's *Acceptance* references a case in
> [[10-QA-TEST-CASES]]. **Build/release infra only — no library source is touched.** Published
> coordinates: `dev.dmitriikonovalov:opa-abac-{core,spring-security,spring-data,keycloak-directory,
> spring-boot-starter,bom}`.

## Critical path

```
T1  plugin + signing/jars wiring (root convention, library modules only)   ─┐
T2  per-module POM_* props (5 libraries)                                    ─┼─ T1→T2→T3 sequential (each builds on the prior POM shape)
T3  opa-abac-bom java-platform module (+ settings include + POM props)      ─┘
T4  version→1.0.0 + broad .gitignore + -Xdoclint:none javadoc              ── depends on T1 (javadoc config lives with the plugin block)
T5  RELEASING.md (manual out-of-band steps)                                 ── independent; can land any time after T1 (references the wiring)
T6  local dry-run verification (publishToMavenLocal) — the proof gate       ── LAST; depends on T1–T4 (needs all 6 coordinates + version + signing)
```

- **Sequential spine:** T1 → T2 → T3 (the POM shape and plugin application must exist before per-module
  metadata, and the BOM lists the finalized coordinates). T4 depends only on T1. T6 is the proof gate and
  must run last.
- **Independently landable:** **T5** (`RELEASING.md`) is pure docs and can land any time after T1 — it
  documents the wiring, doesn't depend on the BOM or version bump. If the window is short, T1+T6 alone
  (all 5 libraries + verification, BOM deferred) would be a shippable subset — but the design commits to
  the BOM, so T3 is in scope.
- **The headline gate:** **T6** — a `publishToMavenLocal` dry-run yielding 6 fully-signed, POM-complete
  coordinates is what proves the entire slice; nothing merges until it is green.

---

## T1 — Root publish wiring: vanniktech plugin + signing + sources/javadoc jars (library modules only)

**Goal.** Apply `com.vanniktech.maven.publish` to the **five library modules** (never the example apps),
configured for the Central Portal host, GPG signing on, and sources + javadoc jars — reading the
`GROUP` / `VERSION_NAME` / `POM_*` properties the repo already declares.

**Deliverables.**
- `gradle/libs.versions.toml`: a `[versions]` entry `vanniktechMavenPublish = "<latest 0.3x>"` and a
  `[plugins]` alias `maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechMavenPublish" }`.
- Root `build.gradle.kts`: declare the plugin `apply false` at the root; apply it **only** to the library
  modules. Use an explicit allow-list of the 5 library module names (NOT a blanket `subprojects`), so
  `example-catalog-management-service` / `example-user-management-service` never receive it. A
  `configure(...)` / `configureEach` block on that allow-list wires: `mavenPublishing { publishToMavenCentral(...)` (Central Portal), `signAllPublications()`, and the `JavaLibrary` variant with sources + javadoc jars.
- Signing reads the local key via Gradle's standard `signing` properties (from `~/.gradle/gradle.properties`
  — `signingInMemoryKey` / `signing.gnupg.*`); **no key material in the repo**. The build must not *fail*
  when signing credentials are absent locally (dev builds) — signing is required only for the
  publish/release tasks, not for `build`/`test`.

**Acceptance.** [[10-QA-TEST-CASES]] **U1** (`./gradlew build` stays green with no signing creds present —
publishing config does not break the normal build) + **U2** (`./gradlew :opa-abac-core:tasks --group publishing`
lists `publishToMavenLocal` / `publishAllPublicationsToMavenCentralRepository` for a library module, and
`./gradlew :example-catalog-management-service:tasks --group publishing` lists **none** — the plugin is not
applied to examples).

**What NOT to touch.** No library **source**, no `opa-abac-core` dependency (it stays Spring-free — the
plugin is build-only). Do not apply the plugin to `example-*`. Do not set any real key or credential in a
committed file. Do not change `GROUP` or the existing `POM_*` values (reuse them).

---

## T2 — Per-module POM identity: `POM_NAME` / `POM_DESCRIPTION` / `POM_ARTIFACT_ID` (5 libraries)

**Goal.** Give each published library a complete, human-readable POM identity — the per-artifact fields
vanniktech reads from a module-local `gradle.properties`.

**Deliverables.**
- A `gradle.properties` in each of the 5 library modules with `POM_NAME`, `POM_DESCRIPTION`,
  `POM_ARTIFACT_ID` (= the module dir name). Reuse each module's existing `description = "…"` string as the
  `POM_DESCRIPTION` (they already exist in the module builds — keep them in sync; the module `gradle.properties`
  value wins for the POM). Names are neutral/clean-room (e.g. "OPA ABAC — Core", "OPA ABAC — Spring Security").
- Confirm the root `gradle.properties` `POM_*` block (URL/SCM/license/developer) still supplies the shared
  fields (it does — no change needed unless a field is missing).

**Acceptance.** [[10-QA-TEST-CASES]] **I1** (after `publishToMavenLocal`, each of the 5 library POMs under
`~/.m2/repository/dev/dmitriikonovalov/…` carries a non-empty `<name>`, `<description>`, `<url>`, `<scm>`,
`<licenses>` Apache-2.0, and `<developers>` block — asserted by reading the generated `.pom`).

**What NOT to touch.** No source. Clean-room: the `POM_NAME`/`POM_DESCRIPTION` strings are original and
generic (product-catalog domain), never a proprietary name. Do not duplicate the shared root POM fields
into every module — only the per-artifact three.

---

## T3 — New `opa-abac-bom` module (`java-platform`) + settings include

**Goal.** Ship the BOM — a `java-platform` that pins all five library modules at `VERSION_NAME`, published
as the 6th coordinate so adopters can `implementation(platform("dev.dmitriikonovalov:opa-abac-bom:1.0.0"))`
and reference the modules version-free.

**Deliverables.**
- New module dir `opa-abac-bom/` with a `build.gradle.kts` applying `java-platform` + the vanniktech
  plugin, and a `constraints { api(project(":opa-abac-core")); … }` (or `api("dev.dmitriikonovalov:opa-abac-…:${version}")`)
  block listing **all five** library modules. `javaPlatform { allowDependencies() }` only if a transitive is
  genuinely needed (default: pure constraints, no deps).
- `opa-abac-bom/gradle.properties` with its own `POM_NAME` / `POM_DESCRIPTION` / `POM_ARTIFACT_ID`.
- `settings.gradle.kts`: add `"opa-abac-bom"` to the library `include(...)` list (in the "publishable"
  group, NOT the examples group).
- Apply the T1 publish wiring to `opa-abac-bom` (add it to the T1 allow-list — a `java-platform` publishes a
  POM-only artifact; no sources/javadoc jar for a platform).

**Acceptance.** [[10-QA-TEST-CASES]] **I2** (after `publishToMavenLocal`, `~/.m2/repository/dev/dmitriikonovalov/opa-abac-bom/…`
contains a POM whose `<dependencyManagement>` lists all five `opa-abac-*` modules at the release version, with
`<packaging>pom</packaging>` and **no** jar).

**What NOT to touch.** The BOM is a `java-platform` — **no code, no Spring**, so the core-stays-Spring-free
invariant is trivially held. Do not include the example apps in the BOM constraints. Do not give the BOM a
sources/javadoc jar (a platform has none).

---

## T4 — Release version + `.gitignore` hardening + `-Xdoclint:none` javadoc

**Goal.** Set the release version, make the javadoc jar build on the un-linted surface, and harden
`.gitignore` against key material **before** any key exists.

**Deliverables.**
- `gradle.properties`: `VERSION_NAME` `0.1.0-SNAPSHOT` → **`1.0.0`**. (The post-release bump to
  `1.1.0-SNAPSHOT` is an **operator step in `RELEASING.md`**, done *after* the publish — NOT in this commit,
  so the dry-run in T6 verifies the `1.0.0` coordinates.)
- Javadoc: configure the `Javadoc` tasks with `(options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")`
  so the javadoc jar builds without doclint failing on missing/malformed tags.
- `.gitignore`: add `*.gpg`, `*.key`, `*.p12`, `*.jks`, `*.keystore`, `*.pem`, and a generic `*.local`
  (keeping existing `.env*` / `*.asc` / `signing.properties` / `local.properties`). Verify `CLAUDE.local.md`
  is still covered (the generic `*.local` now also covers it — confirm no regression).

**Acceptance.** [[10-QA-TEST-CASES]] **U3** (`./gradlew :opa-abac-core:javadocJar` succeeds on the un-linted
surface — no doclint failure) + **U4** (`git check-ignore secring.gpg signing.p12 foo.keystore bar.pem
baz.local` returns each path — the broad globs match; and `git status` shows no tracked key files).

**What NOT to touch.** Do NOT write or fix javadoc **content** (that's out of scope — `-Xdoclint:none` is the
whole mitigation). Do NOT bump to `1.1.0-SNAPSHOT` here (that's the post-publish operator step). No source.

---

## T5 — `RELEASING.md` — the manual, out-of-band release runbook

**Goal.** Document the parts that are the **maintainer's**, not the code's — the one-time account/key setup
and the actual publish — completely enough to execute from the doc alone.

**Deliverables.**
- New `RELEASING.md` at the repo root covering, in order: (1) **Central Portal** account + claim the
  `dev.dmitriikonovalov` namespace + add the DNS **TXT** record on `dmitriikonovalov.dev`; (2) **GPG**
  keygen, upload the public half to a keyserver, and place the private key + passphrase + Central token in
  `~/.gradle/gradle.properties` (with the exact property names, and a note that this file is user-home, never
  the repo); (3) the **local dry-run** (`./gradlew publishToMavenLocal` + inspect `~/.m2/…`); (4) the
  **release** (`./gradlew publishAndReleaseToMavenCentral`) and pressing **Publish** on the Portal; (5) the
  **post-release** `VERSION_NAME` → `1.1.0-SNAPSHOT` bump + tag `v1.0.0`.
- A short pointer to `RELEASING.md` from `README.md` (a "Releasing" line) and/or root `CLAUDE.md` build
  section if a build/run step matters.

**Acceptance.** [[10-QA-TEST-CASES]] **D1** (a fresh reader can follow `RELEASING.md` end-to-end: every
command is copy-pasteable, every credential's location is named, the DNS-TXT and keyserver steps are
explicit, and no step assumes hidden context — verified by review, not a test runner).

**What NOT to touch.** Do NOT put any real key, token, TXT value, or credential in `RELEASING.md` (it is
committed/public) — only placeholders + where the real values live locally. Clean-room: no proprietary
names.

---

## T6 — Local dry-run verification (the proof gate)

**Goal.** Prove the whole slice with a local dry-run: all six coordinates build as fully-signed,
POM-complete artifacts, and the examples produce none.

**Deliverables.**
- Run `./gradlew publishToMavenLocal` (with a **throwaway local signing key** configured in
  `~/.gradle/gradle.properties` for the duration — documented as an operator prerequisite in `RELEASING.md`,
  NOT committed). Inspect `~/.m2/repository/dev/dmitriikonovalov/`:
  - 5 library coordinates each with **main jar + `-sources.jar` + `-javadoc.jar` + `.pom` + `.asc`** for each.
  - `opa-abac-bom` with a **POM-only** artifact (`<packaging>pom</packaging>`) + `.asc`, no jar.
  - Each POM carries name/description/URL/SCM/Apache-2.0-license/developer.
- Confirm `./gradlew :example-catalog-management-service:publishToMavenLocal` is a **no-op / absent** (the
  examples have no publish tasks) — they publish nothing.
- Record the actual artifact list + any surprise in `STATUS-06.md`.

**Acceptance.** [[10-QA-TEST-CASES]] **E1** (the six-coordinate signed artifact set is present in the local
repo, POMs complete, examples absent) — the slice's headline gate. If a signing key isn't available in the
run environment, the fallback proof is **U5** (`./gradlew publish -m` dry-run / `generateMetadataFileFor…` +
`generatePomFileFor…` produce the correct task graph and POMs for all 6, examples excluded) — documented as
the CI-safe substitute, with the full signed dry-run flagged as a maintainer step in `RELEASING.md`.

**What NOT to touch.** No real Central credentials (this is *local* only — `publishToMavenLocal`, never
`…ToMavenCentral`). Do NOT perform the actual Central publish (maintainer, out-of-band). No source.

---

## Cross-cutting acceptance

- **`./gradlew build` stays green** at every ticket — publishing wiring never breaks the normal build/test.
- **Clean-room:** no proprietary names/paths/ids in any committed file (POM names, RELEASING.md,
  gradle.properties) — the `verify-package.sh` clean-room scan is empty.
- **`opa-abac-core` stays Spring-free** — trivially (build-only slice; the BOM is a `java-platform`).
- **Examples never publish** — no publish task on `example-*` at any point.
- **No key material in the tree** — the broad `.gitignore` globs are in place before any key is generated;
  `git status` never shows a key/keystore/token file.
- **Fail-closed (this slice's form):** a missing signing credential or namespace-verification failure makes
  the *publish* fail — it never publishes an **unsigned** or **partially-formed** artifact. No error path
  produces a publishable-but-broken coordinate.
