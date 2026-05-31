---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring-security
  - area/spring
---

# Library spine — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the authorization spine autonomously,
> ticket by ticket, with an architecture-review gate and a checkpoint after each ticket. The design and
> work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/library-spine` off a
> clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **library spine** on branch `feature/void3110/library-spine`.

**The problem.** The catalog example authenticates nobody and authorizes nothing: no Spring Security on
the classpath, a fixed `DEMO_PRINCIPAL` instead of the real caller, and an allow-all OPA placeholder.
The only "identity" work happens at the gateway via a throwaway Lua enricher. You are moving real,
fine-grained ABAC **into the app via the library**: a fail-closed OPA client, JWT→subject extraction, a
**role-definition-driven** `@OpaPreAuthorize` enforcement path, starter auto-wiring, and the catalog
app's adoption of all of it — retiring the demo enricher. Scope is **single-decision only** (no batch,
no partial-eval list filtering, no hierarchical walk — those are Phase 5).

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `LIBRARY-SPINE.md` (this folder's index) — what this slice delivers, the file glossary, the ticket
   status table, the critical path, conventions.
2. `00-DESIGN.md` — the design: the spine, the `RoleDefinition` + `RoleDefinitionSupplier` backbone
   (demo→Phase-4 swap), the fail-closed `HttpOpaClient`, the signature-trust posture, per-type rego, the
   two-layer model, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the seven tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. `10-QA-TEST-CASES.md` — the unit / integration / policy / e2e cases your work must satisfy.
5. **Pattern guide you build against:** `docs/architecture/DOMAIN-MODEL.md` (the `AbacDataObject`
   resource side every secured entity already implements). You will **author** two new guides in ticket
   7 (`docs/guides/ABAC-AUTHORIZATION.md`, `docs/architecture/TWO-LAYER-AUTHORIZATION.md`).
6. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only; the prior platform is
   study-only) and the **commit identity** rule (`Void3110 <void31102025@gmail.com>`).
7. `infra/README.md` — the local rig (Keycloak → APISIX → OPA → Jaeger), needed for tickets 5–7,
   including the **in-network token caveat** and how OPA loads policies from `infra/opa/policies/`.
8. **Prime Mulch:** `ml prime opa-abac`. Note especially the fail-closed convention (`mx-926c85`), the
   two-layer authorization pattern (`mx-7130a1`), the ABAC input model (`mx-2c6374`), and the decision
   that gateway enrichment is demo scaffold replaced by library extraction (`mx-cbca87`).

### Per-ticket loop (tickets 1 → 7, IN ORDER)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the specific module or
   class, and re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,security,autoconfigure}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` for the app), the rego documents, and the infra edits. Match
   the surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I*/E* cases from `10-QA-TEST-CASES.md`).
   Use the in-process `com.sun.net.httpserver.HttpServer` stub for the OPA client (no WireMock); for the
   policies use `opa test`. The example ITs run against **real Postgres via Testcontainers** (never H2).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then apply refactoring and re-test:
   - **Fail-closed check:** every OPA error/timeout/ambiguity denies, at both the client and the
     manager; `allow()` never throws for a transport/parse failure; deny never silently becomes allow.
   - **Boundary check:** `opa-abac-core` stays Spring-free (no Spring/JPA imports; JDK `HttpClient` +
     Jackson only); Spring-Security code lives only in `opa-abac-spring-security`; the starter wires
     beans `@ConditionalOnMissingBean`/`@ConditionalOnClass` and does **not** register a
     `SecurityFilterChain`.
   - **Pluggability check:** `RoleDefinitionSupplier`, `AbacSubjectExtractor`, `PolicyPathResolver` are
     clean SPIs with sensible defaults, overridable by one bean — so the Phase-4 user-service swap is a
     single bean. Claim names + policy paths are configuration, not hardcoded.
   - **SOLID / decomposition:** is each piece cohesive (SRP) and depending on interfaces (DIP)? Anything
     to split or simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - Ticket 4: `ApplicationContextRunner` slice tests (bean presence/absence, overridability, no-security
     classpath). Ticket 5: `./gradlew build` (the example ITs incl. the `ddl-auto: validate` boot) +
     `opa test infra/opa/policies/`. Fix-until-green.
   - Ticket 7: bring the rig up (`ENABLE_OIDC=1 ./deploy.sh up --pods 2`, with OPA carrying the per-type
     policies), then `cd scripts/postman && ./run-tests.sh`. Honor the **in-network token caveat** (mint
     the Keycloak tokens from inside the compose network; see `docs/guides/E2E-TESTING.md`).
     Fix-until-green.

