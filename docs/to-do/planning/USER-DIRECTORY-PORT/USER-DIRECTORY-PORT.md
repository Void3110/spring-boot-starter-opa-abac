---
tags:
  - status/planned
  - type/index
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — Keycloak-admin user-directory port (Slice 2)

> **Status: Planning.** A reusable **`UserDirectory` search SPI** in the library + a concrete, optional
> **Keycloak-admin implementation** (its own module) + a bearer-only `search` endpoint under
> `/api/v1/users`, so the demo SPA's member picker can find **any realm account**, not just provisioned
> profiles. Net-new external integration; purely additive (every seam is new — no shared mechanism
> changed). Phase 7 of [[POC-ROADMAP]] (Slice 2 of the user-directory work; the query filters are the
> separate, shipped-first [[DIRECTORY-QUERY-FILTERS|Slice 1]]).

## Why this slice exists

**The gap.** The SPA member picker offers only **provisioned** users (rows in the user-service `users`
table) — its empty state reads "only provisioned profiles are listed." Adding a teammate who has never
logged in requires searching the **identity directory** (the IdP's accounts), which the library has no
seam for, and which this repo has **zero** existing Keycloak-admin code for.

**The mechanism.** A pure **search** read-model SPI — `UserDirectory.search(query, limit) →
List<DirectoryUser>` — in `opa-abac-spring-security`, with a `NoOpUserDirectory` default and a concrete
`KeycloakUserDirectory` in a **new optional module** (`opa-abac-keycloak-directory`), auto-wired like B3's
Resilience4j (`@ConditionalOnClass` + `@ConditionalOnProperty`, NoOp fallback). A bearer-only `search`
endpoint under `/api/v1/users` exposes it as a **bounded plain list**. The Keycloak impl uses a
least-privilege **`client_credentials`** service account (`view-users` only), fails **closed to an empty
list** (a deliberate no-oracle response), and hides the Keycloak URL entirely behind the port.

**The headline.** The SPA can add **anyone in the realm** to a team — and the library gains a reusable,
provider-agnostic identity-search seam. Provisioning stays out of scope (the SPA provisions on select via
the existing `POST /users`).

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, the 8 pinned forks, the fail-closed/no-oracle posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T6 + the critical path (backend-first). |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-06 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | Port SPI: `UserDirectory` + `DirectoryUser` + `NoOpUserDirectory` (library) | ✅ DONE |
| T2 | New module `opa-abac-keycloak-directory`: `KeycloakUserDirectory` impl | 📋 TODO |
| T3 | Starter auto-config (`@ConditionalOnClass`+`@ConditionalOnProperty` + NoOp fallback) | 📋 TODO |
| T4 | `search` endpoint under `/api/v1/users` (spec + controller + bounded-list DTO) | 📋 TODO |
| T5 | Realm config: `catalog-directory` client + `view-users` + deploy wiring | 📋 TODO |
| T6 | e2e (newman) + SPA picker rewrite + docs (guide + ADR link) + folder move | 📋 TODO |

## Related

- [[POC-ROADMAP]] — Phase 7 (pre-publish); Slice 2 of the user-directory work.
- [[0020-user-directory-port|ADR 0020]] — the 8 pinned structural decisions this slice implements.
- [[0019-pluggable-cross-service-ownership|ADR 0019]] — the `ResourceOwnershipResolver` SPI this port mirrors.
- [[DIRECTORY-QUERY-FILTERS]] — Slice 1 (the `listAll*`-killer filters), shipped first.
