---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/mcp
---

# STATUS — T8: Boot 4.1.x tested in CI; slice close-out

**Status:** ✅ DONE (2026-10-10, autonomous — part 1 runner; part 1 closed) — the local Boot 4.1.1 proof deferred (PKIX; see *Decisions*)

## What shipped

- **`gradle/libs.versions.toml`:** `springBootCompat = "4.1.1"` with a comment naming its single purpose; the repo
  pin `springBoot = "4.0.8"` untouched.
- **`-PspringBootLine`:** the root build computes `springBootBomVersion` once (absent ⇒ `springBoot`; `compat` ⇒
  `springBootCompat`; **any other value fails the build**, so a typo can never quietly run the default line while
  claiming 4.1) and exposes it on every project. Every `mavenBom("…spring-boot-dependencies:…")` import reads it —
  `opa-abac-spring-security`, `opa-abac-spring-data`, `opa-abac-keycloak-directory`, `opa-abac-spring-boot-starter`,
  `opa-abac-mcp` — and `example-mcp-server` gains an **explicit** import (its Boot plugin's own import is the plugin
  version, 4.0.8; the later explicit import wins). The example's build-file comments now say it consumes the module
  and the starter's auto-configuration.
- **`.github/workflows/ci.yml`:** one step inside the existing `build` job, after "Build and test …":
  `./gradlew :opa-abac-mcp:test :opa-abac-spring-boot-starter:test :example-mcp-server:test -PspringBootLine=compat --no-daemon`.
  No new job, no renamed job id.
