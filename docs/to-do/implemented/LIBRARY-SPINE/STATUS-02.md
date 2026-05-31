---
tags:
  - status/implemented
  - type/project
  - area/spring-security
  - area/abac
---

# STATUS — Ticket 2: Security: AbacSubjectExtractor + AbacFilter + AbacAuthentication

> Filled in at the ticket-2 checkpoint. See [[01-DECOMPOSITION]] ticket 2.

**Status:** ✅ implemented (2026-06-01)

## What shipped
All in `opa-abac-spring-security` (`dev.dmitriikonovalov.opaabac.security`):

- **`AbacSubjectExtractor`** — `@FunctionalInterface` SPI:
  `Optional<AbacContext.Subject> extract(HttpServletRequest)`. The seam for identity sourcing (JWT
  today; mTLS / trusted header / session swappable with one bean).
- **`SubjectClaimsConfig`** — record carrying the configurable claim paths
  (`idClaim`=sub, `rolesClaim`=realm_access.roles, `usernameClaim`=preferred_username,
  `attributeClaims`=[], `validateExpiry`=true) with dotted-path support and a `defaults()` factory.
  Nothing about the Keycloak shape is hardcoded (Mulch `mx-ea7974`).
- **`JwtClaimsSubjectExtractor`** — default extractor. Reads `Authorization: Bearer <jwt>`,
  base64url-decodes the **payload segment only** (**no signature verification** — trusts the gateway),
  structural check (3 segments + JSON-object payload) + optional `exp` check, maps configurable claims
  (dotted paths) to a `Subject`. Any problem → `Optional.empty()`; never throws.
- **`AbacAuthentication extends AbstractAuthenticationToken`** — carries the typed `Subject`
  (`getSubject()`); principal/name = subject id; authorities = `ROLE_<role>` (plain Spring role checks
  still work); pre-authenticated; no credentials.
- **`AbacFilter extends OncePerRequestFilter`** — delegates to the extractor; sets the
  `AbacAuthentication` on a resolvable subject, leaves anonymous otherwise; **never overwrites an
  existing authentication, always continues the chain, never throws** (extraction wrapped in
  try/catch → anonymous).

## Tests
`./gradlew :opa-abac-spring-security:test` — **14 passed, 0 failed.**
- **`JwtClaimsSubjectExtractorTest`** (hand-crafted `b64url(header).b64url(payload).sig` tokens):
  U11 well-formed (id/roles/username), U12 missing-sub→empty, U13 missing-roles→empty-roles,
  U14 flat configured roles claim, U15 expired→empty (+ U15b accepted when validation off),
  U16 2-segment/non-JSON→empty, U17 no/`Basic` header→empty, U18 fully-configurable claim paths,
  **U21 garbage signature still extracts** (no verification performed).
- **`AbacFilterTest`** (mock request/response/chain): U19 valid→context populated +
  `ROLE_catalog-viewer` authority + chain continued; U20 no-subject→anonymous + chain continued;
  U20b extractor-throws→anonymous + chain continued (never breaks the request).

## Architecture review + refactor
Ran the gate against `00-DESIGN.md`:
- **Never-throws / anonymous-on-failure** — extractor returns empty on every malformed input; filter
  wraps extraction and always continues the chain. Proven by U16/U17/U20b.
- **Boundary** — import scan shows only `java.*`, Jackson, SLF4J, `jakarta.servlet.*`,
  `org.springframework.security.*`, `org.springframework.web.filter.*`, and `core`. No OPA call here
  (correct — that is T3). Confirmed **no `oauth2-resource-server` / nimbus / jjwt / jose** on the
  compile classpath — the signature-trust posture is real, not accidental.
- **Pluggability** — `AbacSubjectExtractor` is a clean SPI; the JWT extractor is fully claim-configurable
  via `SubjectClaimsConfig` with sensible Keycloak defaults; a different IdP is a config change.
- **SOLID** — parsing vs mapping split inside the extractor; dotted-path navigation in small private
  statics; `AbacAuthentication` is a thin token; `AbacFilter` only orchestrates.

**Refactor applied:** nothing substantive — removed an unused SLF4J logger field surfaced at compile.
The design held. No invented churn.

## Integration / e2e
None for T2 (pure unit, no app). Heavier validation lands in T4 (context-runner) and T5/T7 (the rig).

## Decisions recorded
No new decision; honored the pluggable-claim-extractor pattern (`mx-ea7974`) and the signature-trust
posture documented in `00-DESIGN.md`. Mulch record still deferred — re-check after T3 when the
enforcement path is whole.

## Commit
`feat(abac-security): JWT subject extraction (no sig verify) + AbacFilter + AbacAuthentication`.
