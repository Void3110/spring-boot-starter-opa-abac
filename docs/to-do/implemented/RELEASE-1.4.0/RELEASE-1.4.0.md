---
tags:
  - status/done
  - type/project
  - area/release
---

# RELEASE-1.4.0 — "could not decide" is a typed 503, not a deny

> **Status: the cut of 2026-10-05.** Main has been on `1.4.0-SNAPSHOT` since the 1.3.0 cut (2026-09-11). The API
> delta is source- and binary-compatible, and the release changes error-path **behaviour** (ADR 0037 §10), so it
> is a **minor**: 1.4.0. The runbook is [`RELEASING.md`](../../../../RELEASING.md); the previous cut's record is
> [[RELEASE-1.3.0]].

## 1. What is actually in this release

The five published libraries and the BOM: `dev.dmitriikonovalov:opa-abac-{core, spring-security, spring-data,
keycloak-directory, spring-boot-starter}` plus `opa-abac-bom`. Library `src/main` since `v1.3.0` is **27 files,
+1,002/−455**, all of it [[ENGINE-ERRORS]] (ADR [[0037-indeterminate-decision-distinct-from-deny|0037]]). The
adopter-facing account is [`CHANGELOG.md`](../../../../CHANGELOG.md) `[1.4.0]`, the first entry of the new
changelog. In short:

- **Core.** A Spring-free `DecisionIndeterminateException` family. `PolicyEngineException` carries a `Kind`, and
  `RoleResolutionException` joins the family. `HttpOpaClient` throws the family when it could not get a decision;
  a real deny stays a deny.
- **Resilience.** It retries only transient faults and never a decision, so a deny is called exactly once. The OPA
  breaker counts exactly what it retries, which means it opens on a real outage for the first time.
- **Gates.** Both gates throw `AuthorizationIndeterminateException`, which is still an `AccessDeniedException`.
  `AbstractProblemAdvice`, and the starter's fallback advice, answer **503 `DEPENDENCY_UNAVAILABLE`**.
- **Data layer.** The list, hierarchy and subtree paths propagate the family instead of answering an empty page,
  `false` or no widening.
- **Fixed** (pre-existing since 1.0.0, found in review): an `ALLOW_ALL` residual OR-ed with a subtree widening
  collapsed to the subtree alone.
- **Dependencies.** `opa-abac-core` declares Jackson 3 **3.1.7**; see §4.

**The baseline** is unchanged: Spring Boot 4.0.8 on Java 25.

**Since 1.3.0, not published:**
- the supervised-scope matrix's E11, the mixed catalog page (backlog item 19);
- the partial-eval guide corrections;
- the Codex-CLI reviewer experiment.

### Compatibility

No signature was removed or changed. `CallGuard` gains an optional fourth argument, a default method. **The
guarantee holds: nothing refused in 1.3.0 is allowed in 1.4.0.** What changes is how a refusal *for want of a
decision* surfaces: a typed exception and a 503 where 1.3.0 gave `false`, a 403 or an empty page. Read the
CHANGELOG's **upgrade notes** before bumping, especially if you:
- call `OpaClient` directly;
- catch `AccessDeniedException` to render a 403;
- treat an empty list as authoritative.

## 2. Decisions already taken

- **Version 1.4.0**, as ADR 0037 §10 decided: a compatible API with error-path behaviour changes, and a new
  `CHANGELOG.md` starting here.
- **The Jackson pin moves to 3.1.7** in this cut (maintainer, 2026-10-05). It is our own pin, which
  `opa-abac-core` resolves and publishes. The Boot-managed copies follow Boot (backlog item 10, widened); §4 has
  the triage.
- **The quiet-host perf re-run still does not gate a release**, as for 1.1.0–1.3.0.
- **No new module is published.** The allow-list is unchanged, as the §5 dry-run verified.
- **No separate pre-publish browser QA pass.** The demo SPA has no change since `v1.3.0`. The two user-visible
  changes were measured through the gateway today, on rebuilt images:
  - the 503 on a role-source outage (resilience matrix E2);
  - the mixed catalog page (supervised-scope E11; a pre-fix image fails it).

## 3. Pre-flight — every gate green (2026-10-05)

- [x] `./gradlew clean build --no-build-cache` on the release tree, with Jackson 3.1.7 and `VERSION_NAME` unchanged:
      **BUILD SUCCESSFUL in 1m 56s**, 54 tasks executed plus 1 up-to-date, **0 FROM-CACHE**. The JUnit XMLs sum to
      **1,350 tests, 0 failures, 0 errors**: core 148, spring-security 258, spring-data 154, keycloak-directory 8,
      starter 88, and the examples 295 + 260 + 139. The same total ran before the bump.
- [x] `opa test infra/opa/policies`: **451/451** (OPA 1.10.1).
- [x] Local Sonar: **0 findings in main code** on the ENGINE-ERRORS final tree, with the 15 test-only findings in
      the by-design catalog (`docs/code-review/ENGINE-ERRORS-REVIEW.md`). Nothing since touches `.java`.
- [x] `example-demo-ui`: `npm run lint` clean; `vitest` **69/69**.
- [x] Live e2e on `main` with all three images rebuilt (`9f228b2`; E11 at `a3b9397`):
  - resilience: E1 200, E2 **503 `DEPENDENCY_UNAVAILABLE`**;
  - supervised-scope: 49/49, then E8 6/6;
  - agent-tool, including the OPA kill drill: every folder green;
  - `run-tests.sh`: 22/22.
