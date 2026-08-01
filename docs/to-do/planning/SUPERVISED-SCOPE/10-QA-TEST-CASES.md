---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/spring
---

# SUPERVISED-SCOPE — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process HttpServer OPA stub — no WireMock),
> E = e2e (asserts the actual cut, not just response shape).
>
> **This slice ships zero Rego changes**, so there are no `opa test` additions. **E7** asserts the
> existing policy suite and the affected newman matrices still pass unchanged — that is the proof the
> claim held. E7 is an **enumerated** list, not "the full suite": see T5's Acceptance.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `transitiveReportsOf` over a 3-level chain (anna → bob, carol; carol → dave) | returns `{bob, carol, dave}` — transitive, manager-exclusive | T1 |
| U2 | Subject with no outgoing edges | **empty set**, never null, no throw | T1 |
| U3 | A chain of **11 hops** (the cap is 10) | fails **closed to empty**; a partial 10-hop set is NOT returned; one WARN | T1 |
| U4 | Cycle reachable at read time (a → b → a) | fails **closed to empty**; terminates — no infinite loop, no `StackOverflowError` | T1 |
| U5 | Writing an edge that would close a cycle | **rejected on write** (422); the edge is not persisted | T1 |
| U6 | Self-edge (a → a) | rejected on write | T1 |
| U7 | Duplicate edge written twice | idempotent — one row; no constraint violation surfacing as a 500 | T1 |
| U8 | A report holding OWNER/t1, ADMINISTRATOR/t2, SENIOR/t3, MEMBER/t4, READER/t5 | supervised teams = `{t1,t2,t3}` — **MEMBER and READER excluded** | T1 |
| U9 | Two reports both CONTROL-capable on the **same** team | the team appears **once**; target ids are distinct | T1 |
| U10 | A CONTROL-capable membership on a team governing **no** catalog | contributes no ids; no null/blank id in the array | T1 |
| U11 | `effective-role` for a subject **with** a membership on the target | the **membership** role, byte-identical to today | T2 |
| U12 | `effective-role` for a non-member who **supervises** the target | `200` + the synthesized supervisor role | T2 |
| U13 | `effective-role` for a subject with **neither** | `204` — unchanged | T2 |
| U14 | The synthesized role's shape | `permissions` is exactly `{"catalog": ["READ"]}` — the **coarse token**, and **no `category`, `product` or `"*"` key**; `requiredTags` empty; reserved code + provenance marker in `attributes`. **Assert against the policy too, not only the Java shape:** via `opa eval`, `data.catalog.filter` is true and `data.category.allow` is false under this role — a fine-verb grant expands to ∅ and would pass a Java-only check while granting nothing | T2 |
| U15 | A subject **both** member and supervisor of the target | the **membership** role wins | T2 |
| U16 | The synthesized role serialized as `input.role_definition` | round-trips through the core record; the marker survives; **no envelope field added** | T2 |
| U17 | Supervised-targets stub returns `200` + two uuids | both parsed, distinct, order-independent | T3 |
| U18 | Stub returns `200` + `[]` | **empty list** — the authoritative "supervises nothing" | T3 |
| U19 | Stub returns `500` / `404` / `401` | **empty list**, one WARN, **no throw escaping to a 500** | T3 |
| U20 | Stub returns `200` + a blank body | empty list + WARN | T3 |
| U21 | Stub returns unparseable JSON, and `[123,"not-a-uuid"]` | empty list + WARN — a malformed element never yields a **partial** list | T3 |
| U22 | Stub never responds (timeout) | empty list within the configured timeout; the thread is not wedged | T3 |
| U23 | Thread interrupted mid-call | empty list; the interrupt flag is restored | T3 |
| U24 | The edge under the shipped `CallGuard` | a transient failure retries; a **fail-closed empty result is NOT a breaker failure** (`mx-951d2f`) | T3 |
| U25 | Membership `{c1,c2}`, supervised `{}` | one effective leg; the page equals today's result — **byte-identical for existing personas** | T4 |
| U26 | Membership `{}`, supervised `{c3,c4}` | the supervised leg alone returns `c3,c4` | T4 |
| U27 | Membership `{c1}`, supervised raw `{c1,c3}` | supervised reduced to `{c3}`; `c1` resolved on the **membership** role and appearing **once** | T4 |
| U28 | Both scopes empty | **empty page** — no widening to the table | T4 |
| U29 | Membership role source throws while supervised ids exist | the membership leg contributes nothing; the supervised leg still resolves; no 500 | T4 |
| U30 | Supervised source fails (per U19) while membership ids exist | the page equals **membership-only** — the documented degrade, never wider | T4 |
| U31 | the page's total (wire `count`, from `Page.getTotalElements()`) on a mixed page | the authorized total across both legs — not the table count, not one leg's | T4 |
| U32 | Unauthenticated / no `AbacQueryService` / no `GovernedScopeResolver` | **empty page** in each branch (ADR 0018 §Consequences, unchanged) | T4 |
| U33 | A chain of **exactly 10 hops** (the inclusive boundary) | resolves **fully** — every hop returned, **no WARN, no collapse**. Pins that the cap is inclusive; an off-by-one here silently empties a legitimate manager | T1 |
| U34 | The supervisor role's residual is **unconditional** | compiling the synthesized role yields `ALLOW_ALL` (it grants `READ` with empty `requiredTags`). **This is the precondition that makes admitting supervised rows through `subtreeSpec` correct** — if a later slice gives the role a tag requirement, T4's composition must change | T4 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | Full derivation against a seeded org, CONTROL-capable seats only | the exact supervised catalog id set, asserted **by id** — not by count alone | T1 |
| I2 | `ddl-auto: validate` boots against the new `reporting_edge` changeset | clean boot; the changeset is idempotent on re-run | T1 |
| I3 | `effective-role` over HTTP against a real repository, all four branches | U11–U15 hold end-to-end through the controller | T2 |
| I4 | The two-leg page over real data, mixed scopes, paged 2-at-a-time | stable total ordering; no row twice; none skipped at a page boundary | T4 |
| I5 | A supervised row's `_actions` map | `{view:true, update:false, delete:false, assign-tags:false}` — **present, not omitted**; verb set verified against the real endpoints | T4 |
| I6 | A supervised read emits the audit event | one structured event carrying subject/root/access-path; **no event** on an ordinary membership read | T4 |

