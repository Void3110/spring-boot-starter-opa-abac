---
tags:
  - status/done
  - type/project
  - area/build
---

# STATUS — T5: RELEASING.md — the manual out-of-band release runbook

**Status:** ✅ DONE

## What shipped

- **`RELEASING.md`** (repo root) — the maintainer runbook, executable from the doc alone, in five steps:
  1. **Namespace verification (DNS TXT)** — Central Portal register `dev.dmitriikonovalov` → TXT token →
     add on `dmitriikonovalov.dev`. **Grounded in the actual flow performed this session:** reg.ru's `@`
     = apex convention (empty host is rejected; don't type a subdomain/full-name), ~1–2 min propagation,
     and the `dig +short TXT dmitriikonovalov.dev @ns1.reg.ru / @8.8.8.8 / @1.1.1.1` verification before
     clicking Verify, plus the "leave the record in place" note.
  2. **GPG key + Central token** — `gpg --full-generate-key`, keyserver upload of the public half,
     ASCII-armored private-key export, Central Portal user-token generation.
  3. **`~/.gradle/gradle.properties`** — the exact property names (`mavenCentralUsername` /
     `mavenCentralPassword`, `signingInMemoryKey` / `signingInMemoryKeyId` / `signingInMemoryKeyPassword`,
     with the `signing.gnupg.keyName` agent alternative), each a `<placeholder>`, with the "user-home,
     never the repo" warning.
  4. **Dry-run** (`publishToMavenLocal` → inspect `~/.m2/…` for the 6 signed coordinates; examples none)
     and **release** (`publishAndReleaseToMavenCentral` → watch the Deployments tab / press Publish).
  5. **Post-release** — `git tag v<version>` + bump `VERSION_NAME` → `<next>-SNAPSHOT` (the deferred bump).
  - Plus a final checklist.
- **`README.md`** — the "Not yet published" note now points at `RELEASING.md` and states the wiring is in
  place (6 signed coordinates under `dev.dmitriikonovalov`, incl. `opa-abac-bom`).

## Tests

- **D1 ✅** (review-only): a fresh reader can execute end-to-end. Self-audit confirms all four required
  sections present (namespace+TXT / GPG+keyserver+token / dry-run+publish / post-release bump+tag), every
  command copy-pasteable, every credential location named. **No real key/token/TXT value** — a grep for
  real-token patterns (`glpat-`, `squ_`, `BEGIN…PRIVATE`, populated `signingInMemoryKey=`/
  `mavenCentralPassword=`) found **only `<placeholders>`**. Clean-room scan clear.

## Architecture review + refactor

- **Correctness ✅** — property names + task (`publishAndReleaseToMavenCentral`) verified against the
  vanniktech Central-Portal docs, not guessed.
- **Fail-closed documented ✅** — the doc states that without signing configured the publish task fails at
  signing, never emitting an unsigned/partial artifact.
- **Clean-room ✅** — no proprietary names; only public GitHub/domain/coordinate strings.
- **Secret hygiene ✅** — all credential values are placeholders; the doc repeats "user-home, never the
  repo" and references the `.gitignore` key net from T4.

**Refactor applied:** none. The DNS section was written from the real session flow (the `@`-apex reg.ru
convention + the multi-resolver `dig` check), which is more accurate than a generic "add a TXT record"
instruction would have been.

## Integration / e2e

D1 is review-only (no test runner). The runbook's §3 dry-run is exactly what T6 executes as the proof gate.

## Decisions

- Documented the reg.ru `@`-apex convention explicitly (a real gotcha — an empty host is rejected, a
  subdomain/full-name would put the record on the wrong name and fail verification).
- Kept the `signing.gnupg.keyName` agent path as an alternative to the in-memory key, so the maintainer
  can sign via the local gpg agent without exporting the armored key into properties.

## Commit

`docs(releasing): add RELEASING.md — the manual out-of-band Central release runbook` on
`feature/void3110/maven-central-publishing`, identity `Void3110 <void31102025@gmail.com>`.
