---
tags:
  - status/planned
  - type/project
  - area/user-service
  - area/abac
  - area/spring
---

# Control-plane vocabulary categorization — QA test cases (Phase 6.7)

> Concrete cases; each becomes a ticket's *Acceptance* in [[01-DECOMPOSITION]]. **R** = Rego (`opa test`),
> **U** = unit, **I** = integration (Testcontainers **real Postgres** — never H2; the dogfood OPA path),
> **E** = e2e (newman — asserts the actual cut: allow-vs-deny per role, not just response shape),
> **D** = docs/record. Pinned by ADR [[0015-control-plane-vocabulary-categorization|0015]].

## Rego (`opa test`) — R* — Ticket T1

Counts **RISE** from baseline (`infra` 157, service-team 14). The `permissions_test.rego` `READ`
updates are the **known intended break**, not regressions.

| ID | Case | Asserts | → |
|---|---|---|---|
| R1 | `permissions.effective_actions({permissions:{category:[READ]}}, "category")` | `== {view, list, list-members}` (the updated assertion) | T1 |
| R2 | `effective_actions({permissions:{team:[CONTROL]}}, "team")` | `== {add-member, change-role, remove-member}` | T1 |
| R3 | `effective_actions({permissions:{team:[CONTROL]}, denied_actions:{team:[remove-member]}}, "team")` | `== {add-member, change-role}` (deny-override subtracts on the team plane) | T1 |
| R4 | `effective_actions({permissions:{team:[READ, CONTROL]}}, "team")` (senior shape) | `== {view, list, list-members, add-member, change-role, remove-member}` | T1 |
| R5 | `team.allow` — owner (`code:"owner"`, `team:[READ,CONTROL,TAG]`) asks `team:define-tags` | `true` (via TAG) | T1 |
| R6 | `team.allow` — administrator (`team:[READ,CONTROL,TAG]`) asks `team:define-tags` | `true` (via TAG) | T1 |
| R7 | `team.allow` — **senior** (`team:[READ,CONTROL]`) asks `team:define-tags` | **`false`** (holds CONTROL, not TAG — pinned #3) | T1 |
| R8 | `team.allow` — senior asks `team:add-member` / `team:change-role` / `team:remove-member` | `true` (via CONTROL) | T1 |
| R9 | `team.allow` — member (`team:[READ]`) asks `team:list-members` | `true` (via READ — the loosening); asks `team:add-member` → `false` | T1 |
| R10 | `team.allow` — **owner-only fence**: owner asks `team:define-roles` / `team:transfer-ownership` | `true`; administrator/senior/member ask either | **`false`** (allowed ONLY for `code=="owner"`) | T1 |
| R11 | `team.allow` — a **custom** role (`code:"lead"`, level 30, `team:[CONTROL]`) asks `team:define-roles` | `false` (fence is by `owner` code, not level — unspoofable) | T1 |
| R12 | `team.allow` — unknown verb / no `role_definition` / empty `team` token list | `false` (default deny; ∅-expansion floor) | T1 |

Plus: **both `permission_categories.json` copies byte-identical** (`diff` clean); `team.rego` byte-identical
across both bundles; `permissions.rego` present in the service bundle.

## Unit (U*)

| ID | Case | Asserts | → |
|---|---|---|---|
| U1 | `TeamRoleCapabilities.forCode("owner"/"administrator")` | `[READ, CONTROL, TAG]`; `senior` → `[READ, CONTROL]`; `member`/`reader` → `[READ]` | T2 |
| U2 | `TeamRoleCapabilities.forCode("lead")` (custom code) | `[READ]` (the I12 default — custom is management-incapable) | T2 |
| U3 | `EffectiveRoleService.managementRole(team, user)` for an admin membership | `permissions["team"] == [READ, CONTROL, TAG]` (the projection emits category tokens; shape unchanged) | T2 |
| U4 | `RoleDefinitionService.validateContract` — custom role `permissions:{team:[CONTROL]}` | throws → **422 `ROLE_DEFINITION_INVALID`**; a custom role with only catalog-type tokens (`{category:[READ,WRITE]}`) still validates | T2 |
| U5 | `MembershipController` annotations (web-slice or reflection assert) | `listMembers`=`team:list-members`, `addMember`=`team:add-member`, `changeMemberRole`=`team:change-role`, `removeMember`=`team:remove-member`; `team:manage` absent | T3 |

## Integration (I*) — Ticket T4 (real Postgres via Testcontainers; the dogfood OPA path)

| ID | Case | Asserts (the cut) | → |
|---|---|---|---|
| I1 | a **`member`** calls `GET /teams/{id}/members` | **200** + roster (the loosening — was 403) | T4 |
| I2 | the same `member` calls `add/change/remove-member` and a tag-dictionary write | **403** on each (the loosening is EXACTLY listing — nothing wider); `reader` likewise | T4 |
| I3 | a **`senior`** adds a member then is denied a tag-dictionary write | add **succeeds** (CONTROL); `define-tags` → **403** (CONTROL-not-TAG) | T4 |
| I4 | **`owner`**/**`administrator`** curate a team tag key (`define-tags`) | **2xx** (both carry TAG) | T4 |
| I5 | the owner-only fences | `owner` `define-roles` / `transfer-ownership` → **2xx**; `administrator` either → **403** | T4 |
| I6 | **two-axis re-proof**: a `senior` `change-role`s a member *up past the member tier* | **422 `ROLE_SUBSET_VIOLATION`** — the renamed verb still hits the untouched `MembershipService` escalation gate (the verb rename did not bypass it) | T4 |

## E2E (E*) — newman — Ticket T4

| ID | Flow | Asserts | → |
|---|---|---|---|
| E1 | control-plane verb-split cell | a `senior` manages members (200) but is denied `define-tags` (403) through the rig | T4 |
| E2 | **member-can-list-members** cell | a plain `member` lists the roster → **200**; the same member → **403** on a mutation (the loosening proven end-to-end) | T4 |
| E3 | the whole existing suite | every `run-*.sh` matrix + `catalog-e2e` + every module's tests green; `opa test` **both** bundles green at their new counts | T4 |

## Docs / record (D*)

| ID | Item | → |
|---|---|---|
| D1 | [[PERMISSION-MODEL]] + [[TEAM-BASED-AUTHORIZATION]] reconciled (categorized control plane, `CONTROL`, the fences, the two-axis split, `list-members`=READ, `define-tags`=TAG); infra/README notes the team policy is now category-driven | T4 |
| D2 | [[POC-ROADMAP]] 6.7 shipped (next Phase 6); [[USER-STORIES]] Epic G / Story G4 ticked (define-tags enforcement closed); [[CONTROL-PLANE-VOCABULARY]] status table through T4; folder `git mv` to `implemented/` | T4 |

## Headline proof

**I1 + I2 + I6** are the load-bearing cells: I1/I2 prove the one intended loosening is *exactly*
`list-members` and nothing wider; **I6 proves the two-axis invariant** — the renamed verbs still flow
through the untouched `MembershipService` escalation gates, so categorizing the verbs did not re-open any
escalation path. **R7 + R10/R11** prove the policy fences (senior ≠ define-tags; the owner-only-by-code
fence is unspoofable by a custom level-30 role).
