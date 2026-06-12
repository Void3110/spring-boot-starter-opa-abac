---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/opa
  - area/user-service
---

# 00 — Design: Coarse permission categories + delegation (Phase 6.5)

> The design, written from the settled **ADR [[0007-coarse-grained-permission-categories|0007]]**
> (which pins the taxonomy and the delegation model) plus the 2026-06-12 design interrogation that
> resolved the implementation forks the ADR left open. Stories: [[USER-STORIES]] Epic G. The
> decomposition package (`01-DECOMPOSITION` + QA + the autonomous prompt) is produced by
> `/decompose` from this document.

## 1. The problem, precisely

A `RoleDefinition.permissions` today is `{type: ["read"|"write"]}` — too coarse to express
edit-but-not-delete, tag-curation, or safe delegation, and ungrouped for any editor UI. ADR 0007
pins the answer (four categories → fine actions, deny-overrides, a five-tier level ceiling, two
assignment gates); this slice makes it real across the deciding side (catalog policies + the
`@OpaPreAuthorize` sweep) and the authoring/assignment side (user-mgmt), with **no back-compat**.

## 2. The decisions (the ten settled forks — do not reopen during decomposition)

1. **Scope**: (a) the deciding side + (b) authoring/assignment are in; (c) the control-plane
   vocabulary (`team:*` verbs, `TeamRoleCapabilities`) is OUT — it becomes its own roadmap slice
   ("control-plane categorization", sequenced after Phase 6).
2. **Storage/wire**: category tokens live **in the existing `permissions` map** (`{"category":
   ["WRITE","TAG"]}`); a new `denied_actions` jsonb column + wire field (default `{}`) carries
   subtractions; `role_level` stays in `attributes`. One grant shape; denials only narrow.
3. **Clean cut — the additive-only invariant is consciously waived.** The starter is unpublished:
   no legacy aliases, no compatibility clause. `permissions` carries ONLY uppercase category tokens
   after this slice; **unknown/stale tokens expand to ∅ = deny** (fail-closed, pinned by a cell).
   One migration changelog rewrites the seeds (+ a sweep `UPDATE` for stray lowercase tokens);
   every annotation, policy, IT fixture, and e2e bootstrap payload migrates in-slice.
4. **`assign-tags` is a conditional second decision** (AWS tag-on-create semantics): the REST
   surface is unchanged; iff a create/update request's **tags delta is non-empty**, the handler
   requires an additional `assign-tags` decision on the same resolved instance (via the manager
   seam, fail-closed, reusing the 5.97 request cache). Both boundary directions hold: `WRITE`
   without `TAG` edits content but never labels; `TAG` without `WRITE` relabels but never edits.
5. **`define-tags` is enforcement-deferred** to the control-plane slice: it ships in the vocabulary
   and the expansion table and participates fully in the effective-set math (denials, the senior
   subset gate, Phase-6 enrichment), but the tag-dictionary endpoints keep their `team:define-tags`
   gate. Documented; not dead data.
6. **Seed migration** (one changelog): `owner 40` (kept; any value `> 30` satisfies the strict-`<`
   gate) and `administrator 30` get `{"*": ["READ","WRITE","TAG","GRANT"]}`; **new `senior 25`**
   row with `{"*": ["READ","WRITE","TAG"]}`; `member 20` the same; **`viewer` renames to
   `reader`** at 10 with `{"*": ["READ"]}`; all rows get `denied_actions: {}`. The Keycloak
   `viewer` username / `catalog-viewer` realm role are unrelated realm artifacts — untouched.
7. **The two assignment gates — hybrid placement.** Level gates run in Java inside the
   team-row-locked transaction (B3 decide-under-protection): cross-tier `actor.level >
   candidate.level` for everyone; at senior additionally `candidate.level ≤ 20`. The senior-only
   **subset-on-effective verdict** goes to a new OPA entrypoint **`data.role.assignable`**
   (input: the two role snapshots read under the lock; OPA is a pure function — no TOCTOU
   reopened). OPA error/timeout → assignment **rejected**. Senior gains the coarse `manage` verb in
   the resolve-side team-verb ladder (nothing else). The ADR pair **replaces** the always-on
   subset check; pinned designed cell: an admin whose own role denies `delete` may still assign
   full `WRITE` (cross-tier seniority, not subset, is the admin gate). The retro-audit Critical-1
   invariant is re-proven under the new gates and its pinning ITs migrate.
8. **Authoring contract** (owner-only, validated in `RoleDefinitionService`): a role is authored by
   picking `roleLevel ∈ {10,20,25,30}` (stored in `attributes.role_level`); granted categories must
   satisfy `⊆ ceiling(level)` (`GRANT` only at 30); tokens outside the four categories → 422
   (retires the flat shape at the API boundary); **strict denial validation** — `denied_actions ⊆
   expand(granted)`, a denial of something not granted is rejected; custom roles get **no team
   verbs** (a custom level-25 role has senior's ceiling but no live assign power until the
   control-plane slice — pinned by a test); the authoring-time subset-of-author check is dropped
   (vestigial under owner-only authoring; the level ceiling is the real bound).
9. **Policy mechanics**: one shared `permissions.rego` module exposing
   `effective_actions(role_def, type)` = expand minus `denied_actions[type]`, imported by
   `category`/`product`/`catalog.rego` and the new `role.rego` (`assignable`); the expansion table
   is `infra/opa/policies/permission_categories.json` → `data.permission_categories`
   (`READ→[view,list]`, `WRITE→[create,update,delete]`, `TAG→[define-tags,assign-tags]`,
   `GRANT→[assign-roles]`); `direct_grant` = `action ∈ effective ∧ tags_satisfied`;
   `inherited_grant` keeps its shape with membership through `effective` on the ancestor type;
   `filter` = `"list" ∈ effective` (role-def-only, no fallback); the **realm fallback maps through
   the same table**: `catalog-viewer → effective({READ})`, `catalog-editor →
   effective({READ,WRITE,TAG})` (preserves today's editor-sets-tags reality the e2e bootstrap
   depends on); `team.rego` untouched.
