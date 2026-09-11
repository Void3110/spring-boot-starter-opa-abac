---
tags:
  - status/done
  - type/project
  - area/release
---

# RELEASE-1.3.0 — the placement gate, on the Spring Boot 4.0.8 baseline

> **Status: the cut of 2026-09-11.** Main was on `1.3.0-SNAPSHOT` since the 1.2.0 cut (2026-08-18). The
> delta to the **published** modules is additive, so this is a **minor**: 1.3.0. Runbook: [`RELEASING.md`](../../../../RELEASING.md);
> the previous cut's record: [[RELEASE-1.2.0]].

## 1. What is actually in this release

The five published libraries + the BOM (`dev.dmitriikonovalov:opa-abac-{core, spring-security,
spring-data, keycloak-directory, spring-boot-starter}` + `opa-abac-bom`). Library `src/main` since
`v1.2.0`: **three files, +282/−14** — all of it [[TAG-GATED-CREATE]] (ADR
[[0034-tag-gated-placement-input-contract|0034]]):

- `AbacContext.Resource` gains an **additive sixth component** `parent_attributes` on the
  `root_attributes` pattern (three states — absent = unproven, `{}` = untagged, a map = the parent's
  tags; `NON_NULL`; null-preserving copy; the 5-arg constructor kept as compat, so every existing
  caller compiles unchanged and an unenriched resource serializes byte-for-byte as before).
- `@OpaPreAuthorize` gains three optional SpEL attributes — `parentResourceType`, `parentResourceId`,
  `attributes` — for **type-level** gates (a create, an assign-tags-for-create): the placement parent
  and the raw payload. The manager resolves the parent through the same read-through memo as the
  governing root, **confined to the governing target** (the target itself, or a resource whose ancestor
  chain roots at it — anything else is unproven, so a foreign resource's tags never reach a decision
  and a status code never reveals them), and every declaration it cannot honor **denies before OPA is
  asked** (half a parent pair, a parent expression resolving to null/blank, a non-map / null-valued
  payload, a payload on an instance form). A gate that declares nothing is unchanged.
- The shipped example policies (`category.rego` / `product.rego`) now decide every non-list type-level
  verb through the **placement gate** — the parent's tags **and** the payload's tags must satisfy a
  tag-requiring role, whichever way the verb is granted; LIST stays the one coarse verb; `deny_reason`
  keys on the grant the request actually rides. The policies are not a published artifact, but
  adopters who copied them should copy the new shape: a tag-requiring role that could create what it
  could not read is the defect this closes (DEF-1 of the pre-Habr UI QA).

**The baseline:** Spring Boot **4.0.7 → 4.0.8** (backlog item 9 — the BOM-managed Jackson/Log4j
advisories closed) on Java 25. Tooling since 1.2.0, not published: the PIT mutation-testing baseline,
the committed OSV sweep (`scripts/checks/osv-*`), the demo console **on by default** on the rig
(`ENABLE_SPA` / `ENABLE_DIRECTORY` = 1), the [[DEMO-CONSOLE-WALKTHROUGH]] guide and the reader prompts.

### Compatibility

Additive only. No signature changed; no bean factory changed; no OpenAPI change. A consumer that never
declares the three attributes gets a byte-identical decision input. **One behavioral note for adopters
of the example policies:** with the new policy shape, a tag-requiring role's type-level create is denied
unless the gate declares the placement parent and the payload — the fail-closed direction, documented
in ADR 0034's consequences and in [[TAG-BASED-AUTHORIZATION]] §Layer 3.

## 2. Decisions already taken

- **Version 1.3.0** — additive surface ⇒ minor, the snapshot said so.
- **The quiet-host perf re-run still does not gate a release** (as for 1.1.0 / 1.2.0).
- **No new module is published** — the allow-list is unchanged; verified by the §5 dry-run (six
  coordinates, no `example-*`).
- **No separate pre-publish UI QA pass**: the release's only user-visible change was measured in the
  console at slice close-out (the E8 cell, [[TAG-GATED-CREATE]] STATUS-05, 2026-09-11), one day after
  the 34-cell pre-Habr pass ([[PRE-HABR-UI-QA-2026-09-10]]) that found the defect it fixes.

## 3. Pre-flight — every gate green on `main` @ `37ac494` (2026-09-11)

- [x] `./gradlew clean build --no-build-cache` — **BUILD SUCCESSFUL in 1m 57s, 1,275 tests, 0 failures**
      (the uncached build backs the signed artifacts — the recorded 1.2.0 trap)
- [x] `opa test infra/opa/policies` — **451/451**
- [x] local Sonar — CLEAN (the merged tree; the slice branch scanned CLEAN at every ticket)
- [x] `example-demo-ui`: `npm run lint` clean, `vitest` **69/69**
- [x] `scripts/postman/run-tests.sh` — 11 requests / 22 assertions / 0 failed
- [x] `run-tag-matrix.sh` — 25 / 46 / 0; `run-demo-world-matrix.sh --skip-matrices` — green (both on the
      images built from the merged tree)
- [x] CI green on `main` — all five checks on `37ac494`

## 4. Pre-publish sweeps (delta since 1.2.0)

