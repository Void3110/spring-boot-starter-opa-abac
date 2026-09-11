---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# STATUS — T5: the pane row (E8), the close-out records, the review sequence

**Status:** ✅ done — 2026-09-11 (collaborative build; the review sequence recorded below)

## What shipped

- **E8, the console cell — measured in the Browser pane, 2026-09-11.** Setup through the user-service
  bootstrap API (no console login needed for it): `viewer` rebound to `alice-role` on the Demo team
  (WRITE+TAG on catalog/category/product, `region:[apac]` ∧ `sensitivity:[public]`, ALL_OF); the
  maintainer logged in as `viewer`, the agent drove the console.
  - The Demo catalog opens (the root-read exemption), "No categories visible to you in this catalog."
    (EMEA `region:emea` and APAC `region:apac` both fail ALL_OF); `+ New category` is offered.
  - **Name only** → the console shows `create failed: 403 — Access denied`, no optimistic row; the
    wire: `POST …/categories` → **403**, `application/problem+json`, `errorCode: ACCESS_DENIED`.
  - **The same form with `sensitivity: public` + `region: apac`** → **403** again (the untagged root:
    placement, not payload). A `fetch` probe on the viewer's own session with the exact payload
    `{"name":…,"tags":{"region":["apac"],"sensitivity":"public"}}` → **403** `ACCESS_DENIED` — the QA
    note's reproduction row 7, until now inferred, is **measured**.
  - **A product under EMEA** with the matching payload (the QA note's row 5) → **403**; `GET` EMEA → 403.
  - The Demo catalog's children after every attempt: exactly `EMEA region`, `APAC region` (DB count 2).
  - **Control:** the maintainer switched to `editor`; the same name-only form → **201**, the row rendered
    (`e8-control-editor`, no tags); removed via the editor's session (`DELETE` → **204**); the list is
    back to the two seeded categories. `viewer` rebound to `demo-viewer` (roster: editor/owner,
    demo/demo-editor, viewer/demo-viewer).
- Records: [[PRE-HABR-UI-QA-2026-09-10]] DEF-1 → fixed; ADR 0034 status → implemented; `USER-STORIES.md`
  C5 ✅; the roadmap line; the package index (status + ticket table).

## Tests

**The final branch, re-proven after the review fold-in (2026-09-11):** `./gradlew build` — **1,275 tests
across the eight modules, 0 failures** (core 130 · spring-security 227 · spring-data 141 ·
keycloak-directory 8 · starter 80 · catalog service 290 · user-management 260 · mcp 139); `opa test
infra/opa/policies` **451/451**; the catalog + MCP images rebuilt on the final tree and the pods
recreated on them (`ENABLE_MCP=1 ./deploy.sh up --pods 2`); `run-tag-matrix.sh` **25 requests / 46
assertions / 0 failed**; `run-demo-world-matrix.sh --skip-matrices` green; local Sonar **CLEAN**;
`check-collection-conformance.py` 18 clean; `check-shell-guards.py` 27 clean; `verify-package.sh`
PACKAGE OK before the folder move.

## Architecture review + refactor

**The layer-3 review, 2026-09-11 (the maintainer's routing under the usage limits: two Fable
single agents, read-only, in parallel — not the multi-lens fan-out; ~0.29M + ~0.24M tokens).**

- **Agent A, all eight deep-review lenses at once** (fail-closed authz · core boundary · rego ·
  persistence/concurrency · security audit · API contract · CI/dead code · infra/e2e), primed with
  the repo's Mulch review patterns and ADRs 0009/0022/0031/0032; it re-ran `opa test`, mutation-probed
  five conjuncts (12/18/6/14/12/2 cells fall), diffed the two policies for sibling drift (empty),
  proved partial-eval residuals byte-identical to main, and traced every new manager branch.
  **BLOCK ×1**: the unconfined placement parent was a **cross-tenant tag-match oracle** — a member of
  catalog A could name any category id of catalog B as `parentId` and read its tag match off the
  403-vs-404 pair; the ADR's "identical to the instance GET" parity claim was false (the GET resolves
  the role on the foreign root and answers a uniform 403). **SHOULD-FIX ×1**: the "no fallback to
  `root_attributes`" invariant was unpinned (a fallback mutant survived 447/447). Nits: E2E-TESTING's
  request counts, the runner's restart resetting a live flag override, the ADR's `Map.copyOf`
  mechanism sentence, the parent pair silently honored on instance forms, 7a's "both flag states"
  name, the 7e-ii/7d-iv coupling.
- **Agent B, the security delta on the published surface**: a 448 + 240-cell main-vs-branch matrix
  (`opa eval`, eight role shapes × the request shapes) — **0 widening cells, 92 narrowing, 0 eval
  errors**; 22 hostile payload shapes on the type-level decision — no eval error, only `abac_deny:true`
  moves a decision, and only closed; SpEL takes annotation strings only. **SHOULD-FIX ×3**: the same
  oracle (S1, with the fix "make foreign and mismatching parents indistinguishable"); **`deny_reason`
  keyed on the instance grant** — a supervised WRITE-holding tag-requiring role on a production root with
  a mismatching parent was minted a challenge that elevation could never clear (ADR 0030 §7's loop;
  latent, no shipped role reaches it); a **pre-existing** self/descendant re-parent answering 500.
  Nits: `withRoleResourceOverride` dropping the parent coordinates (order-dependent), `abac_deny` now
  reading a client-controlled key (closed direction), the ADR's "at every mutation" overclaim, no
  request-body bound at the gateway.

**Folded (this commit):** *confinement* — `enrichWithParentAttributes` proves a parent only when it is
the governing target or its ancestor chain's root is that target (no target / no chain supplier / a
walk that throws ⇒ unproven ⇒ absent), so a tag-requiring role answers a foreign parent exactly as a
mismatching one and a no-requirement role keeps the body's 404 (U19b ×4, I6b; the ADR's Confinement
paragraph and the corrected Costs sentence; the annotation javadoc; the two guides);
`request_granted` in both policies — the instance clauses off strict requests, the coarse list grant,
the placement conjuncts — and `deny_reason` keyed on it (U23 ×2); the no-fallback pin (U6b ×2);
`withRoleResourceOverride` carries the parent pair; the `abac_deny` comment; the ADR mechanism and
"at placement time" wording; the instance-form note; E2E-TESTING's counts; the runner's flag note;
7a's name. **Deferred to the backlog** (pre-existing, outside the diff): item 11 (the self/descendant
re-parent 500 → map `AncestorResolutionException`) and item 12 (no request-body bound).

Filled at the ticket's checkpoint: the review path used (the collaborative build's per-ticket read-through; the slice-level passes are recorded in STATUS-05), what it found, what was refactored (or "nothing substantive").

## Integration / e2e

E8 above (the packaged SPA through the gateway, `viewer` and `editor` logins by the maintainer).

## Decisions

## Commit

The ship commit (`docs(tag-gated-create): ship — …`): the package folder moved to `implemented/`, the
index and STATUS frontmatter flipped to `status/done`, the ADR index row to "implemented". The slice's
commits on the branch: `58bff42` design + ADR 0034 + package · `e306b7c` / `e4c0706` / `b867abe` the
three delta-check fold-ins · `4f0493c` T1 · `cfa6395` T2 · `a7b37c4` T3 · `61c3c11` T4 · `45043f9` T5
records · `43d3a94` the review fold-in · plus the Mulch syncs. Push, PR and merge are the maintainer's.