7. **Update documentation (after each ticket).**
   - This folder: tick the ticket in the `LIBRARY-SPINE.md` status table; record real
     values/decisions in the `STATUS-0N.md`.
   - Ticket 7 authors `docs/guides/ABAC-AUTHORIZATION.md` + `docs/architecture/TWO-LAYER-AUTHORIZATION.md`
     and reconciles `docs/guides/E2E-TESTING.md` + `infra/README.md` + the roadmap.
   - Root/project `CLAUDE.md` only if a new build/run step matters for manual testing.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. Skip only
   if nothing is non-obvious. Verify the sync commit touches `.mulch/` only.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(library-spine): <ticket summary>` (use `feat(opa-client)` / `feat(abac-security)` /
   `feat(starter)` / `feat(example)` / `chore(infra)` / `test(e2e)` if a narrower scope reads better). A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without
    a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example code, the rego policies, tests, docs in this folder + the two new
  guides, the `scripts/postman/` suite, `infra/` (the realm export, `init-routes.sh`, `deploy.sh`,
  delete `enricher-plugin.py`), and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`); reset
  fixtures; rebuild images; reload OPA policies; add realm users — local environment only.
- Use `/rego-skill` to author + `opa test` the per-type policies.
- Fix any issue your own validation reveals (compile, unit, IT, policy, e2e, refactor). Iterate until
  green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/policy/e2e. Document what the
  review found each ticket.
- **Fix-until-green within the ticket** for compile/test/IT/policy/e2e/config issues. Only STOP
  mid-ticket if genuinely *blocked*: the same root cause survives ≥3 focused attempts, OR a design
  decision the docs don't cover is needed, OR a local prerequisite is unrecoverable.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
  Reference the prior platform only generically.
- **`opa-abac-core` stays Spring-free** — the OPA client uses the JDK `HttpClient`; no Spring/JPA/Feign
  imports leak into core.
- **Fail-closed everywhere** — any OPA error/timeout/ambiguity denies; `allow()` returns `false`, never
  throws for OPA failures; deny never becomes allow.
- **The app trusts the gateway for the JWT signature** — do structural + `exp` checks only; do **not**
  add `oauth2-resource-server` or re-verify the signature (a `verifySignature` mode is reserved, not
  built).
- **The starter does NOT register a `SecurityFilterChain`** — it exposes beans; the example app declares
  its chain and installs `AbacFilter`. Every bean is `@ConditionalOnMissingBean`.
- **`ddl-auto: validate` must still pass** — this slice adds **no** schema change; a clean boot is the
  proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. Report at checkpoints; the
  maintainer pushes.

---

## Operator notes (not part of the prompt)

- **Checkpoint-gated per ticket.** The architecture-review step (5) is the deliberate addition — it
  makes the agent self-review (fail-closed, module boundaries, SPI pluggability, SOLID) and apply real
  (not ritual) refactoring *before* the heavier IT/policy/e2e validation, then re-test. Eyeball each
  `STATUS-0N.md` for what it refactored.
- **T1 + T2 are the standalone library foundation** and are independent (land either first). If only a
  short window is available, landing 1 + 2 + 3 delivers the reusable spine; 4–7 layer it onto the
  example.
- **The role-definition backbone is the headline.** The whole point is that decisions are driven by
  `input.role_definition.permissions`, with the `RoleDefinitionSupplier` SPI isolating the *source* of
  role definitions — a static demo supplier now, an HTTP one (the user-service) in Phase 4. Check that
  the OPA input actually carries `role_definition` (ticket 3's ArgumentCaptor test) and that the rego
  reads it.
- **The `demo` user holds BOTH roles**, so it can't show viewer-denied-on-write. Ticket 6 adds a
  viewer-only and an editor user — without it the e2e matrix can't prove the deny path.
- **E2E needs the full rig and in-network tokens.** APISIX validates the issuer as `keycloak:8888`, so a
  host-minted token is rejected at the gateway. That's why `run-tests.sh` mints both tokens from inside
  the compose network — a property of the rig, not a bug.
- **Signature trust is a documented tradeoff**, safe only behind a validating gateway. The
  `ABAC-AUTHORIZATION.md` guide must state this loudly, with the reserved `verifySignature` switch as the
  gateway-less escape hatch.
- **CI does not run the rig yet**, so the newman suite is a local/manual gate for now. Wiring an e2e job
  into `.github/workflows/ci.yml` is a sensible follow-up, tracked separately.
- **Workflow-as-artifact:** keep this prompt verbatim and let the `STATUS-0N.md` notes record each
  ticket's outcome — this folder is a studied case study of the plan→autonomous-implement→test→review
  workflow, moved to `docs/to-do/implemented/` on ship alongside `DOMAIN-MODEL-FOUNDATION`.
