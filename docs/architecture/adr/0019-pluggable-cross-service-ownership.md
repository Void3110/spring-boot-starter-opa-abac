---
tags:
  - status/active
  - type/decision
  - area/abac
  - area/architecture
  - area/security
---

# ADR 0019 — Pluggable cross-service resource-ownership resolution

**Status:** Accepted (planned — Slice B4, [[MULTI-TENANT-ISOLATION]])
**Date:** 2026-06-29
**Context tags:** resource ownership, target squatting, `ResourceOwnershipResolver`, discovery client, `created-by` contract

> Pins the **ownership-verification fork** for **Slice B4**: how one service checks that a caller owns
> a resource that lives in *another* service, without point-to-point coupling. Settled in a planning
> interview (2026-06-29). Companion: ADR [[0018-team-scoped-resource-isolation|0018]].

## Context

Self-service onboarding lets a user create a catalog, then a **team** governing it (owner-on-create).
`createTeam(targetType, targetId)` is deliberately ungated (it precedes any membership to authorize
against) — but it binds **any** `targetId` with **no check that the caller owns it** (the documented
"target squatting" hole). With ADR 0018 making team membership the *sole* access path, squatting
becomes the **primary** way to break isolation: a user could create a team on someone else's catalog
and grant themselves owner access. The hole must be closed.

The creator is recorded on the **catalog** (`created_by`, the `sub`, in the catalog service); the
team-create happens in the **user-service**. A naïve fix — the user-service calls the catalog service
directly — creates a bidirectional point-to-point dependency that does **not scale**: with *N*
resource-owning services, the user-service would need *N* hardcoded clients.

## Decision

**A pluggable, type-keyed ownership resolver with config-driven service discovery — productized in the
library.**

1. **SPI** (`ResourceOwnershipResolver`): `boolean isOwner(String subject, String resourceType,
   UUID resourceId)`. Keyed by resource **type**; **fail-closed** (false on any breach).

2. **Default impl `DiscoveryOwnershipResolver`:**
   - A **config-driven type→base-URL registry**: `abac.ownership.services.<type> = <url>` (cached as
     ~static). Adding a new owning service is **one config line** + that service implementing the
     standard contract — **no new code dependency per service** (the decoupling win).
   - Calls the standard contract **`GET /internal/{resourceType}/{resourceId}/created-by`** → `200
     { "createdBy": "<sub>" }` or `404`. The endpoint is a **pure data read** (returns the creator;
     the resolver does the comparison) — so the **cache key is `(type,id)`**, subject-independent, with
     a good hit rate, and "what counts as ownership" stays one library decision, not re-implemented per
     service.
   - **Short-TTL cache** on `(type,id)→createdBy`. Ownership-transfer staleness up to the TTL is
     **documented**; event-invalidation is a follow-up.
   - **Fail-closed:** unknown type (no registry entry) / unreachable / `404` → **not owner**.

3. **Identity join key = the IdP `sub`.** The catalog's `AuditorAware` stores
   `UUID.fromString(abac.getSubject().id())` in `created_by`; the user-service's `User.subject` holds
   the same `sub`. The resolver compares the caller `sub` to `createdBy`. A non-UUID `sub` → `created_by`
   null → ownership fails closed.

4. **Use site:** the public `createTeam` (gateway path) calls
   `resolver.isOwner(callerSub, targetType, targetId)`; failure → **403**. The **`/internal/bootstrap`**
   path **bypasses** the check (trusted in-network admin seam, `permitAll`, never gateway-exposed) — so
   the seed and the e2e matrices are unaffected.

## Consequences

- **Decoupled by construction.** The call goes user-service → (discovery registry) → owning service,
  not a hardcoded user-service → catalog dependency. New owning services plug in via config + the
  standard `created-by` endpoint.
- **A reusable library primitive.** "Resolve resource ownership across services by type" is shipped by
  the starter — adopters get the SPI + discovery client; they wire the registry config and implement
  `created-by` on their owning services.
- **Architectural note:** introduces a user-service → catalog call (via discovery). Acceptable — it is
  a narrow internal read, and the alternative (duplicating ownership state) is worse.
- **TTL staleness** on ownership transfer is a known, documented trade (bounded by the TTL); a
  push/event-invalidation path is a future enhancement.

## Alternatives rejected

- **Point-to-point (user-service hardcodes a catalog client):** does not scale to N owning services.
- **Unique-`target_id` constraint + documented gap only:** closes hijacking an *already-governed*
  catalog but leaves the orphan-claim window open; insufficient for the security core when we are
  already touching it.
- **Boolean `isOwner?subject=` endpoint** (the owning service does the comparison): pushes policy into
  each service and makes the result subject-keyed (worse cache hit rate). Rejected for the pure-data
  `created-by` read.
- **A separate discovery *service* (deployable):** infrastructure overkill; an in-process discovery
  **client** with a config registry is sufficient.

## Related

- Slice: [[MULTI-TENANT-ISOLATION]] (00-DESIGN)
- Companion: [[0018-team-scoped-resource-isolation|0018]]
- Precedent: [[0014-supplier-outage-error-distinct|0014]] (fail-closed-on-failure; the in-network
  trust boundary)
