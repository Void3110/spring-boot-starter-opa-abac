---
tags:
  - status/active
  - type/decision
  - area/architecture
  - area/build
---

# ADR 0027 — Maven Central release engineering (groupId, artifacts, signing, plugin)

**Status:** Accepted (planned 2026-07-13; the MAVEN-CENTRAL-PUBLISHING slice implements it)
**Date:** 2026-07-13
**Context tags:** Maven Central Publisher Portal, `dev.dmitriikonovalov` namespace, vanniktech maven-publish, local GPG signing, multi-module + BOM, 1.0.0, Gradle 9.6.1 / Java 25

> The release-infrastructure decisions behind the library's **first Maven Central publish** — the sole
> remaining 1.0 blocker. Settled by grill-me (2026-07-13). The design (`docs/to-do/planning/
> MAVEN-CENTRAL-PUBLISHING/00-DESIGN.md`) references this ADR; the rationale is recorded here once so
> it isn't re-litigated at implementation or at the release itself. Publishing is **build/release
> infra only** — it changes no library source and does not touch the `opa-abac-core` Spring-free
> boundary.

## Context

Every functional slice, the Spring Boot 4 port (ADR 0026), and the entire 7.4 pre-publish gauntlet
(security review, secret scan, CVE sweep, zero-config fail-safety, load re-baseline, UI QA) are done
and merged. The library has **no `maven-publish` or `signing` wiring anywhere** — a clean slate. What
remains is to make the five library modules resolvable on Maven Central at a stable `1.0.0`.

Maven Central (as of the March-2024 migration) publishes through the **Central Publisher Portal**, not
the legacy OSSRH staging API. Publishing requires: a **verified namespace** (groupId), every artifact
(jar + sources + javadoc + POM) **GPG-signed** with a key whose public half is on a public keyserver,
and complete POM metadata (name, description, URL, SCM, license, developer).

The repo was scaffolded anticipating this: `gradle.properties` already declares `GROUP=dev.dmitriikonovalov`,
`VERSION_NAME=0.1.0-SNAPSHOT`, and the full `POM_*` block (URL/SCM/Apache-2.0/developer) — written to the
**vanniktech property convention**. A root `LICENSE` exists. The example apps carry no publish wiring.

## Decision

Six forks were genuinely open; each is resolved below.

### 1. groupId = `dev.dmitriikonovalov` (unchanged)

The maintainer owns the domain `dmitriikonovalov.dev`, so the namespace is verifiable by a single DNS
**TXT** record. This keeps the groupId **identical to the Java package root** (`dev.dmitriikonovalov.opaabac.*`)
— no coordinate/import mismatch to ever explain — and the repo already assumes it. Rejected `io.github.void3110`
(zero-friction GitHub verification, but a permanent cosmetic mismatch on a portfolio artifact where the
owned-identity coordinate has signalling value).

### 2. Publish all 5 library modules **+ a new `opa-abac-bom`** (6 artifacts)

All five must publish: the starter's POM references core/spring-security/spring-data as `api`
dependencies (a POM pointing at unpublished coordinates is broken), and `opa-abac-keycloak-directory`
is deliberately **opt-in / `compileOnly`** — an adopter adds it as their own explicit dependency, so it
must be independently fetchable. A **BOM** (`opa-abac-bom`, a Gradle `java-platform`) is added for
portfolio polish and to let adopters pin one version and reference the modules version-free. The example
apps (`example-catalog-management-service`, `example-user-management-service`) are **never** published.

### 3. Signing key custody = **local-only**

The GPG signing key lives in the maintainer's local GnuPG keyring; Gradle reads it via signing
properties in **`~/.gradle/gradle.properties`** (user-home, never the repo). This is the **smallest
trust surface** — the Central-publishing key never becomes a GitHub secret. It pairs with a manual
first release (fork 5). CI-automated signing (key as a GitHub secret) is a later, additive option, not
1.0.

### 4. `.gitignore` hardened with the broad keystore glob set (before any key exists)

