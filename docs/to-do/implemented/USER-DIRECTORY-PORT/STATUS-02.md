---
tags:
  - status/done
  - type/project
  - area/security
  - area/api
---

# STATUS — T2: New module `opa-abac-keycloak-directory`: `KeycloakUserDirectory` impl

**Status:** ✅ DONE

## What shipped

The new optional Gradle module `opa-abac-keycloak-directory`
(`dev.dmitriikonovalov.opaabac.keycloak.directory`):

- `KeycloakUserDirectory` — implements the T1 port over the official `org.keycloak:keycloak-admin-client`
  (**26.0.5**, pinned in `gradle/libs.versions.toml`; the 26.x client line matches the rig's 26.3.2
  server): `client_credentials` grant, `realm.users().search(q, 0, clampedLimit)`, maps `id → subject`
  (the IdP `sub` — verified: the seed script provisions users from the token's `sub` claim) and
  `username → displayName` (blank → `subject`). Clamp ≤0→20, >50→50, enforced on the outgoing request
  **and re-enforced on the response** (`.limit(clamped)` — a misbehaving server cannot widen the bound).
  Fail-closed: one catch-all → `[]`, auth failures classified by **cause-chain walk** for the distinct
  WARN; blank `q` returns locally with zero HTTP calls. Fixed 3 s connect/read timeouts (interactive
  picker degrades to no-oracle empty rather than hanging; property surface stays the pinned five).
  `AutoCloseable` so the starter bean closes the client on context shutdown.
- `KeycloakDirectoryProperties` — `opa.abac.directory.keycloak.{enabled,server-url,realm,client-id,client-secret}`,
  mirroring `OwnershipProperties` style.
- Consumers: T3's `OpaDirectoryAutoConfiguration` constructs the bean (next ticket); no other caller.

## Tests

`:opa-abac-keycloak-directory:test` — **8/8 green**; `./gradlew build` (all modules incl. the new one +
both example apps + Testcontainers ITs) — **green, 27 s**.

- **U2a** — clamp on the outgoing `max` param (−1→20, 1000→50, 10→10) + the response re-enforcement
  (server returns 60 rows despite `max=50` → result is 50).
- **U2b** — blank/empty/null `q` → empty with **zero** stub requests (token + search counters).
- **I2a** — 2 matching users mapped to `DirectoryUser`s; request path carries `search=al&max=10`.
- **I2b** — users-endpoint 500 → empty + "search failed" WARN; connection-refused → empty + WARN;
  token-grant 401 → empty + **distinct** "token grant/authorization failed" WARN (asserted via a logback
  ListAppender), and the users endpoint is never reached. Never throws on any edge.
- **I2c** — null and whitespace usernames → `displayName == subject`.

## Architecture review + refactor

One genuine finding, fixed in-loop: **the admin client wraps the token grant's 401** — the exception
reaching the caller is a wrapper whose *cause* is `NotAuthorizedException`, so a typed
`catch (NotAuthorizedException)` misclassifies grant failures as generic search failures. Refactored to
a single catch with a cause-chain walk (`isAuthFailure`), which the I2b test now locks in. Re-run green.

Verified explicitly:
- **Fail-closed/no-oracle:** every edge → `[]` proven by tests; timeouts bound the hang case (the slow-
  response edge shares the catch-all path with connection-refused — not separately tested to keep the
  suite fast). Outage vs empty differ only in WARN.
- **Security:** mapper drops everything but `id`/`username`; the secret never appears in a log (WARNs
  carry `e.getMessage()` only); blank-`q` guard prevents realm enumeration.
- **Concurrency:** n/a — pure read; the class is stateless beyond the thread-safe admin client; no gated
  mutation in this slice.
- **Boundary:** existing files touched = `settings.gradle.kts` (the named mechanical cost: one include
  line) + `libs.versions.toml` (version pin). No existing signature changed; core untouched.
- **Layer separation:** Keycloak types confined here (`implementation` scope — not even on consumers'
  compile classpath); no Spring beans/components — the starter owns wiring; the one Spring annotation is
  `@ConfigurationProperties` binding metadata (the `OwnershipProperties` precedent).
- **Pattern reuse:** WARN-and-empty mirrors `DiscoveryOwnershipResolver`; the HttpServer-stub test
  mirrors `DiscoveryOwnershipResolverTest` (no WireMock).

## Integration / e2e

The I2a–I2c HttpServer-stub ITs are this ticket's integration validation (above) — green.

## Decisions

- `keycloak-admin-client` pinned to **26.0.5** (latest on Central; server-independent release line).
- Timeouts are fixed constants (3 s), not properties — keeps the property surface exactly as pinned;
  documented in the class Javadoc.

## Commit

`feat(directory): add opa-abac-keycloak-directory module with fail-closed KeycloakUserDirectory`
