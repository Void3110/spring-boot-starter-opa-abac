---
tags:
  - status/done
  - type/project
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — STATUS-01 — T1: the _provenance affordance (backend + ADR 0033)

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-17

## Record

### What landed

- **Spec** — `Catalog` gains `_provenance` (`type: string`, `readOnly`, vocabulary in the
  `description`, **no `enum:` key**) beside `_actions`, and its `x-implements` list gains the new
  marker. `CatalogPage.items` `$ref`s `Catalog`, so the one edit covers list *and* GET.
- **Marker** — `…catalog.security.CatalogProvenanceCarrier`: `@JsonInclude(NON_NULL)
  @JsonProperty("_provenance") String getProvenance()` + setter, and the two vocabulary constants.
  A *second* marker rather than a member of `Enrichable` — that is a library type and an access-path
  label is a domain noun. **No library module changed.**
- **Memo** — `…catalog.security.CatalogProvenanceMemo`: a request attribute
  (`RequestContextHolder` + `SCOPE_REQUEST`, the repo's existing idiom), written by the authorizer
  and read by the advice. A shared key needs one home; this is it.
- **Write-site** — `CatalogListAuthorizer.authorizedPage`, **unconditionally, immediately before
  `abacQuery.findAuthorized(...)`**. Nothing else in the authorizer changed.
- **Advice** — `…catalog.web.CatalogProvenanceAdvice` (`ResponseBodyAdvice`): the paged body stamps
  from the memo; a single `Catalog` derives from the resolved role's stamp **on GET only**. Never
  throws.
- **Docs** — `REST-API-DESIGN.md` §4 gains `_provenance` (one semantic, two derivations,
  absent-when-not-computed, affordance-not-enforcement) plus the `enum:`/codegen gotcha as a
  blockquote, and a Phase-10 line in the adoption history. ADR 0033 was already on main.

### The traps the package named, and what actually happened

1. **No `enum:` key** — heeded. The design's reasoning is confirmed by the live precedent
   (`ProblemDetail.ErrorCodeEnum`); the generated `Catalog` now carries a plain
   `private @Nullable String provenance` and satisfies the marker's `String getProvenance()`.
2. **The write-site sits before the query, not in `auditSupervisedRead`** — heeded, and now
   *guarded*: `memoIsPresentButEmptyForAPlainMember` fails if it ever moves back behind that
   method's `supervisedIds.isEmpty()` early return.
3. **Present-but-empty ≠ absent** — the memo stores `Set.copyOf(supervisedIds)` including the empty
   set; every reader tests `isPresent()`. Asserted at both layers.
4. **The single-body branch is GET-only** — keyed on the **HTTP verb**, not the handler name, so a
   rename cannot silently re-enable it for `createCatalog`. `u8_createAndUpdateBodiesAreUntouched…`
   asserts the supplier is never called on POST/PUT.
5. **Absence comes from `@JsonInclude(NON_NULL)` on the interface getter** — confirmed on serialized
   bytes, not on the getter (a field serializing as `"_provenance": null` would let a client read
   "not supervised" out of a value the server never computed). Note the asymmetry with `_actions`,
   which needs `NON_EMPTY` because its generated backing field starts as an empty map; a scalar's
   starts `null`.
6. **The subject helper is copied, not extracted** — the same `SecurityContextHolder` →
   `AbacAuthentication` private static the existing advices carry.
7. **Newman cell ids** — the package's `E30` landed as the collection's next free block: a new
   **`E10 provenance`** folder in `step-up-matrix.postman_collection.json`, wired into the runner.

### Measured — both derivations agree, live

On the rig, the seeded demo world, after the jar swap:

| | list | single GET |
|---|---|---|
| `sup-demo` (supervises, bound to no team) | `supervised`, `supervised` | `supervised` |
| `pm-demo` (owner of the very same two) | `member`, `member` | `member` |

The same two catalogs carrying opposite labels for the two personas is what makes the cells
falsifiable rather than a constant — a derivation stuck on one value fails E10b/E31f. The list and
the GET share **no code** (the query leg vs. the resolved role's stamp), and they agree.

### The falsifier

Removing the single memo write line and re-running collapsed **10 tests across both layers** —
4 in `CatalogListAuthorizerTest` (all four memo-branch cells) and 6 in `SupervisedListIT` (every
label cell, including `theGeneratedDtoImplementsTheMarkerAndUsesTheExactKey`, which reads the wire).
Restored after.

### Sonar

The first gate run returned **8 findings on changed files**; all fixed, re-run **CLEAN**. One was in
main code and worth naming: **S2589** on `page.getItems() == null` — the generated envelope
initializes `items` to an empty list and never nulls it, so the guard was genuinely dead. Removed,
with a comment recording that `beforeBodyWrite`'s catch would turn a hypothetical NPE into the same
omit. The other seven were test-side (unused imports, assertion chaining, and one *comment* Sonar
read as commented-out code — reworded).

### Acceptance

| Cell | Result |
|---|---|
| **U7** — the advice stamps from the memo and omits on absence | ✅ (4 cases) |
| **U8** — the GET derivation maps the stamp, never throws, skips create/update | ✅ (6 cases) |
| **I1** — the list labels both legs (mixed / memberless / plain member) | ✅ |
| **I2** — the degrade branches label honestly (agent, both outages, present-but-empty) | ✅ |
| **I3** — list and GET agree; an unstamped role omits | ✅ |
| **I4** — the spec + codegen carry the field; absence on the bytes | ✅ |
| **E30** → the collection's `E10 provenance` block | ✅ 11/11 |
| `./gradlew build` | ✅ |
| local Sonar on changed files | ✅ CLEAN |
| `opa test` | untouched — no policy change in this ticket |

### T5's deferred half, now closed

STATUS-05 recorded that E31/E32/E33's `_provenance` assertions waited on this ticket. They are now
in the demo-world collection (`E31a`/`E31f` — the supervised/member contrast on the same ids,
`E32a`, `E33a`), and the whole demo-world matrix re-ran green with them: **Demo world 31,
Idempotency 6, Coexistence 11**, alongside supervised-scope 42+6 and the step-up matrix's
25 + **11** + 16 + 8 + 1 + 3 + 5.

### Notes for the tickets that follow

- The field is on the wire now, so **T4's badge predicate** has its input: `_provenance` absent must
  render as *no* supervised badge and never amber (absence is "unknown", not "member") — U6's rule.
- Categories and products carry nothing; the SPA propagates the catalog's value on drill-in.