10. **Rollout/proof**: no kill-switch (atomic in-repo cut); a new `permission-categories` e2e
    matrix on fixture `9999…` (cells in §6); the internal bootstrap endpoint gains
    `roleLevel`/`deniedActions`; ALL nine existing runners' fixture payloads + the `opa test`
    suites migrate in-slice; acceptance = build + opa test + the new matrix twice (idempotent) +
    the **entire existing newman suite green post-migration** on a rebuilt rig.

## 3. The action-string sweep (the fine vocabulary, per endpoint)

| Endpoint class | Old action | New action |
|---|---|---|
| GET one (catalog/category/product) | `<type>:read` | `<type>:view` |
| GET list | `<type>:read` | `<type>:list` |
| POST create | `<type>:write` | `<type>:create` (+ `assign-tags` iff tags present) |
| PUT update | `<type>:write` | `<type>:update` (+ `assign-tags` iff tags delta) |
| DELETE | `<type>:write` | `<type>:delete` |

`team:*` actions are out of scope (decision 1). The PE `filter` entrypoint serves the `list` action.

## 4. Behavior matrix (the cells that change — and the ones that must not)

| Cell | Before | After |
|---|---|---|
| Role `{"category":["WRITE"]}` minus `denied {delete}`, DELETE category | n/a | **403** (deny-overrides) |
| `WRITE`-without-`TAG`, update with tags delta | n/a (write allowed all) | **403** (second decision) |
| `WRITE`-without-`TAG`, update content only | 200 | **200** (unchanged boundary) |
| `TAG`-without-`WRITE`, tags-only update | n/a | **200**; content edit **403** |
| Stale flat role `{"catalog":["read"]}` | decided | **deny everywhere** (∅-expansion) |
| Senior assigns member-level role | n/a (no senior) | **201** (both gates pass) |
| Senior assigns senior/admin-level | n/a | **rejected** (level gate) |
| Senior with `delete` denied assigns full `WRITE` | n/a | **rejected** (`assignable` subset) |
| Admin assigns admin | rejected (subset+ladder) | **rejected** (strict `<`) |
| Admin with `delete` denied assigns full `WRITE` | rejected (subset) | **allowed** (designed: cross-tier gate) |
| Realm-fallback editor/viewer cells | read/write | identical reach via `effective({READ,WRITE,TAG})` / `effective({READ})` |
| PE residual shape (`filter`) | role-def-only fold | **unchanged** (expansion folds over known input+data — T1 experiment pins it) |

## 5. What this slice does NOT change

The library modules (`opa-abac-*`) — the vocabulary is app/policy-level; `AbacContext`,
`RoleDefinition` (except the additive `denied_actions` field on the wire record — a flagged
build-breaker), the manager, the cache, and partial-eval machinery are untouched. `team.rego` and
the membership/role/tag-definition **endpoint gates** (beyond senior's `manage` ladder entry).
Pagination, the error contract, hierarchy mechanics. The tag dictionary's enforcement (decision 5).

## 6. Proof obligations (QA skeleton — cases get ids in 10-QA)

- **Unit**: `permissions.rego` expansion/denial algebra (incl. ∅ for unknown tokens);
  `assignable` truth table; authoring validation (ceiling, strict denials, 422 paths); the
  level-gate pair in the membership service.
- **IT (real Postgres)**: seed migration lands (senior row, reader rename, category tokens);
  authoring round-trip; assignment gates incl. the latch-based senior-demotion race (the B3
  pattern); the conditional `assign-tags` second decision both directions; OPA-error → assignment
  rejected.
- **opa test**: rewritten per-type suites + `permissions_test` + `role_test`; the PE-fold harness
  (`opa eval --partial`) asserting the residual shape is unchanged — a **T1 acceptance**, not a
  discovery.
- **e2e (`run-permission-categories-matrix.sh`, fixture `9999…`)**: E1 deny-overrides; E2 the TAG
  boundary both directions; E3 senior delegation (grant ✓ / over-level ✗ / subset ✗); E4 admin
  tier (below ✓ / peer ✗ / the designed deny-vs-assign cell); E5 stale-flat-role decides nothing;
  E6 ladder parity (reader/member). Runner restarts OPA + health-polls (its slice edits policies);
  registry row added; re-run idempotent; **whole existing suite green after the migration**.

## 7. Forks already closed (do not reopen during decomposition)

Scope a+b/not-c · same-map tokens + `denied_actions` · clean cut (no back-compat; waived
additivity) · conditional `assign-tags` · deferred `define-tags` · the seed table incl.
`viewer→reader` · hybrid gates + `assignable` + senior's coarse `manage` + the replaced subset
semantics · the authoring contract incl. strict denials · shared module + data-file table + the
fallback mapping · no kill-switch + the proof shape above.

## Related

ADR [[0007-coarse-grained-permission-categories|0007]] (+ its Phase-6.5 implementation addendum) ·
[[USER-STORIES]] Epic G · [[POC-ROADMAP]] (6.5; the new control-plane slice) ·
[[ATTRIBUTE-RICH-PRE-AUTHORIZATION]] (the resolved-instance gate the second decision reuses) ·
[[TEAM-BASED-AUTHORIZATION]] (the gates this refines) · [[PARTIAL-EVALUATION-FILTERING]] (the
`filter` entrypoint the expansion must fold into).
