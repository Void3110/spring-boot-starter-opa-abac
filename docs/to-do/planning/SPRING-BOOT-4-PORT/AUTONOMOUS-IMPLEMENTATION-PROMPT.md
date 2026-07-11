---
tags:
  - status/planned
  - type/project
  - area/architecture
  - area/spring
---

# Spring Boot 4 port — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the SPRING-BOOT-4-PORT slice
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/spring-boot-4-port`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste
> the **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **the SPRING-BOOT-4-PORT slice** on branch
`feature/void3110/spring-boot-4-port`.

**The problem.** The library targets Spring Boot 3.4, and the whole 3.x line is out of OSS support —
a 1.0 published in late 2026 against it is dated on arrival. This slice ports the monorepo to
**Boot 4.0.x (Framework 7 / Security 7 / Jakarta EE 11 / Hibernate 7 / Jackson 3) on Java 25 /
Gradle 9.x**, as a single-line artifact (ADR 0026 — the dual 3.5/4.0 door is closed). The headline:
**byte-identical observable behavior on the new line** — this is a mechanical port, not a redesign;
any behavior delta you discover is a defect to fix, never a decision to make. Explicitly NOT in
scope (deferred): JSpecify adoption, the SF7-native CallGuard backend, the 1.0.0 version bump,
Maven-Central publish plumbing, any SPA or rig-image change, any rego or e2e-collection edit.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every place across the suite that asserts X") and for
**log-noisy validation** (e.g. run a newman matrix / a long build and report back only the failure
summary) — their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `SPRING-BOOT-4-PORT.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the settled forks F1–F9, the wire-format parity rule, the scope fence, the
   considered-&-rejected (dual artifact, JSpecify, SF7 resilience).
