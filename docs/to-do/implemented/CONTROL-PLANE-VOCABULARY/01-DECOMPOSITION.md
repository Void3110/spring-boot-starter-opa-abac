---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# Control-plane vocabulary categorization — decomposition (Phase 6.7)

> The ordered work list for [[CONTROL-PLANE-VOCABULARY]] (Phase 6.7 of [[POC-ROADMAP]], route step 2).
> Four tickets, one focused commit each. Design: [[00-DESIGN]]. QA: [[10-QA-TEST-CASES]]. Run via the
> [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]. Pinned by ADR [[0015-control-plane-vocabulary-categorization|0015]];
> extends ADR [[0007-coarse-grained-permission-categories|0007]]; closes [[USER-STORIES]] Epic G / Story G4.
>
> **Packages.** Example user-service: `dev.dmitriikonovalov.example.usermgmt.{service,web}`. Rego: the
> two policy bundles (`infra/opa/policies/` + `example-user-management-service/src/main/resources/opa/policies/`).
> **One new category (`CONTROL`); `opa test` counts RISE (NOT zero-Rego); no DB migration; B2's tri-state
> supplier contract untouched.**

## Critical path

```
T1 ──► T2 ──► T4
 │      │
 └────► T3 ──┘
```

**T1 is the gate** (the `CONTROL` category in both `permission_categories.json` copies + the two
`team.rego` rewrites + the service-bundle mirror + the `*_test.rego` rewrites). Everything references the
new vocabulary. **T2 (the Java projection + `validateContract` tightening) and T3 (the controller verb
renames) are independent of each other** once T1 lands — T2 is the resolve-side ladder + the custom-role
422, T3 is the membership endpoint annotations. They can land in either order after T1. **T4 (IT + e2e +
docs + record) is last** — it proves the categorized control plane through the rig and across the whole
suite. **T1 is the independently-landable subset**: the policy + vocabulary is complete and correct on
its own (the dogfood `team.rego` + `opa test` prove it) even before the Java projection emits the new
tokens — but note T1 alone would leave the running app's `managementRole` emitting *fine verbs* the new
`team.rego` no longer matches, so T1+T2 are the smallest **app-correct** subset.

## Three pinned semantics (so the run never stops to ask)

1. **`opa test` counts RISE — this is expected, not a regression.** Baseline: `opa test
   infra/opa/policies/` **157**, `opa test example-user-management-service/src/main/resources/opa/policies/`
   **14**. Both rise. The **known intended break**: `permissions_test.rego` asserts `READ == {view, list}`
   at ~3 sites — these MUST update to `{view, list, list-members}`. A passing-test-turned-failing there is
   the *expected* edit, not a defect. (ADR 0015 §Consequences; 00-DESIGN §5.)
2. **The two-axis invariant — `MembershipService` is UNTOUCHED.** 6.7 changes only the **verb category**
   axis (which kinds of acts a role may perform). The **escalation gates** (cross-tier strict `<`, senior
   subset-on-effective, target-tier, owner-protection) decide *on whom / to what tier* and are an
   invariant — no edit to `MembershipService`, `requireAssignableByActor`, `requireTargetDoesNotOutrankActor`,
   `RoleAssignableClient`, or `data.role.assignable`. Any ticket touching them is wrong. (00-DESIGN §4.)
3. **`TAG` is NOT split; `define-tags` stays in it.** `define-tags` re-gating is purely mechanical — the
   `team:define-tags` annotation is unchanged, now *granted* via the `TAG` token in the owner/admin ladder
   and *expanded* by `team.rego`. Allow/deny outcomes are identical (owner/admin curate, all else 403;
   `senior` correctly stays without it — it holds `CONTROL`, not `TAG`). Any cell asserting `senior` can
   `define-tags`, or that `define-tags` moved categories, is wrong. (ADR 0015 §6.)

---

## T1 — Vocabulary + policy: the `CONTROL` category, the category-driven `team.rego`, the bundle mirror

**Goal.** Introduce the one new category and make the control plane category-driven: `team.rego` decides
via the same `permissions.effective_actions` the catalog uses (symmetric with `catalog.rego`), plus an
owner-only-by-code fence — with the dogfood service bundle made self-contained for `opa test`.

