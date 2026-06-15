---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/security
  - area/spring
---

# STATUS — T4: example — strict HTTP classification + consumer wrap + conformant showcase

**Status:** ✅ DONE

The actual outage source stops swallowing failures and **classifies** (throws); the list consumer maps
the throw to its fail-closed empty page; the dogfooding user-service supplier becomes conformant.

## What shipped

- **`HttpRoleDefinitionSupplier.lookup`** — the strict classification (ADR 0014 §4): **only `204` →
  `Optional.empty()`**, **only `200`+non-blank+parseable body → resolved**; **everything else throws
  `RoleResolutionException`** — `200`-blank, all 4xx, all 5xx/other status, `IOException` (timeout /
  connection-refused / reset), Jackson parse failure. `InterruptedException` restores the interrupt flag
  before throwing. WARN at each throw site with **status code or exception class only** (never
  `userId`/token/body); the cause is wrapped (logs only). Class javadoc rewritten: the old "NOT fully
  fail-closed (tracked: B2)" section replaced with the now-true tri-state posture.
- **`CategoryListAuthorizer.readable`** — the governing-catalog role lookup is wrapped:
  `catch (RoleResolutionException) → return Page.empty(pageable)` (matches its no-role empty-list
  posture; prevents an uncaught throw → 500). Added a logger + DEBUG log (class name only). The
  hierarchy widening path (`resolveSubtreeSpec` → `SubtreeSpecResolver`) is already T3-fail-closed, so
  no throw escapes there.
- **`TeamRoleDefinitionSupplier.lookup`** — minimal touch: the data access (`users.findBySubject` →
  `effectiveRoles.managementRole`) is wrapped so `org.springframework.dao.DataAccessException` → **throw**
  `RoleResolutionException("team role source unavailable", e)`. The no-role guards (not a team /
  unparseable id / no id / no user / not a member) stay `Optional.empty()`. Javadoc note added.
- **`DemoRoleDefinitionSupplier`** — javadoc-only note: an in-process supplier never throws.

## Tests

- `HttpRoleDefinitionSupplierTest` (QA **U7–U12**) — extended the in-process `HttpServer` stub: 200+body
  → resolved (U7); 204 → empty (U8); 200-blank → throws (U9); 500 & 503 → throws (U10); 404 & 400 →
  throws (U11); malformed-200 / connection-refused / timeout (slow handler past the 500ms request
  timeout) → throws (U12); **`outageThrow_carriesNoPii`** asserts the thrown message + cause contain
  neither a distinctive `userId` nor the response body. (The three pre-existing fail-closed-to-empty
  cells were **updated** to assert throws — they tested the now-removed swallow behavior.)
- `CategoryListAuthorizerOutageTest` (QA **U13**, new) — outage → empty page, `getTotalElements()==0`,
  `AbacQueryService.findAuthorized` **never** called.
- `TeamRoleDefinitionSupplierTest` (QA **U14**, new) — `DataAccessException` → throws; no-user /
  not-a-member / not-a-team / unparseable-id → `Optional.empty()`.
- **All PASS.** `./gradlew build` green (all modules + both example services + their ITs).

## Architecture review + refactor

**One deliberate design choice, no churn to undo.** I classified transport failures with **narrow**
catches (`IOException`, `InterruptedException`, `JacksonException`) rather than a broad `catch (Exception)`
— so a *programming* bug (e.g. an NPE) is **not** mislabeled as a `RoleResolutionException`; it
propagates to the gate's broad catch as a generic deny. That granularity is intentional and matches the
strict-classification intent. Checks: **fail-closed** — every non-204/non-200-valid outcome throws
(U9–U12); list → empty page (U13); team → throw on outage, empty on no-role (U14). **security** — the
four named widenings cannot happen: swallow-to-empty (gone), non-204→no-role (only literal 204),
unswept-consumer (all 5 swept across T2–T4), PII-in-logs (status/class only — `outageThrow_carriesNoPii`
+ a grep of every log/throw line confirms no userId/token/body). **interrupt** flag restored.
**additivity** — the 5.97 resolver/cache, `AbacQueryService` + 4 `findAuthorized` paths, pagination,
every `@OpaPreAuthorize`, OpenAPI, Rego, schema all byte-identical (full build incl. catalog ITs green);
NoOp/Demo never throw; HTTP client timeouts unchanged (resilience = B3). **clean-room** — scan of all
four touched files clean.

## Integration / e2e

The unit classification matrix is the deterministic proof; the through-the-rig + headline IT are T5.
Full `./gradlew build` green here proves no existing IT relied on the old swallow-everything behavior
(catalog ITs default to the `demo` role-source; live `http`-mode paths still see 204/200 as designed).

## Decisions

None reopened. Implements ADR 0014 §4 (strict HTTP invariant) + §7 (implementor conformance:
Http=classify, Team=minimal-touch, Demo/NoOp=never-throw). The pinned semantic #2 (strict 204-only)
holds exactly; no new error code (an outage is the uniform 403 once it reaches a gate).

## Commit

`feat(example): strict role-source outage classification + fail-closed consumer wrap` — branch
`feature/void3110/supplier-outage`.
