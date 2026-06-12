---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# Resource resolution (attribute-rich pre-authorization) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Phase 5.97 (resource resolution at the
> `@OpaPreAuthorize` gate) autonomously, ticket by ticket, with an architecture-review gate and a
> checkpoint after each ticket. The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/resource-resolution`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste
> the **PROMPT** section below to the agent.

---

## PROMPT

You are implementing **resource resolution — attribute-rich pre-authorization** (Phase 5.97) on branch
`feature/void3110/resource-resolution`.

**The problem.** The `@OpaPreAuthorize` gate is reference-based: it names a resource by `(type, id)`
with **empty attributes** and resolves the caller's role on the **leaf** id — so tag rules are
undecidable at the gate (the example's `CategoryAuthorizer` exists solely to re-check after load), and
under the HTTP role source an id'd member decision finds no role at the leaf and falls through to the
policy's **realm-role fallback, tag-blind** (a member's realm role leaks writes their team role's tags
deny). You are adding the **opt-in resolution mechanism pinned in ADR 0013**: an app-implemented
`AbacResourceResolver` SPI resolves the *instance* behind a declared `resourceId`; the gate decides on
its real attributes + ancestors with the role resolved **once on the governing root**; a request-scoped
write-through cache binds the handler to the authorized snapshot; mutations guard the snapshot's
`@Version` → `409 STATE_CONFLICT`. The headline: the team/tag model finally governs id'd writes (story
C4 — the fallback hole closes), and the gate-then-handler double load disappears. **Scope boundary:**
the single-resource gate path only — `AbacQueryService`, all four `findAuthorized` list paths,
pagination, and list-path cache population are explicitly NOT in this slice (Phase 6 consumes the
cache); type-level checks (lists, creates) keep today's semantics pending the Phase-6.5 action
vocabulary; user-mgmt registers nothing (the live opt-in coexistence proof).

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every e2e cell that pins a 404 for a missing id") and
for **log-noisy validation** (e.g. run the newman matrices and report back only the failure summary) —
their findings come back to you; the code, tests, and docs are written in this loop.

### Read before you start (in order)

1. `RESOURCE-RESOLUTION.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the mechanism (§2), the **behavior matrix** (§3 — the cells that change and the
   ones that must not), the catalog adoption (§4), what this slice does NOT change (§5), the proof
   obligations (§6), and the forks already closed (§7 — do not reopen them).
3. `01-DECOMPOSITION.md` — the seven tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, **plus the two decomposition-pinned semantics** (missing-id `403`; the
   `tags_satisfied` conjunct). **This is your work list.**
4. **The pinned decisions** — ADR `docs/architecture/adr/0013-attribute-rich-pre-authorization.md`
   (every fork, with rejections — split SPI, split failure semantics, governing-root role, the cache,
   version binding, the kill-switch). Skim ADR `0006` (the layer model whose 2/3 boundary this
   redraws), `0008` (the ancestor model the gate now consumes), `0011` (the problem+json contract
   `STATE_CONFLICT` lands on).
5. `10-QA-TEST-CASES.md` — the U/I/P/E/D cases your work must satisfy, the pinned-contract table, the
   **retro-audit baseline cells (B1–B3)**, and the fail-closed checklist.
