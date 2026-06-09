---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# STATUS T3 — User-service: `UserMgmtErrorCode` + advice remap + OpenAPI `ProblemDetail` schema + `Location` on 201 + intent comments + MockMvc IT

> Stub — filled at the T3 checkpoint. One focused commit. ☐ not yet shipped.

## What shipped

_TBD at the T3 checkpoint — incl. the final user-service exception→`errorCode` map (which 409/422 conflicts got distinct `UserMgmtErrorCode` constants vs reused `STATE_CONFLICT`/`ROLE_SUBSET_VIOLATION`)._

## Tests

_TBD — I4–I6 + I6-201 (problem+json + the expected errorCode per status; Location on the 5 user-svc 201s), I7 (grep: intent comments present, no @OpaPreAuthorize added); C1–C3._

## Architecture review + refactor (the ★ gate)

_TBD — fail-closed (same statuses; ungated bootstrap stays ungated, only commented; 403 inherited from the library base); boundary (opa-abac-core untouched; build-breaker confined to the user-service module); semantic-granularity check; what was refactored, or "nothing substantive"._

## Integration / e2e

_TBD — MockMvc/@WebMvcTest slice; `./gradlew :example-user-management-service:build` green (codegen clean + existing ITs green). Gateway e2e is T4._

## Decisions

_TBD — the final conflict-code split; the exact intent-comment wording at UserController.createUser + TeamController.createTeam._

## Commit

_TBD — `feat(user-svc): …` on `feature/void3110/rest-api-refinement`._
