---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# Control-plane vocabulary categorization (Phase 6.7) — autonomous implementation prompt

> **What this is.** A self-contained prompt for implementing Phase 6.7 (extending the coarse-category
> permission model to the `team:*` control plane and closing the `define-tags` enforcement deferral)
> autonomously, ticket by ticket, with an architecture-review gate and a checkpoint after each ticket.
> The design and work list it refers to live alongside it in this folder.
>
> **Before you run it:** create the branch — `git checkout -b feature/void3110/control-plane-vocabulary`
> off a clean `main`. Confirm `git config --local user.email` is `void31102025@gmail.com`. Then paste the
> **PROMPT** section below to the agent.

---

## PROMPT

You are implementing the **control-plane vocabulary categorization** slice (Phase 6.7) on branch
`feature/void3110/control-plane-vocabulary`.

**The problem.** Phase 6.5 categorized the **catalog** plane — roles grant coarse categories
(`READ`/`WRITE`/`TAG`/`GRANT`) that *expand* through a shared `data.permission_categories` table via
`permissions.effective_actions`, refined by `denied_actions`. The **control plane** (the
user-management-service's `team:*` management verbs) never got that treatment: `team.rego` does a **raw
verb match** (`verb in permissions["team"]`), and the capability ladder is a **hardcoded Java map**
(`TeamRoleCapabilities`) of fine verbs. This is a second, divergent authorization idiom, and `define-tags`
enforcement is a one-off raw gate (the deferral ADR 0007's addendum carried here). You are folding the
control plane into the **one** shared vocabulary: add **one** new category — `CONTROL → [add-member,
change-role, remove-member]` (the coarse `manage` verb split so it is deny-refinable) — add `list-members`
to `READ`, make `team.rego` category-driven via the same `effective_actions` (symmetric with
`catalog.rego`) plus an **owner-only-by-code fence** for `define-roles`/`transfer-ownership`, recast
`TeamRoleCapabilities` to emit category tokens, and tighten role authoring so a custom role can no longer
smuggle control-plane power. The headline: the control plane uses the same teaching artifact as the
catalog, and `define-tags` enforcement is uniform with the model. **Scope boundary:** this is a
**vocabulary/categorization** change to the *verb* axis only. The **`MembershipService` escalation gates**
(cross-tier strict `<`, the senior subset-on-effective verdict, the target-tier gate, owner-protection)
are an **invariant — untouched** (the two-axis principle, 00-DESIGN §4); custom-role team *power* stays
disallowed ("ceiling ≠ capability"); `TAG` is **not** split (`define-tags` stays in it); there is **no DB
migration** (the team ladder is a Java projection); there is **no kill-switch**; and B2's tri-state
`RoleDefinitionSupplier` contract is a **non-goal — untouched**.

Implement the core work directly. Do not delegate the implementation to a sub-agent. Sub-agents are
welcome for **read-only scouting** (e.g. "find every `team:` action string and every `team_test.rego`
assertion across both bundles") and for **log-noisy validation** (e.g. run the newman matrices and report
back only the failure summary) — their findings come back to you; the code, tests, and docs are written in
this loop.

### Read before you start (in order)

1. `CONTROL-PLANE-VOCABULARY.md` (this folder's index) — what this slice delivers, the file glossary, the
   ticket status table, the critical path, conventions.
2. `00-DESIGN.md` — the problem (§1, incl. the current-state table + the two-bundle wiring), the decision
   per surface (§2), the **behavior matrix** (§3 — the cells that change and the ones that must not), the
   **two-axis principle** (§4 — what 6.7 touches vs the untouched escalation gates), the proof obligations
   (§5), the non-goals (§6), and the autonomous-run risk pins (§7).
3. `01-DECOMPOSITION.md` — the four tickets, each with Goal / Deliverables / Acceptance /
   What-NOT-to-touch, **plus the three pinned semantics** (`opa test` counts rise — the `READ`-expansion
   update is a known intended break; the `MembershipService` two-axis invariant; `TAG` is not split).
   **This is your work list.**
4. **The pinned decisions** — ADR `docs/architecture/adr/0015-control-plane-vocabulary-categorization.md`
   (every fork, with rejections — the one shared vocabulary, the `CONTROL` category, the owner-only-by-code
   fence, custom-roles-stay-management-incapable, `TAG` left intact, the bundle mirror). Skim ADR `0007`
   (the catalog category model + its addendum that deferred `define-tags` enforcement here), ADR `0014`
   (B2's tri-state supplier contract this slice must not reopen), ADR `0011` (the problem+json error
   contract — the custom-role rejection is the existing `422 ROLE_DEFINITION_INVALID`, nothing new).
5. `10-QA-TEST-CASES.md` — the R / U / I / E / D cases your work must satisfy, incl. the headline-proof
   cells and the byte-identical-bundle checks.
6. **Context you will be checked against** in the review gate (step 5): the shipped
   `docs/to-do/implemented/PERMISSION-CATEGORIES/` (the 6.5 category model this extends — `permissions.rego`,
   `permission_categories.json`, the `validateContract`/ceiling idiom in `RoleDefinitionService`, the
   `effective_actions` call in `catalog.rego` you mirror); the current `team.rego` (the raw-match idiom
   you replace) + `team_test.rego` (the cases you rewrite); `TeamRoleCapabilities` + `EffectiveRoleService`
   (the projection you recast); `MembershipService` (the escalation gates you must NOT touch);
   `docs/guides/TEAM-BASED-AUTHORIZATION.md` + `docs/guides/PERMISSION-MODEL.md` (the guides to reconcile);
   `infra/README.md` for the rig + the two-bundle `team.rego` note.
7. Root `CLAUDE.md` — the **IP boundary** (clean-room: original names only) and the **commit identity**
   rule (`Void3110 <void31102025@gmail.com>`).
8. `infra/README.md` — the local rig (needed for the e2e ticket), incl. the **in-network token caveat**
   and the **"restart OPA after editing a policy"** gotcha — this slice **edits `team.rego` + the
   expansion table**, so OPA **must** be restarted before the e2e matrices, and `./deploy.sh build` forces
   new app code into pods.
9. **Prime Mulch:** `ml prime opa-abac` and `ml search "control-plane CONTROL category team verbs
   define-tags two-axis escalation gate"` (the directly-relevant records: the **6.7 control-plane
   categorization decision** "CONTROL category + two-axis separation", the **resolve-side decision**
   "team management ladder stays a Java projection", the **OPA-wiring decision** "mirror permissions.rego
   into the service bundle", the 6.5 category-model patterns — the PE-inline idiom, the hybrid assignment
   gates, the dictionary-governance `team:define-tags` record).

### Per-ticket loop (tickets T1 → T4, IN ORDER; T2/T3 are independent once T1 lands; T4 last)

For each ticket do ALL of the following, in order, and **STOP at the checkpoint before the next**:

1. **Prime for the files you're about to touch.** `ml prime --files <path>`, and re-read that ticket's
   section in `01-DECOMPOSITION.md`.

2. **Build the deliverables** exactly as `01-DECOMPOSITION.md` lists them — exact classes, packages
   (`dev.dmitriikonovalov.example.usermgmt.{service,web}` for the app), rego rules, the two policy bundles
   (`infra/opa/policies/` + `example-user-management-service/src/main/resources/opa/policies/`). Match the
   surrounding code's naming and idioms (the catalog's `effective_actions` call, the 6.5
   `validateContract` shape). **Clean-room:** no proprietary names anywhere.

3. **Write/extend the tests** for the ticket (the relevant R/U/I/E cases from `10-QA-TEST-CASES.md`).
   Policy tests use `opa test` on **both** bundles; the projection/validation unit tests follow the
   existing `RoleDefinitionService` / `EffectiveRoleService` patterns; the headline IT runs against **real
   Postgres via Testcontainers** (never H2) through the dogfood OPA path.

4. **Compile + run unit tests until green.** `./gradlew :<module>:test` (and `./gradlew build` for the
   example/IT tickets); `opa test <bundle>` for the policy ticket. Fix-until-green.

5. **★ ARCHITECTURE REVIEW + REFACTOR — the gate before integration/e2e validation.** Once unit tests
   pass, do NOT advance yet. Run a focused self-review, then refactor and re-test:
   - **Fail-closed check — the slice's load-bearing invariant, stated concretely: every control-plane
     decision still default-denies; an unknown/stale/removed token expands to ∅ (the shared
     `effective_actions` floor) → deny; a role with no `permissions` denies; the owner-only fence grants
     `define-roles`/`transfer-ownership` ONLY for `code == "owner"` (no level, no category, no custom role
     can reach them); the loosening is EXACTLY `list-members` (a `READ`-holder gains the roster and nothing
     else — every mutation still denies for member/reader).**
   - **Security check — name the widening that would matter for this ticket and state why it cannot
     happen: the owner-only fence keyed on `role_level` instead of `code` (a custom level-40 role could
     then transfer/author — keyed on the reserved `owner` *code*, unspoofable); a custom role's stored
     `team` tokens reaching `managementRole` (they do not — custom projects `[READ]`; and `validateContract`
     now 422s storing them); `define-tags` leaking to `senior` (senior holds `CONTROL`, not `TAG` — pinned);
     `list-members` in `READ` accidentally widening a mutation; a `team.rego`/table edit applied to only
     one of the two bundles (drift → a runtime/test mismatch).**
   - **Concurrency / idempotency check — this slice gates no new mutation and adds no lock; confirm the
     verb-rename and the projection change do NOT alter the `MembershipService` decide-under-protection
     ordering (every mutation still locks the team row first and the escalation gates read lock-snapshots
     — untouched). `CONCURRENCY-AND-LOCKING.md` Rules 1–2 are untouched — say so.**
   - **Wiring check** — every seam this ticket adds has a **named consumer** and a test through its
     **non-happy path**: the `CONTROL` category is consumed by `team.rego`'s category rule (tested by the
     add/change/remove `team.allow` cells + a deny-override cell); the owner-only fence rule has the
     non-owner-denied cells (R10/R11); the recast `TeamRoleCapabilities` is consumed by `managementRole`
     (U3); the tightened `validateContract` has the 422 case (U4); the renamed annotations are exercised
     end-to-end (I1–I6). A rule/token with no test = the ticket is not done.
   - **Boundary / additivity check — name the byte-for-byte-unchanged surfaces: the catalog policies'
     logic (`catalog.rego`/`category.rego`/`product.rego`), `WRITE`/`TAG`/`GRANT` contents,
     `MembershipService` + the escalation gates, `role.rego`/`data.role.assignable`, `resourceRole`/
     `resolveForResource`, the schema, the OpenAPI specs (the action string is an annotation attribute,
     not a wire field). The table change is **additive** (`CONTROL` is a new key; `list-members` is a new
     `READ` member no catalog endpoint asks). The one mechanical cost: the `permissions_test.rego`
     `READ`-expansion assertions update (the known intended break).**
   - **Module-layer separation — the vocabulary + policy in the rego bundles; the projection + authoring
     validation in `example-user-management-service.service`; the annotations in `…web`; the IT/e2e in the
     example test + `scripts/postman`. No catalog/library code changes. No layer reaches across.**
   - **Pattern-reuse check — `team.rego` mirrors `catalog.rego`'s `effective_actions` call (no novel
     expansion); the `CONTROL` category mirrors `WRITE`'s split-and-deny-refine shape; the
     `validateContract` tightening mirrors the existing 6.5 category-validation; the owner-only fence is
     the ADR-0015 §3 rule verbatim — no novel design.**
   - **SOLID / decomposition** — cohesive (SRP), depends on interfaces (DIP); anything to split/simplify?
   - **Apply** the refactoring the review surfaces, then **re-run the unit tests** to confirm green.
   - Write a short note of what the review found + what you refactored into `STATUS-0N.md`. If it found
     nothing substantive, say so explicitly — **do not invent churn.**

