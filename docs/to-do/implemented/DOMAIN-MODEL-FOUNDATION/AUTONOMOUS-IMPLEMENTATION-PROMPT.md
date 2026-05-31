---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/abac
  - area/spring
---

# Domain-model foundation — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing the domain-model foundation
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each
> ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/domain-model-foundation`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then
> paste the **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **domain-model foundation** on branch
`feature/void3110/domain-model-foundation`.

**The problem.** The catalog example's entities have no shared base — no optimistic `@Version`, no
audit fields, no tags, no service layer, and no concurrency control on writes. Phase 3 cannot layer
real ABAC onto that. You are building the foundation: a reusable base entity, a *secure* (taggable +
authorizable) base entity, a JSONB tag value object, and a generic CRUD service with explicit
pessimistic locking — in the `opa-abac-spring-data` library — then adopting them in the catalog app
and proving the concurrency story.

Implement the core work directly. Do not delegate the implementation to a sub-agent.

### Read before you start (in order)

1. `DOMAIN-MODEL-FOUNDATION.md` (this folder's index) — what this slice delivers, the file glossary,
   the ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the design: the base/secure split, `ResourceTags` + the JSONB mapping choice,
   `LockableJpaRepository` + `AbstractCrudService` + `mutate`, the plain-`UUID` id trim and its
   tradeoff, the example-adoption + Liquibase plan, and considered-&-rejected.
3. `01-DECOMPOSITION.md` — the five tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch. **This is your work list.**
4. `10-QA-TEST-CASES.md` — the unit / integration / e2e cases your work must satisfy.
5. **Pattern guides — you will be checked against these** in the review gate (step 5):
   `docs/architecture/DOMAIN-MODEL.md` and `docs/guides/CONCURRENCY-AND-LOCKING.md`. The e2e details
   are in `docs/guides/E2E-TESTING.md`.
6. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit
   identity** rule (`Void3110 <void31102025@gmail.com>`).
7. `infra/README.md` — the local rig (needed for ticket 5's e2e), including the in-network token
   caveat.
8. **Prime Mulch:** `ml prime opa-abac` (the project's expertise store) and skim any
   library-design / base-stack records.

### Per-ticket loop (tickets 1 → 5, IN ORDER)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>` for the specific module
   or class, and re-read that ticket's section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.data.{model,repository,service}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` for the app), mappings, and the Liquibase changeset with
   its master-changelog include. Match the surrounding code's naming and idioms. **Clean-room:** no
   proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U*/I* cases from `10-QA-TEST-CASES.md`).
   For the concurrency IT (ticket 4) use a latch/barrier to force thread overlap — never `sleep`.
   Integration tests run against **real Postgres via Testcontainers** (never H2 for the example).

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for
   the example tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit
   tests pass, do NOT advance yet. Run a focused self-review against the pattern guides, then apply
   refactoring and re-test:
   - **Domain-model check** (vs `docs/architecture/DOMAIN-MODEL.md`): tags live on the *secure* base
     only, never the plain base; a secured entity implements `AbacDataObject` cleanly;
     `opa-abac-core` stays Spring-free; the JSONB mapping is the converter + `@JdbcTypeCode` approach;
     timestamps are `OffsetDateTime`/`timestamptz`; ids are plain `UUID`.
   - **Concurrency check** (vs `docs/guides/CONCURRENCY-AND-LOCKING.md`): `getById` (unlocked) and
     `getByIdForUpdate` (pessimistic) are both exposed explicitly; `mutate(id, fn)` is the
     atomic lock+mutate+save path; the `instanceof LockableJpaRepository` guard fails loudly, not
     silently; no external/slow call sits inside the locked transaction in the example usage.
   - **SOLID / decomposition:** is `AbstractCrudService` cohesive (SRP) and reused rather than
     copy-pasted (DRY)? Is the lock behavior behind the one repository seam (DIP)? Anything that
     should be split or simplified?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it
     found nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - Tickets 3–4: `./gradlew build` (runs the ITs incl. the `ddl-auto: validate` boot and, for
     ticket 4, `ProductConcurrencyIT`). Fix-until-green.
   - Ticket 5: bring the rig up (`ENABLE_OIDC=1 ./deploy.sh up --pods 2`), then
     `cd scripts/postman && ./run-tests.sh`. Honor the **in-network token caveat** (mint the Keycloak
     token from inside the compose network; see `docs/guides/E2E-TESTING.md`). Fix-until-green.

7. **Update documentation (after each ticket).**
   - This folder: tick the ticket in the `DOMAIN-MODEL-FOUNDATION.md` status table; record real
     values/decisions in the `STATUS-0N.md`.
   - For the ticket that finalizes a pattern guide topic, reconcile
     `docs/architecture/DOMAIN-MODEL.md` / `docs/guides/CONCURRENCY-AND-LOCKING.md` with what shipped.
   - Root/project `CLAUDE.md` only if a new build/run step matters for manual testing.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. Skip
   only if nothing is non-obvious. Verify the sync commit touches `.mulch/` only.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(domain-model): <ticket summary>`. A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e
    summary, **summarize the review findings + the refactoring you applied** (step 5), list docs
    updated, and note any open question you resolved. Then proceed to the next ticket. **Do not batch
    tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example code, Liquibase, tests, docs in this folder + the two pattern
  guides, the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1 …`);
  reset fixtures; rebuild images; drop/recreate the **local** catalog schema if a migration/checksum
  reset is needed (local Postgres only).
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what the review
  found each ticket.
- **Fix-until-green within the ticket** for compile/test/IT/e2e/config issues. Only STOP mid-ticket
  if genuinely *blocked*: the same root cause survives ≥3 focused attempts, OR a design decision the
  docs don't cover is needed, OR a local prerequisite is unrecoverable.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
  Reference the prior platform only generically.
- **`opa-abac-core` stays Spring-free** — no JPA/Spring imports leak into it.
- **Tags live on the secure base only**; **ids stay plain `UUID`** (no typed-ID value object); **no
  `@AutoTag` machinery** in this slice.
- **`ddl-auto: validate` must pass** — the migrated schema and the entity mappings must agree; a
  green boot is the proof.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. Report at checkpoints; the
  maintainer pushes.

---

## Operator notes (not part of the prompt)

- **Checkpoint-gated per ticket.** The architecture-review step (5) is the deliberate addition — it
  makes the agent self-review against the two pattern guides and apply real (not ritual) refactoring
  *before* the heavier IT/e2e validation, then re-test. Eyeball each `STATUS-0N.md` for what it
  refactored.
- **Tickets 1 + 2 are the standalone library foundation.** If only a short window is available,
  landing 1 + 2 already delivers reusable value (the base stack + the service), with the example
  adoption (3–5) following later.
- **The concurrency proof (ticket 4) is the headline.** A passing `ProductConcurrencyIT` that
  serializes two writers with no stale-version escape is the thing that justifies the whole locking
  design — make sure it's deterministic (latch-forced overlap), not flaky.
- **E2E needs the full rig and an in-network token.** APISIX validates the issuer as `keycloak:8888`,
  so a host-minted token is rejected at the gateway. That's why `run-tests.sh` documents minting the
  token from inside the compose network — not a bug, a property of the rig.
- **CI does not run the rig yet**, so the newman suite is a local/manual gate for now. Wiring an
  e2e job into `.github/workflows/ci.yml` (compose up → newman) is a sensible follow-up, tracked
  separately.
