---
tags:
  - status/planned
  - type/architecture
  - area/user-service
  - area/abac
---

# Research — auto-tagging, the dynamic tag dictionary, and list filtering

> **Status:** Research notes for [[USER-MANAGEMENT-SERVICE]] (Phase 4) and the Phase-5 list-filtering
> work. These are **generalized, study-only** mechanics captured so the dynamic-dictionary design has
> them on hand. No proprietary source/names are reproduced — only the reusable shape of the ideas.
> Cross-references: the [[LIBRARY-SPINE]] slice (Phase 3) lands `RoleDefinition` +
> `RoleDefinitionSupplier`, which this service will feed.

## Why this note exists

While planning the [[LIBRARY-SPINE]] slice we traced how the prior platform manages tags and filters
list endpoints. That mechanism is **out of scope for Phase 3** (which does single-decision
authorization), but it directly informs two later pieces:

1. the **dynamic tag dictionary** the user-management-service owns (Phase 4), and
2. the **list ACL filtering** (partial-eval → JPA `Specification`) that is Phase 5.

Capturing it here keeps the research from being lost between phases.

## 1. Declarative auto-tagging (`@AutoTag` + a processor)

A generalizable pattern for setting resource tags *automatically* rather than by hand:

- An **`@AutoTag` / `@AutoTags`** annotation on an entity (class/field) declares a tag's `key` and how
  its value is produced: a **static value**, a **field value**, or a **mapper** (`TagMapper<T>`) that
  derives the value (e.g. an "environment" tag computed from a set of supported tiers). Optional
  `priority` / `override` ordering.
- A **tag processor** runs on the JPA `@PrePersist` / `@PreUpdate` lifecycle (an entity listener),
  reads the annotations, resolves each value (static / field / mapper), and writes the resulting tags
  into the entity's `tags` (the existing `ResourceTags` value object from [[DOMAIN-MODEL]]).
- **Clean-version improvement:** the source hardcodes mapper logic (e.g. tier→environment) in code; the
  generalized version should let mappers pull from **external configuration**, not just entity fields.

> Status in this repo: **deliberately deferred** (see [[DOMAIN-MODEL-FOUNDATION]] "considered &
> rejected" — `@AutoTag` was judged "large machinery, not needed to prove the base stack"). Tags are
> set explicitly today. Auto-tagging is the natural companion to the dynamic dictionary below.

## 2. The dynamic tag dictionary (the Phase-4 "done properly")

The source platform hardcodes its tag keys as constants. This service's improvement is a
**runtime-editable dictionary**:

- a first-class **dictionary entity** — `key` + a **validation rule** (enum / regex / custom) per key;
- CRUD endpoints to manage it; tags applied to users/teams validated against it;
- distinguish **subject/user** tag keys (attributes about *who is asking* — e.g. tier, department) from
  **resource** tag keys (attributes about *what is accessed* — e.g. owner, members, status). Conflating
  the two produces phantom decisions.

This is what makes the demo "data-driven, not hardcoded enums" — the dictionary can change at runtime
and the change shows up in decisions.

## 3. List ACL filtering (Phase 5 — partial evaluation → JPA `Specification`)

The "interesting" list-endpoint mechanism: instead of fetching everything and filtering in memory, ask
OPA to **compile the policy into SQL-like conditions** for the current subject, and push those into the
query. Two layers:

- **OPA partial evaluation → JSONB JPA `Specification`.** OPA returns residual *conditions*
  (`{field, operator, value}` with a join operator), which a `Specification` translates into JPA
  `Predicate`s over the `jsonb` `tags` column — operators like `=`, `in`, and `json_contains` (array
  membership) via `jsonb_extract_path_text(...)`. `allowed=false` ⇒ match nothing; `allowed=true` with
  no conditions ⇒ match everything.
- **Optional post-fetch allowlist filter** for per-resource ACLs that don't reduce to a SQL predicate.

> This is why the [[DOMAIN-MODEL-FOUNDATION]] slice added a **GIN index** on the `tags` column already —
> the future partial-eval feature wants it. Depends on the OPA client from [[LIBRARY-SPINE]] **plus** a
> new partial-eval client method (a Phase-5 addition to `OpaClient`).

## How this feeds the spine

- The [[LIBRARY-SPINE]] slice ships `RoleDefinition` + `RoleDefinitionSupplier` with a **static demo
  supplier** in the catalog app. **This service provides the real, HTTP-backed `RoleDefinitionSupplier`**
  (a read API returning a subject's role definition for a resource) — a single-bean swap on the catalog
  side.
- The dynamic tag dictionary here supplies the **subject attributes** (and the validated resource tag
  keys) that role definitions and policies read.
- The partial-eval list filtering (Phase 5) reuses the same `tags` JSONB model and the OPA client.

## Related
- [[USER-MANAGEMENT-SERVICE]] — the Phase-4 service that owns the dictionary + the role-definition API.
- [[LIBRARY-SPINE]] — Phase-3 authorization spine (`RoleDefinition` / `RoleDefinitionSupplier`).
- [[DOMAIN-MODEL]] — the `ResourceTags` value object + JSONB mapping this builds on.
- [[POC-ROADMAP]] — Phase 4 (this service) and Phase 5 (partial-eval data filtering).
