---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/user-service
---

# STATUS — T4: user-mgmt: the authoring contract (level ceiling, category tokens, strict denials)

**Status:** ✅ DONE (2026-06-12)

## What shipped

- `service/PermissionCategories` — the app-side **validation** table (the four tokens, the expansion
  map, per-level ceilings `10→{READ}` / `20,25→{READ,WRITE,TAG}` / `30→all four`, unauthorable →
  **empty** ceiling). Runtime decisions stay in OPA `data`; U9 parity-pins the constant to
  `infra/opa/policies/permission_categories.json` (path-walk from the module dir) so drift breaks
  the build.
- `RoleDefinitionService.create/update` — the contract, validated after the code/uniqueness checks,
  every violation → `RoleDefinitionInvalidException` → **422 `ROLE_DEFINITION_INVALID`**:
  roleLevel ∈ {10,20,25,30} (missing → 422, not 400 — see Decisions); tokens ∈ the four categories;
  categories ⊆ ceiling(level); **strict denials** (per type, denied ⊆ expand(granted), wildcard-aware
  grant lookup mirroring the policy's `tokens_for`); the explicit `roleLevel` overwrites
  `attributes.role_level` (single source, U8).
- **Dropped the authoring-time author-subset check** (`subsetGuard.requireWithinActorPermissions` —
  removed from the service AND deleted from `SubsetGuard`; zero callers don't linger). The R-test
  that pinned it (`customRoleExceedingOwnPermsIsDenied`) **flipped to the ceiling cell**
  `customRoleExceedingLevelCeilingIsDenied` (level-20 granting GRANT → 422). `CallerIdentity`
  dropped from `RoleDefinitionController` (only the subset check used it).
- `UserMgmtErrorCode.ROLE_DEFINITION_INVALID` (422) + advice handler + the **closed `errorCode`
  enum** in `user-mgmt-api.yaml` gains it (same commit — the enum-sweep lesson).
- `user-mgmt-api.yaml`: `RoleDefinitionRequest`/`RoleDefinitionUpdate` gain `roleLevel` +
  `deniedActions` (responses already carry them from T3).
- `InternalBootstrapController.EnsureCustomRole` gains `roleLevel` (+ `deniedActions`) and now
  **routes through `RoleDefinitionService`** — the bootstrap is no longer a validation bypass; the
  upsert stays idempotent (update path on re-run).

## Tests

`./gradlew :example-user-management-service:test` + full `./gradlew build` green (real Postgres):
- **U4–U7** `RoleDefinitionContractTest` (plain JUnit on the package-private static validation):
  ladder, token boundary (flat verb AND fine-action-as-grant rejected), ceiling (incl. GRANT below
  30), strict denials (incl. wildcard-grant lookup, denial-on-ungranted-type).
- **U8** in the CRUD round-trip IT: update with `roleLevel 20` + `attributes.role_level 40` →
  stored/echoed 20.
- **U9** `PermissionCategoriesParityTest` — Java table == the OPA JSON; ceilings ⊆ categories;
  owner-40 unauthorable.
- **I4** `seniorRoleWithDenialRoundTrips` — 201, read-back `roleLevel 25` + `deniedActions`.
- **I5** `contractViolationsAnswer422RoleDefinitionInvalid` — five violation cells, each
  `422 problem+json` containing `ROLE_DEFINITION_INVALID`.
- `InternalBootstrapIT` (new) — the no-bypass pin (flat token via bootstrap → 422, nothing stored)
  + idempotent re-run convergence.

## Architecture review + refactor

- **Wiring gap found and closed**: the bootstrap seam had no test through its non-happy path —
  added `InternalBootstrapIT` (422 + convergence).
- Security: denial validation iterates every type key with the same wildcard lookup the policy
  uses — no second grant path exists to re-widen; error messages name only the offending
  token/level (no internal state).
- Concurrency: `lockTeam` stays first in create/update; validation is pure; the bootstrap upsert
  converges on retry.
- Nothing else substantive.

## Decisions

- **`roleLevel` is NOT schema-required/enum in the spec** (deviation from the decomposition's
  "(integer enum)" parenthetical): a schema enum/required makes missing/off-ladder levels answer
  `400 VALIDATION_FAILED` at deserialization, but U4/I5 pin the WHOLE authoring contract to
  `422 ROLE_DEFINITION_INVALID`. The field is documented REQUIRED in the spec description and
  enforced by the service — one uniform violation contract. (Codegen also turned the enum into a
  `RoleLevelEnum` type, which the bootstrap path bypasses anyway — the service check is the single
  real gate.)
- Unauthorable levels get an **empty** ceiling in `PermissionCategories.ceiling` (fail-closed
  belt-and-braces; the level itself is rejected first).

## Commit

`feat(usermgmt): the Phase-6.5 authoring contract (level ceiling, category tokens, strict denials)`
