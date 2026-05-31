---
tags:
  - status/implemented
  - type/project
  - area/spring-security
  - area/abac
---

# STATUS — Ticket 3: Security: @OpaPreAuthorize + authorization manager + role-def wiring

> Filled in at the ticket-3 checkpoint. See [[01-DECOMPOSITION]] ticket 3.

**Status:** ✅ implemented (2026-06-01)

## What shipped
All in `opa-abac-spring-security` (`dev.dmitriikonovalov.opaabac.security`):

- **`@OpaPreAuthorize`** — `@Target(METHOD) @Retention(RUNTIME)`: `action()`, plus SpEL
  `resourceType()` / `resourceId()` / `resource()` (the last → an `AbacDataObject` instance). Documented
  as pre-invocation (coarse, type-level resource; rich, role-definition-driven decision).
- **`OpaPreAuthorizeAuthorizationManager implements AuthorizationManager<MethodInvocation>`** — Spring
  Security 6.4 `check(Supplier<Authentication>, MethodInvocation)` (a code comment flags the 7.0
  `authorize()` rename). Reads the `Subject` from the current `AbacAuthentication`; resolves the resource
  (an `AbacDataObject` via `resource()`, else type/id via SpEL bound to the method args); calls
  `RoleDefinitionSupplier.lookup(userId, type, id)`; builds the single `AbacContext` (subject + action +
  resource + **role definition** + env); `opaClient.allow(ctx)` → `AuthorizationDecision`. **Fail-closed:**
  unauthenticated / unresolvable / any exception → denied.
- **`OpaMethodSecurityConfiguration`** — `@Bean AuthorizationManagerBeforeMethodInterceptor` advisor with
  a pointcut matching `@OpaPreAuthorize` (method + class), ordered just before `@PreAuthorize` (190).
- **`OpaAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext>`** — opt-in
  request-level manager (action = lowercased HTTP method; resource type from a longest-prefix path map +
  fallback); fail-closed. The app wires it only if it wants request-level rules; `@OpaPreAuthorize`
  remains the headline.

## Tests
`./gradlew :opa-abac-spring-security:test` — **28 passed, 0 failed** (T2's 14 + T3's 14).
- **`OpaPreAuthorizeAuthorizationManagerTest`** (mocked `OpaClient` + `RoleDefinitionSupplier`, stub
  `MethodInvocation`): U22 allow→granted, U23 deny→not-granted, U24 OPA-error→fail-closed-deny, U25
  unauthenticated→deny, U26 unresolvable-type→deny, unannotated→abstain(null), **U27/U28 ArgumentCaptor
  proves the looked-up `RoleDefinition` + action + resource type/id reach the `AbacContext` sent to OPA**,
  U29 `resource()` SpEL resolves an `AbacDataObject` (type/id/attributes).
- **`OpaMethodSecuritySliceTest`** (real interceptor via `ProxyFactory.addAdvisor`): allow runs the
  method; deny throws `AccessDeniedException`; unauthenticated throws `AccessDeniedException`.
- **`OpaAuthorizationManagerTest`**: lowercased-method + longest-prefix type, unauthenticated deny,
  OPA-error fail-closed deny.

## Architecture review + refactor
Ran the gate against `00-DESIGN.md`:
- **Fail-closed (second layer)** — confirmed: every exception/unauth/unresolvable in both managers →
  `DENY`; never an allow on error. Proven by U24/U25/U26 + `opaError_failClosedDeny`.
- **Boundary** — import scan shows only `java.*`, Spring (`core`/`aop`/`expression`/`security`),
  `org.aopalliance.*`, `jakarta.servlet.*`, and the project's `core`. Core untouched.
- **Pluggability / DIP** — the managers depend only on the `OpaClient` + `RoleDefinitionSupplier`
  interfaces; the role-definition lookup is the documented Phase-4 swap seam; the annotation is
  declarative.

**Refactor applied (two real items):**
1. **Correctness fix the slice test surfaced** — the manager originally read `@OpaPreAuthorize` off
   `invocation.getMethod()`, which on a proxied bean is the *interface* method, so the impl-method
   annotation was missed and the manager silently abstained (every call allowed). Fixed to resolve
   `AopUtils.getMostSpecificMethod(method, targetClass)` before reading the annotation **and** before
   discovering SpEL parameter names. Recorded as Mulch failure `mx-b9619b` (see below).
2. **Cohesion** — bind the SpEL `StandardEvaluationContext` (method args) **once** per `resolveResource`
   and reuse it for the type/id expressions, instead of rebuilding+rediscovering parameter names per
   expression.
   Re-ran the suite green after both.

## Integration / e2e
None for T3 (unit + a proxied method-security slice). Wiring through the starter is T4; the real rig is
T5/T7.

## Decisions recorded
Mulch **failure** recorded: "custom `AuthorizationManager<MethodInvocation>` must resolve the
most-specific method before reading a method annotation; drive the slice via `ProxyFactory.addAdvisor`,
not `addAdvice`" (`mx-b9619b`, relates to `mx-410f48`). `ml sync` → `.mulch`-only commit `54ff547`.

## Commit
`feat(abac-security): role-definition-driven @OpaPreAuthorize + managers + method-security advisor`.
