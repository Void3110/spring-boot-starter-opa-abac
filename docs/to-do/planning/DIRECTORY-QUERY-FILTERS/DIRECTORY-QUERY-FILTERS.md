---
tags:
  - status/planned
  - type/index
  - area/api
  - area/spring
---

# DIRECTORY-QUERY-FILTERS — server-side directory / query filters (the `listAll*`-killers)

> **Status: Planning.** Adds server-side lookup filters to the user-service list endpoints so a
> single-resource lookup is one request, not a whole-collection page-walk — plus two small correctness
> fixes (a `produces` media-type gap and a bootstrap displayName upsert). Purely additive to
> `example-user-management-service`; no library change, no external dependency.
> Phase 7 of [[POC-ROADMAP]] (Slice 1 of the user-directory work; the Keycloak-admin `UserDirectory`
> port is the separate Slice 2).

## Why this slice exists

**The gap.** `example-user-management-service`'s `GET /users` and `GET /teams` support only offset
pagination (`page`/`perPage`, `perPage` hard-capped at 100). The demo SPA needs three single-resource
lookups these endpoints can't answer directly — find a user **by IdP subject** (`ensureUser`), find the
team **governing a target** (`targetType`+`targetId`), and (for the future directory) resolve a profile
by subject. Today the SPA emulates each with a **client-side `listAll*` page-walk** (`api.ts`
`listAllUsers`/`listAllTeams`, consumed at `teams.tsx:42` and `api.ts:298`). That walk is
O(collection), and — worse — **silently truncates**: a page-0-only scan misses rows once the store
outgrows one page, so a lookup can wrongly report "not found" and re-provision a duplicate.

**The mechanism.** Add **exact-match query filters** to the two list endpoints — `?subject` on `/users`,
`?targetType`+`?targetId` on `/teams` — each backed by a repository finder that **already exists**
(`UserRepository.findBySubject`, `TeamRepository.findByTargetTypeAndTargetId`). When the filter is
present the endpoint returns the single match as a one-item page; when absent it is today's paged
`findAll`. Two correctness fixes ride along in the same publication-hygiene spirit: a `produces` fix so
the 204-only endpoints stop 406-ing a bare `Accept: application/json`, and a bootstrap `displayName`
**upsert** so re-seeding a known subject refreshes its display name.

**The headline.** The SPA's directory/team lookups become **one-shot and correct** — no page-walk, no
silent truncation — which is also the clean foundation Slice 2's provisioning story stands on.

## Files in this folder

| File | What it is |
|---|---|
| [[00-DESIGN]] | The mechanism, decided forks, fail-closed/back-compat posture, considered-&-rejected. |
| [[01-DECOMPOSITION]] | The ordered work list T1…T5 + the critical path. |
| [[10-QA-TEST-CASES]] | Concrete U*/I*/E* cases → each ticket's Acceptance. |
| AUTONOMOUS-IMPLEMENTATION-PROMPT | The self-contained prompt the run executes. |
| STATUS-01 … STATUS-05 | One stub per ticket, filled at each checkpoint. |

## Ticket status at a glance

| # | Title | Status |
|---|---|---|
| T1 | `?subject` filter on `GET /api/v1/users` | ✅ DONE |
| T2 | `?targetType`+`?targetId` filter on `GET /api/v1/teams` | 📋 TODO |
| T3 | `produces` spec fix — 204 endpoints accept a JSON `Accept` | 📋 TODO |
| T4 | Bootstrap `displayName` upsert (`/internal/bootstrap/users`) | 📋 TODO |
| T5 | e2e (newman) + docs + SPA one-shot adoption + folder move | 📋 TODO |

## Related

- [[POC-ROADMAP]] — Phase 7 (pre-publish); Slice 1 of the user-directory work.
- [[0012-pagination-envelope]] — the paging envelope these filters return a one-item page within.
- [[0011-error-contract-problem-json]] — the 400 error body the "both target params required" validation emits.
- Slice 2 (Keycloak-admin `UserDirectory` port) — the separate, later slice this unblocks.
