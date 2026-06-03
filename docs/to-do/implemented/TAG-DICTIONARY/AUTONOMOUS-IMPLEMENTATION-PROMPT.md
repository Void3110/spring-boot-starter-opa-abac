---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
---

# Dynamic tag dictionary — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Phase 4.5 (the dynamic tag dictionary +
> tag-based grants) autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after
> each ticket. The design and work list live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/tag-dictionary` off a clean
> `main` (the planning package itself is committed on this branch already; rebase/branch as the maintainer
> directs). Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the **PROMPT**
> section below to the agent.

---

## PROMPT

You are implementing the **dynamic tag dictionary** (Phase 4.5 — runtime tag definitions + tag-based
grants) on branch `feature/void3110/tag-dictionary`.

**The problem.** Authorization is role-definition-driven and app-resolved (Phase 4), but a decision has no
**controlled vocabulary** for resource tags and no way for a **role to require tags**. You are adding the
attribute half in three separable layers: a **dynamic tag dictionary** (a runtime entity, global *and*
team-scoped — the source platform hardcodes tag keys; we do it properly), **tag assignment** to a
sub-resource (validated against the dictionary), and **tag-based grants** where a role carries
`requiredTags` + a `matchMode` and OPA grants when the resource's tags satisfy it — **the match evaluated
in Rego** (`some in` for ANY_OF, `every` for ALL_OF).

Scope is **dictionary + management + assignment + role `requiredTags` + the Rego match + an e2e matrix** —
NOT `@AutoTag` auto-population (deferred), NOT partial-eval list filtering (Phase 5), NOT ReBAC-in-Rego
(Phase 7).

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `TAG-DICTIONARY.md` (this folder's index) — purpose, the three layers, who-manages-what, where each
   layer lands, the ticket status table.
2. `00-DESIGN.md` — the design: the `TagDefinition` entity (global+team scope, value-type/cardinality/
   allowed-values), the assignment + validation path, the additive `RoleDefinition.requiredTags`/`matchMode`,
   the OPA-input shape, the Rego `some in`/`every` match, who-manages-what, considered-&-rejected.
3. `01-DECOMPOSITION.md` — the six tickets, each with Goal / Deliverables / Acceptance / What-NOT-to-touch.
   **This is your work list.**
4. `10-QA-TEST-CASES.md` — the unit / integration / policy / e2e cases your work must satisfy.
5. **The patterns you build against:**
   - The shipped [[USER-MANAGEMENT-SERVICE]] slice (`docs/to-do/implemented/USER-MANAGEMENT-SERVICE/`) and
     the `example-user-management-service` code — copy its idioms: the `RoleDefinitionEntity` JSONB +
     partial-unique-index pattern (the **direct template** for `TagDefinition`), the `service/` layer, the
     hand-written `UserMgmtMapper`, the dogfooded `@OpaPreAuthorize` + `team.rego`, the
     `/internal/effective-role` resolve endpoint + `TeamTargetMatcher` (the template for
     `/internal/tag-definitions`), the `HttpRoleDefinitionSupplierTest` `HttpServer`-stub style.
   - The catalog app (`example-catalog-management-service`) — the Category entity + `ResourceTags`, the
     per-type `.rego`, the `@OpaPreAuthorize(category:write)` you reuse for assignment authorization.
   - `opa-abac-core`'s `RoleDefinition` + `AbacContext` (the additive change lands here);
     `docs/architecture/DOMAIN-MODEL.md` for `ResourceTags` (scalar + array JSONB).
6. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only; the prior platform's `tag/`
   package is **study-only** — generalize the mechanics, never copy names/source) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
7. `infra/README.md` + `deploy.sh` — the local rig (both services), needed for ticket 6 (the e2e matrix),
   including the in-network token caveat.
8. **Prime Mulch:** `ml prime opa-abac`. Note especially: the team/role-def core with the JSONB +
   partial-unique role codes (`mx-40324e` — your `TagDefinition` template); the app-resolved resolver +
   `TeamTargetMatcher` (`mx-ef42c9`); fail-closed (`mx-926c85`); the verb-prefix gotcha (use a `team:`
   prefix so `team:define-tags` splits to a clean token); chained-ids in collection scope (`mx-ecc3ef`) +
   the dual-token matrix (`mx-05b2c1`); and the `ml sync` swept-staged-files trap (`mx-d8a173`) — before
   each `ml sync`, `git restore --staged .` so the commit touches `.mulch/` only.

### Per-ticket loop (tickets 1 → 6, IN ORDER)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the module/class, and
   re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact entities, packages
   (`dev.dmitriikonovalov.example.usermgmt.*` / `…example.catalog.*` / `dev.dmitriikonovalov.opaabac.core`
   for the additive `RoleDefinition` change), the Liquibase changelogs, the rego, the infra edits. Match
   the existing apps' naming and idioms. **Clean-room:** no proprietary names anywhere (no
   `TagDictionary`/`@AutoTag`/`AutoTagProcessor`/`ResourceTag`/source package names — use original neutral
   names like `TagDefinition`, `TagValueValidator`, `TagDefinitionClient`).

3. **Write/extend the tests** for the ticket (the relevant cases from `10-QA-TEST-CASES.md`). ITs run
   against **real Postgres via Testcontainers** (never H2), like both apps. Use an in-process
   `com.sun.net.httpserver.HttpServer` stub for the catalog's `TagDefinitionClient` (no WireMock, mirroring
   `HttpRoleDefinitionSupplierTest`); use `opa test` for policies.

4. **Compile + run tests until green.** `./gradlew :example-user-management-service:test` /
   `:example-catalog-management-service:test` / `:opa-abac-core:test` as the ticket touches, and
   `./gradlew build` for the build-wide tickets (esp. T4 — proving additivity repo-wide). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once tests pass, do
   NOT advance yet. Run a focused self-review, then apply refactoring and re-test:
   - **Additivity / boundary check (esp. T4):** the `RoleDefinition` change is **purely additive** — every
     pre-existing test passes unchanged and a role with no `requiredTags` serializes byte-for-byte as
     before. `opa-abac-spring-security`/`-spring-data`/`-starter` public APIs are **unchanged**. If you find
     yourself needing a non-additive library change, STOP and report.
   - **Fail-closed check:** an unknown/illegal assigned tag → 422 (never silently stored); a
     definitions-fetch failure → the write is **rejected** (not persisted untagged); a malformed
     `required_tags` → `tags_satisfied` fails → deny; OPA `default allow := false`. Deny never silently
     becomes allow.
   - **The three-layer separation:** definition (governance) · assignment (a write) · requirement
     (authorization) stay distinct; the dictionary constrains *legality*, the existing write authorization
     governs *who assigns*, the role+rego govern *the grant*. Don't collapse them.
   - **Pattern-reuse check:** `TagDefinition` reuses the role-def **partial-unique-index** + JSONB pattern
     (not a new bespoke scheme); the `/internal/tag-definitions` endpoint reuses `TeamTargetMatcher`; the
     `TagDefinitionClient` mirrors the `HttpRoleDefinitionSupplier` fail-closed shape; `team:define-tags`
     shares the `team:` verb prefix. Shared `TagValueValidator`, not duplicated validation.
   - **Apply** the refactoring the review surfaces, then **re-run the tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If nothing
     substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - Tickets 1–4: Testcontainers ITs (the schema/seed + partial-unique indexes, the validator, the
     dogfooded define authorization, assignment validation, the additive role-def round-trip) + `opa test`
     for the updated `team.rego` (T2). Ticket 5: `opa test` for `category.rego` (all ANY_OF/ALL_OF/vacuous/
     fail-closed cases) + a manual OPA `curl` probe. Fix-until-green.
   - Ticket 6: bring the rig up with **both** services (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     …`), seed demo tag definitions + a tag-gated role + two differently-tagged Categories, then run the
     newman matrix (`cd scripts/postman && ./run-*.sh`). Honor the in-network token caveat. Fix-until-green.

