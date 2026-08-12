---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# PRODUCTION-TIER — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process HttpServer OPA stub — no WireMock),
> E = e2e (asserts the actual cut, not just response shape — and per the E2E-TESTING
> assertion-style convention, every `pm.test` callback throws; a returned boolean is the
> vacuous-cell defect the SUPERVISED-SCOPE layer-3 review swept out repo-wide).

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `TagDefinition` gains `operatorManaged` | column `operator_managed` maps, persists, and **defaults `false`** — a definition saved without it reads back `false` | T1 |
| U2 | `UserMgmtMapper.toDto` carries the flag | the DTO's `operatorManaged` mirrors the entity for `true` and `false` rows | T1 |
| U3 | `TagDefinitionView` back-compat deserialization | JSON **with** `operatorManaged:true` → `true`; JSON **without** the field (an old user-service) → `false`, never null/undefined behavior | T2 |
| U4 | Delta-based rejection matrix in `validateAndBuild` | with `env` operator-managed: **assign** (absent→present), **re-value**, and **strip** (present→absent) each throw `TagOperatorManagedException` — **including strip-via-empty-map** (`null`/`{}` submitted over current tags carrying `env`: the empty-map fast path must not bypass the check); an **echo** (same value both sides, or absent both sides) passes; a non-managed key changes freely in the same call; the 3-arg overload behaves as `currentTags = {}` (create semantics: submitting `env` rejects) | T2 |
| U5 | `SupervisorRoles.readOnlyFor` widening | `readOnlyFor("catalog")` → `{catalog:[READ], category:[READ], product:[READ]}` with code + `provenance=supervised` unchanged; `readOnlyFor("team")` → the single-key shape, unchanged | T4 |
| U6 | `category.rego` instance-shape tier states (supervised) | with the widened role on a category input carrying ancestors: `root_attributes` **absent** → deny; `{}` → allow; `{env:"production"}` → deny; `{env:"staging"}` → allow | T4 |
| U7 | `category.rego` type-level gate tier states | same four states through `list_gate_input` (the coarse `category:list` gate) | T4 |
| U8 | `product.rego` instance-shape tier states | the U6 quartet on the product input shape | T4 |
| U9 | `product.rego` type-level gate tier states | the U7 quartet through `product_list_gate_input` | T4 |
| U10 | Members structurally unaffected | `provenance=membership` (and a role with **no** provenance) + `{env:"production"}` → allow, and + absent `root_attributes` → allow — in **both** files | T4 |
| U11 | One deletion guard per clause site | deleting any ONE of the four new `denied` clauses fails at least one test in that file (run the four mutations during the ticket; record the four failing-test names in STATUS-04) | T4 |
| U12 | `Resource` record additivity | 3-arg and 4-arg constructors compile and serialize **byte-identically** to today (no `root_attributes` key); the 5-arg with `{}` serializes `"root_attributes":{}`; with `null` the key is absent (**NON_NULL, not NON_EMPTY**); existing module tests pass unmodified | T3 |
| U13 | Manager instance-path enrichment | governing root distinct from leaf → `root_attributes` = the root instance's `abacAttributes()`; leaf-is-root (a catalog GET) → absent; resolver returns empty for the root → absent; resolver **throws** on the root → absent (and never an exception out of the manager); `resolutionSupport == null` → absent | T3 |
| U14 | Manager type-level enrichment | declared + resolvable `roleResource` override → `root_attributes` = the override target's attributes; no override → absent; declared-but-unresolvable override → **deny before enrichment** (the existing fail-closed branch, asserted not regressed) | T3 |
| U15 | Root resolve memoized per request | two checks in one request (gate + instance) → exactly **one** resolver call for the root (`RequestAttributesResourceCache` read-through) | T3 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | Changeset `0008` + seed boot | `ddl-auto: validate` boots clean on Testcontainers Postgres; the `env` row exists with scope `GLOBAL`, ENUM `production|staging|dev`, `is_system=true`, `operator_managed=true` | T1 |
| I2 | The internal projection carries the flag | `GET /internal/tag-definitions?resourceType=catalog&resourceId=…` includes `env` with `operatorManaged:true` **and** `sensitivity`/`region` with `operatorManaged:false` | T1 |
| I3 | Public write rejection over HTTP | on a resource whose current tags do **not** include `env`: a tag write adding `env` through a public path → **409** problem+json with `errorCode: TAG_OPERATOR_MANAGED` (the advice mapping + body asserted); the same write minus `env` → succeeds | T2 |
| I4 | The operator endpoint, happy + non-happy | `POST /internal/bootstrap/resource-tags` merge-upserts: posted keys change, unposted keys survive, a posted `null` removes; an illegal enum value (`env=prod`) → 422; an unknown resource id → 404; an unknown key → 422 | T2 |
| I5 | Child GET input shape + deny honored | recorded OPA input for `getCategory`/`getProduct` carries `resource.root_attributes` equal to the parent catalog's tag map (`{}` when untagged); a stub deny → plain 403 (no `deny_reason` anywhere in B) | T5 |
| I6 | Child LIST: gate enriched, residual not | the coarse-gate input (type-level, `resource.id` null) carries `root_attributes` from the override target; the subsequent Compile/filter request carries **no** `root_attributes` | T5 |
| I7 | Enrichment failure, both populations | with a root-throwing resolver: the recorded gate input has **no** `root_attributes` key (absent — not `{}`, not `null`); a member-shaped request still completes (stub allow → 200) while a supervised-shaped one is denied by the stub'd policy answer | T5 |
| I8 | One root fetch per request | resolver invocation count for the root is exactly 1 across the request's gate + instance checks | T5 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | Non-prod contents open | `sup-anna` on a report's **staging** catalog: `listCategories` 200 + **exact ids**, `getCategory` 200, `listProducts` 200 + exact ids, `getProduct` 200 | T6 |
| E2 | Production contents closed | the same four requests on the **production** fixture catalog → each **403** | T6 |
| E3 | Untagged defaults open | the four requests on an **untagged** supervised catalog → 200 (ADR 0030 §3's default, safe only while `env` is operator-controlled) | T6 |
| E4 | Tier-flip liveness | operator flips `staging → production` via `/internal/bootstrap/resource-tags` → the **very next** supervised child read is 403; flip back → 200 (the B analogue of A's E4) | T6 |
| E5 | The tag is unstrippable | the catalog's own **owner** attempts strip and re-value of `env` (and an assign on the untagged catalog) → each **409** with `errorCode: TAG_OPERATOR_MANAGED` asserted by value | T6 |
| E6 | Members unaffected | the owner reads their own **production** catalog's contents → 200, with an honest `_actions` map still present on their rows | T6 |
| E7 | The `_actions` omission contract | `sup-anna`'s category/product list rows on the staging catalog carry **no `_actions` key at all** (omitted, never a fabricated all-false map) | T6 |
| E8 | The E6 flip + non-regression | A's rewritten E6a/E6b/E6c (untagged supervised contents → 200, exact ids) green inside `run-supervised-scope-matrix.sh`; the enumerated non-regression set green; every skip recorded with its reason in STATUS-06 | T6 |

## Headline proof

**E4 + E5 together are the slice**: the tier changes behavior on the very next request when — and
only when — the **operator** moves it, and nothing the supervised population can do through the API
moves it at all. **U11** is the guard that keeps the whole closure real (no unguarded clause site),
and **I7** is the fail-closed drill: an enrichment outage narrows the supervisor and never touches
the member.
