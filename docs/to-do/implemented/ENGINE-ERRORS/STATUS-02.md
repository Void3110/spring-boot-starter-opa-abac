---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T2: The gates throw `AuthorizationIndeterminateException`; the base advice answers 503

**Status:** ✅ DONE (2026-10-05, collaborative)

## What shipped

- **`AuthorizationIndeterminateException extends AuthorizationServiceException`** (new, `security` package) —
  constructor takes the core `DecisionIndeterminateException` cause (non-null).
- **`OpaPreAuthorizeAuthorizationManager`** — the `RoleResolutionException` catch widened to the family and
  turned into a throw; the catch-all `DENY` untouched for everything outside it. Rethrow guards ahead of the
  three degrade-catches (`parentGovernedByRoleTarget`, `resolveAttributesOf`, the ancestor walk in
  `resolveInstance`); a family member from the resource resolver now reaches the family catch instead of the
  catch-all. The indeterminate path logs at DEBUG (the WARN is the classifier's). Javadocs follow.
- **`OpaAuthorizationManager`** — the same catch change; the class doc says the filter chain's
  `AccessDeniedHandler` answers it (403 by default) and how an app gets a 503.
- **`ActionEnrichmentAdvice`** — the role-batch catch widened to the family (a custom supplier's own subtype can
  no longer escape `beforeBodyWrite`); the bulk catch's comment no longer claims the shipped client never throws.
- **`AbstractProblemAdvice.handleIndeterminate`** — `AuthorizationIndeterminateException` and the raw
  `DecisionIndeterminateException` → 503 `DEPENDENCY_UNAVAILABLE`, detail "Authorization is temporarily
  unavailable", no `Retry-After`. `LibraryErrorCode.DEPENDENCY_UNAVAILABLE`'s javadoc names the new use.

## Tests

- `OpaPreAuthorizeAuthorizationManagerTest`: role outage → thrown with its cause, OPA never asked (rewritten);
  engine failure → thrown with its cause; the type is an `AuthorizationServiceException` caught by a
  `catch (AccessDeniedException)` and **not** an `AuthorizationDeniedException`; a non-family throw stays a
  plain `DENY`. `OpaAuthorizationManagerTest`: the same two throws (rewritten + new). **U18–U21**; U22 (answers
  unchanged) is held by the existing step-up and deny cells, all green.
- The rethrow invariant, one cell per guard — `OpaPreAuthorizeAuthorizationManagerResolutionTest` (resolver,
  ancestor walk), `OpaPreAuthorizeRootAttributeEnrichmentTest` (root resolver),
  `OpaPreAuthorizeParentAttributesTest` (placement-parent walk), all with a test `SpiOutage` family subtype.
  **U23.**
- `ActionEnrichmentAdviceTest`: a `PolicyEngineException` from `allowAll` and a custom family subtype from the
  role batch both omit the group (the latter with no OPA call). **U24.**
- **I1** — `OpaMethodSecuritySliceTest`: a manager that is also a `MethodAuthorizationDeniedHandler` (how
  `@HandleAuthorizationDenied` is wired — verified in the 7.0.7 bytecode) masks a plain deny (control) but never
  sees an indeterminate decision, which propagates past it.
- **I2** — `ProblemDetailContractTest`: both types render 503 problem+json with the code and detail, no
  `Retry-After`; Spring MVC's own `ExceptionHandlerMethodResolver` picks `handleIndeterminate` for the gate's
  type, the raw engine exception and `RoleResolutionException`, and `handleAccessDenied` for a plain denial.
- **I3** — `SupplierOutageGateIT` (Testcontainers): the outage cell flipped to 503 `DEPENDENCY_UNAVAILABLE` +
  detail; OPA never called; the row byte-identical; the contrast cell unchanged.
- `./gradlew build`: **green** (1m11s). spring-security 249 tests, catalog 290, 0 failures.

## Architecture review + refactor

Self-review: nothing substantive. Checked that no user-management IT pinned a gate outage as 403 (the
validator's note held — the full build is green without touching one) and that
`OpaPreAuthorizeRootAttributeEnrichmentTest` had no outage cell to move (it gained one instead).

## Integration / e2e

No rig run (T5). With T2 in, the T1 intermediate state is gone: a list over a failing OPA now answers 503 via
the base advice in both example services.

## Decisions

- The resource resolver opting in needed no new guard: its throw already reached the gate's outer catches, and
  widening the role catch to the family is what makes it indeterminate (pinned by
  `resolverThrowsAFamilyMember_isIndeterminate`).
- I1 builds the interceptor directly over an `@OpaPreAuthorize` pointcut, not through
  `OpaMethodSecurityConfiguration`: that factory wraps the manager in a lazy provider, which would hide the
  `MethodAuthorizationDeniedHandler` interface the test relies on.

## Commit

`8364947` — feat(security): the gates throw AuthorizationIndeterminateException; the base advice answers 503
(ENGINE-ERRORS T2).