## E2E (E*)

> Run via `scripts/postman/run-supervised-scope-matrix.sh`.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | **Headline.** `sup-anna` (member of no team) lists catalogs | **exactly** her unit's ids, including her report's report's — asserted id-by-id with an exact count | T5 |
| E2 | `sup-victor` lists catalogs | a set **disjoint** from anna's; neither leaks into the other | T5 |
| E3 | `outsider-eve` lists catalogs | **empty page** — `200` with the envelope's `count: 0` (the wire field; ADR 0012), not 403, not 500 | T5 |
| E4 | **Liveness + two denials.** Remove `pm-bob` from anna's reports, re-list | his catalogs gone next request; a direct `GET` of one now **403** | T5 |
| E5 | **Read-only ceiling.** anna on a supervised catalog | `GET` 200; `PUT`/`DELETE`/tag-assign each **403**; `_actions` as I5 | T5 |
| E6 | **Contents closed.** anna `GET`s the supervised catalog's categories, a category, a product | each **403** — the slice-boundary assertion that contents did not open early | T5 |
| E7 | **Non-regression.** `opa test infra/opa/policies` **plus** the existing matrices that touch catalog listing or role resolution (an explicit list — there is **no** aggregate runner, and the resilience matrix needs a mutually exclusive rig flavour) | green and **unchanged**; every existing persona byte-identical; zero Rego edits. The exact matrices run — and any deliberately skipped, with the reason — recorded in `STATUS-05.md` | T5 |
| E8 | **Fail-closed outage.** Fault-inject the supervised edge, list as anna | **only her own memberships** — never all catalogs, and **no 5xx** | T5 |
| E9 | **Dual-hat.** A subject both member and supervisor of one catalog | the row appears **once**, with the **membership** role's affordances | T5 |
| E10 | The realm marker alone | supervisor claim + **zero** reports → **empty page**; the claim grants nothing | T5 |

## Docs (D*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| D1 | [[TEAM-BASED-AUTHORIZATION]] gains the supervised-path section | both access paths, the precedence rule, the reach rule and both failure classes are documented | T5 |
| D2 | `infra/README.md` + `scripts/postman/README.md` name the new matrix and personas | a reader can run E1–E10 from the docs alone | T5 |

## Headline proof

**E1** and **E4**. E1 proves the derived scope is *correct and exact* — a manager on zero teams sees
precisely their unit and nothing adjacent. E4 proves it is *live and bounded* — revoking a reporting edge
withdraws access on the very next request, and the withdrawn row is then genuinely denied rather than
merely hidden from the list. Together they are the whole slice: the isolation invariant is pierced
exactly as far as the reporting structure reaches, and not one row further.
