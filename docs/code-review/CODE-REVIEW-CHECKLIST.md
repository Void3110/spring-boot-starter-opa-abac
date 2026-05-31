---
tags:
  - status/active
  - type/review
  - area/docs
---

# Code Review Checklist

Per-finding checklist. Not every item applies to every change — use the
[workflow](CODE-REVIEW-WORKFLOW.md) to scope.

## Library (`opa-abac-*`)

- [ ] `opa-abac-core` has **no Spring dependency** — it stays framework-agnostic.
- [ ] Public types are documented (Javadoc) and named for a general audience (not the example's domain).
- [ ] New public API is minimal and hard to misuse; defaults are safe.
- [ ] Authorization helpers **fail closed** — any error/uncertainty denies, never allows.
- [ ] Module dependencies flow one way: `core` ← `spring-security`/`spring-data` ← `starter`. No cycles.
- [ ] Auto-configuration is conditional (`@ConditionalOn…`) and overridable (`@ConditionalOnMissingBean`).
- [ ] Configuration properties are documented and have sensible defaults.

## Example app (`example/*`)

- [ ] OpenAPI spec ↔ controllers ↔ generated DTOs are consistent; `openApiGenerate` is clean.
- [ ] JPA entity columns match the Liquibase changelog match real Postgres (no `ddl-auto` validation gaps).
- [ ] Controllers implement the generated `*Api` interfaces; mapping is total (no missing fields).
- [ ] Error responses use the shared `ApiError` shape and correct status codes.
- [ ] The example still reads clearly as a teaching artifact for the ABAC concept.

## Tests

- [ ] Integration tests run against **real Postgres** (Testcontainers), not a substitute DB.
- [ ] New behavior is covered; assertions check real outcomes, not just status codes.
- [ ] `./gradlew build` is green locally (and will be in CI, which provides Docker).

## Security & authorization

- [ ] No authorization bypass; gateway-level and app-level checks are consistent.
- [ ] Secrets/credentials are never committed; no real hosts/tokens in committed files.
- [ ] Rego policies default-deny; new endpoints have explicit allow rules + tests.

## Public-surface / clean-room

- [ ] No proprietary names, identifiers, comments, or copied text anywhere in the diff.
- [ ] Anything ported from prior work is a clean reimplementation, not a copy.

## Docs

- [ ] Affected `docs/guides`, `docs/architecture`, `docs/api` updated.
- [ ] Front-matter tags present and correct (see [`../TAG-SYSTEM.md`](../TAG-SYSTEM.md)).
