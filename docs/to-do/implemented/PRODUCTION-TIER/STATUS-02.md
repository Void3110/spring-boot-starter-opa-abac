---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T2: catalog-service: operator-managed write rejection + the operator endpoint

**Status:** ✅ DONE

## What shipped

- **`TagDefinitionView`** gains `operatorManaged` as the **`Boolean` wrapper**, normalized in the compact
  constructor (`operatorManaged != null && operatorManaged`, the record's existing `allowedValues`
  idiom), plus an `isOperatorManaged()` reader and a **5-arg convenience constructor** so the four
  existing construction sites stay unmodified. The wrapper is load-bearing, not stylistic: Jackson throws
  on a missing **primitive** record component, so a user-service that predates the flag would 5xx every
  tag write; wrapper + normalize makes "absent" read as `false`, back-compat by construction.
- **`TagAssignmentService`** — a 4-arg `validateAndBuild(type, id, submitted, currentTags)`; the existing
  3-arg signature delegates with `Map.of()` (create semantics). The rejection is **delta-based** over the
  **union** of submitted and current keys — a strip only appears on the current side, an assign only on
  the submitted side, so iterating one map alone would miss half the moves. Assign / re-value / strip
  throw `TagOperatorManagedException`; an **echo** passes. The empty-submission fast path is now
  conditional: it may skip the dictionary fetch **only when `currentTags` is also empty**, because
  submitting `null`/`{}` over a resource carrying the key *is* a strip.
- **`validateAsOperator(type, id, tags)`** — the operator's entry point: values are validated exactly as
  on the public path, but the delta check is **never called**. The bypass is a separate method, not a
  boolean parameter, so no public request can reach it.
- **Every update call site passes the loaded entity's current tags** — `CatalogController:128`,
  `CategoryController:160`, `ProductController:154` (enumerated by grep, all six sites inspected). The
  three **create** sites keep the 3-arg overload, which *is* `currentTags = {}` — see *Decisions*.
- **`CatalogErrorCode` gains its first constant**: `TAG_OPERATOR_MANAGED(CONFLICT, "Operator-managed tag
  key")`, and the enum grows the `status()`/`title()` implementation the empty stub owed
  (`UnsupportedOperationException` is gone). `ApiExceptionHandler` maps the exception → 409 problem+json;
  `catalog-api.yaml`'s `ProblemDetail.errorCode` enum grows the constant (the closed-enum discipline,
  mx-fb443b).
- **`InternalBootstrapController`** (catalog) — the service's **first internal write endpoint**:
  `POST /internal/bootstrap/resource-tags`, a merge-upsert over `{resourceType, resourceId, tags}` where
  a posted `null` removes a key and every unposted key survives. Returns the resulting full tag map.
  Mirrors the user-service bootstrap posture; `SecurityConfig` already permits `/internal/**` in both
  branches, so no security change was needed.
- **Guide delta** — the *Operator-managed keys* subsection grows the enforcement paragraph (delta-based,
  the echo rationale, the strip-via-empty-map consequence, why not `TAG_DEFINITION_IMMUTABLE`) and the
  operator-path paragraph.

## Tests

`./gradlew :example-catalog-management-service:test` — **BUILD SUCCESSFUL**. 29 new cases:

| Case | Where | Count | Asserts |
|---|---|---|---|
| **U3** | `TagDefinitionViewTest` | 4 | flag present → `true`; **absent → `false`**; explicit `null` → `false`; the 5-arg arity is unmanaged |
| **U4** | `TagAssignmentOperatorManagedTest` | 13 | assign / re-value / strip / **strip-via-empty-map** / **strip-via-null-map** each throw; echo passes; absent-both-sides passes; a non-managed key changes freely alongside an echoed managed key; both-empty needs no fetch; the 3-arg overload rejects on create and still accepts ordinary keys; the operator path writes the managed key **and still validates values** |
| **I3** | `OperatorManagedTagWriteIT` (6) | | 409 + `errorCode: TAG_OPERATOR_MANAGED` on assign / strip / strip-via-`{}` / re-value / tag-on-create; the same write minus `env` succeeds; the echo leaves ordinary tag editing unfrozen |
| **I4** | `OperatorManagedTagWriteIT` (6) | | merge (posted key changes, unposted survives, asserted through a public GET); idempotent re-post; posted `null` removes; illegal enum → 422; unknown key → 422; unknown resource **and** unknown type → 404 |

