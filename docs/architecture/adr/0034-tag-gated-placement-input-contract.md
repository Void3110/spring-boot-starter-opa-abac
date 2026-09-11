---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/opa
  - area/spring
---

# ADR 0034 — Tag-gated placement: `parent_attributes` and declared `attributes` on type-level decisions

**Status:** Accepted — implemented 2026-09-11 by slice **TAG-GATED-CREATE** (T1 policy, T2 starter, T3 example gates, T4 e2e, T5 console cell; PR pending)
**Date:** 2026-09-11
**Context tags:** tag requirement, type-level create, placement parent, input contract, fail-closed three-state, `@OpaPreAuthorize` surface

> [[0009-tag-requirement-subject-side|ADR 0009]] put the tag requirement on the role and matched every
> resource against its **own** tags; [[0022-root-read-tag-exemption|ADR 0022]] promised that "mutating
> verbs on the root and everything below the root stay fully tag-gated". A type-level **create** was
> the one decision that kept neither promise, because the input carried nothing to match. This ADR
> pins the rule and the **contract** of the two inputs that make it decidable — a change to the
> published OPA input and to the `@OpaPreAuthorize` surface every adopter sees.

## Context

`category.rego` and `product.rego` decide type-level requests (no resource id: list, create,
assign-tags-for-create) through one verb-agnostic clause — the inheritable grant alone
(`list_inheritable_grant`, ADR 0031-confined). For LIST that is complete: the rows are cut afterwards
in SQL by the same tag match. For CREATE nothing cuts afterwards, and the decision *could not* consult
the requirement anyway: a type-level `AbacContext.Resource` carries an empty attribute map (the
tag-on-create payload never reaches OPA) and, while the governing catalog's tags do arrive as
`root_attributes` (ADR 0032 threads the `roleResource` override target on these gates), the
**placement parent** — the parent category of a nested category, the category of a product — is
fetched nowhere. Measured 2026-09-10 (DEF-1 of the pre-Habr UI QA): a WRITE-holding role with
`required_tags {region:[apac], sensitivity:[public]}` created an untagged category it then could not
read (201, then 403 on its own GET), and created a product inside a category its GET answered 403
for. A category update may also change `parentId` with only an existence check, so any create-time
fix alone is bypassable by create-then-move. No escalation beyond the role's own WRITE; a
contradiction of the published contract.

## Decision

### 1. The rule: placement by tags, root included, re-parent included

A type-level **create** or **assign-tags** on a hierarchical type by a role with a tag requirement is
allowed only when, beside the grant on the verb (direct on the child type, or inherited from a declared
ancestor — both paths must carry the conjuncts), **both** hold:

1. **The placement parent's tag map satisfies the requirement** under the role's `match_mode`. The
   parent is the resource the new one hangs from (the parent category when declared, the catalog
   otherwise; for a product, the category). **The governing root is included and the root-read
   exemption does not apply**: creating under an untagged root by a tag-requiring role is denied in
   both states of `data.config.root_read_tag_exemption`. The exemption widens *reads* of the root;
   a create is a mutation of the root's subtree — the same class as the root `PUT` that already
   answers 403.
2. **The payload's tag map satisfies the requirement.** `{}` when the request carries no tags — so a
   tag-requiring role cannot create what it could not read.

A role without a requirement passes both vacuously (nothing changes for it). The rule never demands
READ. **Re-parenting is a placement**: an update that changes a parent asks the same create-shaped
decision on the new parent before mutating; the instance update decision is untouched.

### 2. The input: a sixth additive component, and three annotation attributes

`AbacContext.Resource` gains **`parent_attributes`** on the `root_attributes` pattern:

```java
public record Resource(
        String type,
        String id,
        Map<String, Object> attributes,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ParentRef> ancestors,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("root_attributes") Map<String, Object> rootAttributes,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("parent_attributes") Map<String, Object> parentAttributes) { … }
```

Compat constructors keep every existing caller compiling; an absent value serializes byte-for-byte as
today. **Three states, all distinguishable in policy:**

| Wire state | Meaning | Placement consequence |
|---|---|---|
| key **absent** | a parent was declared but could not be proven (resolver empty/threw, no resolution support) | a tag-requiring role is **denied** (unproven ⇒ closed); a no-requirement role is unaffected |
| `{}` (present, empty) | the parent was fetched and has **no tags** | a tag-requiring role is **denied** (nothing matches) |
| `{"region": ["emea"], …}` | the parent's tags | matched as an instance's own tags would be |

`NON_NULL`, never `NON_EMPTY` — under `NON_EMPTY` an untagged parent would vanish from the wire and
merge with a failed fetch (the ADR 0032 argument). The two deny states give the same decision today
and stay distinguishable for a future denial reason; the policy tests pin them as separate cells.

`@OpaPreAuthorize` gains three optional SpEL attributes, blank by default:

