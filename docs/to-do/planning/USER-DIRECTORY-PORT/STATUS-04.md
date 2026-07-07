---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T4: search endpoint under /api/v1/users (spec + controller + bounded-list DTO)

**Status:** ✅ DONE

## What shipped

- `user-mgmt-api.yaml`: the `searchUsers` operation on the `search` sub-path of `/api/v1/users`
  (`q` + optional `limit`), responding `DirectoryUserList` — `{items:[{subject,displayName}], limit}`,
  deliberately **not** the page envelope (no `count`, no pages; the spec description spells out the
  no-oracle 200-empty contract and the 20/50 clamp echo). Two new schemas (`DirectoryUserList`,
  `DirectoryUser` — the wire-side disclosure ceiling). Regenerated.
- `UserController.searchUsers` — injects the **port** (`UserDirectory`, the starter bean), computes the
  effective limit via the contract clamp, passes through, maps. Bearer-only (no `@OpaPreAuthorize`,
  consistent with the ungated sibling list; no `directory:*` gate invented). Zero Keycloak/URL
  knowledge in app code.
- `UserMgmtMapper.toDirectoryUserList` — library rows → DTO; items initialized so an empty result
  serializes as `[]`, never `null`.
- `example-user-management-service/build.gradle.kts`: **`runtimeOnly(project(":opa-abac-keycloak-directory"))`**
  — the controller codes against the port only; the dep just puts the impl on the runtime classpath so
  T5's rig flag can light it up (decided here so T5 stays purely realm/deploy config).
- Consumers: the SPA member picker (T6); until T5 enables Keycloak, the NoOp answers 200-empty.

## Tests

`:example-user-management-service:test` green (incl. all Testcontainers ITs — `ddl-auto: validate`
boots clean, proving no schema surface); full `./gradlew build` green (codegen ticket).

- **I4a** — standalone MockMvc, stub port returning 2 rows: 200, `limit:10`, 2 items, and **exactly two
  fields per row** (`$.items[0].*` hasSize 2 — the disclosure ceiling asserted at the wire); no `count`
  / `page` keys (plain list, not an envelope).
- **I4b** — `NoOpUserDirectory`: **200** with `items:[]` (not 404, not 500 — the no-oracle empty at the
  HTTP boundary); absent `limit` echoes the contract default 20.
- **Clamp echo** — `limit=1000` → response `limit:50` AND the port received 50 (one clamp, both sides).
- Slice-1's `UserControllerFilterTest` untouched semantically (constructor arg only) — still green,
  proving `GET /users` behavior is byte-identical.

## Architecture review + refactor

One review-driven refactor, applied mid-ticket: the **20/50 clamp moved onto the contract** —
`UserDirectory` now carries `DEFAULT_LIMIT`/`MAX_LIMIT` + a static `clamp(int)`; `KeycloakUserDirectory`
lost its private copy and the endpoint echoes the same rule. Rationale: the echoed `limit` must be
*honest*, and duplicating clamp constants across impl + controller was a drift bug waiting (additive,
binary-compatible interface change; T1/T2 tests re-run green).

Verified explicitly:
- **Fail-closed/no-oracle:** every response is 200; empty/outage/blank-q are indistinguishable JSON
  (I4b); the port owns the edges (T2) so no 500 path exists in the handler body.
- **Security:** the wire test pins the two-field ceiling; bearer-only posture matches the sibling list;
  acting stays gated by `team:add-member`.
- **Concurrency:** n/a — read-only pass-through, no gated mutation in this slice.
- **Boundary:** the one named cost — regenerated `UserApi` gains `searchUsers` — landed with its
  override in the same commit; `GET /users` and every other operation byte-identical.
- **Layer separation:** app code imports the port only (`runtimeOnly` keeps Keycloak types off the app
  compile classpath); URL-agnostic endpoint (§6 holds).

## Integration / e2e

MockMvc slice tests above (this ticket's mandate); live-rig proof lands with T5/T6.

## Decisions

- The module classpath dep (`runtimeOnly`) belongs to T4, not T5 — T5 stays realm/deploy-only; noted
  for the reviewer.
- Test idiom: standalone MockMvc (not the plain-unit Slice-1 style) because I4a/I4b assert the JSON
  wire shape (field count, envelope absence), which needs serialization.

## Commit

`feat(directory): expose the identity-directory search endpoint on the user service`
