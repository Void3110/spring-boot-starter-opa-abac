---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
---

# STATUS — T2: user-service: the non-membership `effective-role` branch + the synthesized supervisor role

**Status:** ✅ DONE

## What shipped

- **`service.SupervisorRoles`** — the vocabulary + factory for the synthesized role:
  `SUPERVISOR_CODE = "supervisor-readonly"`, `PROVENANCE_ATTRIBUTE = "provenance"`,
  `PROVENANCE_SUPERVISED = "supervised"`, and `readOnlyFor(resourceType)` building the
  `RoleDefinition`. Built in code on every request, **never persisted**.
- **The synthesized role's exact shape**: `permissions = {<supervised type>: ["READ"]}` — the
  **coarse category token**, with **no `category` key, no `product` key, no `"*"` key** — empty
  `requiredTags` (so no `match_mode`), no `deniedActions`, the reserved code, and
  `attributes.provenance = "supervised"` riding the **existing** generic map.
- **`EffectiveRoleService.resolveForResource` gained the ordered fallthrough**:
  1. a membership role resolves → **return it** (membership always wins);
  2. else the subject **supervises** this exact resource → the synthesized role;
  3. else `Optional.empty()` → the controller's existing `204`, unchanged.
  The supervised check reads the **same derived id set** the catalog's base scope will consume, so
  the role branch and the list scope can never disagree about who supervises what.

## Tests

`./gradlew :example-user-management-service:test` → **241 tests, 0 failures**. `./gradlew build`
green. `opa test infra/opa/policies/` **266/266** — unchanged; T2 touches no policy.

- **`service.SupervisorRolesTest`** — **U14** (the role shape: exactly `{"catalog": ["READ"]}`, no
  child or wildcard key, the coarse token and explicitly *not* the fine verbs, empty tags, reserved
  code, provenance marker) and **U16** (round-trips through `core.RoleDefinition`; the marker
  survives; the wire carries exactly `code`/`attributes`/`permissions`, proving **no envelope field
  was added**). Plus: the role names the type actually supervised, never a hard-coded one.
- **`SupervisorEffectiveRoleIT`** (Testcontainers, real Postgres) — **I3**, all branches over HTTP:
  **U11** (a member still resolves the membership role, byte-identical, and carries no supervised
  provenance), **U12** (a non-member who supervises → `200` + the synthesized role), **U13**
  (neither → `204`), **U15** (**both** member and supervisor → the **membership** role wins). Plus
  four boundary cases: the realm marker is UX-only (a supervisor-eligible subject with **zero
  reports** → `204`), a report's **READER** seat does not synthesize a role, removing the reporting
  edge **withdraws the role on the very next request**, and a custom role bearing the reserved code
  buys **no** reach (ADR 0029 §7).
- **The batch path** (`/internal/effective-roles`) is asserted to see the supervisor branch
  **consistently** — it loops the same service, so no special-casing was added; the ungoverned target
  still returns the authoritative explicit `null` (ADR 0024), never `204`.

### U14's policy half — asserted with `opa eval` against the real corpus

T2 may not edit Rego, so the "assert against the policy, not only the Java shape" half was run
directly against `infra/opa/policies` with the exact role the Java factory produces:

```console
$ opa eval -d infra/opa/policies -i sup-role.json 'data.catalog.filter' --format raw
true
$ opa eval -d infra/opa/policies -i sup-view.json 'data.catalog.allow' --format raw
true
```

(`sup-role.json` = `role_definition {code: supervisor-readonly, attributes:{provenance: supervised},
permissions:{catalog:[READ]}}`, `resource.type: catalog`, `action: catalog:list`; `sup-view.json` the
same role with a concrete catalog id and `action: catalog:view`.)

**This is the assertion that matters**: a role written with the *fine* verbs would expand to ∅ and
`filter` would be **false** — a Java-only shape check would have passed while the supervised page came
back silently empty.

## Architecture review + refactor

Ran the ★ gate inline after unit-green, before the ITs. **Nothing substantive to refactor** — the
ticket is one ordered branch plus a value class, and the review found no defect to fix. No churn was
invented. What it verified:

- **Fail-closed.** Every breach of the derivation already collapses `supervisedTargets` to empty
  (T1), so `supervises(...)` is false and the resolve falls through to `204` → the catalog supplier
  maps it to no-role → the residual compiles to `DENY_ALL`. That is exactly failure **class 2**
  (partial derivation → **membership-only**): the subject keeps their own memberships and gains
  nothing. No branch can return a *partial* supervisor role — the role is all-or-nothing by
  construction.
