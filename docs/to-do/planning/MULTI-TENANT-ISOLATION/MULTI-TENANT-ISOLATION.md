---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
  - area/security
  - slice/B4
---

# Slice B4 — Multi-tenant isolation + self-service

> **Status:** Planned. Design settled (grill-me 2026-06-29); decomposition in progress. The slice that
> makes **team membership the sole access path** to catalogs, and adds the **self-service** flow
> (create catalog + team + members) the isolation makes meaningful — with a **real cross-service
> ownership check** so team-create cannot squat another user's catalog. Sits in the security-hardening
> **B-series** (like [[B2-SUPPLIER-OUTAGE]], [[B3-HTTP-RESILIENCE]]), **before Phase 7 publish** — a
> correctness gap the Phase-7 demo work uncovered.

## The gap

Every authenticated user currently sees **all 13 catalogs**, and `catalog-editor` can edit any of
them: the per-type policies' **realm-role fallback** grants blanket access by Keycloak realm role,
and the catalog **list** rides it (no `filter` entrypoint). The system *intends* team-governance —
a team governs a catalog, membership grants access — but the fallback contradicts it.

## The mechanism (see [[00-DESIGN]])

- **Isolation:** a role-def-only `filter` entrypoint in `catalog.rego` + a `GovernedScopeResolver`
  SPI (library) supplying the catalog list's **base scope** (`id IN (governed ids)`), composed through
  `AbacQueryService.findAuthorized`. The realm-role fallback is **removed** from the single-decision
  path of all three policies (a narrow `catalog:create` fallback is retained — creation is
  pre-membership). Pinned by ADR [[0018-team-scoped-resource-isolation|0018]].
- **Self-service + ownership:** a pluggable `ResourceOwnershipResolver` SPI + config-driven discovery
  client (TTL cache) calling a standard `/internal/{type}/{id}/created-by` contract, wired into
  `createTeam` to close the target-squatting hole; the public `/api/v1/teams*` + `/api/v1/users*`
  routed through the gateway. Pinned by ADR [[0019-pluggable-cross-service-ownership|0019]].
- **Fail-closed throughout.** `opa-abac-core` stays Spring-free.

## Tickets

| # | Ticket | Status |
|---|--------|--------|
| T1 | `catalog.rego` `filter` + fallback removal (3 policies) + narrow `create` fallback (`opa test`) | ✅ |
| T2 | `GovernedScopeResolver` SPI + `HttpGovernedScopeResolver` (catalog) — fail-closed, unit | ✅ |
| T3 | user-service `GET /internal/governed-targets` endpoint | ✅ |
| T4 | `CatalogListAuthorizer` + `JpaSpecificationExecutor<CatalogEntity>` + `listCatalogs` adoption + spring-data/service ITs | ✅ |
| T5 | `ResourceOwnershipResolver` SPI + `DiscoveryOwnershipResolver` (registry + TTL cache) — fail-closed, unit | ✅ |
| T6 | catalog `GET /internal/{type}/{id}/created-by` endpoint + confirm `created_by`=sub | ☐ |
| T7 | wire ownership into `createTeam` (public path enforces, `/internal/bootstrap` bypasses) + IT | ☐ |
| T8 | gateway routing: `usermgmt-pool` + `/api/v1/teams*` `/api/v1/users*` (init-routes.sh) | ☐ |
| T9 | demo users alice/bob/carol + seed + the e2e **isolation matrix** + docs/roadmap/Mulch | ☐ |

## Related

- Design: [[00-DESIGN]] · QA: [[10-QA-TEST-CASES]] · Prompt: [[AUTONOMOUS-IMPLEMENTATION-PROMPT]]
- ADRs: [[0018-team-scoped-resource-isolation|0018]], [[0019-pluggable-cross-service-ownership|0019]]
- Demo that exercises it: [[demo-spa-state]]
- Roadmap: [[POC-ROADMAP]] (slice B4)
