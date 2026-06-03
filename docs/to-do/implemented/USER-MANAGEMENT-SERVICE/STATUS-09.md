---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/build
---

# STATUS — Ticket 09: Infra (second service) + e2e matrix + docs/roadmap/Mulch

> Filled in at the ticket-09 checkpoint. See [[01-DECOMPOSITION]] ticket 9.

**Status:** ✅ done

## What shipped

The full loop — a catalog request authorized by a role resolved from real team membership — proven
through the rig; docs tell the team story; roadmap + Mulch updated; the folder shipped.

**Infra**
- `example-user-management-service/Dockerfile` (multi-stage, mirroring the catalog's).
- `infra/compose.usermgmt.yaml` — the user-service container + its **own** Postgres
  (`opa-abac-usermgmt-postgres`, host `:5434`); shares the project network so the catalog reaches it as
  `http://usermgmt:8080`. Dogfoods the starter (`OPA_ABAC_*` pointed at the shared OPA).
- `deploy.sh` — a new `ENABLE_USER_SERVICE` toggle: builds the user-service image, starts its compose,
  waits for health, and (when on) gives the catalog pods `CATALOG_ROLE_SOURCE=http` +
  `CATALOG_USER_SERVICE_BASE_URL=http://usermgmt:8080`. Wired into `up`/`down`/the summary.
- `infra/opa/policies/team.rego` (+ `team_test.rego`) — the user-service's policy, mounted into the
  rig's shared OPA so its dogfooded `@OpaPreAuthorize` decisions resolve.
- `InternalBootstrapController` (`/internal/bootstrap/*`) — a minimal, idempotent, in-network-only seed
  surface (users/teams/custom-roles/memberships) so the e2e can wire demo data keyed on the runtime
  `sub`s. Not part of the public, secured management API.
- `infra/keycloak/realm-export.json` — added an `outsider` user (the non-member case).

**e2e** (`scripts/postman/`)
- `team-abac-matrix.postman_collection.json` + `run-team-matrix.sh` — mints four in-network tokens
  (owner/viewer/custom-editor/non-member), decodes their subjects, seeds a fixed demo catalog
  (team-target), bootstraps the team + memberships via the internal API, then asserts the matrix.

**Docs**
- `docs/guides/TEAM-BASED-AUTHORIZATION.md` (new) — the team model (`role ≠ grant`, owner-on-create,
  the subset rule, transfer, the app-resolved resolve API, dogfooding) + how to run it.
- Reconciled `infra/README.md` (the `ENABLE_USER_SERVICE` section + Pieces/users), the postman
  `README.md` (the two matrices), and `POC-ROADMAP.md` (Phase 4 → ✅ done; next 4.5/5/7).
- Moved this folder to `docs/to-do/implemented/` with a "Shipped" banner on the index.

## Tests

- **e2e team matrix → green (8/8 assertions), stable across reruns:**
  - owner writes the owned catalog → **200**; owner + viewer-member read → **200**;
  - viewer-member writes → **403**; custom-editor member writes → **200**; non-member writes → **403**;
  - dogfood: owner manages the user-service's own API → **200**; viewer-member → **403**.
- `./gradlew build` (whole repo) → green: 4 library modules + **both** example apps + codegen + ITs.
- `opa test` (the user-service `team.rego`) → 10/10; the catalog per-type policies unchanged.
- Manual smoke confirmed the resolve path: `editor` (owner on the team) → `{owner, catalog:[read,write]}`
  via `/internal/effective-role`; the gateway write returned 201 while a non-member got 403.

## Architecture review + refactor

- **Fail-closed end to end** ✅ — the non-member case (no team → empty resolve → policy default-deny →
  403) is the live proof through the gateway.
- **Library public APIs unchanged** ✅ — the slice added a second app + app-side wiring + infra; no
  library module changed.
- **The hard rules hold in the rig** ✅ — owner writes, viewer can't, a team-scoped custom role grants
  write, a non-member is denied, and the dogfood path authorizes the actor.
- **`ddl-auto: validate`** ✅ — the user-service boots clean against its own Postgres in the rig.

**Refactor applied during the gate:** the e2e needed deterministic data keyed on runtime values (the
team-target catalog id and the IdP `sub`s, unknown until tokens are minted). Rather than a brittle
fixed-`sub` realm hack, added the small idempotent **internal bootstrap** surface (under the already
in-network-only `/internal/**`) and had `run-team-matrix.sh` seed through it after decoding the token
subjects — keeping the *public* management API the demonstrated, secured path and the bootstrap as
clearly-scoped test scaffolding. A fixed demo catalog row is seeded into the catalog DB so the
team-target is stable.

## Integration / e2e

The heavy validation **is** this ticket: the rig up with both services (`ENABLE_OIDC=1
ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`), the catalog pods pointed at the user-service, the
newman team matrix green twice. `bash -n` clean on the scripts; the compose + collection JSON validate.

## Decisions recorded

Recorded one Mulch insight (the second-service rig wiring + the runtime-bootstrap e2e pattern + the
team-policy-in-shared-OPA gotcha). `ml sync` (`.mulch`-only); `ml doctor` clean.

## Commit

- `mulch: …` (`.mulch` only) + `chore(infra)+test(e2e)+docs(...): user-service in the rig, team matrix,
  docs, ship (T9)` — code + infra + e2e + docs + the folder move.