6. **Integration / e2e validation (MANDATORY for the relevant tickets).**
   - T1: `opa test infra/opa/policies/` AND `opa test
     example-user-management-service/src/main/resources/opa/policies/` — both green at their **new (risen)**
     counts; `diff` the two `permission_categories.json` copies and the two `team.rego` copies (must be
     byte-identical). Fix-until-green.
   - T4: `./gradlew build` (all modules + the user-mgmt ITs against real Postgres) — the
     `ControlPlaneVocabularyIT` headline cells (I1–I6, incl. the member-can-list loosening and the
     two-axis re-proof). Fix-until-green.
   - T4: bring the rig up (`./profile.sh up`; `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up
     --pods 2`; `./deploy.sh build` for fresh app images), **restart OPA** (the `team.rego`/table edit
     requires it), then `cd scripts/postman` and run **every existing `run-*.sh` matrix** + `catalog-e2e`
     + the new control-plane / member-can-list cells + `opa test` on both bundles. Honor the in-network
     token caveat. Fix-until-green.

7. **Update documentation (after each ticket).** Tick the ticket in the `CONTROL-PLANE-VOCABULARY.md`
   status table; record real values/decisions in `STATUS-0N.md` (the exact new `opa test` counts, the
   422 body, the verb strings). **T4** reconciles `docs/guides/TEAM-BASED-AUTHORIZATION.md` (the ladder is
   now category tokens; `define-tags` via `TAG`; `list-members` is `READ`; the owner-only fences) and
   `docs/guides/PERMISSION-MODEL.md` (the control plane is now categorized — the `CONTROL` category + the
   two-axis split) and notes in `infra/README.md` that the team policy is now category-driven (depends on
   the shared table). Root/project `CLAUDE.md` only if a new build/run step matters (it does not).

