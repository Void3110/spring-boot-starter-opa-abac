---
tags:
  - status/active
  - type/review
  - area/docs
---

# Code Review Workflow

The lifecycle a review follows in this repo, driven by the `/deep-review` skill. Adapted from a
mature review process; trimmed to what this project needs today and expanded as the
library and example grow.

## Phases

### 0. Intake & prime
- Identify the change set (branch diff vs `main`).
- Prime expertise: `ml prime code-review-process`, plus `ml search` for the touched surface
  (OPA/Rego, Spring Security, Spring Data, OpenAPI, build).
- Read the relevant guides (see the selection matrix below).

### 1. Analyze
- Understand intent and blast radius. Map changed files to the modules they affect
  (`opa-abac-*` library vs `example/*` app).
- For a **library** change, ask: does the public API stay clean and framework-appropriate?
  Is anything Spring-specific leaking into `opa-abac-core`?
- For an **example** change, ask: does it still demonstrate the concept clearly?

### 2. Deep review (find issues)
Scan for, at minimum:
- **Correctness** — logic, null/edge handling, error paths.
- **Authorization** — fail-closed behavior; no bypass; gateway and app checks consistent.
- **Schema** — JPA entity ↔ Liquibase changelog ↔ real Postgres agree (Testcontainers catches this).
- **API contract** — OpenAPI spec ↔ controllers ↔ DTOs in sync; codegen still clean.
- **Tests** — integration tests cover the change against real Postgres; assertions are meaningful.
- **Public-surface hygiene** — no proprietary names/text; Javadoc on public types; module boundaries respected.

### 3. Fix
- Apply fixes (or file findings). Re-run `./gradlew build` — the Testcontainers IT must stay green.

### 4. Verify
- `./gradlew build` green (libraries + example + IT).
- Where relevant, a manual run (`./profile.sh up` + `bootRun` + a curl) for behavior not covered by tests.

### 5. Document & record
- Update affected docs (`docs/guides`, `docs/architecture`, `docs/api`).
- Record durable insights in Mulch (`ml record … && ml sync`).

## Guide selection matrix

| Changed surface | Read |
|-----------------|------|
| OPA / Rego policies | `guides/` Rego + OPA notes; `/rego-skill` |
| Spring Security integration | `architecture/` ABAC model + Spring Security extension notes |
| Spring Data / partial-eval | `architecture/` data-filtering notes |
| OpenAPI / controllers | `guides/` OpenAPI codegen guide; `docs/api/` |
| Build / Gradle | `guides/` build notes |
| Example app behavior | `docs/api/`, the example's README |

## Mulch domains used

`code-review-process`, plus per-surface domains as the store grows (`api-design`, and
ABAC/OPA-specific domains added in this repo).
