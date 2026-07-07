---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T5: Realm config: catalog-directory client + view-users + deploy wiring

**Status:** ✅ DONE

## What shipped

- `infra/keycloak/realm-export.json` — the **`catalog-directory`** confidential client:
  `serviceAccountsEnabled: true`, **`standardFlowEnabled: false` + `directAccessGrantsEnabled: false`**
  (pure `client_credentials` — no browser flow, no password grant), demo secret
  `catalog-directory-secret`; plus the `service-account-catalog-directory` user entry mapping
  `realm-management → view-users` and **nothing else**. Separate client from `catalog-gateway` (distinct
  trust roles, ADR 0020 §4).
- `deploy.sh` — the **`ENABLE_DIRECTORY`** flag (default **0** — the default rig is unchanged);
  force-enables its prerequisites (OIDC + user-service) exactly like `ENABLE_SPA`; exports
  `DIRECTORY_ENABLED=true|false` for compose interpolation; adds the status line to the `up` summary.
- `infra/compose.usermgmt.yaml` — the five `OPA_ABAC_DIRECTORY_KEYCLOAK_*` env vars, with `ENABLED`
  interpolated from the flag; `server-url` pinned to the **in-network** `http://keycloak:8888` and the
  comment repeating the §6 warning (`KC_HOSTNAME_ADMIN_URL` is a console rewrite, not the REST path).
- `OpaDirectoryAutoConfiguration` — an **INFO** line when the Keycloak directory wires (realm + client,
  never the secret) and a DEBUG for the NoOp default, so E-pre's "bean active" check is a log grep.
- **Seed script: no change needed** — verified it provisions only demo/editor/viewer; `carol`, `alice`,
  `bob`, and `outsider` are realm accounts with **no** `users`-table row (E1's never-provisioned pool).

## Tests

Starter suite re-run green (the INFO log addition); realm JSON validated.

## Architecture review + refactor

One real bump, fixed in-loop: **Keycloak's `CLIENT.DESCRIPTION` column is VARCHAR(255)** — the first
realm import died at startup ("Value too long") on a long client description; shortened and re-imported
clean. Worth remembering for any future realm-export edit.

Verified explicitly:
- **Security (the §4 cut):** live-proved least privilege in-network — the `catalog-directory` token
  **can** search users (200, carol returned) but is **denied** user create (403), update (403), and
  delete (403). The client has no browser/password flow to phish.
- **Fail-closed:** flag off → `DIRECTORY_ENABLED=false` → the NoOp stays active (T3's I3a); the default
  rig is byte-identical in behavior.
- **Boundary:** existing-file edits are additive (a new client + service-account entry; a new flag
  block; new env lines). No existing client, user, or route was touched.
- **Concurrency:** n/a — config only.

## Integration / e2e (E-pre, live on the rig)

- Rebuilt `opa-abac-usermgmt:local` (explicit `docker build` — the deploy-only-builds-when-MISSING
  gotcha), recreated Keycloak (fresh realm import), then
  `ENABLE_SPA=1 ENABLE_DIRECTORY=1 ./deploy.sh up --pods 2` + reseed.
- **Log proof:** `user-directory: KeycloakUserDirectory active (realm 'catalog-demo', client
  'catalog-directory')` — the Keycloak bean, not the NoOp.
- **Privilege proof:** in-network `client_credentials` token → admin users read 200 / create 403 /
  update 403 / delete 403 (least privilege holds).
- **Whole-path smoke (pre-E1):** through the gateway, the `search` sub-path of `/api/v1/users` with
  `q=carol` returns the **never-provisioned** carol (`subject` = her Keycloak id); blank `q` → empty
  items; `limit=1000` → echoed `limit: 50`. The T6 newman cells will pin these as committed assertions.

## Decisions

- `ENABLE_DIRECTORY` force-enables OIDC + user-service (the `ENABLE_SPA` precedent) — a half-started
  directory rig can't exist.
- The E-pre "bean active" proof is an INFO log line in the auto-config (operational fact, secret never
  logged) — added here because E-pre needs it observable.

## Commit

`feat(rig): stand up the catalog-directory service account and the ENABLE_DIRECTORY wiring`