- **Security — the widening that would matter here** is the synthesized role reaching a row it did
  not earn. It cannot: reach is exact-id containment in the derived set (no wildcard key, no
  type-level grant), and the branch is only consulted **after** the membership loop fails, so the
  role's **vacuous tag requirement can never judge a tag-gated membership row** — the two can never
  meet on the same resource. The **realm marker is not read anywhere** in the resolve path (pinned by
  `supervisorWithZeroReportsResolvesNothing`), and the **code is provenance, not authority** (pinned
  by `customRoleBearingTheReservedCodeGrantsNoSupervisedReach`).
- **Blast-radius check on the dogfooded path (verified, not assumed).** `resolveForResource` has
  exactly **two** call sites, both on `InternalResolveController` (the single + batch resolve).
  The user-service's own `@OpaPreAuthorize` management decisions go through
  `TeamRoleDefinitionSupplier` → **`managementRole`**, a different method — so the supervised branch
  **cannot** leak into `team.rego`. This matters because T3 depends on the same split.
- **Concurrency / idempotency.** The role is synthesized per request from immutable inputs
  (`RoleDefinition` defensively copies every map); there is no shared or cached state to race.
- **Wiring.** Both consumers exercised through their non-happy paths (`204`, zero reports,
  non-CONTROL seat, ungoverned batch target). `SupervisorRoles.readOnlyFor` has exactly one
  production call site.
- **Boundary / additivity.** `core.RoleDefinition` **untouched** — the marker rides the existing
  `attributes` map, and U16 proves the serialized envelope is unchanged. No library module, no Rego,
  no decision-envelope change. Byte-for-byte unchanged: `resourceRole`, `managementRole`,
  `governedTargets`, the batch contract, every policy file, the OpenAPI spec.
- **Pattern reuse.** The fallthrough extends the existing membership loop rather than adding a
  parallel resolver, and `readOnlyFor` uses the **3-arg convenience constructor** (the prior canonical
  shape), so the synthesized role serializes byte-shape-identically to any other untagged,
  denial-free role.
- **Recorded cost, accepted deliberately.** A non-member's resolve now walks the closure, and the
  batch endpoint re-walks it **per target** (it loops the same service by design — ADR 0024's
  one-entry-per-target contract). Members pay nothing, because supervision is consulted only after
  the membership loop fails. Correctness first: ADR 0029 §1 explicitly defers precomputation until
  list latency forces it, and says it hides behind this same seam. Not optimised here.

## Integration / e2e

ITs only — **no rig, by design** (part 0 stays provable with ITs plus `opa test`). `SupervisorEffectiveRoleIT`
runs the four resolve branches plus the batch path against real Postgres via Testcontainers. The
rig-level proof is T6's (part 1).

## Decisions

- **Role codes are only partially unique — and that is NOT an escalation.** A team owner can define a
  custom role bearing `supervisor-readonly`. Per ADR 0029 §7, reach comes entirely from the
  org-relation seam and **never** from the role, so claiming the code grants no additional scope: it
  only moves the holder onto the stricter branch. Spoofing it is **self-demotion**. Recorded here so
  it is not re-derived later, and pinned by a test rather than left as prose.
- **The role names the REQUESTED resource type, not a hard-coded `"catalog"`.** The branch only fires
  when the subject genuinely supervises a target of that type, so the grant is always a *direct*
  grant on the type actually supervised — never on a child type. For a catalog request this is
  exactly the `{"catalog": ["READ"]}` the design pins. Hard-coding `"catalog"` would have been a
  latent bug the moment a team governs any other type.
- **`SupervisorRoles` owns the code + key + the `"supervised"` value only.** The complementary
  `"membership"` value belongs to the funnel that stamps it and lands in **T3**, keeping each
  commit's diff to what it actually implements.
- **U14's policy half is a recorded `opa eval` run, not a new Rego case.** T2's *What NOT to touch*
  forbids Rego edits, and this slice ships **exactly one** narrow policy change (T3's). The permanent,
  in-corpus assertions for the supervisor role are **T3's U35–U40**; the transcript above is T2's
  evidence that the coarse token really does open `filter`.
- **No seam deviation to report.** Every artifact named was verified against the source first: the
  `resolveForResource` signature and its two call sites, the batch endpoint's per-target loop,
  `ExactTeamTargetMatcher`'s exact `(type, id)` semantics (which is why the supervised check is
  exact-id containment — the two branches ask the same question), and `core.RoleDefinition`'s 3-arg
  constructor with its `NON_EMPTY`/`NON_NULL` omission behaviour.

## Commit

`feat(supervised-scope): T2 the non-membership effective-role branch + the synthesized supervisor role`
— on `feature/void3110/supervised-scope`.