3. `01-DECOMPOSITION.md` — the 7 tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. **The pinned decisions** — ADR `0026-spring-boot-4-single-line-port` (packaging, Java 25,
   Jackson 3, R4j 2.4.0); ADR `0017-cross-service-http-resilience` (the CallGuard seam T3 amends);
   ADR `0021-load-testing-methodology` (governs T7's re-baseline).
5. `10-QA-TEST-CASES.md` — the B/S/R/H/C/W/D/E/P cases your work must satisfy.
6. **Context you will be checked against** in the review gate (step 5):
   `docs/guides/HTTP-RESILIENCE.md` + the existing `Resilience4jCallGuard` test suite (T3);
   `docs/guides/PARTIAL-EVALUATION-FILTERING.md` (T6); `docs/guides/CONCURRENCY-AND-LOCKING.md`
   (the review gate's locking check); `docs/guides/E2E-TESTING.md` + `scripts/postman/README.md`
   (T7); root `PERFORMANCE.md` + `scripts/load/README.md` (T7); `RESEARCH.md` in this folder (the
   per-module inventory).
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token
   caveat** and the **"restart OPA after editing a policy"** gotcha.
9. **Prime Mulch:** `ml prime opa-abac` + the directly-relevant record ids: mx-50a48b (per-runner
   rig postures + the gateway-timeout law), mx-926c85 (fail-closed conventions), mx-aa63a6 (the
   keycloak-admin-client seam T4 re-verifies under EE 11), mx-3db8a8 (the external-consumer context
   behind this port).

### Per-ticket loop (tickets T1 → T7, IN ORDER; T2 ∥ T3 and T5 ∥ T6 may land in either order within
their stage, but never before their stage's predecessor)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,security,data,autoconfigure}`,
   `dev.dmitriikonovalov.example.{catalog,usermgmt}.*`, the gradle catalog/wrapper/CI files),
   mappings, rego rules. Match the surrounding code's naming and idioms.
   **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant B/S/R/H/C/W/D/E/P cases from
   `10-QA-TEST-CASES.md`). Core/client tests use an **in-process `com.sun.net.httpserver.HttpServer`
   stub** (no WireMock). Persistence/IT tests run against **real Postgres via Testcontainers** (never
   H2). Policies use `opa test` (this slice edits zero rego — 228/228 unchanged is itself an
   acceptance).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — this slice must not MOVE any fail-closed edge: every existing
     deny/empty/503/throw path answers byte-identically on the new line; a port-induced
     serialization default-flip that widens any wire contract (OPA input / JWT claims / jsonb tags)
     is exactly the defect class W1–W3 exist to catch.**
   - **Security check — name the widening that would matter for this ticket: a Jackson-3 default-flip
     changing absent-vs-null on the OPA `input` (a rego `undefined` becoming a defined value), claims
     parsing silently loosening, the R4j swap changing when the breaker opens (never-opens = an
     availability change; misclassified failures = a denial change), Security 7 dispatch altering
     when the manager runs — and state why it cannot happen.**
   - **Concurrency / idempotency check — this slice adds no new decisions or mutations; the check is
     that the platform swap didn't change them under us: the existing optimistic-lock/version-guard
     ITs are the pin that Hibernate 7's flush/locking semantics still satisfy
     `CONCURRENCY-AND-LOCKING.md` Rules 1–2 (decide-under-protection).**
   - **Wiring check** — every seam this ticket adds (an SPI, a property, a guard, an exception + advice
     mapping, a cache accessor, a rego entrypoint, a recovery edge) has a **named consumer** and a test
     through its **non-happy path**; zero call sites = the ticket is not done. (For this port the only
     new "seam" is the Jackson-3 mapper construction — its non-happy paths are W2's malformed-payload
     and W3's Jackson-2-literal cases.)
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (Jackson 3 is a JDK-level
     dependency, allowed; nothing from `org.springframework.*` enters core); name the
     byte-for-byte-unchanged surfaces (OpenAPI contracts, the rego corpus, the error contract, the
     e2e collections) and the one accepted public-API type change (the `HttpOpaClient` constructor's
     Jackson-3 `ObjectMapper` — absorbed by the starter, first publish).**
   - **Module-layer separation — the dependency flow `core ← spring-security/spring-data ← starter`
     is unchanged; a compile-error fix that reaches across a module boundary is wrong even if it
     compiles.**
   - **Pattern-reuse check — the private-mapper isolation comments in `OpaAbacAutoConfiguration` /
     `OpaAbacSecurityBeans` govern the Jackson-3 builder swap (the app's Jackson customizations must
     keep NOT leaking into the OPA wire); the Boot-4 test annotations follow the canonical forms, not
     workarounds; T3 follows the existing deterministic-timing test idiom.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - Tickets T1/T4/T5/T6: the **full** `./gradlew build` (Testcontainers ITs against real Postgres)
     is the integration gate; T4 additionally proves the `ddl-auto: validate` boot on both example
     services (H3) and the no-`JAVA_HOME`-override build (B2). Fix-until-green.
   - e2e ticket T7: rebuild images (`./deploy.sh build` — **verify the build actually succeeded and
     the images are fresh: check `${PIPESTATUS[0]}`/`BUILD SUCCESSFUL` and pod created-at; never
     trust `| tail` exit codes**), bring the rig up per the per-runner posture law (run-tests /
     run-matrix on `ENABLE_OIDC=1 ./deploy.sh up --pods 2`; run-team-matrix on `ENABLE_DIRECTORY=1`;
     resilience on the B3 stub rig; the rest per each runner's header), then `cd scripts/postman` and
     run the fleet. Honor the in-network token caveat. The perf re-baseline follows ADR 0021's
     two-pass discipline (`scripts/load/run-load.sh`, incl. the `ENABLE_OPA=0` unguarded pass).
     Fix-until-green — but remember: **an e2e assertion that needs editing is a behavior delta and
     therefore a code defect (slice invariant 1), never a collection fix.**

7. **Update documentation (after each ticket).** Tick the ticket in the `SPRING-BOOT-4-PORT.md` status
   table; record real values/decisions in `STATUS-0N.md`. The ticket that finalizes a guide topic
   writes/reconciles `docs/guides/*` version references, root `CLAUDE.md`'s build line (T7), the ADR
   0017 addendum (T3), and `PERFORMANCE.md` (T7). Root/project `CLAUDE.md` only if a new build/run
   step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `build(port): …` / `refactor(security): …` / `test(data): …` as fits the ticket.
   A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example code, build files (catalog, wrapper, toolchains, CI workflow),
  tests, docs in this folder + the guides + `PERFORMANCE.md`, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`,
  `ENABLE_DIRECTORY=1 …`); reset fixtures; rebuild images; restart OPA; run the k6 harness; drop/
  recreate the **local** schema if needed.
- Resolve "latest patch" versions at run time (Boot 4.0.x, Gradle 9.x, springdoc 3.x, the
  openapi-generator release) — record the chosen pins in `STATUS-0N.md`; version *lines* are pinned
  by the design, exact patches are yours.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Fail-closed is the load-bearing invariant — for this slice: no error path answers differently on
  the new line than on the old one, and no port-induced serialization or platform change widens any
  result; byte-identical behavior IS the fail-closed statement here.**
- **Slice-specific invariants — never trade these away: byte-identical behavior (no OpenAPI diff,
  zero rego edits, zero e2e-collection edits, no error-contract change); wire-format parity asserted
  via W1–W3 (default-flips restored explicitly on the builder, with a comment naming the flipped
  default); the mixed Jackson intermediate state exists only between T4 and T5; deprecations zeroed
  by T6 or explicitly accepted with a dated note; no version bump, no publish plumbing, no JSpecify,
  no SF7-native CallGuard; SPA and rig images untouched.**
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **`opa-abac-core` stays Spring-free** — Jackson 3 is allowed in core; Spring is not.
- **`ddl-auto: validate` must pass** — the port touches no schema, so a clean boot on Hibernate 7 is
  the proof the platform swap didn't move it (H3).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.

---

## Operator notes (not part of the prompt)

- **The headline tickets** — **T4** (the bump: full build green on JDK 25/Gradle 9/Boot 4 = Hibernate
  7, Security 7, the toolchain, and every rename proven at once) and **T7** (the live fleet + the
  honest re-baseline). **T5** is the *safety* headline: the three wire-parity pins are where a silent
  port defect would live.
- **The fail-closed edge to eyeball** — the jsonb tags column (W3: existing databases hold
  Jackson-2-written strings; a round-trip regression silently corrupts tag-based authorization), and
  absent-vs-null on the OPA `input` (W1: a rego `undefined` becoming defined flips policy semantics).
- **Standalone-value subset** — T1+T2+T3 merge cleanly on their own (still a 3.5 repo, two
  deprecation classes retired early) if the window is short.
- **Rig / e2e specifics** — Docker Desktop, never podman, for this rig; per-runner postures
  (mx-50a48b + each runner's header); in-network token minting; image freshness before trusting a
  fleet run (`${PIPESTATUS[0]}`, pod created-at — the `| tail` trap bit twice in the PR #68 session);
  the perf re-baseline needs ADR 0021's two-pass discipline incl. the `ENABLE_OPA=0` unguarded pass,
  and its fine print must carry the **double attribution** (new stack + PR #68's filtered product
  list, commingled by design — F8).
- **At merge time (maintainer):** tag the last 3.4 commit on `main` as `pre-sb4-port` before this
  branch lands; release notes for 1.0 anchor to the Boot-4/Java-25 story (PRs #64, #65, #67, #68 +
  ADR 0026).
- **CI does not run the rig yet** — the newman matrix is a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up. The ci.yml JDK-25 change is proven live only on the maintainer's push.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship.