6. **Context you will be checked against** in the review gate (step 5): the shipped
   [[HIERARCHY-SINGLE-RESOURCE]] (`HierarchicalAuthorizer` — the governing-root rule the manager must
   mirror *exactly*; the SPI + conditional starter wiring shape), [[TAG-DICTIONARY]] (`category.rego`'s
   tag block — the **template** T5 ports, not redesigns), [[REST-API-REFINEMENT]] (the
   `LibraryErrorCode`/problem+json advice idiom), [[DATA-FILTERING]] (the additive-overload
   discipline), `docs/guides/CONCURRENCY-AND-LOCKING.md` Rules 1–2 (decide under the protection you
   act under), and `docs/code-review/RETRO-AUDIT-2026-06-12.md` (the four fold-ins this slice
   carries). E2E details: `docs/guides/E2E-TESTING.md`.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha; `./deploy.sh build` to force new app code
   into pods.
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "resource resolution gate attribute"` (records
   `mx-76e16f` per-instance load-then-check — the pattern this slice replaces, `mx-b78fca`
   AncestorResolver SPI, `mx-9dbdc1` opt-in starter wiring, `mx-df5475` fail-closed two shapes,
   `mx-666644` ANY_OF/ALL_OF tag match, `mx-360261` RoleDefinitionSupplier SPI are directly relevant).

### Per-ticket loop (tickets T1 → T7, IN ORDER; T5 may land any time after T1 but before T6)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.opaabac.{core,security,autoconfigure}` for the library;
   `dev.dmitriikonovalov.example.catalog.*` for the app), mappings, rego clauses. Match the
   surrounding code's naming and idioms. **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant U/I/P/E cases from `10-QA-TEST-CASES.md`).
   Manager tests follow the existing Mockito `OpaPreAuthorizeAuthorizationManagerTest` pattern; the
   catalog IT runs against **real Postgres via Testcontainers** (never H2) with the programmable
   context-aware `OpaClient` stub; policies use `opa test`.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets). Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: instance resolution
     that fails or returns empty is a DENY with no OPA call (there must be no branch that builds an
     attribute-less context while resolution is active — that skips attribute-keyed deny rules, i.e.
     widens); an ancestor failure collapses to the empty chain (direct-only — never strips a direct
     grant, never a partial chain); no resolver bean / kill-switch off is byte-identical baseline;
     the two semantics are never confused in either direction.**
   - **Security check — name the widening that would matter for this ticket and state why it cannot
     happen: the cache serving an authz artifact across requests or subjects (it is request-bounded
     and never read by decisions); the resolver absorbing routing semantics (the URL-scope rule stays
     in the handler — a resolver that filters by path scope would turn 404s into grants elsewhere);
     a deny that still caches; the missing-id posture (403, pinned — never an existence oracle via
     mixed 403/404 on the same annotated path); no internal detail in problem bodies.**
   - **Concurrency / idempotency check — every decision that gates a mutation is computed under the
     same lock or version guard that holds through the commit (`CONCURRENCY-AND-LOCKING.md` Rules 1–2
     — code that locks first but acts on a pre-lock decision is the defect); the gate snapshot is
     version-guarded in the mutating transaction **before any write** and the snapshot is never
     persisted; a 409-retried request converges (the retry's gate decides on the new state); the
     audited `reparentCategory` lock ordering is not reordered or weakened.**
   - **Wiring check** — every seam this ticket adds (`AbacResourceResolver`, `AncestorChainSupplier`,
     `ResourceResolutionSupport`, `AbacResourceCache`, `VersionGuard`, `VersionConflictException` +
     its advice mapping, `PersistenceConflictProblemAdvice`, the kill-switch property, the rego
     conjunct) has a **named consumer** and a test through its **non-happy path**; zero call sites =
     the ticket is not done.
   - **Boundary / additivity check — `opa-abac-core` stays Spring-free (prove with the import set);
     `opa-abac-spring-security` gains no dependency on `opa-abac-spring-data`; the manager change is
     a constructor overload (existing ctors byte-compatible); `BaseModel extends Versioned` is the
     lone spring-data line; name the byte-for-byte-unchanged surfaces (`AbacQueryService`, the four
     `findAuthorized` paths, pagination, `CategoryListAuthorizer`, every annotation except
     `getCategory`'s gain, the OpenAPI specs, user-mgmt end-to-end) and the one mechanical cost (any
     widened test stub, landed in the same commit).**
   - **Module-layer separation — the SPI contracts in core; the manager flow + cache in
     spring-security; the composition + kill-switch + dao advice in the starter; the resolver bean +
     guards in the example. No layer reaches across (the manager sees only core types — that is why
     `AncestorChainSupplier` exists).**
   - **Pattern-reuse check — the governing-root rule mirrors `HierarchicalAuthorizer` verbatim; the
     starter conditionals mirror `HierarchyAutoConfiguration`; the advice mirrors the ADR-0011
     idiom; T5 ports `category.rego`'s block structure — no novel policy design.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T4: `./gradlew build` (all modules + the catalog ITs against real Postgres) — `ResourceResolutionGateIT`
     incl. the deterministic 409 race (I4), the gate-decided tag cells (I1/I2), the missing-id contrast
     (I6); the CRUD ITs stay green on the resolution-off profile. Fix-until-green.
   - T5: `opa test infra/opa/policies/` — every pre-existing case green unmodified + P1–P5. Fix-until-green.
   - T6: bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh images), then `cd scripts/postman &&
     ./run-resource-resolution-matrix.sh` **and every existing `run-*.sh` matrix**. Honor the
     in-network token caveat and **restart OPA after the T5 rego edits**. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `RESOURCE-RESOLUTION.md`
   status table; record real values/decisions in `STATUS-0N.md` (the exact serialized context the
   byte-identical test pinned, the 409 body, every e2e cell that flipped `404→403`). The ticket that
   finalizes the guide topic writes `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md` and reconciles
   [[TAG-BASED-AUTHORIZATION]] / [[HIERARCHICAL-AUTHORIZATION]] / [[ABAC-AUTHORIZATION]] /
   `infra/README.md` / [[E2E-TESTING]] (T7). Root/project `CLAUDE.md` only if a new build/run step matters.

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(resource-resolution): <ticket summary>` (or a narrower `feat(core)` / `feat(spring-security)` /
   `feat(starter)` / `feat(example)` / `feat(opa)` / `test(e2e)` / `docs(…)` scope). A
   `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary,
    **summarize the review findings + the refactoring you applied** (step 5), list docs updated, and
    note any open question you resolved. Then proceed to the next ticket. **Do not batch tickets
    without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify library code, example-catalog code, rego, tests, docs in this folder + the guides, the
  `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; restart OPA; drop/recreate the **local**
  schema if needed.
- Use `/rego-skill` to author + `opa test` the T5 conjunct cells.
- Fix any issue your own validation reveals (compile, unit, IT, policy, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Fail-closed is the load-bearing invariant — no path returns a wider decision on an error than on
  success:** instance resolution empty/throws → DENY (never an attribute-less context); ancestor
  failure → the empty chain, direct-only (never strips a direct grant); no resolver bean / kill-switch
  off → byte-identical baseline; version drift → 409, never a silent overwrite; the cache is never an
  input to a decision.
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/policy/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Slice-specific invariants — never trade these away:**
  - The two failure semantics stay split and are never confused (instance → deny; ancestors → collapse).
  - The role is resolved **once, on the governing root** — `HierarchicalAuthorizer`'s rule, verbatim.
  - The gate never reads the cache; the cache is populated only on allow; mutations load fresh and
    **guard** — the snapshot is never persisted.
  - `AbacQueryService`, the four `findAuthorized` paths, pagination, and `CategoryListAuthorizer` stay
    byte-identical; list-path cache population is Phase 6's, not yours.
  - user-mgmt is **zero bytes** of diff — it is the slice's opt-in coexistence proof.
  - Only `getCategory` gains an annotation; every other `@OpaPreAuthorize` (and deliberate absence) is
    byte-identical; the URL-scope rule stays in the handler.
  - The mechanism needs **zero Rego**; the only policy diff is T5's `tags_satisfied` conjunct,
    mirroring `category.rego` — a pure narrowing with vacuous back-compat.
  - The two decomposition-pinned semantics hold: missing id behind an annotated `resourceId` → `403`;
    wrong-scope-but-existing → the handler's `404`.
- **`opa-abac-core` stays Spring-free**; `opa-abac-spring-security` gains no dependency on
  `opa-abac-spring-data`.
- **No schema change** — the JPA `@Version` column already exists; a clean `ddl-auto: validate` boot is
  the proof. No OpenAPI shape change.

---

## Operator notes (not part of the prompt)

- **The headline tickets are T4 + T6.** The IT where a tag-mismatched write dies **at the gate** with
  the handler never running (I2) and the e2e flip pair (E1/E2 — the C4 story: the viewer-realm member
  gains the tag-matched write, the editor-realm member loses the tag-mismatched one) justify the whole
  design. Make the e2e assert the **decision flip**, not just a status code.
- **The fail-closed edge to eyeball:** the attribute-less fallback. The single most dangerous bug shape
  is a resolver failure that quietly degrades to today's empty-attributes context — that *passes* every
  pre-5.97 test and silently skips attribute-keyed deny rules. Every `STATUS-0N.md` for T2–T4 must
  state explicitly that no such branch exists. Second edge: the two failure semantics swapping
  (ancestor failure denying = stripping direct grants; instance failure collapsing = the widening above).
- **Standalone-value subset:** T1 + T2 + T3 land the complete, dormant library mechanism (SPI + manager
  flow + cache + kill-switch + 409 plumbing) even before the catalog adopts (T4) — opt-in, zero
  behavior change for any app without a resolver bean.
- **Rig / e2e specifics:** mint tokens **in-network** (APISIX validates issuer `keycloak:8888`);
  **restart OPA after the T5 rego edits** (`--watch` is unreliable); `./deploy.sh build` to force new
  app images; the new matrix seeds through the user-service bootstrap like `run-tag-matrix.sh`; expect
  a handful of existing cells pinning `404` for missing ids on annotated endpoints to legitimately flip
  to `403` — each one is listed in STATUS, none is "fixed" by weakening the resolver.
- **CI does not run the rig yet** — the newman matrices are a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T7).