8. **Mulch expertise check (after each ticket).** `ml prime`/skim, then record any genuine reusable
   insight (`ml record opa-abac --type <pattern|decision|failure|reference> …`) and `ml sync`. **Before
   `ml sync`, `git restore --staged .`** so the sync commit touches `.mulch/` **only** (the
   swept-staged trap). Skip recording only if nothing is non-obvious.

9. **Commit** — one focused commit on this branch (code + tests + docs + the `STATUS-0N.md` note
   together). Identity `Void3110 <void31102025@gmail.com>`. Conventional subject
   `feat(control-plane): <ticket summary>` (or a narrower `feat(rego)` / `feat(user-service)` /
   `test(e2e)` / `docs(…)` scope). A `Co-Authored-By: Claude` trailer is welcome.

10. **CHECKPOINT — STOP and report.** Summarize what shipped, paste the unit + integration/e2e summary
    (incl. the new `opa test` counts), **summarize the review findings + the refactoring you applied**
    (step 5), list docs updated, and note any open question you resolved. Then proceed to the next ticket.
    **Do not batch tickets without a checkpoint.**

### Permissions / autonomy granted (do these WITHOUT asking)

- Create/modify the rego bundles, example-user-management code, tests, docs in this folder + the guides,
  the `scripts/postman/` suite, and Mulch — all on this branch.
