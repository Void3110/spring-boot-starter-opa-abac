---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring-security
  - area/spring-data
---

# STATUS — T5: The catalog example adopts it, and both live matrices prove it

**Status:** ✅ DONE (2026-10-05, collaborative)

## What shipped

- **`CategoryListAuthorizer`, `ProductListAuthorizer`** — the role-outage catch into `Page.empty()` is gone; the
  outage propagates and the service's base advice answers 503. Class docs follow.
- **`CatalogListAuthorizer.resolveRole`** — no longer catches the outage (fork 16): a membership-role outage
  propagates instead of degrading to the supervised-only page; an **authoritative** no-role (revoked between the
  two calls) keeps that designed degrade. The class doc's fail-closed paragraph and the degrade comment say which
  is which.
- **`CatalogProvenanceAdvice`** — behaviour unchanged (omit `_provenance`); the role-lookup catch's comment names
  the family.
- **e2e** — `resilience-matrix.postman_collection.json`: E2 asserts **503** + `application/problem+json` +
  `errorCode == DEPENDENCY_UNAVAILABLE` + `status == 503`, and positively that it is neither a 2xx nor a 403; E1
  additionally asserts the recovered body is the requested category. `run-resilience-matrix.sh`: header narrative,
  the pass label (`E2-sustained-unavailable`) and expectation (503). The agent-tool kill-drill cells were edited at
  T1.

## Tests

- `CatalogListAuthorizerTest`: membership-only outage → propagates, never queries (rewritten); membership outage
  with supervised ids → propagates, the supervised role is never looked up (rewritten — fork 16); **new** sibling:
  an authoritative no-role with supervised ids keeps the supervised-only composition (the old cell's assertions,
  now on the case they belong to); `bothLegsUnresolvable_returnsEmptyPage` and
  `memoCoversTheWholePageWhenTheMembershipLegDropped` keep their intent with an authoritative no-role (they used an
  outage only as a way to drop the leg). `CategoryListAuthorizerOutageTest`, `ProductListAuthorizerOutageTest`:
  the outage propagates, never queries. **U32.**
- **I6** — new `CatalogListOutageIT` (Testcontainers, the real secured chain + the service's advice): the governed
  scope answers, the role source is down → `GET /api/v1/catalogs` answers **503 `DEPENDENCY_UNAVAILABLE`**, the
  detail, no `items`, no `Retry-After`; the contrast cell (role source up) lists the member's catalog.
- `./gradlew :example-catalog-management-service:test` — 293 tests, 0 failures.

## Integration / e2e — run live, 2026-10-05

Images rebuilt from this branch first (catalog, MCP, user-management — `up` reuses existing images, which were
three weeks old). The first build died on a transient in-container DNS failure (`UnknownHostException: github.com`
during the Gradle distribution download); host DNS was fine, a probe from a cached image resolved again, and the
retry built all three.

| Cell | Rig | Result |
|---|---|---|
| **E1** transient resolve blip | A — `ENABLE_OIDC=1 ENABLE_RESILIENCE_STUB=1` | ✅ 200, body is the category (2/2 assertions) |
| **E2** sustained resolve outage | A | ✅ **503** problem+json `DEPENDENCY_UNAVAILABLE`, not 2xx, not 403 (3/3) |
| **E4** agent-tool matrix incl. the OPA kill drill | B — `ENABLE_MCP=1` | ✅ every folder 0 failures; E6: both rosters exactly `[]`, both calls `tool-gate-policy-unavailable`, no widening; E6-restore returns the pre-kill cut exactly |
| **E3** main suite (`run-tests.sh`) | B | ✅ 11 requests, 22 assertions, 0 failures |

## Architecture review + refactor

Self-review: nothing substantive in the code. Two rig findings, folded into the decomposition (T5's rig line):

- **The resilience stub cannot share a rig with the other suites.** `ENABLE_RESILIENCE_STUB=1` repoints the
  catalog pods' *whole* role source at the stub, so the agent-tool matrix and the main suite would fail for
  reasons unrelated to this slice. The decomposition put all three on one rig; it now names two configurations.
- `deploy.sh up` needs base Postgres first (`./profile.sh up`) after a `down`, and the agent-tool runner needs the
  gitignored `scripts/postman/local.postman_environment.json` — absent in a fresh worktree; copied from the primary
  checkout.

## Decisions

- E1 gained a positive body assertion. Before it only checked the status, so a 200 carrying the wrong body would
  have passed the transient-recovery cell.

## Commit

_Recorded with the next ticket._
