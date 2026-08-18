---
tags:
  - status/planned
  - type/project
  - area/release
---

# Release 1.2.0 — the cut plan

> **Status: 📋 planned, not started.** Written 2026-08-18. The mechanics live in
> [`RELEASING.md`](../../../../RELEASING.md) §3–§5 — this page is *what is specific to 1.2.0*, not a
> re-statement of the runbook. Prime `ml prime opa-abac-publish --budget 8000` before starting.

**Precedent to copy:** the 1.1.0 cut, which was exactly two PRs —
[#90 `release: 1.1.0`](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/90) (pre-publish QA
note + `VERSION_NAME` drop, then publish) and
[#91 `release: open 1.2.0-SNAPSHOT + README updates`](https://github.com/Void3110/spring-boot-starter-opa-abac/pull/91).

**Mildly time-sensitive:** the awesome-opa listing went live **2026-08-17** (Java integrations list).
Everyone arriving from it resolves **1.1.0** until this ships.

---

## 1. What is actually in this release

30 commits since `v1.1.0`, and — unlike a docs bump — the **published library modules really changed**:
**5 new public types, 14 modified**, all additive.

| New public type | Module | Why it exists |
|---|---|---|
| `OpaDecision` | core | the allow + optional structured reason, for callers that want the reason |
| `DenyReason` | core | the `insufficient_user_authentication` payload (`required_acr`, `max_age`) |
| `StepUpRequiredDecision` | spring-security | the `AuthorizationDecision` subtype the advice maps to a 401 challenge |
| `PrivilegedReadAuditPolicy` | spring-security | the adopter's own vocabulary for "a privileged read worth auditing" |
| `AbacAuditLogger` | spring-security | the `opa.abac.audit` emission point (`STEP_UP_CHALLENGED`, `PRIVILEGED_READ`) |

Modified surfaces worth naming in the release notes: `OpaClient.decide()`, `AbacContext.Resource`'s
fifth `root_attributes` component ([[adr/0032-root-attribute-enrichment-input-contract|ADR 0032]]),
`LibraryErrorCode.STEP_UP_REQUIRED`, and `OpaAbacProperties` (`subject.attribute-claims`,
`audit.privileged-read.*`).

Feature-level, this is **Phase 9 + Phase 10**: agent tool-call authorization (MCP), the supervisor read
path, the operator-managed production tier, RFC 9470 step-up elevation, and the demo console that
consumes the challenge — plus the packaged SPA, the parts-orchestration runner, `CONTRIBUTING`/`SECURITY`,
and the partial-eval foreign-type-disjunct fix.

### Compatibility

**Additive only — no breaking change.** The one deliberate compatibility artifact: the starter's
3-argument `opaPreAuthorizeAuthorizationManager` bean factory is **retained as a link-compatibility
shim** (1.0.0/1.1.0 shipped that signature), delegating with a `null` audit policy — which is exactly
what a pre-Phase-10 caller should get. It carries no `@Deprecated`; its removal is a **MAJOR**-version
decision recorded in [[adr/0030-step-up-decision-contract|ADR 0030]] Amendment 7. **1.2.0 keeps it.**

---

## 2. Decisions already taken

- **The quiet-host perf re-run does NOT gate this release.** It did not gate 1.1.0 either, and the
  awesome-opa arrivals are landing on 1.1.0 now. It stays an independent task (see
  *Not in this release* below).
- **No new module is published.** `example-mcp-server` — the one new Gradle module since 1.1.0 — is
  excluded **by construction**: `build.gradle.kts` publishes from an explicit five-name allow-list, not
  a blanket `subprojects {}`. Still verify the six-coordinate output in the §3 dry-run.

## 3. Pre-flight — every gate green on `main`

Export `ENABLE_SPA=1 ENABLE_MCP=1` for the whole session (the matrices call `deploy.sh up`, and the
flag-off arms delete those stacks).

- [ ] `./gradlew build`
- [ ] `opa test` — 389/389
- [ ] `./.sonar-local/sonar-local.sh` — CLEAN
- [ ] `example-demo-ui`: `npm run lint` + `npm test` (63)
- [ ] `scripts/planning/test-parts-gates.sh` — 39/0
- [ ] `scripts/postman/run-tests.sh` — 22
- [ ] `run-demo-world-matrix.sh` — 169 assertions / 0 failures
- [ ] `run-demo-world-matrix.sh --convergence` (E33) — **separate pass**, after a realm re-import + re-seed
- [ ] CI green on `main` — all 4 checks, including `clean-room` and `demo-ui`

## 4. Pre-publish sweeps (delta since 1.1.0)

The 1.0 cut ran secret-scan / CVE-sweep / zero-config-fail-closed. Re-run them **scoped to the delta**,
because the delta added a whole new example service and a new client-side surface:

- [ ] **Secret scan** over the 30-commit delta — the `clean-room` CI job now covers `.mulch` + `docs` on
      every push, so this is a check of *code and config*, especially `example-mcp-server` and
      `example-demo-ui` (a bundled SPA is a new shipping surface for an accidental token).
- [ ] **CVE sweep** — Spring AI 2.0.0 and the MCP server's transitive tree are new since 1.1.0.
- [ ] **Zero-config fail-closed** — the starter on a bare classpath with no `opa.abac.*` set must still
      deny. The step-up additions must not have opened a default: unset
      `audit.privileged-read.*` is *silent*, and an absent `acr`/`auth_time` leaves `elevated`
      undefined. Re-assert both.
- [ ] **Pre-publish UI QA** — 1.1.0's precedent was a *delta* browser pass (`PRE-PUBLISH-UI-QA-2026-07-15.md`).
      For 1.2.0 the delta is the step-up console, which was already run adversarially as the committed
      **E10–E21** case list at slice close-out. **Proposed: a short delta pass** re-running E10, E11,
      E15 and E17 against a rig built from current library code, written up as
      `docs/code-review/PRE-PUBLISH-UI-QA-<date>.md`. *(Rebuild both service images first — a stale
      image silently tests old library code; that is a recorded 1.1.0 failure.)*

## 5. The cut — PR 1 (`release: 1.2.0`)

1. Land the pre-publish QA note + any mulch updates.
2. `gradle.properties`: `VERSION_NAME=1.2.0` (drop `-SNAPSHOT`). **Only now** — RELEASING.md §5.
3. Dry-run into the local Maven repo with the gpg-command init script (§3). Confirm:
   - 5 libraries × (jar + sources + javadoc + pom + `.asc` each)
   - `opa-abac-bom` → pom + `.asc`, **no jar**
   - `find ~/.m2/repository/dev/dmitriikonovalov -maxdepth 1 -type d` → **exactly 6** coordinates,
     no `example-*`
   - the signature carries **issuer fpr (subpkt 33)** — sign with the **gpg binary**, never the
     in-memory key (the 1.0.0 failure)
4. `./gradlew publishAndReleaseToMavenCentral -I /tmp/use-gpg-cmd.init.gradle.kts --no-configuration-cache`
5. Watch the Portal **Deployments** tab; a failed deployment releases nothing and is safe to retry.

## 6. PR 2 (`release: open 1.3.0-SNAPSHOT + README updates`)

1. `git tag v1.2.0 && git push origin v1.2.0` on the released commit.
2. `gradle.properties`: `VERSION_NAME=1.3.0-SNAPSHOT`.
3. **README sync** — the 1.1.0 cut's second PR did exactly this, and 1.2.0 has more to say:
   - **Status**: `1.1.0` → `1.2.0` in the headline *and* the BOM snippet (line ~55).
   - A **1.2.0 paragraph**: agent tool-call authorization, the supervisor read path, the production
     tier, RFC 9470 step-up, and the console that consumes the challenge.
   - **Proof-point counts** — re-measure rather than increment; the callout currently claims 28 slices,
     33 ADRs, 1225 unit/IT tests, 63 SPA tests, `opa test` 389/389, a 19-runner gateway matrix.
     *Checked 2026-08-18: the ADR count is accurate (33 files excluding the index README, highest is
     0033). Re-measure the test counts against a fresh `./gradlew build`.*
   - The **Shipped** list gains the Phase 9/10 entries.

## Not in this release

- **The quiet-host perf re-run** (gate delta + ceiling), carried over from the 1.0 plan — its own task.
- Everything in [[ENGINEERING-BACKLOG]] — 8 items, none started; **PIT mutation testing** is top and
  mechanically catches the defect class that survived a full review round.
- **Phase 11** (Keycloak capability projection) and **Phase 8** (ReBAC-in-Rego, now positioned by
  [[REBAC-AND-ABAC]]).

## Related

- [`RELEASING.md`](../../../../RELEASING.md) — the mechanics (§3 dry-run, §4 publish, §5 post-release)
- [[adr/0027-maven-central-release-engineering|ADR 0027]] — the publishing design
- [[POC-ROADMAP]] — Phase 10 complete, the cut unblocked