**Deliverables.**
- **`permission_categories.json` — BOTH copies, byte-identical** (`infra/opa/policies/` +
  `example-user-management-service/src/main/resources/opa/policies/`): add `"CONTROL": ["add-member",
  "change-role", "remove-member"]`; add `"list-members"` to `READ` → `["view", "list", "list-members"]`.
  `WRITE`/`TAG`/`GRANT` unchanged.
- **`permissions.rego` — copied verbatim** from `infra/opa/policies/` into the service bundle
  (`example-user-management-service/src/main/resources/opa/policies/`) so the dogfood `team.rego` resolves
  `data.permissions.effective_actions` under an isolated `opa test`. (The runtime already co-loads it from
  the shared infra bundle; this is purely so the service-module test passes.)
- **`team.rego` — BOTH copies, identical** rewrite. `import data.permissions` (mirror `catalog.rego`).
  Two `allow` rules: (1) category-driven — `verb in permissions.effective_actions(input.role_definition,
  input.resource.type)` guarded by `input.role_definition.permissions`; (2) **owner-only fence** — `verb
  in {"define-roles", "transfer-ownership"}` AND `input.role_definition.code == "owner"`. Keep the `verb`
  helper (split on `":"`) and `default allow := false`. Rewrite the header comment to the category model.
- **`permissions_test.rego`** (`infra/opa/policies/`): **update** the ~3 `READ == {view, list}`
  assertions → `{view, list, list-members}` (the known break); **add** `CONTROL` expansion cases
  (`CONTROL` → `{add-member, change-role, remove-member}`; a `["CONTROL"]`-minus-`denied_actions` case
  proving deny-override subtracts; an unknown-token-on-team case → ∅).
- **`team_test.rego` — BOTH copies, identical** rewrite to the category model (see QA T1 cases): every
  system-code ladder × the live verbs; the owner-only fence allowed ONLY for `code == "owner"`;
  `define-tags` allowed for owner/admin (carry `TAG`) but **denied for `senior`** (carries `CONTROL` not
  `TAG`); `list-members` allowed for any `READ`-holder; `add/change/remove-member` for `CONTROL`-holders;
  deny-override (grant `CONTROL`, deny `remove-member` → `remove-member` denied); unknown-verb denies;
  no-`role_definition` denies.

**Acceptance.** QA **R1–R12**. `opa test infra/opa/policies/` green at the **new** (higher) count;
`opa test example-user-management-service/src/main/resources/opa/policies/` green at its new (higher)
count. **Both `permission_categories.json` copies byte-identical** (a `diff` is part of acceptance).
`./gradlew :example-user-management-service:compileJava` still green (no Java touched yet — the policy
change is independent).

