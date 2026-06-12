---
tags:
  - status/done
  - type/project
  - area/abac
  - area/spring
  - area/opa
---

# STATUS — T2: spring-security: manager resolution flow + AbacResourceCache + VersionConflictException → 409

**Status:** ✅ DONE

## What shipped

Package `dev.dmitriikonovalov.opaabac.security`:

- `AbacResourceCache` — `get(type, id, Class<T>)` / `put(type, id, resource)`; Javadoc pins
  write-through-on-allow, request-bounded, **never consulted by decisions**.
- `RequestAttributesResourceCache` — storage via `RequestContextHolder` request attributes; no
  request context → `get` empty / `put` no-op, never throws; null resources dropped.
- `ResourceResolutionSupport` — `(resolver, nullable chainSupplier, cache)` composition; package-
  private accessors (only the manager consumes it; the starter constructs it in T3).
- `OpaPreAuthorizeAuthorizationManager` — **constructor overload** taking the support (the existing
  2-arg ctor delegates with `null`, byte-compatible). `resolveResource` became `resolveCheck`
  returning a private `ResolvedCheck(resource, roleType, roleId, instance)`; with support present,
  the declared-`resourceId` branch runs `resolveInstance`: resolver empty → deny (no OPA call) /
  throw → the global fail-closed catch (deny); ancestor throw → caught locally, chain `[]`
  (direct-only); role looked up **once on the governing root** (`ancestors.isEmpty() ? leaf :
  ancestors.get(0)` — `HierarchicalAuthorizer`'s rule, quoted in the comment); context carries
  `abacAttributes()` + ancestors; `cache.put` only on allow (the `resource()`-SpEL branch puts too,
  decision inputs unchanged). Type-level checks never engage the resolver.
- `AbstractProblemAdvice` — `@ExceptionHandler(VersionConflictException.class)` →
  `LibraryErrorCode.STATE_CONFLICT` 409 problem+json with a **static detail** ("The resource changed
  after it was authorized; re-read and retry") — no versions/internals in the body.

## Tests

`:opa-abac-spring-security:test` green — 18 new cases across three classes, every pre-existing test
**unmodified** (no stub widened; the additive-overload proof):

- `OpaPreAuthorizeAuthorizationManagerResolutionTest` (U5–U12): the U5 golden pinned the serialized
  baseline context for an id'd check, string-equal with the production mapper defaults:
  `{"subject":{"id":"user-1","roles":["catalog-editor"],"attributes":{"username":"alice"}},"action":"product:write","resource":{"type":"product","id":"11111111-1111-1111-1111-111111111111","attributes":{}},"environment":{}}`
  (role_definition omitted via NON_NULL; ancestors omitted via NON_EMPTY — the pre-5.97 wire shape).
- `RequestAttributesResourceCacheTest` (U12 cache half): round-trip, wrong-Class empty, key misses,
  no-context no-op, null dropped.
- `VersionConflictAdviceTest` (U13): 409, `application/problem+json`, `errorCode=STATE_CONFLICT`,
  instance = request URI, detail free of versions/reference/exception text.

## Architecture review + refactor

Review path: branch-structure walk of the fail-closed paths; cache-as-input grep; additivity check
against the pre-existing test class; pattern check vs `HierarchicalAuthorizer`/ADR-0011.

- With support present there is **no code path to an attribute-less context** for a declared id —
  the `resolutionSupport == null` branch is the only baseline exit, then `resolveInstance` either
  returns a full context or denies.
- The two failure semantics are structurally separated: instance-throw propagates to the global
  catch (deny); ancestor-throw is caught inside `resolveInstance` (collapse). Neither can take the
  other's path.
- The manager's only cache call is the single allow-gated `put` — the gate cannot read the cache.
- The `resource()`-SpEL branch's decision inputs are bit-identical to before (same `Resource`
  construction); it gained only the put.
- Module boundary: the manager imports core types only; no spring-data dependency.
- **Nothing substantive refactored** — the first-pass structure already isolated the semantics; all
  tests green first run.

## Integration / e2e

Not applicable for T2 (no starter wiring yet — T3; live 409 path reached by T4's IT per plan).

## Decisions

- A chain supplier returning `null` (contract breach, not a throw) is treated as the empty chain —
  the same narrowing posture, never a widening or an NPE-deny.
- `ResourceResolutionSupport` accessors are package-private: the type is a manager-facing
  composition, not public API surface beyond construction.

## Commit

`feat(spring-security): gate resolution flow + request-scoped resource cache + 409 mapping (T2)` —
see `git log` on `feature/void3110/resource-resolution`.