`.gitignore` already covers `.env*` / `*.asc` / `signing.properties` / `local.properties`. This slice
adds `*.gpg` / `*.key` / `*.p12` / `*.jks` / `*.keystore` / `*.pem` and a generic `*.local` **before**
any key material touches the machine — defense-in-depth (git history is already verified clean; this is
prevention, not remediation). Folds in the previously-deferred keystore-glob task.

### 5. Plugin = **`com.vanniktech.maven.publish`**; first release = **manual**

`com.vanniktech.maven.publish` is Central-Portal-native (not the sunsetting OSSRH shim), reads the
**exact** `POM_*` / `GROUP` / `VERSION_NAME` properties the repo already declares (so the POM metadata is
effectively done), and wires sources + javadoc + signing + Portal upload for the multi-module + BOM case.
Rejected `nexus-publish` (legacy OSSRH, deprecated path) and `nmcp` (viable, Portal-native, but less
batteries-included — more hand-wiring for the same result). The **first release is manual**
(`./gradlew publishAndReleaseToMavenCentral` from the maintainer's machine), which pairs with local
signing and lets the maintainer validate the artifacts and press release with their own eyes; the plugin
config is written so later CI automation is a small additive step, not a rewrite.

### 6. Version = **`1.0.0`**; then bump `main` → `1.1.0-SNAPSHOT`

`1.0.0` is the honest label for a library this mature (full test suite, security-reviewed, load-tested,
UI-QA'd) — `0.1.0` would undersell the portfolio artifact. Central rejects `-SNAPSHOT`. After the release,
`main` bumps to `1.1.0-SNAPSHOT` (standard "release then open next dev version") so `main` never sits on a
released version.

### Ancillary: javadoc jar with `-Xdoclint:none`

Central requires a javadoc jar. Java's `javadoc` runs `doclint` by default and fails on any malformed or
missing tag — on an un-linted public surface it would block the release on doc completeness. The javadoc
jar is generated with `-Xdoclint:none`: a real, resolvable javadoc artifact without gating the release on
doc perfection. Writing/fixing javadoc content is explicitly **out of scope** (a separate quality pass if
ever wanted).

## Manual, out-of-band steps (the maintainer's — not code)

The slice's build wiring is inert without these one-time acts, which cannot be automated in the repo:

1. Register a **Central Portal** account and claim the `dev.dmitriikonovalov` namespace; add the DNS
   **TXT** verification record on `dmitriikonovalov.dev`.
2. **Generate the GPG key**, upload its public half to a public keyserver, and place the private
   key/passphrase in `~/.gnupg` + the signing + Central-token credentials in `~/.gradle/gradle.properties`.
3. Run the publish and press **Publish** on the Portal for the first release.

These are documented step-by-step in a new `RELEASING.md`.

## Consequences

- **Positive:** the library becomes a resolvable `dev.dmitriikonovalov:opa-abac-*:1.0.0` dependency — the
  1.0 milestone and the portfolio artifact's public face. POM metadata reuse (the pre-scaffolded `POM_*`)
  makes the wiring small. Smallest signing-key trust surface. No library source touched — publishing is
  pure release infra, so no re-review of the authorization behavior is triggered.
- **Negative / accepted:** the first release is machine-bound (only the maintainer can publish until CI
  automation is added later). A BOM is a sixth coordinate to maintain. `-Xdoclint:none` ships a javadoc
  jar whose content is un-linted (acceptable — the API is the contract, not the prose).
- **Follow-ups (not 1.0-blocking):** CI-automated releases (moves the key to a GitHub secret — reopens
  fork 3); an optional CI publish-dry-run/validation job on tags (build + POM-verify + sign against a
  throwaway key, without holding the real key); a real javadoc-authoring pass.

## Related
- The slice that implements this: [[MAVEN-CENTRAL-PUBLISHING]].
- The platform baseline it publishes: [[0026-spring-boot-4-single-line-port|ADR 0026]] (Boot 4 / Java 25 single-line artifact).
- The roadmap milestone it closes: [[POC-ROADMAP]] (Phase 7 — publish 1.0).