- [x] CI is green on `main`: all five checks on `a3b9397`.

## 4. Pre-publish sweeps (delta since 1.3.0)

- [x] **Secret scan** over the added lines of the code and config delta (`git diff v1.3.0..main`, excluding
      `docs/`, `.mulch/` and `*.md`; the `clean-room` CI job covers those on every push).
  - Scope: 3,175 lines. Rules: private keys, cloud and VCS tokens, JWTs, bearer literals, credential
    assignments, absolute user paths, internal IPs.
  - Result: **0 hits**.
  - The credential words that do appear are a javadoc phrase, a deliberately fake sentinel in
    `HttpOpaClientCompileTest` (it proves the client never echoes a body), and a Postman variable.
- [x] **CVE sweep** (`printResolvedRuntime | osv-sweep.py`, 194 resolved coordinates).
  - **New since 1.3.0:** seven Jackson advisories, published 2026-09-28 – 10-01 (five HIGH), on both lines
    Boot 4.0.8 manages: `tools.jackson` 3.1.5 and `com.fasterxml` 2.21.5.
  - **Triage:**
    - The library uses none of the affected features: polymorphic typing, object identity, `Path`,
      `XMLGregorianCalendar`, `DataInput` parsing, string-to-number coercion. Its one parse site is
      `HttpOpaClient`'s `readTree(byte[])` of OPA's response.
    - **Split by ownership:** the `tools.jackson` pin is ours, so it moved to **3.1.7**. The hits on
      `:opa-abac-core` went **7 → 0**, and the published core POM declares 3.1.7.
    - The modules that import the Boot BOM still resolve Boot's copies, as every Boot adopter does. So do
      the three **Tomcat** advisories on 11.0.24.
  - Both are **backlog item 10**, widened. **Not a blocker:** the re-check rule stands, and the trigger is a
    Boot 4.0.x patch.
- [x] **Zero-config fail-closed:** `OpaAbacAutoConfigurationTest` passes **64/64** in the build above. A bare
      classpath with no `opa.abac.*` still denies, and 1.4.0 adds no default that could open anything. Its new
      fallback advice only *renders* a refusal.

## 5. The cut — PR 1 (`release: 1.4.0`)

1. This PR:
   - `gradle.properties`: `VERSION_NAME=1.4.0`;
   - `libs.versions.toml`: `jackson = "3.1.7"`;
   - `CHANGELOG.md`: `[1.4.0] — 2026-10-05`, plus the *Dependencies* entry;
   - backlog item 10, widened;
   - this record.
2. **Dry-run** into the local Maven repo with the gpg-command init script (`RELEASING.md` §3): **exactly six
   coordinates**, no `example-*`.
   - Each of the five libraries has a jar, sources, javadoc, a pom and a module file; the BOM has a pom
     (`<packaging>pom</packaging>`, all five modules managed) and a module file.
   - Each artifact has a `.asc` beside it (**0 unsigned**), and the signature carries **issuer fpr (subpacket 33)**.
   - The core POM declares `jackson-databind` **3.1.7**, and the starter's POM imports the Boot BOM, as before.
3. Merge this PR (CI), then **the maintainer publishes** from `main`:

   ```bash
   ./gradlew publishAndReleaseToMavenCentral -I <the gpg-command init script> --no-configuration-cache
   ```

   Then watch the Portal's **Deployments** tab. **Probe the uplink first** (`RELEASING.md` §4a): the 1.3.0 upload
   failed three times over VPN paths. If the plugin cannot get through, upload the bundle it built and signed
   (`build/publish/dev.dmitriikonovalov-1.4.0-*.zip`) with the §4a `curl`, from a tunnel-free uplink. A failed
   deployment releases nothing and is safe to retry.

## 6. PR 2 (`release: open 1.5.0-SNAPSHOT + README updates`)

1. `git tag v1.4.0` on the released `main` commit, then `git push origin v1.4.0`.
2. `gradle.properties`: `VERSION_NAME=1.5.0-SNAPSHOT`. 1.5.0 is earmarked for the MCP auth library (ADR 0036).
3. **README sync:**
   - the status headline and the BOM snippet move to 1.4.0;
   - the **1.4.0 paragraph** changes from "unreleased" to published;
   - the proof-point callouts are re-measured and reconciled (1,350 unit/IT tests, `opa test` 451/451, ADR count,
     slice count).
4. This record's §7, the publish log, and a `RELEASING.md` addendum if the cut teaches anything new.

## Not in this release

- Backlog item 10 (Boot-managed Tomcat and Jackson; waits on Boot).
- Items 11–12 (re-parent 500; no request-body bound).
- Items 13–15 (deny-shaped gaps ADR 0037 documents; 14 is first to schedule).
- Item 16 (calibrating the OPA breaker under a kill).
- Item 18 (the example clients' base-URL validation).
- MCP-AUTH-LIBRARY (ADR 0036, 1.5.0), GATEWAY-AUDIENCE-BINDING (ADR 0035), Phase 11, the quiet-host perf re-run.

## Related

- [[RELEASE-1.3.0]] — the previous cut, and the shape this note follows
- [[ENGINE-ERRORS]] · ADR [[0037-indeterminate-decision-distinct-from-deny|0037]] — the content
- [`CHANGELOG.md`](../../../../CHANGELOG.md) — the upgrade notes
- [[ENGINEERING-BACKLOG]] — items 10–19
- [`RELEASING.md`](../../../../RELEASING.md) — the mechanics