- **Close-out docs:** [[POC-ROADMAP]] (the slice entry flipped to built — Phase 9's exit criterion delivered as the
  incubating `opa-abac-mcp`; the Phase 9 row's "remains the exit criterion" sentence updated); [[USER-STORIES]] Epic J
  (J5 reconciled with ADR 0037 — an engine outage yields the **empty** roster, an edge outside the batch the
  unfiltered one; J7–J9 ticked); ADR 0036 status → *Accepted (implemented 2026-10-10, release 1.5.0)* and its index
  row; [[AGENT-TOOL-AUTHORIZATION]] *Supported versions* (MCP SDK 2.0.x + Spring AI 2.0.x on Boot 4.0.x and 4.1.x,
  both tested in CI; Spring AI 2.1 out until GA) and its scope-boundary line; root `README.md` (the Phase 9 paragraph:
  the exit criterion delivered; the E1–E12 matrix); `CLAUDE.md` (the `-PspringBootLine=compat` command and the PKIX
  note; E1–E12); the CHANGELOG `## [Unreleased] — 1.5.0` module entry reconciled into one coherent entry (the
  module, the supported range, the SPI, the auto-configuration and every startup failure, the declarations check,
  the `agent-clients` guard, schema v1, the upgrade note, the property rename) beside ADR 0038's — the heading itself
  untouched. `ENGINEERING-BACKLOG` items **21–23** opened (see *Decisions*).

## Tests

- **U34 — the default line is untouched:** the full `dependencies` output for `compileClasspath`, `runtimeClasspath`
  and `testRuntimeClasspath` of all six affected modules (`opa-abac-spring-security`, `-spring-data`,
  `-keycloak-directory`, `-spring-boot-starter`, `-mcp`, `example-mcp-server`) is **byte-identical** before and after
  the change (18 of 18 `cmp` equal); `dependencyInsight --dependency org.springframework.boot:spring-boot
  --configuration testRuntimeClasspath` on `opa-abac-mcp`, the starter and the example ⇒ `4.0.8`. `ci.yml`: the job
  ids are `opa-policy-tests, clean-room, verification-layer, demo-ui, build` on both `main` and this branch; the diff
  adds exactly one step (`- name:` count +1), inside `build`.
- **U33 — the compat line:** **deferred** (PKIX, see *Decisions*). The mechanism itself was proven offline by
  temporarily pointing `springBootCompat` at the cached `4.0.7`: `-PspringBootLine=compat dependencyInsight` resolved
  `spring-boot:4.0.7` in `opa-abac-mcp`, the starter **and** the example (so the example's explicit import does
  override the plugin's), and the test run then stopped only on artifacts not in the cache — catalog restored to
  `4.1.1` after. An unknown value (`-PspringBootLine=4.1`) fails the build with "Unknown -PspringBootLine".
- `./gradlew --offline build` green on the default line (see *Integration / e2e*).

## Architecture review + refactor

Inline ★ review of the ticket: the property is read in exactly one place (the root script) and threaded to every
BOM import, so no module can disagree about the line; an unrecognized value fails rather than defaulting (the
fail-closed reading of a build switch); the default resolution is proven unchanged byte for byte, not argued. The CI
step runs the starter's tests, so on 4.1 it re-proves the auto-configuration's startup failures and the four per-type
ITs, and — because GitHub Actions sets `CI` — U24's reactive half on both lines. Nothing to refactor. No `.java`
changed in this ticket (Sonar not applicable; the part-wide scan is in the layer-2 record below).

## Integration / e2e

No new e2e for T8. The rig ran the full agent matrix in T6 and T7 (E1–E12 green, 62 requests / 92 assertions) on
images rebuilt from those commits; T8 changes only build wiring and docs. `./gradlew --offline build` on the default
line — **BUILD SUCCESSFUL** (all modules, the Testcontainers ITs, the example apps). The rig is torn down at the end
of this part (`./deploy.sh down && ./profile.sh down`).

## Decisions

- **LOCAL PROOF DEFERRED:** PKIX — Boot 4.1.1. The sandboxed Gradle cannot fetch the 4.1.x artifacts (measured: the
  cache holds `spring-boot-dependencies` 4.0.5/4.0.7/4.0.8 only; online resolution of the compat line fails, the
  same host-JDK truststore gap as `spring-webflux` in STATUS-05 — it surfaces here as a Gradle resolve error on the
  dependency-management plugin's BOM configuration). The maintainer runs, outside the sandbox:
  `JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=KeychainStore-ROOT -Djavax.net.ssl.trustStore=NONE" ./gradlew :opa-abac-mcp:test :opa-abac-spring-boot-starter:test :example-mcp-server:test -PspringBootLine=compat -PwithWebflux --no-daemon`
  then `./gradlew :opa-abac-mcp:dependencyInsight --dependency org.springframework.boot:spring-boot --configuration testRuntimeClasspath -PspringBootLine=compat`
  (expected `4.1.1`). Otherwise the CI step on the PR to `main` is the proof. The docs written here say the line is
  *tested in CI* — never that a local run passed. **Residual risk for that run:** Boot 4.1.1's BOM may manage a
  different JUnit than the catalog's `junit-bom` pin (the mx-744c3e class); if the compat step fails on launcher
  alignment, that is a real finding for a follow-up, not a reason to drop the step.
- **Backlog items opened** (ENGINEERING-BACKLOG, from 21): **21** a supported `tools/list` installer when java-sdk #578
  ships; **22** identity from the MCP transport context (the upstream Spring AI context-extractor hook to request; it
  also folds in T5's residual — the async wrappers' labelling read happens on the emitting thread); **23**
  `MemoizingRoleDefinitionSupplier`'s per-class request key (noted by part 0's review, outside this slice's boundary
  — judged real but low: cross-supplier, never cross-caller).
- Releasing 1.5.0 (ADR 0038 + this slice, one cut, no 1.4.1) stays the maintainer's step after merge; the version,
  the CHANGELOG heading and tags were not touched.

## Part review (layer 2)

**Path:** the 2A lens set applied **inline** in this part-runner's own context (the deep-review path table's
inside-a-subagent row — no review sub-agent, 2B unreachable). **Downgrade recorded:** T5 and T7 are headline tickets
and got inline-2A instead of the multi-lens path; the whole-delivery layer-3 `/deep-review` re-covers them.
**Scope:** part 1 as one diff, `1783b47..HEAD` (T5–T8 + their Mulch syncs): 62 files outside `.mulch/` — the three wrappers and the
shared invocation rule, the starter's four-type post-processing and reactive detection, the per-type ITs, the
`agent-clients` guard, schema v1 (jar + `agent_tools.rego` + tests), the compose/runner/collection E12 drill, the
Boot-line wiring and CI step, docs.

**Lenses run:** fail-closed · security (widening, cross-subject state, log/body leaks) · concurrency / threading
(decide on invocation, resubscription, emission-time reads) · wiring (every new seam has a consumer and a
non-happy-path test) · core boundary · additivity · rego (truthiness, vacuous negatives, bulk) · every-reader-of-a-switch
(`agent-clients`, `withWebflux`/`CI`, `springBootLine`) · docs-vs-code · autonomous-run (laziness, partial acceptance).

**Findings — one, fixed in the T8 commit:**

1. **Low (docs vs reality) — `CLAUDE.md` called the compat step "the CI `build` job's last step"**; it sits before
   "Upload test reports". Reworded to "a step in the CI `build` job".

**Checked and clean:**
- *Boundary:* `opa-abac-core` untouched; the only lines changed in `opa-abac-spring-security`, `-spring-data`,
  `-keycloak-directory` are their Boot-BOM import (T8's named deliverable — build wiring, no API); under
  `infra/opa/policies/` only `agent_tools.rego` + its test; the starter's main diff adds package-private wiring and
  one nested detection configuration — its public API is unchanged; the module still declares no beans; the package
  index, STATUS-01..04 and the other package docs untouched; the CHANGELOG heading untouched.
- *Fail-closed:* every tool-bearing element is one of the four types (wrapped, recorded, and asserted gated by
  **its own type's** gate) or a startup failure; a reactive app fails first; a non-web app and an off-thread
  customizer deny every call (pinned: U25, I6); the guard denies before any policy call and fails startup when it
  could never fire; schema v1 is a conjunct of the one request guard both `allow` rules and `bulk` inherit, pinned
  against non-string and near-miss markers, load-bearing per the U32 mutation (7 named tests).
- *Threading:* the decision is taken at `apply` (mutation-checked: `Mono.defer` fails the once-only case); the async
  decoration reads no identity. Residual recorded as backlog item 22 (labelling read on the emitting thread —
  error-to-error only).
- *Every reader agrees:* `agent-clients` — the authorizer (call **and** roster, through the one `buildContext`) and
  the wiring check, all from the one `IdentityProperties` bean; agent-gate OFF covered on both paths (U27/U29).
  `withWebflux`/`CI` — the build adds the artifact, the test skips without it locally and **fails** without it on CI.
  `springBootLine` — read once in the root script; an unknown value fails the build.
- *Rig / e2e:* `check-shell-guards.py` (28 clean) and `check-collection-conformance.py` (18 clean) on the touched
  runner and collection; E12 asserts the deny code by value and both allows by the returned catalog id (no
  absence-only cell); the default pod's empty env value is proven to bind to an empty list (binder test + the live
  default pod).
- *Static analysis:* local Sonar on files changed vs `origin/main` — 44 findings, all the standing documented
  by-design classes (S5778 ×32, S5853 ×4, S1168 ×2, S2925 ×2, S3011, S125, S1075, S5976 — Mulch `quality-gate-sonar`);
  every real finding the part introduced (S3416, S2094, S1130 in T5; S3776, S4144, S5778 in T6) was fixed in its
  ticket.
- *Autonomous-run lens:* each STATUS names what its review changed (T5: the shared invocation rule + three Sonar
  fixes; T6: the roster split + two test fixes; T7: nothing, stated; T8: the CLAUDE.md wording) — no ritual "nothing
  found" over a diff that says otherwise. The two deferred proofs (U24's reactive half, U33) carry their exact
  commands; no other acceptance case is partial.

No finding reaches part 0 (T1–T4): T5 extends part-0 code only as its Deliverables name it (the post-processor, the
verifier's assertion, `McpToolSpecifications`, `ToolCallGate`'s body moved into the shared rule with its tests
untouched), and T6 adds a guard to `ToolCallAuthorizer` / `ActorClaimWiringCheck` as its Deliverables name it.

## Commit

This commit — `ci: Boot 4.1.x line for the MCP tool-gate; MCP-AUTH-LIBRARY close-out (MCP-AUTH-LIBRARY T8)`.