- Stand up / tear down / reseed the local rig (`./profile.sh`, `./deploy.sh`, `ENABLE_OIDC=1
  ENABLE_USER_SERVICE=1 …`); reset fixtures; rebuild images; **restart OPA after the policy edit**;
  drop/recreate the **local** schema if needed.
- Fix any issue your own validation reveals (compile, unit, IT, e2e, refactor). Iterate until green.
- Commit per ticket on this branch.

### Hard rules

- **Fail-closed is the load-bearing invariant — no path returns a wider decision on an error than on
  success:** every control-plane decision default-denies; an unknown/stale token expands to ∅ → deny; the
  owner-only fence grants `define-roles`/`transfer-ownership` ONLY for `code == "owner"`; the one
  loosening is EXACTLY `list-members` (every mutation still denies for member/reader).
- **Do NOT push, open PRs, or touch `main`.** Local + this branch only. The maintainer pushes.
- **Clean-room IP boundary** — never introduce proprietary names, package names, comments, or source.
- **Report at every checkpoint and continue.** Don't batch tickets.
- **The architecture-review + refactor step (5) is NOT optional and happens BEFORE integration/e2e
  validation** — unit green → review → refactor → re-test → then ITs/e2e. Document what it found.
- **Fix-until-green within the ticket.** Only STOP mid-ticket if genuinely *blocked*: the same root
  cause survives ≥3 focused attempts, OR a design decision the docs don't cover is needed, OR a local
  prerequisite is unrecoverable.
