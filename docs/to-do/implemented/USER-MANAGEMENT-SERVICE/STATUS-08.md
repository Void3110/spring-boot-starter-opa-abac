---
tags:
  - status/done
  - type/project
  - area/user-service
  - area/catalog-service
---

# STATUS — Ticket 08: Catalog adoption: HttpRoleDefinitionSupplier swaps the demo one

> Filled in at the ticket-08 checkpoint. See [[01-DECOMPOSITION]] ticket 8.

**Status:** ✅ done

## What shipped

The catalog resolves real roles via the user-service — a single-bean swap, **app code only** (the
library SPI is unchanged).

- `HttpRoleDefinitionSupplier` (catalog `config/`) — `implements RoleDefinitionSupplier`; calls
  `GET <base>/internal/effective-role?userId&resourceType&resourceId` on the JDK `HttpClient` + Jackson
  (no Feign/RestTemplate/WebClient). **Fails closed**: a non-200 (incl. the 204 no-match), a timeout, a
  connection refused, or a malformed body → `Optional.empty()` → the policy default-denies. Logs status
  only, never the token. `userId` is the forwarded IdP subject.
- `@ConditionalOnProperty(name="catalog.role-source", havingValue="http")` on the HTTP supplier;
  `DemoRoleDefinitionSupplier` gains `havingValue="demo", matchIfMissing=true` — so `demo` stays the
  default and the two are mutually exclusive. Base URL + timeout via `catalog.user-service.*`.
- `application.yml` documents the toggle (`CATALOG_ROLE_SOURCE`, `CATALOG_USER_SERVICE_BASE_URL`,
  `CATALOG_USER_SERVICE_TIMEOUT_MS`).

## Tests

`:example-catalog-management-service:test` → **green (14)**. New `HttpRoleDefinitionSupplierTest`
(in-process `com.sun.net.httpserver.HttpServer` stub, no WireMock — mirroring `HttpOpaClientTest`):
- **H1** resolve round-trip (`200 {RoleDefinition}` → `Optional.of`, code + permissions intact);
- **H2** no-match (`204`) → `Optional.empty()`;
- **H3** fail-closed on 500 / malformed body / connection-refused → `Optional.empty()`;
- **H4** the request URL shape (`/internal/effective-role?userId&resourceType&resourceId`).
- **H5/H6** the existing `CatalogCrudIT` / `ProductConcurrencyIT` / `BaseEntityAuditingIT` stay green
  under the default (`demo`) profile — the swap is opt-in and changes nothing by default.

`./gradlew build` (whole repo) → green.

## Architecture review + refactor

- **Fail-closed** ✅ — six tests cover every failure path → empty → deny.
- **Library API unchanged** ✅ — the HTTP supplier is **catalog app code** implementing the existing
  SPI; the diff touches only the two example apps, no library module (boundary check confirmed).
- **Single-bean swap** ✅ — mutually-exclusive `@ConditionalOnProperty`; `demo` default keeps existing
  ITs intact.
- **No Feign/RestTemplate/WebClient** ✅ — JDK `HttpClient` + Jackson, matching `HttpOpaClient`.
- **`userId` = subject** ✅ — forwarded straight to the resolve API (matches the user-service's E*).

**No refactor applied** — the supplier is focused and mirrors `HttpOpaClient`'s structure; no invented
churn.

## Integration / e2e

Unit-level here (the `HttpServer` stub); the full two-service path through the gateway (the catalog
pointed at a live user-service) is proven in T9.

## Decisions recorded

Nothing non-obvious beyond the already-recorded patterns (the app-resolved HTTP supplier + the
demo→http swap are covered by `mx-723b5c` / `mx-360261`). No Mulch record — no ritual filler.

## Commit

`feat(example): catalog HttpRoleDefinitionSupplier swaps the demo one (T8)` — code + tests + this note.
