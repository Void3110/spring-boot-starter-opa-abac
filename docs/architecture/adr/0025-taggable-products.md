---
tags:
  - status/active
  - type/decision
  - area/security
  - area/abac
  - area/architecture
---

# ADR 0025 — Taggable products (strict leaf semantics, filtered list)

**Status:** Accepted (shipped 2026-07-11, on the product-tags branch)
**Date:** 2026-07-11
**Context tags:** taggable products, `product:assign-tags`, data-filter cut, leaf semantics, ADR 0022 boundary, tag-on-create

> Opens the last deliberately-closed tag surface: products. Until now "product carries no tags" was a
> load-bearing design fact — no `tags` on the wire contract, no `product:assign-tags` verb, and a
> **plain unfiltered list page** justified by "rows have no policy variance." The demo SPA needed a
> tag panel on product creation; honoring it honestly meant opening the whole chain. Decided with the
> owner in-session.

## Context

Catalogs (ADR 0022) and categories carry dictionary-validated tags; products did not — by decision,
not omission. The persistence layer always had the capability (the secured base's JSONB `tags` column
and GIN index ship on all three tables), but everything above it was closed: the OpenAPI
`Product`/`ProductRequest` schemas had no `tags` field (so a tags delta was *not constructible* and
the product PUT kept a static `product:update` annotation), `ProductEnrichable` excluded
`assign-tags`, and `product:list` returned a **plain repository page** — correct only while product
rows had no policy variance.

Making products taggable breaks that justification: a role with `requiredTags` would see product rows
in the list that it may not read one-by-one — the list and the single-GET would disagree, the exact
inconsistency the category filter exists to prevent.

## Decision

Products adopt the **category tag pattern end to end**, with two forks pinned:

1. **Strict leaf semantics — the list gets the data-filter cut.** `product:list` moves off the plain
   page onto the partial-eval residual (`ProductListAuthorizer`, mirroring the category one;
   `product.rego` gains the `filter` entrypoint, role-definition-only, PE-inline idiom). ADR 0022's
   root-read exemption stays **root-only**: there is **no leaf-read exemption** for products, exactly
   as there is none for categories. A tag-gated role's list shows only rows whose tags satisfy it.
2. **Tag-on-create is accepted** (unlike catalog create, which stays a 422): a product's governing
   team — its catalog's — exists before the product does, so the type-level `product:assign-tags`
   decision resolves through the governing root, same as categories.

The PUT adopts the `TagDecisionGate` delta dispatch (content → `update`; tags → `assign-tags`; both →
both; empty → `update`), `ProductEnrichable` gains `assign-tags`, and the demo SPA reaches full
parity (create-panel tag fields, tag chips, per-card assign-tags editor).

## Consequences

- **PUT full-replace semantics now cover products**: a PUT without `tags` clears them. Every client
  must echo the current tags unless clearing is the intent (the SPA's update button was fixed to echo
  `sku` + `tags` in the same change).
- **The product PUT's missing-id answer flips 403 → 404** (the load precedes the in-handler
  dispatch), joining the catalog/category PUTs as the bounded id-existence-oracle trade-off
  (PERMISSION-MODEL.md). The gate-window race pin (`ResourceResolutionGateIT` I4) survives on the
  product PUT because its version guard runs inside `mutate()`'s locked transaction.
- **Perf profile change**: the product list is now a filtered cut (residual + batch recheck instead
  of a bare page). Re-baselining PERFORMANCE.md folds into the single post-SB4-port pass (7.x plan) —
  do not re-run perf for this alone.
- The e2e tag matrix covers categories; product tag cells are a known conformance gap to close when
  the matrix is next extended.