- **Slice-specific invariants — never trade these away:**
  - **The two-axis invariant:** 6.7 changes only the **verb category** axis. `MembershipService` and its
    escalation gates (cross-tier strict `<`, senior subset-on-effective, target-tier, owner-protection),
    `RoleAssignableClient`, and `data.role.assignable` are **UNTOUCHED**. Re-prove one gate through a
    renamed verb (I6); editing any of them is the defect.
  - **The owner-only fence is keyed on `code == "owner"`** (the reserved, unspoofable code), **never on
    `role_level`** — a custom level-40 role must not reach `define-roles`/`transfer-ownership`.
  - **Custom roles stay management-incapable** — `managementRole` projects a custom code to `[READ]`;
    `validateContract` 422s a custom role carrying `CONTROL` (or a team-meaningful token) under `"team"`.
  - **`TAG` is NOT split** — `define-tags` stays in `TAG`; its re-gating is mechanical (same outcomes);
    `senior` correctly cannot `define-tags` (holds `CONTROL`, not `TAG`).
  - **Both bundles mirror** — every `team.rego` / `permission_categories.json` edit lands in **both**
    copies, byte-identical; `permissions.rego` is copied verbatim into the service bundle.
  - **`opa test` counts RISE** — the `permissions_test.rego` `READ`-expansion update (`{view,list}` →
    `{view,list,list-members}`) is the **known intended break**, not a regression.
  - **No DB/Liquibase migration** (the team ladder is a Java projection; system-role `"*"` resource seeds
    untouched); **no kill-switch**; B2's `RoleDefinitionSupplier` tri-state contract untouched.
- **`opa-abac-core` stays Spring-free** (this slice does not touch core — confirm it remains untouched).
- **No schema change, no OpenAPI shape change** — a clean `ddl-auto: validate` boot still holds (the
  action string is an annotation attribute, not a wire field).

---

## Operator notes (not part of the prompt)

- **The headline ticket is T4 (with T1 as the policy core).** The IT cells I1/I2 (a `member` gains the
  roster but nothing wider) + **I6** (a renamed verb still hits the untouched escalation gate → 422)
  justify the whole design — they prove the one intended loosening is exactly `list-members` and that
  categorizing the verbs did not re-open any escalation path. T1 is where the vocabulary + policy cut
  happens; T4 proves it end-to-end.
- **The fail-closed edge to eyeball:** the **owner-only fence keyed on the wrong thing** (level instead of
  the `owner` code) — a custom level-30/40 role would then reach `define-roles`/`transfer-ownership`, a
  real escalation that *passes* a naive happy-path test. R10/R11 exist to catch it; every T1
  `STATUS-0N.md` must state the fence is `code == "owner"` and that a custom high-level role is denied.
  Second edge: a `team.rego`/table edit applied to only one of the two bundles (the byte-identical `diff`
  is acceptance, not optional).
- **The keystone scope discipline:** the autonomous-runs lesson is "an unpinned externally-visible
  semantic stops the run." This slice pins the **complete** behavior delta (00-DESIGN §3): exactly two
  intended changes (member-can-list; custom-role 422), everything else net-identical (a wire-verb rename).
  Do not invent a third behavior change; if a fork feels unpinned, it is one of these two or it is the
  two-axis invariant (do not touch `MembershipService`).
- **Standalone-value subset:** T1 lands the complete vocabulary + policy (provable by `opa test` alone),
  but the running app is only correct once T2 recasts the projection to emit the new tokens — so **T1+T2**
  are the smallest app-correct subset. T3 (verb renames) and T4 (IT/e2e/docs) follow.
- **Rig / e2e specifics:** mint tokens **in-network** (APISIX validates issuer `keycloak:8888`);
  `./deploy.sh build` to force new app images; **this slice edits the team policy + the table**, so
  **restart OPA** before the matrices (the README gotcha); the **podman CLI is flaky from the agent shell**
  (B2 retro — returns empty output; works via `podman run --rm` inside newman and when detached) — prefer
  the deterministic IT over live container manipulation, and run podman commands detached if needed.
- **CI does not run the rig yet** — the newman matrices are a local/manual gate; a compose-up→newman CI
  job is a tracked follow-up.
- **Context management** — if the window grows long mid-run, finish the ticket, stop at its checkpoint,
  and resume in a **fresh session** (the ticket status table + STATUS notes are the handoff); sub-agents
  are for scouting/validation only, never the implementation.
- **Workflow-as-artifact:** keep this prompt verbatim; the `STATUS-0N.md` notes record each ticket's
  outcome. Move the folder to `docs/to-do/implemented/` on ship (T4).
