---
tags:
  - status/done
  - type/project
  - area/build
  - area/spring-security
---

# STATUS — Ticket 6: Infra: realm users + roles for the allow/deny matrix

> Filled in at the ticket-6 checkpoint. See [[01-DECOMPOSITION]] ticket 6.

**Status:** ✅ implemented (2026-06-01)

## What shipped
- **`infra/keycloak/realm-export.json`** — added two users to the `catalog-demo` realm (the
  `catalog-viewer` / `catalog-editor` realm roles already existed):
  - **`viewer` / `viewer`** → realm role `catalog-viewer` **only** (so it can demonstrate
    viewer-denied-on-write — the existing `demo` user holds both roles and cannot);
  - **`editor` / `editor`** → realm roles `catalog-editor` + `catalog-viewer`.
  Kept **`demo` / `demo`** (both roles) for back-compat. Each mirrors the existing user shape
  (enabled, password credential, realm roles).
- **`infra/README.md`** — documented which user drives which matrix row, and reconciled the OIDC
  section's stale "the service does no JWT validation" note (the library now does extraction).

## Tests
- Realm JSON validates (`json.load`).
- The `catalog-gateway` client's `defaultClientScopes` includes `roles`, so `realm_access.roles` is in
  the access token for each user — the per-type rego fallback and `DemoRoleDefinitionSupplier` both read
  that claim.
- Full token mint + matrix verification happens in T7 (needs the rig up); this ticket is infra-only and
  isolated from the security wiring so the realm change is its own commit.

## Architecture review + refactor
Infra-only ticket — no code. Reviewed: the new users are minimal (only what the matrix needs), the
role-to-user mapping makes each row of *viewer reads / viewer-write-denied / editor writes* expressible,
and `realm_access.roles` is confirmed present in the token. Nothing to refactor.

## Integration / e2e
Deferred to T7 (rig up → in-network token mint for viewer/editor → newman matrix).

## Decisions recorded
No new Mulch record (infra fixture change). The "demo user holds both roles, so it can't show
viewer-denied-on-write" gotcha is captured in the T7 plan and will be recorded there if it proves
load-bearing in the e2e run.

## Commit
`chore(infra): add viewer + editor realm users for the ABAC allow/deny matrix`.
