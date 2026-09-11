---
tags:
  - status/done
  - type/index
  - area/abac
  - area/opa
  - area/spring
  - area/catalog-service
---

# TAG-GATED-CREATE — a tag-requiring role must not create what it cannot read

> **Status: ✅ SHIPPED 2026-09-11 — mini package, collaborative build; five tickets + the review fold-in on
> `feature/void3110/tag-gated-create` (PR by the maintainer).**
> Design settled 2026-09-11 (grill-me: eight forks; [[00-DESIGN]] §Considered-and-rejected); the
> contract is [[0034-tag-gated-placement-input-contract|ADR 0034]]. Opened from **DEF-1** of
> [[PRE-HABR-UI-QA-2026-09-10]]. Severity **Medium**: no escalation beyond the role's own WRITE, but
> it contradicts the published contract — "mutations + everything below the root stay tag-gated"
> ([[0022-root-read-tag-exemption|ADR 0022]]) and "a role may act on resources whose tags match"
> ([[0009-tag-requirement-subject-side|ADR 0009]]).
> **Build: collaborative**
> **Not an autonomous run**: no implementation prompt, no orchestrator. `verify-package.sh` reads the
> declaration above and skips its two prompt arms ([1] the prompt file, [5] prompt invariants); every
> other gate must pass. Branch `feature/void3110/tag-gated-create`.
> **Review routing for this slice (maintainer, 2026-09-11):** `/deep-review` with the lenses on Opus 5
> → one single-agent review pass on Fable → `/security-review delta` on the annotation and context
> change; the fallback if the pool is short is the single-agent pass + the security review alone.
> **Validated:** 2026-09-11 — mechanical gate green; the adversarial pass ran as **one Fable agent over
> the four angles** (the maintainer's cost decision — not the four-audit fan-out; ~0.29M tokens) and
> returned 2 run-stoppers (the direct-grant path bypassing placement; the missing ADR 0031 stamp on the
> unit fixtures) + 5 contradictions + 8 nits, all folded; then **3 delta checks after 3 amendments**
> (~0.19M / ~0.17M / ~0.10M), each still finding wording-level defects (3+4 → 1+2 → 2 nits) · STOPPED
> BY COST DECISION, not by a clean round — residual risks: the two last wording fixes (a citation, an
> `opa fmt` gate phrase) are unverified by a further check; no independent skeptic attacked the single
> agent's findings. Not residual: the amended policy shape was proven on a patched corpus (423/423,
> both policies, every U-cell probed) in the first two delta checks.

## Why this slice exists (the reproduction, measured 2026-09-10, `viewer` holding `alice-role`)

`alice-role`: permissions `{catalog, category, product: [READ, WRITE, TAG]}`, `required_tags`
`{region:[apac], sensitivity:[public]}`, `match_mode ALL_OF`. On the untagged Demo catalog:

| Call | Answer | Expected under the contract |
|---|---|---|
| `GET …/categories` (list) | `count:0` — nothing matches | ✓ |
| `GET …/categories/{EMEA}` (`region:emea`) | 403 | ✓ |
| `PUT …/catalogs/{root}` | 403 (root mutations tag-gated) | ✓ |
| `POST …/categories` `{name}` (no tags) | **201** | 403 |
| `POST …/categories/{EMEA}/products` | **201** — inside a category the role is denied to read | 403 |
| `GET` the category it just created (untagged) | 403 — a write-only spawn | — |
| `POST …/categories` `{tags:{region:[apac], sensitivity:public}}` | 201 *(inferred, not measured — the payload never reached OPA and the clause opened on the grant alone; pinned by E5/E8)* | **403** — placement: the root is untagged; a matching payload alone never suffices |

**The mechanism.** `category.rego` and `product.rego` decide every type-level request (no resource
id — a list, or a create/assign-tags before the instance exists) through one verb-agnostic clause,
`is_type_level_request; not denied; list_inheritable_grant`, which checks only that the verb is in
the role's effective actions on an inheritable ancestor type. True for **LIST**, whose rows the SQL
residual then cuts with the same tag match; false for **CREATE**, where nothing cuts afterwards and
`tags_satisfied` is never consulted — and could not be: the create input carries the **catalog's**
tags as `root_attributes` (ADR 0032) but nothing about the placement parent of a nested category or
a product, and its attribute map is **empty** (the payload never reaches OPA). Two widenings found at
design time: a category create may carry `parentId` (the parent is a category, not the catalog), and
a category update may **change** `parentId` with only an existence check (create-then-move bypasses
any create-only fix).

## The pins (settled 2026-09-11; full rationale in [[00-DESIGN]])

