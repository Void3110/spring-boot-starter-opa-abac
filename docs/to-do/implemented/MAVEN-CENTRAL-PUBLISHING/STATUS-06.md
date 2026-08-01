---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T6: Local dry-run verification (publishToMavenLocal) — the proof gate

**Status:** ✅ DONE

## What shipped

No code — T6 is the validation gate. Ran the full signed dry-run and asserted the six-coordinate set.

**Setup (throwaway, removed after):** `gpg` was not installed → `brew install gnupg` (2.5.21); generated a
**passphrase-less, 1-day-expiry** throwaway RSA-3072 key (`%no-protection`, `Expire-Date: 1`); exported the
armored secret key and set `signingInMemoryKey` in `~/.gradle/gradle.properties` (newlines as `\n`). Both
the key and the property were **deleted immediately after the run** (see Security below) — nothing lives in
the repo at any point.

## Tests

- **E1 ✅ — the headline gate.** `./gradlew publishToMavenLocal` → `BUILD SUCCESSFUL`, with
  `signMavenPublication` **executing** (not skipped) for every module. `~/.m2/repository/dev/dmitriikonovalov/`
  contains **exactly 6 coordinates** at `1.0.0`:
  - **5 libraries** — each with `-<v>.jar`, `-<v>-sources.jar`, `-<v>-javadoc.jar`, `-<v>.pom`,
    `-<v>.module`, **and a `.asc` next to every one**.
  - **opa-abac-bom** — `-<v>.pom` (with `<packaging>pom</packaging>` + `<dependencyManagement>` listing all
    five modules) + `-<v>.module`, each `.asc`-signed, **and no jar**.
  - **Signature validates:** `gpg --verify opa-abac-core-1.0.0.jar.asc …` → **"Good signature"**.
  - **Examples publish nothing:** no `example-*` coordinate under `dev.dmitriikonovalov` (grep empty);
    coordinate count is exactly 6.
- **U1 ✅** — `./gradlew build` remained green throughout (T4's `clean build` was the last full run).

## Architecture review + refactor

- **Fail-closed confirmed LIVE ✅** — `signMavenPublication` ran and produced real `.asc` files that
  `gpg --verify` accepts; signing is genuinely in the publish path, so a missing key would fail the publish,
  never emit an unsigned artifact. (The 7.4 `-m` dry-run in T1 predicted this; T6 proved it with real bytes.)
- **The fail-closed EDGE held ✅** — the examples produced **zero** coordinates. The library allow-list +
  BOM-in-its-own-build wiring means no demo app can leak to Central.
- **Boundary ✅** — no source; T6 is validation only.

**Refactor applied:** none — T6 validates, it does not build.

## Security (throwaway-key hygiene)

- The key was passphrase-less + 1-day-expiry, generated into `~/.gnupg` only.
- `signingInMemoryKey` lived only in `~/.gradle/gradle.properties` (user-home, gitignored domain), never
  the repo.
- **After the run:** restored `~/.gradle/gradle.properties` to its prior (empty) state (verified: no
  `signingInMemoryKey`), and `gpg --delete-secret-and-public-key` on the throwaway (verified: no secret keys
  remain). `git status` shows **no** key-like file in the working tree.

## Integration / e2e

E1 (the `publishToMavenLocal` signed dry-run) **is** the slice's e2e proof. No rig / newman / gateway
applies. The real Central publish stays the maintainer's out-of-band act (`RELEASING.md`).

## Decisions

- Took the **full signed E1** path (install gnupg + throwaway key) over the CI-safe **U5** POM-only
  fallback — the maintainer chose the stronger proof; it exercises the actual signing path locally.
- Throwaway key made passphrase-less + 1-day-expiry so no passphrase property was needed and the key
  self-expires even if cleanup were ever missed.

## Commit

`docs(publish): record T6 proof-gate results — 6 signed coordinates, examples none` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