- [x] **Secret scan** over the code/config delta (`git diff v1.2.0..main`, docs and `.mulch` excluded —
      the `clean-room` CI job covers those on every push): only Testcontainers fixture credentials and
      npm package names match; nothing to act on.
- [x] **CVE sweep** — `printResolvedRuntime | osv-sweep.py` over 192 resolved coordinates: the only hits
      are the **three known Tomcat advisories on the Boot-managed 11.0.24** — backlog item 10, measured
      unreachable here (no container-managed auth), still no Boot line manages 11.0.25; **not a blocker**,
      the re-check rule stands.
- [x] **Zero-config fail-closed** — `OpaAbacAutoConfigurationTest` in the starter (part of the 80 starter
      tests in the build above): a bare classpath with no `opa.abac.*` still denies; the 1.3.0 surface
      adds no default that could open anything (an undeclared gate is byte-identical).

## 5. The cut — PR 1 (`release: 1.3.0`)

1. `gradle.properties`: `VERSION_NAME=1.3.0` (this PR).
2. Dry-run into the local Maven repo with the gpg-command init script (RELEASING.md §3), from a wiped
   `~/.m2/repository/dev/dmitriikonovalov`: **exactly six coordinates**, no `example-*`; each of the
   five libraries → jar + sources + javadoc + pom + module, a `.asc` beside each; `opa-abac-bom` → pom
   (`<packaging>pom</packaging>`, the five modules under `dependencyManagement`) + module + `.asc`, no
   jar; the signature carries **issuer fpr (subpkt 33)** — signed with the gpg binary, non-interactive.
3. Merge this PR (CI), then on `main`:
   `./gradlew publishAndReleaseToMavenCentral -I <the init script> --no-configuration-cache` and watch
   the Portal's **Deployments** tab; a failed deployment releases nothing and is safe to retry.

## 6. PR 2 (`release: open 1.4.0-SNAPSHOT + README updates`)

1. `git tag v1.3.0` on the released `main` commit, `git push origin v1.3.0`.
2. `gradle.properties`: `VERSION_NAME=1.4.0-SNAPSHOT`.
3. **README sync** — status headline and the BOM snippet to 1.3.0; a **1.3.0 paragraph** (the placement
   gate, the additive surface, confinement, the baseline); the **dynamic tag dictionary** and
   **attribute-rich pre-authorization** feature bullets gain the placement sentence; the two proof-point
   callouts re-measured **and reconciled** (the "How this repo is built" table still carried 1.1.0-era
   numbers): 30 feature slices, 34 ADRs, 1,275 unit/IT tests, 69 SPA unit tests, `opa test` 451/451, a
   19-runner gateway matrix; the browser-gate section gains the pre-Habr pass + the E8 re-measurement;
   the Documentation list gains the tag guide + ADR 0034.

## 7. The publish log (2026-09-11) — what actually happened

- **Attempt 1**, `publishAndReleaseToMavenCentral` from the agent's shell over the corp VPN uplink
  (`utun6`): the six coordinates uploaded task by task, then the build service failed to stop with an
  empty `Upload failed:` after **27 min**. Measured afterwards: the uplink carried ~60–100 KB/s on every
  path (a 20 MB probe to third-party echo services stalled the same way), downloads ran at ~2 MB/s, and
  the Portal answered small requests in a second — not rate limiting, not the sandbox (the same probe
  unsandboxed was identical): the pipe.
- **Attempt 2**, the same command over the personal tunnel (`utun7`): `Connection reset` in 3 s.
- **Attempt 3**, the plugin's own bundle (`build/publish/dev.dmitriikonovalov-1.3.0-*.zip`, 21.7 MB,
  108 files, 27 signatures) uploaded with `curl --http1.1` and retries: the Portal sent `100 Continue`,
  took the whole body at ~33 KB/s over 11 min, then **reset the connection instead of answering** — a
  server-side timeout on a slow request, twice.
- **Attempt 4**, the maintainer ran the same curl from a terminal on a different ISP, tunnel-free:
  `201` with deployment `26023c7c-9edf-43d0-8404-30ff371828c9` in seconds; `VALIDATING` → `PUBLISHING`
  → **`PUBLISHED` at 13:34 MSK**; `published: true` on every coordinate. The manual-upload fallback is
  now in [`RELEASING.md`](../../../../RELEASING.md) §4a.
- Nothing was ever half-released: every failed attempt left no deployment behind (`published: false`
  throughout), which is why the retries were safe.

## Not in this release

- Backlog item 10 (Tomcat, waits on Boot), item 11 (a self/descendant re-parent answers 500 — found by
  the 1.3.0 review, pre-existing), item 12 (no request-body bound at the gateway/app).
- GATEWAY-AUDIENCE-BINDING (ADR 0035 reserved), Phase 11, the quiet-host perf re-run.

## Related

- [[RELEASE-1.2.0]] — the previous cut and the shape this note follows
- [[TAG-GATED-CREATE]] · ADR [[0034-tag-gated-placement-input-contract|0034]] — the content
- [[ENGINEERING-BACKLOG]] — items 10–12
- [`RELEASING.md`](../../../../RELEASING.md) — the mechanics
