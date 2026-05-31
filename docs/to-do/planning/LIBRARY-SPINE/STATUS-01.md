---
tags:
  - status/implemented
  - type/project
  - area/abac
  - area/spring-security
---

# STATUS — Ticket 1: Core: HttpOpaClient + RoleDefinition/SPI + policy-path resolver

> Filled in at the ticket-1 checkpoint. See [[01-DECOMPOSITION]] ticket 1.

**Status:** ✅ implemented (2026-06-01)

## What shipped
All in the Spring-free `opa-abac-core` (`dev.dmitriikonovalov.opaabac.core`):

- **`RoleDefinition`** — record `(String code, Map<String,Object> attributes,
  Map<String,List<String>> permissions)`. Compact ctor null-normalizes and makes deep defensive copies
  (each permission list `List.copyOf`'d, the outer maps unmodifiable) so post-construction mutation of
  the inputs can't leak in.
- **`RoleDefinitionSupplier`** — `@FunctionalInterface`,
  `Optional<RoleDefinition> lookup(userId, resourceType, resourceId)` (`resourceId` nullable).
- **`NoOpRoleDefinitionSupplier`** — default, always `Optional.empty()`.
- **`AbacContext`** widened with a **nullable** `roleDefinition` (4th component, before `environment`).
  Serialized as `role_definition` (`@JsonProperty`) and **omitted when null** (`@JsonInclude(NON_NULL)`),
  so it stays the single OPA-input model. Kept a 4-arg convenience ctor `(subject, action, resource,
  environment)` so existing no-role-def callers compile unchanged.
- **`PolicyPathResolver`** SPI — `String resolve(AbacContext)` (path, no leading/trailing slash).
- **`PerTypePolicyPathResolver`** — `policyPrefix + "/" + resourceType`, trimming blank/slashed
  segments (blank prefix ⇒ just the type; blank type ⇒ just the prefix). One rego document per type.
- **`OpaClientConfig`** — immutable carrier `(baseUrl, Duration timeout, String decisionField)`;
  `decisionField` defaults to `allow`, `baseUrl` trailing slashes normalized.
- **`HttpOpaClient implements OpaClient`** — JDK `java.net.http.HttpClient` + shared `ObjectMapper` +
  the resolver + the config. `allow()` resolves the per-type path → POSTs `{"input": <context>}` to
  `<baseUrl>/v1/data/<path>` → reads `result.<decisionField>` as a boolean. **Fail-closed** on
  non-200 / `IOException` / timeout / connection-refused / malformed body / missing-or-non-boolean
  field; never throws for OPA failures; WARN logs carry the path + status, never the token. The
  `{"input": …}` wrapper and the `{"result": …}` reader are private records; the reader is
  `@JsonIgnoreProperties(ignoreUnknown = true)` (per Mulch `mx-4b9b0c`).

## Tests
`./gradlew :opa-abac-core:test` — **19 passed, 0 failed.** No WireMock: an in-process
`com.sun.net.httpserver.HttpServer` stub.
- **`HttpOpaClientTest`** — U1 allow, U2 deny, U3 500, U4 connection-refused, U5 timeout, U6
  malformed/missing-field/non-boolean/empty-result (4 variants), U7 request-body shape incl.
  `role_definition` (+ U7b omitted-when-null), U8 resolved per-type path (+ U8b blank-prefix).
- **`RoleDefinitionTest`** — U9 defensive copies + null-normalize + immutable returns; U10 no-op
  supplier empty.
- **`PerTypePolicyPathResolverTest`** — prefix+type, blank prefix, slash trimming, blank type.

## Architecture review + refactor
Ran the gate against `00-DESIGN.md`:
- **Fail-closed** — verified two layers: the outer `try/catch(Exception)` in `allow()` and the nested
  guard in `readDecision()`; non-200 returns early; missing/non-boolean field denies. Proven by U3–U6d.
  `allow()` provably never throws.
- **Boundary** — confirmed `opa-abac-core` stays Spring-free: an import scan shows only `java.*`,
  `com.fasterxml.jackson.*`, `org.slf4j.*`, and the package itself; `runtimeClasspath` resolves to
  exactly `jackson-databind` + `slf4j-api` (no Spring, no Feign). X2 satisfied.
- **Pluggability** — `PolicyPathResolver` and `RoleDefinitionSupplier` are clean functional SPIs with
  defaults; `HttpOpaClient` depends only on the interfaces + `OpaClientConfig`; the decision field is
  configuration, not hardcoded.
- **SOLID** — `HttpOpaClient` does one thing; path resolution and role lookup are delegated;
  `readDecision` extracted for cohesion.

**Refactor applied:** nothing substantive — removed two unused imports surfaced during compile; the
design held as written. No invented churn.

## Integration / e2e
None for T1 (pure unit, no app). The heavier validation (gradle `build`, `opa test`, the rig) lands in
T5/T7.

## Decisions recorded
No new decision beyond the already-recorded role-definition backbone (`mx-360261`) and fail-closed
convention (`mx-926c85`). Honored `mx-4b9b0c` (`@JsonIgnoreProperties(ignoreUnknown=true)` on the OPA
response wrapper). Mulch record deferred — nothing non-obvious beyond existing records; will re-check
after T2/T3.

## Commit
`feat(opa-client): fail-closed HttpOpaClient + RoleDefinition SPI + per-type policy resolver` (core).
