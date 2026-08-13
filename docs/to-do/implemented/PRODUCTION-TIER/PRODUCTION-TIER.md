---
tags:
  - status/done
  - type/index
  - area/abac
  - area/opa
  - area/spring
---

# PRODUCTION-TIER — the tier decides how deep oversight goes

> ✅ **SHIPPED 2026-08-13** — branch `feature/void3110/production-tier` (PR #105), T1–T6 all green,
> orchestrated two-part autonomous run + layer-3 adversarial review (Approved with fixes —
> [[../../../code-review/PRODUCTION-TIER-REVIEW|review note]]: 16 findings fixed in-branch, headline
> the shadow-`env` guard + the array-shape tier deny). `./gradlew build` ✅ · `opa test` **308/308** ·
> production-tier matrix **61 assertions, 0 failed** · supervised-scope **42 + 6, 0 failed** ·
> Sonar clean (documented FP classes only). The operator-managed `env` tag on the governing root,
> carried to child decisions as `root_attributes`, opens supervised **non-production** contents by
> **direct grant** and closes **production** and **unproven** tiers; members structurally unaffected.
> Slice C (STEP-UP-ELEVATION) is next.
>
> **Validated:** 2026-08-07 — mechanical + adversarial clean; 2 delta checks after 2 amendments
> (full fan-out: 37 agents, 30 confirmed findings incl. 2 run-stoppers, all fixed; the terminal
> delta check returned no defects).
>
> Slice **B of three** in the supervisor epic (Phase 10 of [[POC-ROADMAP]]).
> Implements [[0030-step-up-decision-contract|ADR 0030]] **§1–4**: an operator-managed `env` tag on the
> governing root, carried to child decisions by root-attribute enrichment
> ([[0032-root-attribute-enrichment-input-contract|ADR 0032]]), opens supervised **non-production**
> contents and leaves **production** contents denied — a plain 403 until slice C adds the structured
> deny and the challenge. Members are structurally unaffected.

## Why this slice exists

[[SUPERVISED-SCOPE]] (slice A, shipped 2026-08-07) gave a unit manager a correct, live, read-only
**list** of their unit's catalogs — and deliberately closed contents outright: the synthesized role
grants nothing on `category`/`product`, and [[0031-inheritance-confined-to-membership-roles|ADR 0031]]
makes that closure real. But oversight that can never open anything is a directory, not oversight.
The consumer's requirement is a **tier**: routine environments open to a supervisor without ceremony;
production detail protected — eventually by a second factor (slice C), and in this slice by a hard
deny that only an operator can lift, because only an operator can tag.

**The headline:** `sup-anna` opens her report's **staging** catalog down to its products with no
ceremony; the operator flips that catalog to `production` and her very next child read is refused;
and nothing its owner can do through the API — assign, re-value, strip — touches the `env` tag that
makes it so (`409 TAG_OPERATOR_MANAGED`).

## The pins (settled 2026-08-07; full rationale in [[00-DESIGN]] + ADR 0030 §1–4 + ADR 0032)

1. **Authority stays in the role**: `SupervisorRoles` widens to
   `{catalog, category, product: ["READ"]}` — contents open via ordinary **direct grant**, never by
   re-opening inheritance ([[0031-inheritance-confined-to-membership-roles|ADR 0031]] stays exact).
2. **The tier deny is deny-shaped and provenance-scoped**: two `denied` clauses per leaf policy —
   supervised + `root_attributes` **absent** (unproven ⇒ closed) and supervised + `env=production`.
   Members cannot reach either clause, even during an enrichment outage.
3. **Three input states, never merged** (ADR 0032): absent = fetch failed · `{}` = untagged
   (non-production, per ADR 0030 §3) · tagged = as tagged. `NON_NULL` serialization — `NON_EMPTY`
   would silently merge the first two.
4. **Enrichment fails narrow, not loud**: a root-fetch failure omits the field — the supervised path
   closes, a member's read proceeds. No new 5xx class.
5. **`operatorManaged` is not client-authorable**: it appears in no request schema; `env` is seeded
   (`is_system` + `operatorManaged`); the only write path is the catalog service's **first internal
   *bootstrap* (write) endpoint**, `POST /internal/bootstrap/resource-tags` (merge-upsert,
   in-network only; its one existing internal surface is the read-only ownership resolve).
6. **The E6 flip is deliberate**: A's matrix cells asserting untagged supervised contents 403 are
   rewritten to the B contract (untagged ⇒ open); the closed-contents proof **moves** to B's matrix
   (production cells + the tier-flip liveness cell), it does not vanish.
7. **Supervised child rows carry no `_actions` map in B** — the bulk path has no root context, so
   omit-on-all-false omits it (never a fabricated `view:false`); members untouched; asserted in e2e
   as the contract. Threading root context through the enrichment advice is slice C's work ([[00-DESIGN]] §5).

## Headline proof

**E-tier (E1/E2/E4)** — `sup-anna` reads categories and products of a report's `staging` catalog
(200, exact ids), is refused on the `production` sibling (403), and is refused on the very next
request after the operator flips `staging → production` (the liveness cell). **E-strip (E5)** — the
supervised owner's attempt to strip `env` from his own catalog returns `409 TAG_OPERATOR_MANAGED`,
asserted on the code. **E-member (E6)** — a member reads their own team's production contents
unelevated, unchanged (200).

## Tickets (status table)

| # | Title | Status |
|---|---|---|
| T1 | user-service: the `operatorManaged` dictionary flag + the `env` seed + the internal projection carries it | ✅ DONE |
| T2 | catalog-service: `TagDefinitionView` carries the flag + operator-managed write rejection (`TAG_OPERATOR_MANAGED` 409) + the `/internal/bootstrap/resource-tags` operator endpoint | ✅ DONE |
| T3 | library (additive): `Resource.root_attributes` + manager-side governing-target enrichment (ADR 0032, amended §Population) | ✅ DONE |
| T4 | the widened supervisor role + the four tier-deny clauses + `opa test` (three states, member-unaffected, one mutation guard per clause site) | ✅ DONE |
| T5 | catalog-service ITs: the four child endpoints' tier behavior below the rig (recorded input shapes, failure states, the memo ride — I5–I8) | ✅ DONE |
| T6 | e2e: the `ffff…` production-tier matrix + the E6 flip in A's matrix + non-regression enumeration + the guide delta | ✅ DONE |

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the six settled forks, fail-closed posture, considered-&-rejected, execution parts. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path. *(written by /decompose)* |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. *(written by /decompose)* |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. *(written by /decompose)* |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. *(scaffolded by /decompose)* |

## Conventions

- **Fail-closed floor for the gated path is deny** — tier unproven or production ⇒ the supervised
  child read is refused; no error path widens.
- **Additive library change only**: the `ancestors` evolution pattern (compat constructors, absent =
  byte-identical wire); every existing library test stays unchanged-green.
- **Policy edits confined to `category.rego` + `product.rego`** — the non-mirrored pair; the
  mirrored bundle (`permissions.rego` + `permission_categories.json`) is untouched (no new verb,
  ADR 0030 §1).
- **Zero realm diff** — no new accounts, no flows; TOTP and everything Keycloak is slice C.
- Clean-room: the consumer is never named; write "the first consumer" or omit.

## Related

- [[POC-ROADMAP]] — Phase 10, slice B.
- [[0030-step-up-decision-contract]] — §1–4 this slice implements; §5–9 are slice C's.
- [[0032-root-attribute-enrichment-input-contract]] — the input contract this slice adds.
- [[0031-inheritance-confined-to-membership-roles]] — the line this slice deliberately does not blur.
- [[SUPERVISED-SCOPE]] — slice A, whose matrix this slice knowingly rewrites (the E6 flip).
