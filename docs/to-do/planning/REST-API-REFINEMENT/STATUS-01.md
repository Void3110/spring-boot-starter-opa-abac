---
tags:
  - status/planned
  - type/project
  - area/api
  - area/spring
---

# STATUS T1 — Library: `ApiErrorCode` interface + `LibraryErrorCode` enum + `ProblemDetail` carrier + advice base/mapping helper

> Stub — filled at the T1 checkpoint. One focused commit. ☐ not yet shipped.

## What shipped

_TBD at the T1 checkpoint._

## Tests

_TBD — U1–U6 (LibraryErrorCode mapping + status; ProblemDetail build + serialization at problem+json, no `message`; AccessDeniedException→ACCESS_DENIED 403; foreign app enum plugs in)._

## Architecture review + refactor (the ★ gate)

_TBD — fail-closed (no status change / no deny swallowed); boundary/additivity (opa-abac-core untouched; only new types — example advices still build against old ApiError); module separation; pattern reuse (library-shipped ProblemDetail, not Spring's); what was refactored, or "nothing substantive"._

## Integration / e2e

_TBD — T1 is library-internal (no rig); the per-service ITs are T2/T3, the gateway e2e is T4._

## Decisions

_TBD — e.g. helper shape (factory vs abstract advice base); default-method derivation of problemType()/title() from code()._

## Commit

_TBD — `feat(spring-security): …` on `feature/void3110/rest-api-refinement`._