- **Placement by tags.** A type-level create/assign-tags by a tag-requiring role passes only when the
  placement parent's tags **and** the payload's tags satisfy the requirement; the root is included
  (denied under an untagged root in **both** flag states — the exemption widens reads, not
  mutations); a role without a requirement is unchanged; the rule never demands READ; it applies
  **whichever way the verb is granted** — directly on the child type or through an inheritable
  ancestor (the instance clause `granted` stops applying to strict type-level requests).
- **Re-parent is a placement** — a second create-shaped decision on the new parent; the instance
  update decision is untouched.
- **`parent_attributes`** — a sixth additive component on `AbacContext.Resource`, the `root_attributes`
  pattern (three states, `NON_NULL`, manager-side, memoized), populated from three new optional
  `@OpaPreAuthorize` SpEL attributes (`parentResourceType`, `parentResourceId`, `attributes`);
  **no fallback** to `root_attributes`; declared-but-unresolvable ⇒ deny, resolver failure ⇒ absent.
- **LIST is the only coarse type-level verb**; every other type-level verb is strict (fail-closed for
  future verbs). The TAG verb keeps its separate type-level decision.
- **Authorization on the raw payload precedes dictionary validation** — a 403 never leaks a key.

## Tickets

| Ticket | Scope | Status |
|---|---|---|
| T1 | the placement gate in `category.rego` + `product.rego`, the shared match helper, U1–U12, the guide's Rego section | ✅ |
| T2 | `parent_attributes` on `AbacContext.Resource`, the three annotation attributes, manager population, U13–U22, the guides | ✅ |
| T3 | the example gates' declarations, `requireCategoryPlacement` on re-parent, `TagGatedCreateIT` (I1–I6), the guide's gate section | ✅ |
| T4 | the tag matrix's `7a`–`7g` — `gated-writer` (+TAG, the inheritable path) and the `gated-direct` rebind (the direct path), the OPA restart, the README matrix row, conformance (E1–E7) | ✅ |
| T5 | the pane row (E8), the close-out records, the review sequence, the ship commit | ✅ (E8 + records; reviews recorded in STATUS-05) |

## Files

| File | Role |
|---|---|
| [[00-DESIGN]] | the design — the rule, the input contract, the policy split, the example gates, the three-tier ratchet, the grill's rejected forks |
| [[01-DECOMPOSITION]] | the ordered tickets T1–T5 (Goal / Deliverables / Acceptance / What-NOT-to-touch), the critical path, the cross-cutting acceptance |
| [[10-QA-TEST-CASES]] | U1–U22 · I1–I6 · E1–E8 — every ticket's acceptance cites these |
| `STATUS-01` … `STATUS-05` | one record per ticket, filled at its checkpoint |
| [[0034-tag-gated-placement-input-contract\|ADR 0034]] | the decision, written up front |

## Critical path

T1 (policy) ∥ T2 (starter) → T3 (example gates + IT) → T4 (e2e cells) → T5 (pane row + close-out).
T1 and T2 are each independently landable: T1 alone denies every tag-requiring create on the rig
(the fail-closed interim; demo personas unaffected), T2 alone changes nothing observable.

## What this slice does not do

Catalog create (no parent, no tag-on-create); product moves (impossible by route shape); a
denial reason distinguishing "parent unproven" from "parent mismatch" (both deny; the states stay
pinned in tests); any change to `filter`, the SQL residual or the list gates; the SPA; the Central
cut (1.3.0-SNAPSHOT stays open).

## Conventions

Clean-room (neutral names, nothing proprietary); commit identity `Void3110 <void31102025@gmail.com>`
(repo-local); Obsidian wikilinks; the rig: OPA never watches its mount (`docker restart opa-abac-opa`
after T1), `./deploy.sh build` after T3 (an `up` reuses an existing image), `ENABLE_MCP=1` on the
same `up` as everything else; Gradle from the Bash tool needs `-Dorg.gradle.jvmargs=-Xmx2g`.

## Related

- [[00-DESIGN]] · [[01-DECOMPOSITION]] · [[10-QA-TEST-CASES]]
- [[0034-tag-gated-placement-input-contract|ADR 0034]] · [[0032-root-attribute-enrichment-input-contract|ADR 0032]] · [[0022-root-read-tag-exemption|ADR 0022]] · [[0009-tag-requirement-subject-side|ADR 0009]]
- [[PRE-HABR-UI-QA-2026-09-10]] — DEF-1, the evidence · Mulch `rego-policy` failure record (2026-09-10): the class and the diagnostic tell
- [[TAG-BASED-AUTHORIZATION]] · [[POC-ROADMAP]]
