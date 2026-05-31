---
tags:
  - status/planned
  - type/project
  - area/spring-data
  - area/catalog-service
---

# STATUS — Ticket 3: Example schema + entity adoption

> Filled in at the ticket-3 checkpoint. See [[01-DECOMPOSITION]] ticket 3.

**Status:** ☐ not started

## What shipped
_The Liquibase `0002` changeset (columns/indexes); the 3 entities extending the secure base;
`@EnableJpaAuditing` + `AuditorAware`; repo changes; the build dep._

## Tests
_`./gradlew build` result; `CatalogCrudIT` unchanged-green; the `ddl-auto: validate` boot proof;
auditing/version checks._

## Architecture review + refactor
_Vs [[DOMAIN-MODEL]]: schema↔mapping alignment; tags on secure base only. What was refactored.
If nothing: say so._

## Decisions recorded
_e.g. created_at default strategy; GIN index kept/dropped. Mulch record(s)._

## Commit
_`feat(domain-model): …` subject + short hash._
