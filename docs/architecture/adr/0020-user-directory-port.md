---
tags:
  - status/active
  - type/decision
  - area/security
  - area/architecture
  - area/api
---

# ADR 0020 — Pluggable user-directory port (identity search)

**Status:** Accepted (shipped 2026-07-07 — Slice 2 of the user-directory work, [[USER-DIRECTORY-PORT]])
**Date:** 2026-07-06
**Context tags:** `UserDirectory` SPI, Keycloak admin API, `client_credentials`, `view-users`, least privilege, fail-closed, no-oracle empty response

> Pins the structural forks for the **Keycloak-admin user-directory port** — a reusable library seam to
> search the identity directory (all realm accounts, not just provisioned profiles) with a concrete,
> optional Keycloak implementation. Settled in a planning interview (grill-me, 2026-07-06). Companion of
> the Slice-1 filter work ([[DIRECTORY-QUERY-FILTERS]]), which this builds on.

## Context

The demo SPA's member picker can only offer **provisioned** users (rows in the user-service `users`
table) because that is all `GET /users` exposes — its empty state literally reads "only provisioned
profiles are listed." To add a teammate who has never logged in, an admin must search the **identity
directory** (the IdP's account list), not the provisioned subset. The library has no seam for that, and
there is **zero existing Keycloak-admin integration** in the repo — this is net-new external-system
integration, against a system with its own auth, failure modes, and realm config. A directory search is
also a user-enumeration / PII surface, so its authorization and disclosure need pinning up front.

## Decision

### 1. A pure **search** read-model SPI — provisioning is out of scope
`UserDirectory.search(String query, int limit) → List<DirectoryUser>` where
`DirectoryUser(String subject, String displayName)`. The port **searches**; it never provisions,
mutates, or joins to the `users` table. What a consumer does with a found person (provision on select,
add to a team, ignore) is the **application's** concern — the SPA provisions via the existing
`POST /users` (`ensureUser`) on select. Consumers may implement the port differently (LDAP, SCIM, a
static list); the contract is "find people by query," nothing more.

### 2. Port location — `opa-abac-spring-security`, next to `ResourceOwnershipResolver`
The port interface + `DirectoryUser` record + a `NoOpUserDirectory` default live in
`opa-abac-spring-security` (package `…security.directory`), mirroring
[[0019-pluggable-cross-service-ownership|ADR 0019]]'s `ResourceOwnershipResolver` — an
identity/authorization-adjacent seam, kept out of `opa-abac-core` (core stays framework-free and reserved
for the OPA decision model).

### 3. Keycloak implementation — its **own optional module**, wired like Resilience4j
The concrete `KeycloakUserDirectory` lives in a **new module** `opa-abac-keycloak-directory`, depending
on `opa-abac-spring-security` (for the port) + the official `org.keycloak:keycloak-admin-client`. The
starter auto-configures it **`@ConditionalOnClass`** (the admin client on the classpath) **+
`@ConditionalOnProperty`** (`opa.abac.directory.keycloak.enabled=true`), falling back to
`NoOpUserDirectory` (**`@ConditionalOnMissingBean`**). An adopter who doesn't want Keycloak never drags
it in — the lean-starter promise (identical to how B3's Resilience4j edge is `@ConditionalOnClass`-gated).

### 4. Keycloak auth — dedicated service account, `client_credentials`, only `view-users`
The impl authenticates with a **dedicated confidential client** (`catalog-directory`,
`serviceAccountsEnabled`) via the **`client_credentials`** grant, granted **only** the
`realm-management` → **`view-users`** role on the realm. No human credential; least privilege — if the
client secret leaks, the blast radius is "list demo usernames," not "own the IdP." A **separate** client
from `catalog-gateway` (OIDC termination) keeps the two trust roles distinct and self-documenting.

### 5. Endpoint — `search` under `/api/v1/users`, bearer-only, bounded plain list
A `search` operation under `/api/v1/users` on the user-service (spec-first, `UserApi`), bearer-only,
returns a **bounded plain list** `{items:[{subject,displayName}], limit}` — **not** a `Page` envelope
(the directory has no cheap server-side cursor; a fake `count` would re-create the walk trap Slice 1
kills). `q` narrows; the client types to filter rather than paging.

### 6. URL is an implementation detail — the port stays URL-agnostic
The Keycloak server URL (`http://keycloak:8888` **in-network** in the rig) is private to the module +
its config; the port and the endpoint never mention Keycloak, a URL, or a realm. **Note for the rig:**
a console-URL rewrite (Keycloak's admin-URL knob, e.g. `http://localhost:28888`) is a **console** concern, *not* the REST admin path —
the module calls the in-network `:8888`. (Recorded so no one "fixes" it to localhost.)

### 7. Authorization — bearer-only; disclosure type-bounded; the real gate is `team:add-member`
Search is bearer-only, consistent with the (deliberately ungated) `GET /users`. The disclosure class is
**type-bounded to `{subject, displayName}`** in the DTO, so no implementation can widen it (email, roles,
attributes stay unexposed) — the privacy control is the *type*, not endpoint filtering. Finding a subject
grants nothing; the authorization boundary for **acting** on a result lives on the existing
`@OpaPreAuthorize(action="team:add-member")`. No new `directory:*` permission-model surface is introduced.

### 8. Fail-closed contract — binary, no-oracle empty response
`search` fails **closed to an empty list, never throws** (the `GovernedScopeResolver` shape, not the B2
tri-state). Every edge → `[]`: Keycloak unreachable/timeout/5xx (`[]` + WARN), token-grant failure
(`[]` + a distinct WARN), **blank `q` → `[]` without calling Keycloak** (never enumerate the realm),
genuine zero matches (`[]`), `limit` **clamped (default 20, hard max 50)**, a null/blank username →
`displayName = subject` (always renderable). The port is **binary** (empty-or-results): an outage and a
genuine empty are **indistinguishable to the caller/UI** — differentiating them would leak backend state
/ realm size, so the identical `[]` is a deliberate **no-oracle** security property. Outage vs empty
differs **only in the WARN log** (for operators).

## Consequences

- **A reusable identity-search seam** ships in the library; the Keycloak impl is a real, isolated,
  optional module. Adopters get the port for free and opt into Keycloak (or bring their own impl).
- **A new deployable-crossing surface** (library + module + service + realm-config + SPA) — which is why
  this is its own slice, sequenced backend-first (newman-provable before the SPA rewrite).
- **A least-privilege admin credential** enters the rig (the `catalog-directory` client secret) — a demo
  secret in the same class as `catalog-gateway-secret`, scoped to the local rig.
- **Provisioning stays where it is** (the SPA's `ensureUser`/`POST /users`); the directory never mutates.

## Considered & rejected

| Option | Why rejected |
|---|---|
| Directory returns a merged `{subject,displayName,userId?}` (owns the Keycloak↔profile join) | Drags provisioning semantics into a read-model; the SPA branch is trivial. Port stays pure search (§1). |
| Master-realm `admin/admin` (admin-cli password grant) | Global superuser for a "list users in one realm" job — enormous privilege excess; a security-review finding waiting to happen (§4). |
| Reuse `catalog-gateway`'s service account | Conflates token-validation and directory-read trust roles on one credential; keep them separate (§4). |
| Gate search behind a `directory:*` capability | No natural resource to scope on (search is global); new permission-model surface for a bearer-only autocomplete. The real gate is `team:add-member` (§7). |
| Tri-state port (outage-distinct, like B2's supplier) | Adds complexity on a *read* path where the UX is identical either way; and surfacing "the directory is down / N users exist" is itself an info leak — the no-oracle empty is the point (§8). |
| Port in `opa-abac-core` (it's dependency-free) | Core is reserved for the OPA decision model; identity-adjacent seams live in `spring-security` (the ADR-0019 precedent) (§2). |

## Related

- [[USER-DIRECTORY-PORT]] · [[DIRECTORY-QUERY-FILTERS]] (Slice 1, the filter work this builds on)
- [[0019-pluggable-cross-service-ownership|ADR 0019]] — the `ResourceOwnershipResolver` SPI this mirrors.
- [[0018-team-scoped-resource-isolation|ADR 0018]] — membership as the sole access path (why the picker matters).
- [[POC-ROADMAP]] — Phase 7.
