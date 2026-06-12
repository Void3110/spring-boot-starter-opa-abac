---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T7: docs: ATTRIBUTE-RICH-PRE-AUTHORIZATION guide + reconciliations + record close

**Status:** ✅ DONE

## What shipped

- **New guide `docs/guides/ATTRIBUTE-RICH-PRE-AUTHORIZATION.md`** (D1): the mechanism (split SPI →
  governing-root role → context → write-through cache), the split fail-closed semantics table, the
  version-binding doctrine + layered-windows note, the 5-step adoption recipe (one bean, zero
  annotation changes; URL scoping stays in the handler), and **all four caveats**: the kill-switch
  restores baseline (attribute-keyed denies only enforced while on), unversioned resources fail loud
  / undetected-window documented, **the supplier-outage scope-out** (fold-in #2 — tracked, not
  fixed, `HttpRoleDefinitionSupplier` byte-identical), and the missing-id 403 posture (with the
  no-existence-oracle argument).
- **Reconciliations** (D2): [[TAG-BASED-AUTHORIZATION]] — the load-then-check section rewritten as
  "resolved at the gate" (CategoryAuthorizer deleted); [[HIERARCHICAL-AUTHORIZATION]] — a
  "gate vs programmatic" callout after the adoption recipe; [[ABAC-AUTHORIZATION]] — the
  pre-invocation paragraph now describes the resolver opt-in and the redrawn ADR-0006 2/3 boundary;
  `OpaPreAuthorize` Javadoc — "a later phase" replaced with the resolver behavior + the 403 posture;
  [[E2E-TESTING]] — the resource-resolution matrix section (cells table + the one suite-wide flip);
  `infra/README.md` — the matrix run + the product/catalog rego restart note. ADR 0006/0013 bodies
  untouched.
- **The record** (D3): [[USER-STORIES]] C4 ✅; [[POC-ROADMAP]] 5.97 shipped (narrative + Related,
  next 6.5); the [[RESOURCE-RESOLUTION]] index — frontmatter `status/done`, the Shipped banner,
  ticket table ✅ T1–T7; folder `git mv`-ed to `docs/to-do/implemented/RESOURCE-RESOLUTION/`.
- **Mulch**: three records — the ADR-0013 gate-resolution shape (foundational), the version-binding
  + layered-windows pattern, and the missing-id-403 decision (with the "sweep the status-code
  contract of preempted paths" lesson); plus the T3 standalone-advice pattern and the T6 ltree-path
  seeding failure recorded at their checkpoints. Synced with `git restore --staged .` first each time.

## Tests

D1–D3 are doc-presence checks: guide exists with valid frontmatter and all four caveats; the
reconciled guides reference [[ATTRIBUTE-RICH-PRE-AUTHORIZATION]]; the Javadoc no longer says "a later
phase" (grep clean); index/roadmap/stories flipped. Wikilink targets verified against
`docs/guides/` + `docs/architecture/adr/` filenames.

## Architecture review + refactor

Docs-only ticket. The guide name avoids the Obsidian wikilink clash with the RESOURCE-RESOLUTION
index note (decided at decomposition). Nothing refactored.

## Integration / e2e

Not applicable (docs). The clean-room scan runs in the final verification before the checkpoint.

## Decisions

- The supplier-outage caveat is phrased as *can widen to the realm role under the HTTP role source* —
  precise about the interplay without promising a fix this slice doesn't ship (B2's posture).

## Commit

`docs(resource-resolution): guide + reconciliations + slice record close (T7)` — see `git log` on
`feature/void3110/resource-resolution`.
