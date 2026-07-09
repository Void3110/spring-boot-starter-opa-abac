---
tags:
  - status/active
  - type/decision
  - area/security
  - area/abac
  - area/architecture
---

# ADR 0022 — Root-read tag exemption (and taggable catalogs)

**Status:** Accepted (shipped 2026-07-09, on the 7.1 feedback branch)
**Date:** 2026-07-09
**Context tags:** `required_tags`, governing root, team-target, navigation lockout, `data.config` flag, fail-closed, taggable catalogs

> Pins the fork ADR 0009 left open: **how a role's `requiredTags` interact with the governing root**
> (the resource the team-target explicitly names). Discovered live during 7.1 manual testing: giving a
> member's custom role a tag requirement locked her out of the *untagged catalog shell entirely* —
> team member, correct role, empty grid. Decided with the owner in-session.

## Context

Under ADR 0009 (subject-side tag requirement), `requiredTags` narrow a role's **entire** reach,
uniformly across resource types: `allow = permission AND tags_satisfied`, no exceptions. The catalog —
the hierarchy root and the team-target — carried no assignable tags (the Phase-4.5 assignment surface
was categories only), so **any** role with a tag requirement stopped reaching **every** catalog shell:
its holder could no longer list or open the catalog whose categories they were entitled to. Membership
was never the grant (it only selects the role definition), so the explicit, admin-created team binding
on the named resource was silently voided by a role-level attribute filter.

Evidence this fork was never consciously decided: the decisive Phase-4.5 e2e reads **categories
directly** (never through catalog navigation), and the load harness had to **SQL-tag the catalog** so
its tag-requiring perf role could list at all — the workaround for exactly this wall. What the lockout
protects is only shell metadata (name/description): content below the root is tag-filtered per row by
partial evaluation regardless.

## Decision

1. **Root-read exemption, default ON.** READ-level verbs (`view`, `list`) on the **governing root**
   (`catalog`) are exempt from `tags_satisfied` when `data.config.root_read_tag_exemption` is `true`.
   Rationale: the team-target is an explicit direct grant on that exact resource; a reach-narrowing
   attribute filter must not void it for navigation. Mutating verbs on the root and **everything below
   the root** (categories, products — where tags actually live) stay fully tag-gated.
2. **The knob is OPA `data`, not application config.** The semantic is policy-owned; a `data` flag is
   concrete at partial-eval time, so the list residual folds clean in both states (verified with
   `opa eval --partial`: exemption ON → the tag conjunct drops, the governed-id base scope alone
   decides; OFF → the 7.0.5 tag-predicated residual). Shipped as `infra/opa/policies/config.json`
   (`true`); `ROOT_READ_TAG_EXEMPTION=0|1 ./deploy.sh up` overrides the live value via a
   `/v1/data` PUT (in-memory; an OPA restart reverts to the file). A literal `application.yaml`
   passthrough was rejected: it would need new starter input-API (against the no-starter-bloat
   decisions) and would dress a policy semantic as an app setting.
3. **An absent flag means STRICT.** Fail-closed on missing config: the exemption is a deliberate,
   bounded widening and activates only by explicit deployment choice, never by a missing file. The
   *shipped rig default* is ON (the file is committed `true`).
4. **Catalogs become taggable** (`CatalogRequest.tags` + the category-style delta dispatch:
   content → `catalog:update`, tags → `catalog:assign-tags`; enrichment gains the verb). **No
   config param for taggability**: a param would create a 2×2 config matrix and toggle API contract
   surface; the "root tags look inert" confusion under the default is handled by UI copy ("root tags
   gate this catalog's *mutations*, not members' visibility"). Tag-on-create is rejected 422 for
   catalogs — the type-level assign-tags decision resolves through the governing team, and a new
   catalog has no team until owner-on-create binds one.

## The 7.0.5 invariant, restated

The baseline security review's High was a list-vs-GET **divergence** (a tag-gated role listed catalogs
it could not view). That invariant is **agreement**, not one-sided strictness — and it holds in both
flag states: strict → both tag-gated; exempt → both membership-only. Pinned by the policy test cells
(`R5`/`R6` now pin strict explicitly; `E1–E6` pin the exemption mode and the absent/false = strict
fallbacks).

## Alternatives rejected

- **Status quo (uniform strictness) + taggability alone** — keeps the footgun: create an untagged
  catalog, hand out a tag-requiring role, lockout; now merely fixable in-UI. The usability trap
  protects nothing (shell metadata only).
- **Per-type `requiredTags`** (mirroring the per-type `permissions` map — the AWS-IAM-like
  granularity, where the role author chooses what the requirement gates). The most expressive
  long-term shape, but it reshapes `RoleDefinition` (core public API), the Rego, the residual
  compilation, and the authoring UI — too large for pre-publish polish. Revisit only with real
  adopter demand; it composes with (not replaces) this exemption.
- **Tag inheritance** (matching against resource ∪ ancestor tags) — orthogonal; doesn't fix the
  untagged-root lockout (the root has no ancestors).

## Consequences

- The alice-class lockout is gone: members always navigate to the roots their teams govern; the
  per-row tag cut below the root is unchanged and remains the demo's core contrast.
- A tag-requiring role can *see* an untagged catalog it cannot *mutate* — the enriched affordances
  say so honestly (`view ✓ / update 🔒 / assign-tags 🔒` until the root's tags match).
- Adopters copying `catalog.rego` inherit the flag and both test suites; omitting the data file
  gets them the strict model (fail-closed).

## Related

- [[0009-tag-requirement-subject-side|ADR 0009]] (the subject-side model this refines) ·
  [[0018-team-scoped-resource-isolation|ADR 0018]] (membership as the sole access path — the grant
  this exemption honors)
- [[TAG-BASED-AUTHORIZATION]] (the guide; its truth table + BYO-dictionary section) ·
  [[PERMISSION-MODEL]]