**What NOT to touch.** `MembershipService` and the escalation gates (pinned #2). The catalog policies'
*logic* (`catalog.rego`/`category.rego`/`product.rego` unchanged — only the shared table + `permissions_test`
change, and the table change is additive: `CONTROL` is a new key, `list-members` is a new `READ` member
no catalog endpoint asks). `role.rego` / `data.role.assignable`. `WRITE`/`TAG`/`GRANT` contents. No Java,
no schema, no controller change in this ticket.

---

## T2 — Resolve-side: `TeamRoleCapabilities` → category tokens; `validateContract` rejects custom team tokens

**Goal.** The management projection emits the **category tokens** `team.rego` now expands (instead of the
retired fine verbs), and authoring a custom role that smuggles control-plane power under `"team"` is now
an explicit 422 instead of silent dead-data.

**Deliverables.** Package `dev.dmitriikonovalov.example.usermgmt.service`:
- `TeamRoleCapabilities` — **recast** `BY_CODE` from fine verbs to **category tokens**: `owner` →
  `[READ, CONTROL, TAG]`; `administrator` → `[READ, CONTROL, TAG]`; `senior` → `[READ, CONTROL]`;
  `member` → `[READ]`; `reader` → `[READ]`; `forCode` default (custom codes) → `[READ]`. Drop the
  `MANAGE`/`DEFINE_ROLES`/`DEFINE_TAGS`/`TRANSFER_OWNERSHIP` fine-verb constants (the verbs are now
  reached via category expansion + the Rego fence). Rewrite the class javadoc to the category model and
  note the two owner-only fences are authorized in `team.rego` (by `owner` code), not via a token here.
  *(Consumer: `EffectiveRoleService.managementRole`, unchanged in shape — still
  `Map.of("team", TeamRoleCapabilities.forCode(code))`.)*
- `RoleDefinitionService.validateContract(...)` — **tighten**: reject (throw
  `RoleDefinitionInvalidException` → 422 `ROLE_DEFINITION_INVALID`) a custom role whose `permissions`
  carry a control-plane category (`CONTROL`) — or a team-meaningful token — under a `"team"` key. The
  catalog-plane validation (the level-ceiling + deny-subtraction checks) is unchanged. Add the rule so the
  message names why ("custom roles cannot carry team-management categories — management capability is
  fixed to the system-role ladder; the team's resource permissions go on the team-target type").

**Acceptance.** QA **U1–U4**. `./gradlew :example-user-management-service:test` green. Unit:
`TeamRoleCapabilities.forCode` emits the right token list per system code and `[READ]` for an unknown
(custom) code (U1–U2); `managementRole` therefore sets `permissions["team"]` to category tokens (U3, via
the existing `EffectiveRoleService` test pattern); `validateContract` rejects a custom role with
`"team": ["CONTROL"]` → 422, while a custom role with only catalog-type category tokens still validates
(U4).

**What NOT to touch.** `managementRole`'s **shape** (signature/return type) and `resourceRole` /
`resolveForResource` (the resource projection — byte-identical; the two projections stay separate).
`MembershipService` and the escalation gates (pinned #2). The `RoleDefinitionEntity` / repository / schema.
No controller change (T3).

---

## T3 — Controller verbs: split `team:manage` into the membership fine actions

**Goal.** The membership endpoints stop sharing one coarse `team:manage` verb and each gate on its own
fine action, so `CONTROL` is actually exercised per-act (and `list-members` rides `READ`).

**Deliverables.** Package `dev.dmitriikonovalov.example.usermgmt.web`:
- `MembershipController` — change the four `@OpaPreAuthorize(action = "team:manage", …)` annotations:
  `listMembers` → **`team:list-members`**; `addMember` → **`team:add-member`**; `changeMemberRole` →
  **`team:change-role`**; `removeMember` → **`team:remove-member`**. `resourceType="'team'"`,
  `resourceId="#teamId"` unchanged on each. Update the class javadoc (the verb list + the "manager-only"
  framing — listing is now `READ`).
- `TagDefinitionController` (`team:define-tags`), `RoleDefinitionController` (`team:define-roles`), the
  transfer endpoint (`team:transfer-ownership`) — **unchanged** (verify, don't edit).

**Acceptance.** QA **U5** + the IT coverage lands in T4 (the renamed verbs are end-to-end-proven there).
`./gradlew :example-user-management-service:test` green (any controller-slice/web unit tests updated to
the new action strings). A grep proof that `team:manage` no longer appears in `MembershipController` and
the four new verbs each appear once.

**What NOT to touch.** `MembershipService` and its escalation gates (pinned #2 — the renamed verbs change
*which gate decision the verb maps to in OPA*, never the service-side cross-tier/target-tier logic). The
three unchanged controllers' annotations. The OpenAPI spec operationIds / request shapes (the action
string is an annotation attribute, not a wire field — no schema/contract change).

---

## T4 — IT + e2e + docs + slice record

**Goal.** Prove the categorized control plane through the rig and across the whole suite, prove the two
intended behavior changes are *exactly* what changed, document the now-true model, and close the record.

**Deliverables.**
- **The headline IT (the load-bearing proof).** In
  `example-user-management-service/src/test/java/.../` — a `ControlPlaneVocabularyIT` (or extend an
  existing membership IT; **real Postgres via Testcontainers**, the dogfood OPA path enabled): assert the
  full behavior matrix (00-DESIGN §3) through the renamed verbs —
  - a **`member`** (READ-only) **can** `GET /teams/{id}/members` (the loosening) **and** is **403** on
    `add/change/remove-member` and `define-tags` (the loosening is EXACTLY listing — nothing wider);
    `reader` likewise;
  - `senior` **can** add/change/remove members (subject to the untouched escalation gates) but is **403**
    on `define-tags` (CONTROL-not-TAG);
  - `owner`/`administrator` can curate tags (`define-tags`) and manage members; only `owner` can
    `define-roles` / `transfer-ownership` (the fences);
  - **re-prove one representative escalation gate through a renamed verb** — e.g. a `senior` is rejected
    (422 `ROLE_SUBSET_VIOLATION`) when `change-role` would promote past the member tier, proving T3's
    rename did not bypass `MembershipService` (the two-axis invariant holds). (QA **I1–I6**.)
- **e2e (newman).** The existing user-mgmt / permission-categories matrices stay green; **add** a
  control-plane cell exercising the verb split (a `senior` manages members, denied `define-tags`) **and**
  an explicit **member-can-`list-members`** cell (a plain member lists the roster → 200; the same member
  → 403 on a mutation). Bring the rig up; **restart OPA after the `team.rego`/table edit** (infra/README
  gotcha). (QA **E1–E2**.)
- **The whole existing suite green** (every module's tests + `./gradlew build` + every `run-*.sh` matrix +
  `opa test` BOTH bundles at their new counts). (QA **E3**.)
- **Docs:** reconcile [[PERMISSION-MODEL]] (the control-plane section: `team:*` verbs are now categorized;
  the `CONTROL` category; the owner-only fences; the two-axis split) and [[TEAM-BASED-AUTHORIZATION]] (the
  capability ladder is now category tokens; `define-tags` via `TAG`; `list-members` is `READ`). Note in
  infra/README that the team policy is now category-driven (depends on the shared table). State which
  guides changed in STATUS.
- **Record:** [[POC-ROADMAP]] — 6.7 shipped, next Phase 6; [[USER-STORIES]] — tick Epic G / Story G4
  (define-tags enforcement closed) and add a control-plane categorization story if missing; tick the
  [[CONTROL-PLANE-VOCABULARY]] index status table through T4.
- **Mulch:** record the durable insights (control-plane folds into the one vocabulary; the owner-only-by-code
  fence; the two-axis verb-vs-escalation-gate separation; the mirror-both-bundles obligation) —
  `git restore --staged .` **before** `ml sync`.
- `git mv docs/to-do/planning/CONTROL-PLANE-VOCABULARY docs/to-do/implemented/CONTROL-PLANE-VOCABULARY`,
  flip the index frontmatter to `status/done`, add the past-tense **Shipped** banner.

**Acceptance.** QA **I1–I6, E1–E3, D1–D2**. `./gradlew build` green; `opa test` BOTH bundles green at
their new counts; the e2e suite green end-to-end. Frontmatter valid on every touched note; wikilinks
resolve; clean-room scan clean. **No push.**

**What NOT to touch.** ADR 0015 body (immutable). `MembershipService` and the escalation gates (pinned
#2). The catalog policies' logic. B2's `RoleDefinitionSupplier` tri-state contract (non-goal — untouched).
`CLAUDE.md` unless a build/run step genuinely changed.

---

## Cross-cutting acceptance

- `./gradlew build` green throughout; **`opa test` green on BOTH bundles at their new (risen) counts**
  (`infra` baseline 157, service-team baseline 14 — both rise; the `READ`-expansion assertion update is
  the known intended break, not a regression); **Testcontainers real Postgres** (never H2) for the
  headline IT; the e2e suite green end-to-end.
- **Both `permission_categories.json` copies byte-identical**; **both `team.rego` copies identical**
  (the mirror obligation — a `diff` is part of T1 acceptance).
- **The two-axis invariant holds:** 6.7 changes only the verb category (`CONTROL` + deny-overrides);
  `MembershipService`'s escalation gates (cross-tier, senior subset, target-tier, owner-protection) are
  **untouched** and re-proven through a renamed verb (T4 I-cell).
- **Exactly two intended externally-visible changes**, both proven: (1) any team member can now
  `list-members` (was manager-only); (2) a custom role carrying control tokens under `"team"` is now 422.
  Everything else is a wire-verb rename (`team:manage` → the three specific verbs) with **no net
  authorization change**; `define-tags`/`define-roles`/`transfer-ownership` outcomes are identical.
- **No DB/Liquibase migration** (the team ladder is a Java projection; system-role `"*"` resource seeds
  untouched). **No kill-switch.** B2's supplier contract untouched.
- Clean-room throughout. One focused commit per ticket, identity `Void3110 <void31102025@gmail.com>`,
  **no push**.

## Related

[[CONTROL-PLANE-VOCABULARY]] (index) · [[00-DESIGN]] (mechanism + behavior matrix + proof obligations) ·
[[10-QA-TEST-CASES]] (the cases the acceptances reference) · ADR
[[0015-control-plane-vocabulary-categorization|0015]] (every pinned fork) · ADR
[[0007-coarse-grained-permission-categories|0007]] (the catalog model extended) · [[USER-STORIES]]
(Epic G / Story G4) · [[POC-ROADMAP]] (route step 2).
