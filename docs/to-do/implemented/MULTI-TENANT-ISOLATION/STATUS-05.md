---
tags:
  - status/done
  - type/project
  - area/abac
---

# STATUS — T5: ResourceOwnershipResolver SPI + DiscoveryOwnershipResolver (registry + TTL cache)

**Status:** ✅ DONE

## What shipped

- **`opa-abac-spring-security`** (`…security.ownership`):
  - `interface ResourceOwnershipResolver { boolean isOwner(String subject, String resourceType, UUID
    resourceId); }` — type-keyed, fail-closed (false on every breach, never throws past its boundary).
    In the security module (needs no Spring Data type → not core).
  - `OwnershipProperties` (`@ConfigurationProperties("abac.ownership")`): `Map<String,String> services`
    (type→base-URL), `Duration ttl` (default 30s), `long timeoutMs` (default 2000).
  - `DiscoveryOwnershipResolver implements ResourceOwnershipResolver`: registry lookup → `GET
    {base}/internal/{type}/{id}/created-by` → compare `createdBy` to the caller. Short-TTL cache keyed
    `(type,id)` (subject-independent) with an **injectable `Clock`** (system in prod, virtual in tests).
    JDK `HttpClient` + Jackson. Only authoritative `200`/`404` results are cached — a transient outage is
    NOT cached (so it can't pin a wrong answer for the TTL).
- **`opa-abac-spring-boot-starter`** — `OwnershipAutoConfiguration`: `@ConditionalOnProperty(abac.ownership
  .enabled=true)` (default off) + `@EnableConfigurationProperties(OwnershipProperties)` +
  `@ConditionalOnMissingBean` so an adopter can override. Imported by `OpaAbacAutoConfiguration`. Uses
  `ObjectProvider<ObjectMapper>` (falls back to a plain mapper) so it wires cleanly in a bare context.

## Tests

- `DiscoveryOwnershipResolverTest` (in-process `HttpServer` stub, virtual `Clock`) — **11/11**: U5
  (owner→true), U6 (mismatch→false), **U7 (unknown type→false, NO call)**, **U8 (404/5xx/unreachable→
  false)**, **U9 (cache hit→ONE fetch for three checks, subject-independent key)**, **U10 (TTL expiry at
  virtual time→re-fetch)**, plus blank/malformed→false, blank-subject/null-coords→false-no-call, and
  outage-not-cached-recovers.
- `OpaAbacAutoConfigurationTest` (+2) — disabled (default) → no `ResourceOwnershipResolver` bean (so
  `createTeam` fails closed); enabled → `DiscoveryOwnershipResolver` wired + properties bound (services
  map, ttl).
- `./gradlew :opa-abac-spring-security:test :opa-abac-spring-boot-starter:test` green; `opa-abac-core`
  has **zero** new Spring imports (verified).

## Architecture review + refactor

- **Fail-closed (keystone):** `false` on every non-affirmative outcome, **no throw past the boundary**
  (a throw a caller might catch-and-allow would re-open squatting). The three squat-reopening widenings
  are refuted: no default-true (default is false), no throw-then-allow (no escape), no silent unknown-type
  allow (U7 false + no call).
- **Cache correctness:** only authoritative results cached; transient outage NOT cached
  (`outageNotCached_recoversOnNextCall`). Subject-independent key → good hit rate without leaking which
  subject asked.
- **Clock discipline:** injectable `Clock`, TTL asserted at virtual time — zero `Thread.sleep`, no
  wall-clock flakiness (the B3 discipline, ADR 0017).
- **Module separation:** SPI + impl + props in spring-security; wiring in the starter; core untouched.
- **Refactor applied (not churn):** the auto-config first injected `ObjectMapper` directly, which failed
  the starter slice test (no Jackson in that context). Switched to `ObjectProvider<ObjectMapper>` with a
  plain-mapper fallback — also more robust for bare-context adopters.

## Integration / e2e

N/A for T5 (pure unit + auto-config slice). The resolver's **consumer is T7** (`createTeam` ownership
gate); the live `created-by` endpoint it reads is **T6**; the e2e squat-deny is **E7** in T9. T5 pins the
resolver's fail-closed contract + cache/TTL in isolation.

## Decisions

- **Opt-in by `abac.ownership.enabled` (default off):** a service that does no self-service ownership
  check (catalog) pays nothing; the user-service opts in with one flag + the `services` registry.
  Absent/empty registry → not-owner → deny (fail-closed in every misconfiguration).
- **Pure-data `created-by` read (resolver does the compare):** the cache key is `(type,id)`,
  subject-independent (ADR 0019) — better hit rate, and "what counts as ownership" stays one library
  decision, not re-implemented per owning service.
- **TTL staleness on ownership transfer is bounded by the TTL and documented** (ADR 0019); event
  invalidation is a follow-up.

## Commit

(see branch `feature/void3110/multi-tenant-isolation`)