`opa test infra/opa/policies/` untouched (unchanged until T4).

**One existing test needed a stub** — `ResourceResolutionGateIT.tagMatchWriteAllowedAtGate` began
answering **503**. Not a regression to paper over: that PUT submits no tags over a category that carries
`{region: emea}`, which under full-map-replace semantics *is* a strip, so it now legitimately consults
the dictionary — and the suite had no `TagDefinitionClient` stub, so the real client reached for an
absent user-service and failed **closed**. Fixed by adding the stub bean to `GateTestConfig` (with the
reason in its javadoc); the test's assertions are untouched. See *Decisions* for the behavior change.

## Architecture review + refactor

Inline ★ review over the ticket's diff, before the ITs' final run.

- **Fail-closed** — the key cannot be moved by a public path in **any** branch: assign, re-value, strip,
  strip-via-`{}`, strip-via-`null`, on create and on update, on all three resource types (the check lives
  in the one service every tag path funnels through, so it is type-agnostic by construction rather than
  by three copies). The load-bearing case is the **dictionary outage**: the fetch throws → 503 → the
  write is rejected. Nothing anywhere says "we could not check, so allow".
- **Security — the widenings that would matter, and why each cannot happen.**
  1. *The echo-rejection freezing tag edits, prompting a workaround* — prevented by delta semantics;
     asserted directly (`echoingTheManagedKeyLeavesOrdinaryTagEditingUnfrozen`).
  2. *Strip-via-empty-map bypassing the whole check* — the fast path is now conditioned on **both** maps
     being empty; two tests (`{}` and `null`) pin it, one of them over HTTP.
  3. *The operator endpoint reachable through the gateway* — **verified against source, not assumed**:
     `infra/apisix/init-routes.sh` carries a positive `internal-blocked` route at **priority 70** (above
     usermgmt's 60 and the catalog catch-all's 0) that 404s every `/internal/*` path at the edge, added
     precisely because the catalog pool is served by a catch-all. The user-service is protected by route
     omission. So "in-network only" is enforced, not merely intended.
  4. *A client-authorable `operatorManaged`* — `TagDefinitionView` is a **read model of the
     user-service's response**, never bound from a client request; T1 kept the flag out of every request
     schema.
  5. *Smuggling the key on a child resource* (where the dictionary is addressed by the catalog root) —
     `env` is GLOBAL, so it resolves for any addressing and the rejection applies;
     `tagOnCreateCannotSmuggleTheManagedKey` pins the category-create route.
- **Concurrency / idempotency** — the operator merge-upsert converges under retry (asserted by posting
  twice). The delta compares against **the same loaded entity the write persists**: the catalog and
  category paths already `guardGateSnapshot(entity)` before writing and carry `@Version`; the product
  path passes `current`, the very object its delta dispatch and version guard use, and its write re-locks
  the row inside `mutate()`. So there is no TOCTOU window between the current-tags read and the save that
  is not already answered with a 409.
- **Wiring** — every seam has a named consumer and a non-happy-path test: the view field (U3 + the stub
  dictionaries), the widened signature (three update call sites + U4), the exception → advice → enum
  constant → yaml enum chain (I3 asserts the **code**, not just the status), and the internal controller
  (I4, four failure modes).
- **Boundary** — no library module, no policy file, no user-service file touched. `TagDefinitionClient`
  behavior is unchanged (no caching added; it has none).
- **Module-layer separation** — the dictionary *declares* (user-service, T1); the catalog service
  *enforces*, because that is where tag values are written; policy will *decide* (T4). None reaches
  across.
- **Pattern reuse** — the user-service `InternalBootstrapController` posture and the read-only
  `InternalOwnershipController` mounting; the record's null-normalizing compact constructor; the
  prior-arity convenience constructor; the closed `errorCode` enum discipline (the yaml enum grew in the
  same commit as the constant).