7. **Update documentation (after each ticket).**
   - This folder: tick the ticket in the `TAG-DICTIONARY.md` status table; record real values/decisions in
     the `STATUS-0N.md`.
   - Ticket 6 authors `docs/guides/TAG-BASED-AUTHORIZATION.md` and reconciles `docs/TAG-SYSTEM.md` +
     `infra/README.md` + `docs/guides/E2E-TESTING.md` + the roadmap; moves the folder to `implemented/`
     with a Shipped banner.
   - Root/project `CLAUDE.md` only if a new build/run step matters for manual testing.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable insight
   (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before `ml sync`,
   `git restore --staged .`** so the commit touches `.mulch/` only (the swept-staged trap, `mx-d8a173`).
   Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note together).
   Identity `Void3110 <void31102025@gmail.com>`. Conventional subject — `feat(user-mgmt): …` for the
   dictionary/management, `feat(example): …` for the catalog assignment, `feat(core): …` for the additive
   `RoleDefinition` change, `feat(opa): …`/`test(opa): …` for the rego, `chore(infra): …` for the rig,
   `test(e2e): …` for the matrix, `docs(...): …` for doc-only. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the test + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and note
    any open question you resolved. Then proceed to the next ticket. **Do not batch tickets without a
    checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the `example-user-management-service` (the `TagDefinition` domain + dictionary management +
  the internal read endpoint + the role-def `requiredTags` passthrough), the catalog app's tag assignment +
  `TagDefinitionClient` + `category.rego`, the **additive** `opa-abac-core` `RoleDefinition` change, the
  rego policies, tests, docs in this folder + the new guide, the `scripts/postman/` suite, `infra/` (compose,
  `deploy.sh`, seed), and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./deploy.sh`, `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 …`);
  reset fixtures; rebuild images; reload OPA policies; seed tag definitions / roles / tagged Categories —
  local environment only.
- Use `/rego-skill` to author + `opa test` the `team:define-tags` rule and the `category.rego`
  `tags_satisfied` match.
- Fix any issue your own validation reveals (compile, unit, IT, policy, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation.** Document what the review found each ticket.
- **Fix-until-green within the ticket** for compile/test/IT/policy/e2e/config issues. Only STOP mid-ticket
  if genuinely *blocked*: the same root cause survives ≥3 focused attempts, OR a design decision the docs
  don't cover is needed, OR a local prerequisite is unrecoverable.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source
  (no `TagDictionary`/`@AutoTag`/`AutoTagProcessor`/`ResourceTag`/`TagMapper`/source package or ticket
  names). Reference the prior platform only generically. Use original neutral names.
- **The ONLY library change is the additive `RoleDefinition.requiredTags`/`matchMode`** (T4) — optional,
  backward-compatible, defaulted, `@JsonInclude` so absent ⇒ old wire shape. **Do NOT** change
  `opa-abac-spring-security`/`-spring-data`/`-starter` public APIs, and do NOT make a non-additive core
  change. If you think you need one, STOP and report.
- **Fail-closed everywhere** — illegal assigned tag → 422 (never stored); definitions-fetch failure →
  reject the write; malformed `required_tags` → `tags_satisfied` deny; OPA default-denies. An absent
  `required_tags` is vacuously satisfied (back-compat), but a *malformed* one denies.
- **The three layers stay separate** — definition (governance, owner/admin via `team:define-tags`) ·
  assignment (a normal `write`, the dictionary only constrains legality) · requirement (the role +
  the Rego match). Do not add a new capability for assignment; do not match in Java.
- **Match in Rego** — `some in` (ANY_OF) / `every` (ALL_OF). The any-of/all-of decision lives in the policy,
  not in Java. This is deliberate (the OPA-native expression; the bridge to Phase 7).
- **Reuse the shipped patterns** — `TagDefinition` reuses the role-def partial-unique-index + JSONB scheme;
  the internal read endpoint reuses `TeamTargetMatcher`; the `TagDefinitionClient` mirrors
  `HttpRoleDefinitionSupplier`'s fail-closed shape; `team:define-tags` shares the `team:` prefix. Don't
  reinvent.
- **`ddl-auto: validate` must pass** — Liquibase owns the new `tag_definition` table + the role-def
  `required_tags` column; a clean boot is the proof.
- **Mulch sync touches `.mulch/` only** — `git restore --staged .` before `ml sync` (`mx-d8a173`).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. Report at checkpoints; the
  maintainer pushes.

---

## Operator notes (not part of the prompt)

- **Checkpoint-gated per ticket.** The architecture-review step (5) is the deliberate addition — it makes
  the agent self-review (additivity, fail-closed, the three-layer separation, pattern reuse) and apply real
  (not ritual) refactoring *before* the heavier IT/e2e validation, then re-test. Eyeball each
  `STATUS-0N.md` for what it refactored.
- **The dictionary being a runtime entity is the headline.** The whole "done properly" thesis is that tag
  keys are **rows, not constants** — global AND team-scoped, editable at runtime. Check the team-scoped
  define path actually works end to end (define a key → assign it → a role requires it → a decision flips).
- **The decisive demo is two Categories, one role, different tags → one allowed, one denied.** That is the
  single clearest proof that *tags* (not just `permissions`) drive the decision, matched **in Rego**. Make
  sure the e2e matrix contains exactly that contrast.
- **Match in Rego, not Java (and ReBAC is still Phase 7).** The any-of/all-of logic is the on-ramp to the
  Phase-7 in-policy join; keep it in the policy. Don't drift into pushing the team graph into OPA `data` —
  that's Phase 7.
- **T4 is the only place the library changes.** It must be provably additive (whole-repo `./gradlew build`
  green with every old test unchanged). If the change tempts a non-additive edit, that's a signal to STOP.
- **Workflow-as-artifact:** keep this prompt verbatim and let the `STATUS-0N.md` notes record each ticket's
  outcome — this folder is a studied case study of the plan→autonomous-implement→test→review workflow,
  moved to `docs/to-do/implemented/` on ship alongside `DOMAIN-MODEL-FOUNDATION`, `LIBRARY-SPINE`, and
  `USER-MANAGEMENT-SERVICE`.
