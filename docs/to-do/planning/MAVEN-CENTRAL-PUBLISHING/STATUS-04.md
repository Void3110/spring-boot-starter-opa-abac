---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T4: Release version 1.0.0 + broad .gitignore hardening + -Xdoclint:none javadoc

**Status:** ✅ DONE

## What shipped

- **`gradle.properties`** — `VERSION_NAME` `0.1.0-SNAPSHOT` → **`1.0.0`**. NOT bumped to
  `1.1.0-SNAPSHOT` (that is a post-publish operator step in `RELEASING.md`; T6 verifies the `1.0.0`
  coordinates).
- **`.gitignore`** — the "Secrets" section expanded to a broad key-material net, in place BEFORE any key
  is generated: `*.gpg`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.pem` (plus the existing
  `*.asc`, `signing.properties`, `local.properties`), and a generic `*.local`. A comment explains the
  real key/passphrase/token live only in `~/.gradle/gradle.properties`.
- **`-Xdoclint:none`** — already wired in T1 (co-located with the publish block, since the javadoc jar
  can't build without it); confirmed live here.

## Tests

- **U3 ✅** — the un-linted javadoc jar builds. **Task-name correction:** the vanniktech
  `JavaLibrary(javadocJar = JavadocJar.Javadoc())` config names the task **`plainJavadocJar`**, not
  `javadocJar` (the QA case's guessed name). `./gradlew :opa-abac-core:plainJavadocJar :…:sourcesJar` →
  SUCCESS, producing `opa-abac-core-1.0.0-javadoc.jar` + `-1.0.0-sources.jar`. The raw
  `./gradlew :opa-abac-spring-security:javadoc` also succeeds → **doclint is genuinely off** (it would
  fail on malformed `@` tags otherwise). Updated `10-QA-TEST-CASES.md` U3 to the real task name.
- **U4 ✅** — `git check-ignore` returns a match for all of `secring.gpg signing.p12 foo.keystore
  bar.pem baz.local mykey.key cert.pfx store.jks` (8/8 ignored); `git ls-files | grep key-like` →
  none tracked; `CLAUDE.local.md` still ignored (the new generic `*.local` covers it — no regression).
- **U1 ✅** — `./gradlew clean build` → `BUILD SUCCESSFUL` at `1.0.0`.

## Architecture review + refactor

- **Version ✅** — `1.0.0` flows end-to-end (the generated `1.0.0` javadoc/sources jars prove it); the
  `1.1.0-SNAPSHOT` bump is deliberately deferred to the post-publish operator step.
- **Security hardening ✅** — the broad key globs are in place before any key material exists (the
  fail-closed net); every glob proven to match via `git check-ignore`; no tracked key file.
- **Doclint ✅** — proven off by the raw `javadoc` task succeeding on the un-linted surface.
- **Boundary ✅** — only `gradle.properties` (version) + `.gitignore` touched; no source.

**Refactor applied:** none substantive. Added `*.pfx` beyond the ticket's listed globs (a real PKCS#12
key extension alongside `*.p12`) — defensible hardening, not churn.

## Integration / e2e

The `1.0.0` coordinate + signed artifact set is the T6 gate. Here: the `1.0.0` javadoc/sources jars and
the gitignore matches are the filesystem-level proof.

## Decisions

- Kept `1.0.0` in-branch (not `1.1.0-SNAPSHOT`) so T6's dry-run verifies real release coordinates.
- Broadened the glob set with `*.pfx` (PKCS#12) in addition to the ticket's list.
- QA case U3 corrected to `plainJavadocJar` (the actual vanniktech task name).

## Commit

`build(publish): set VERSION_NAME 1.0.0 + harden .gitignore against key material` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
