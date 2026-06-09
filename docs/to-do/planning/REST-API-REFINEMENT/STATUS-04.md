---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# STATUS T4 — e2e: EXTEND the existing newman matrices (`problem+json` + `errorCode` on negatives; `Location` on 201s)

> Stub — filled at the T4 checkpoint. One focused commit. ☐ not yet shipped.

## What shipped

_TBD at the T4 checkpoint — which existing matrices were extended and with which assertions (no new collection)._

## Tests

_TBD — E1–E5 (a live 403→ACCESS_DENIED problem+json; a live 422/409 + the right errorCode; a live 201 carries Location; optional 404/400/503)._

## Architecture review + refactor (the ★ gate)

_TBD — test-asset-only (newman JSON + shell); confirm no new collection, no compiled-code change; if the e2e revealed a real body-shape bug, it was fixed by amending the relevant T2/T3 commit._

## Integration / e2e

_TBD — full rig (`ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`; `./profile.sh up` first; `./deploy.sh build` to force new code); the extended matrices green, stable across reruns._

## Decisions

_TBD — e.g. which matrix hosts each negative-case assertion._

## Commit

_TBD — `test(e2e): …` on `feature/void3110/rest-api-refinement`._