| Attribute | Meaning | Fail-closed edges |
|---|---|---|
| `parentResourceType` / `parentResourceId` | the placement parent | declared but resolving to null/blank ⇒ **deny** at the manager (the `resourceId` posture: silently degrading would widen); only **one** of the pair declared ⇒ deny (the `roleResource` pair's precedent); resolver failure ⇒ **absent** field (the policy decides) |
| `attributes` | the decided resource's attribute map for a **type-level** check — the raw request tags | `null` ⇒ `{}`; a non-`Map`, a non-string key, or a map carrying a `null` value ⇒ deny (the manager rejects it explicitly before any context is built); declared together with an instance form (`resourceId`/`resource()`) ⇒ a declaration conflict ⇒ deny |

**Population is manager-side**, after the role-resource override, through the same
read-through-memoized resolve that serves `root_attributes` (generalized to `(type, id)`), via the
application's existing `AbacResourceResolver` SPI. For a top-level category create the parent *is*
the role-resource target: the memo makes the second resolve free and the two fields carry the same
map by design. **The policy reads only `parent_attributes` for placement; there is no fallback to
`root_attributes`** — a fallback is the fail-open ambiguity this decision closes.

**Confinement** *(added at review, 2026-09-11)*. A declared parent is **proven only under the governing
target** the role was resolved on: it *is* that target, or its ancestor chain's root is that target. A
parent anywhere else — another tenant's category chosen by id in the request body, a root that is not
the role's — is **unproven** and the field stays absent, so a tag-requiring role answers exactly as it
would for a mismatching parent and a status code never reveals a foreign resource's tags (the review
found the unconfined draft was a cross-tenant tag-match oracle: 403 for a mismatching foreign parent,
404 from the body's same-catalog check for a matching one). Without a governing target (no
role-resource override) or without an ancestor chain supplier, only the target itself can be proven;
a chain walk that throws is unproven, never an exception. The pair may also be declared on an instance
form; it is populated the same way and the shipped policies ignore it there.

### 3. The policy shape (reference)

LIST is the only coarse type-level verb; every other type-level verb is strict, so a future verb
lands on the closed path — **whichever way the verb is granted**. *(Amended at decomposition,
2026-09-11, verified with `opa eval`: the instance clause `granted` is not id-scoped — `direct_grant`
is "verb on THIS type ∧ `tags_satisfied`" — so a role naming the child type directly reaches a
type-level create through it, and with the payload on the wire a matching payload would pass with
no parent check. The strict clause therefore owns every non-list type-level request, and `granted`
stops applying there.)*

```rego
allow if { granted; not denied; not strict_type_level }   # instance decisions + type-level LIST via the direct path: unchanged
allow if { is_type_level_request; verb == "list"; not denied; list_inheritable_grant }   # coarse LIST via an ancestor: unchanged

allow if {
	strict_type_level        # is_type_level_request ∧ verb != "list"
	not denied
	type_level_verb_grant    # the verb on the decided type directly, OR list_inheritable_grant (ADR 0031-confined)
	parent_tags_satisfied    # over input.resource.parent_attributes — undefined when absent/non-object
	tags_satisfied           # over input.resource.attributes — the payload
}
```

`parent_tags_satisfied` and `tags_satisfied` share one parameterized helper (values as a set, key
presence not truthiness, the `ANY_OF`/`ALL_OF` rules, the malformed-`required_tags` guard) so the
two matches cannot drift; the existing `resource_tag_values(key)` entry point stays as a wrapper
(shipped test pins call it). The type-level assign-tags decision stays a separate second decision (the
only place the TAG verb is checked) and runs the same strict clause. `filter`, the instance clauses,
`catalog.rego`: untouched.

### 4. Order

Authorization runs on the **raw submitted** tag map; dictionary validation (422) runs after allow —
so a 403 never leaks whether a key exists. Safe because validation rejects but never rewrites values.

## Consequences

**Good.** The published contract is now true at placement time: a tag-requiring role can read what
it creates and could read where it creates (instance updates keep deciding on the stored map, as
before); the create outcome no longer depends on the root-read
flag; a future type-level verb is closed by default. The wire for every existing caller is
byte-identical until an annotation opts in; the library still fetches only through the app's resolver
SPI, one memoized call per request.

**Costs.** The published input schema grows a field whose three-state semantics must be documented
wherever policy authors read (`ABAC-AUTHORIZATION`, `TAG-BASED-AUTHORIZATION` §Layer 3), and the
annotation grows three attributes (`ATTRIBUTE-RICH-PRE-AUTHORIZATION`). **Adopter impact, stated
plainly:** an adopter who copies the new example policies without declaring the parent and the
attributes on their create gates sees every tag-requiring create denied — the fail-closed direction,
documented in the release notes, not softened. A create now costs one extra resolver call (memoized)
per request, plus one ancestor-chain walk when the parent is not the governing target itself; a
re-parent costs one extra decision. Two shapes stated rather than hidden: a **foreign** or nonexistent
parent is unproven, so a tag-requiring role gets the same 403 as for a mismatching one while a
no-requirement role keeps the body's 404 — the same information the same-catalog existence check gives
today, and nothing about a foreign resource's tags; and a payload carrying a `null` tag value denies at
the manager (never 422), which a request that could never validate does not deserve a gentler path for.
The step-up challenge (`deny_reason`, ADR 0030 §7) keys on the grant the request actually rides
(`request_granted`): a strict type-level request the placement gate closes is never told a fresh second
factor would open it.

**Rejected.** A "readable parent" rule (diverges at the root through the exemption and adds a hidden
READ requirement); the catalog as the parent always (wrong for nested categories and products);
carrying the parent's tags on `ancestors`/`ParentRef` (ADR 0030 §4 already declined it) or under a
reserved key in `resource.attributes` (a client-influenced namespace); a policy fallback from an
absent `parent_attributes` to `root_attributes` (the ambiguity itself); deciding on the payload after
validation from a gate method (the annotation with an empty map would deny at the door, so the
decision would leave the declaration site); enumerating the strict verbs (fail-open for a future
verb); folding the TAG check into the create decision (one verb name, two permissions); a new
conjunct on the instance update for re-parent ("absent means no move" is fail-open the day it is
forgotten); amending ADR 0009 instead of this record.
