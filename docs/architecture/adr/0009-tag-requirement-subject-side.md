---
tags:
  - status/active
  - type/architecture
  - area/abac
  - area/opa
  - area/spring-data
---

# ADR 0009 — The tag access requirement is subject-side (on the role), not resource-side

**Status:** Accepted — refined by [[0022-root-read-tag-exemption|ADR 0022]] (2026-07-09): READ-level
verbs on the **governing root** are exempt from the requirement by default (a `data.config` flag);
this ADR's subject-side model is otherwise unchanged.
**Date:** 2026-06
**Context tags:** ABAC, OPA, tags, fail-closed, partial-eval row filtering, AWS-IAM vs Keycloak

> This ADR pins *where the tag access requirement lives* — a structural fork that until now lived only in
> prose ([[TAG-BASED-AUTHORIZATION]]) and an implementation detail of the Rego. It records *why* the
> requirement is carried by the **role** (`requiredTags` + `matchMode`) while a resource carries plain tag
> **values**, and *why the resource-side ("a resource demands a clearance") model was deliberately not
> taken*. Expensive to reverse (it shapes the `RoleDefinition` schema, the Rego `allow`/`filter` rules,
> and the partial-eval residual) and surprising to a reader who expects per-resource access rules.

## Context

Tag-based grants (Phase 4.5, [[TAG-DICTIONARY]]) let tags gate access. There are **two legitimate ABAC
shapes** for expressing "tags gate access," and they are not the same knob in two places — they answer
different questions and have different security defaults:

- **Model A — subject-side (requirement-on-the-role).** The **role** carries `requiredTags` + a `matchMode`
  (ALL_OF/ANY_OF); a **resource carries plain tag *values*** (`region=emea`). Access is granted iff the
  resource's tags satisfy the role's requirement. *"This role grants its permissions only on resources
  whose tags match."* A role with no `requiredTags` sees everything its permissions cover — tags
  **narrow** reach. This is the **AWS IAM ABAC** shape (`ForAllValues`/`ForAnyValue` == ALL_OF/ANY_OF).
- **Model B — resource-side (resource-gated clearance).** The **resource** carries required tags + a
  combinator (it *demands* a clearance); the **subject/role** carries the clearances it holds. An untagged
  resource is **open to all**; tagging it *restricts* it. This is the **Keycloak Authorization Services**
  shape (a permission attaches policies *to the protected resource*) and the Bell-LaPadula clearance-label
  pattern.

A reader could reasonably expect Model B — "when I tag a resource I pick the rule for accessing it." The
question is which model this project commits to.

This decision is backed by multi-source research (NIST SP 800-162, AWS IAM ABAC, Keycloak Authorization
Services, OASIS XACML 3.0), verified adversarially. NIST confirms **both** are valid ABAC; the deciding
factors for *this* project are its two load-bearing properties: **fail-closed** authorization and a
**partial-evaluation row filter** as the headline data-filtering feature (ADR 0005).

## Decision

**The tag access requirement is subject-side: it lives on the `RoleDefinition` (`requiredTags` +
`matchMode`). A resource carries only plain tag *values*.** Concretely:

- `RoleDefinition` carries `requiredTags {tagKey -> [acceptable values]}` + `matchMode` (ANY_OF/ALL_OF).
- The Rego matches the **role's** requirement against the **resource's** tag values:
  ANY_OF ≡ `some … in` (existential), ALL_OF ≡ `every` (universal).
- **No requirement ⇒ vacuously satisfied ⇒ the role's full (permission-bounded) reach.** Tags only ever
  **subtract** from what a permission already grants; they never grant from an empty baseline.
- The **same requirement** drives the Phase-5 list filter: it compiles (OPA Compile API) into a residual
  `WHERE` over the resource's `tags` JSONB column — the requirement *is* the row predicate.

`requiredTags` is a **per-request filter on the resource**, evaluated every time a member touches a
resource. It is **not** consulted when assigning a role to a member (that is team/ownership governance,
ADR 0003 / [[TEAM-BASED-AUTHORIZATION]]).

**Fail-closed posture (the reason this side was chosen):** the genuine empty result comes from **no role
definition** (the `filter` rule requires `has_role_definition` → unsatisfiable residual → `DENY_ALL`), not
from "a role with no tags." Every degenerate input lands on *deny/empty*, never on a wider result.

## Considered options

| Option | Why not |
|--------|---------|
| **Model B — resource-side clearance (Keycloak-style):** the resource carries required tags + combinator; the role carries clearances; an untagged resource is open to all. | Its natural default — *"untagged resource is open to everyone"* — is **fail-OPEN**, the one bug class this project is built to exclude. AWS documents the identical hazard: `ForAllValues` (universal quantification) is **vacuously true over an absent/empty key** ("returns true if there are no context keys … or … a null dataset"), prescribing an explicit `Null` existence guard. In a partial-eval row filter this becomes "a resource with no clearance tag produces no residual → ALLOW_ALL → table-wide leak." Adopting Model B here would require *inverting* its default to fail-closed and adding subject-tag-vs-resource-tag matching (currently deferred) — a larger, riskier change for a model that fits the row filter worse. |
| **Put `matchMode` on the resource (at tag-assignment), keep `requiredTags` on the role** | Splits one decision across two authorities and changes its meaning: `matchMode` quantifies over *the role's required keys*, so it is a property of the role's requirement, not of the resource's values. A resource-chosen combinator answers a different question (how the resource's own tags combine) and can't be reconciled with the role-side requirement without ambiguity about which wins. |
| **Match tags in Java (load resource, compare in the service)** | Loses the OPA-native expression of ABAC and — decisively — does **not** reduce to a SQL residual, so it cannot drive the partial-eval list filter (ADR 0005). Match-in-Rego is what lets `allow` and `filter` share one requirement (ADR 0004). |
| **No tag requirement at all (permissions only)** | Leaves authorization unable to say "you may touch resources that look like *this*" — the attribute half of ABAC. Tags exist precisely to add controlled resource attributes to the decision. |

## Consequences

- **Good:** fail-closed by construction (no role def → `DENY_ALL`; no requirement → permission-bounded
  reach; tags only subtract); the requirement composes **natively** with the row filter (one requirement →
  one residual `WHERE`); AWS-IAM-aligned, so it scales without policy edits as resources are tagged; one
  requirement shared by the single-decision `allow` and the list `filter`.
- **Cost:** less intuitive for a *resource owner* who wants to self-declare "only cleared people see this
  row" — that mental model is Model B's. We accept that: the policy author (role) holds the model, not the
  resource owner. Documented with a truth table in [[TAG-BASED-AUTHORIZATION]] so the model is unmistakable.
- **Boundary / future work:** a **resource-declared clearance** capability (Model B, *with its fail-open
  default inverted to fail-closed*, plus subject-tag-vs-resource-tag matching) is a coherent **future-slice
  candidate** — it would get its own ADR superseding/extending this one if pursued. It is **not** built.
  Subject-tag-vs-resource-tag equality matching remains deferred ([[TAG-BASED-AUTHORIZATION]] → Boundaries).

## Related
- ADR 0004 (the dynamic tag dictionary + match-in-Rego this requirement uses) · ADR 0005 (the partial-eval
  row filter this requirement compiles into) · ADR 0003 (role ≠ grant; role assignment governance,
  distinct from `requiredTags`).
- [[TAG-BASED-AUTHORIZATION]] → "Where the requirement lives" (the truth table + worked data-flow).
- The evidence base (private research note, not in this repo): the ABAC tag-requirement-placement research
  comparing NIST / AWS IAM / Keycloak / XACML.