- **Refactor applied** — the first draft of the operator endpoint carried a per-type `save()` switch with
  a downcast back to each entity class. Removed: the entity is loaded **inside** the `@Transactional`
  method, so it is managed and the tag change flushes on commit through dirty checking. The endpoint lost
  a switch, three casts and an unreachable default branch. Tests re-run green afterwards.
- **Considered and left** — a concurrent public write racing the operator merge would surface an
  optimistic-lock failure as a 500 rather than a typed 409. Left as is deliberately: this is an
  in-network operator surface with a single writer in practice, mapping it would mean adding a code to a
  surface that documents none (the `REPORTING_EDGE_INVALID` precedent), and the failure mode is a
  rejected write — it widens nothing. Recorded here rather than fixed silently.
- **Static-analysis gate** — `./.sonar-local/sonar-local.sh`: **9 findings, all documented by-design FP
  classes** in Mulch `quality-gate-sonar` **mx-302e78**. S107×1 on `TagDefinition`'s hydration
  constructor (T1's, named explicitly in the FP catalog); S5778×8, all in
  `TagAssignmentOperatorManagedTest`, all of the documented shape `() -> factory(x).methodUnderTest(...)`
  in test code where the extra call is a trivial map-building test factory and every assertion pins
  `.isInstanceOf(...)`. Nothing re-fixed; no new finding on the changed main code.

## Integration / e2e

ITs only (the rig matrix is T6's, in part 1). **I3** and **I4** as tabled above — twelve cases in
`OperatorManagedTagWriteIT` against Testcontainers Postgres through MockMvc, with a stubbed dictionary
(`env` operator-managed, `sensitivity` ordinary). I3's 409s assert the **body's `errorCode`**, which is
what the e2e E5 cell will assert by value; I4 covers the operator path's happy merge plus all four of its
failure modes.

## Decisions

- **Seam deviation — none of substance, one addition.** Every seam the decomposition named was found as
  described (the record's five components, the 3-arg `validateAndBuild` returning `ResourceTags`, the
  empty-map early return, the empty `CatalogErrorCode` with its throwing `status()` stub, the advice, the
  yaml enum at ~line 700, `SecurityConfig` permitting `/internal/**` in both branches). The
  decomposition listed **five** call sites; grep found **six** (`CatalogController:128` update,
  `CategoryController:97/:160`, `ProductController:92/:154` — and `CatalogController`'s create rejects
  tags outright *before* assignment, as the decomposition noted). All inspected.
- **The three create sites keep the 3-arg overload.** The decomposition's own wording blesses this ("the
  existing 3-arg signature delegates with an empty map so create paths stay source-compatible"): a
  not-yet-existing entity's current tags *are* `{}`, so the overload states create semantics more clearly
  than passing a literal empty map would, and U4 pins that the overload rejects an operator-managed key.
- **Intended behavior change: a tags-clearing write on a tagged resource now depends on the
  dictionary.** Before, submitting `{}`/`null` returned `ResourceTags.empty()` without a fetch; now it
  fetches (and a fetch failure is 503). This is not incidental — it is what closes the strip-via-empty-map
  bypass, since whether the cleared key was operator-managed is a question only the dictionary answers.
  Recorded in the guide, and it is the sole reason `ResourceResolutionGateIT` needed a stub.
- **Operator validation covers the posted keys, not the merged map.** Re-validating untouched keys would
  let an unrelated dictionary change (a since-deleted team key) fail an operator write that does not
  touch it — an availability trap on the one path that must work when the tier needs moving. The posted
  values are still fully validated, which is what the decomposition asked for.
- **The operator endpoint returns the resulting tag map** so a caller (T6's runner) can assert the merge
  without a second read.

## Commit

`feat(production-tier): reject operator-managed tag writes and add the operator path (T2)` — the view
field, the delta-based rejection, the new error code + advice + yaml enum, the internal bootstrap
endpoint, the three updated call sites, the guide paragraphs, and the 29 new cases (plus the one stub
added to `ResourceResolutionGateIT`), on `feature/void3110/production-tier`.
