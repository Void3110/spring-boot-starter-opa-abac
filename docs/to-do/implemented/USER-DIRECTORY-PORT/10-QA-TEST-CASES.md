---
tags:
  - status/planned
  - type/project
  - area/security
  - area/api
---

# USER-DIRECTORY-PORT — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit/slice, I = integration
> (in-process `com.sun.net.httpserver.HttpServer` stub for the Keycloak admin API — **no WireMock**;
> Testcontainers real Postgres where a persisted entity is involved — **never H2**;
> `ApplicationContextRunner` for the auto-config), E = e2e (newman through the rig; asserts the actual
> cut — which accounts, counts — not just shape). The directory has **no persistence** of its own.

## Unit / slice (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1a | `NoOpUserDirectory.search("anything", 10)` | returns an **empty** list | T1 |
| U1b | `DirectoryUser` shape | exposes exactly `subject` + `displayName` (the disclosure ceiling) | T1 |
| U2a | `KeycloakUserDirectory.search(q, limit)` limit clamp | `limit<=0`→20, `limit>50`→50, `limit=10`→10 (the cap is enforced by the impl, not the caller) | T2 |
| U2b | `search("   ", 10)` (blank/whitespace `q`) | returns **empty** and makes **no** call to Keycloak (verify the stub received zero requests) | T2 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I2a | HttpServer stub returns 2 matching users for `search` | mapped to 2 `DirectoryUser`s, bounded by `limit` | T2 |
| I2b | stub returns 5xx / connection-refused; and a token-grant **401** | `search` returns **empty list**, never throws (both edges, distinct WARN logged) | T2 |
| I2c | stub returns a user with a **blank/null username** | `displayName == subject` (always renderable) | T2 |
| I3a | `ApplicationContextRunner`, Keycloak client **absent** OR `enabled` unset | context has `NoOpUserDirectory`, **no** `KeycloakUserDirectory` | T3 |
| I3b | client **present** + `enabled=true` + properties | context has `KeycloakUserDirectory`, **no** `NoOp` | T3 |
| I3c | adopter supplies their own `UserDirectory` bean | it wins (`@ConditionalOnMissingBean`); no library bean overrides it | T3 |
| I4a | MockMvc, a stub `UserDirectory` bean returning 2 rows; `GET` search `?q=al&limit=10` | 200, `{items:[…2…], limit:10}`, only `subject`+`displayName` present | T4 |
| I4b | MockMvc with `NoOpUserDirectory` bean; `GET` search `?q=x` | **200** with `{items:[], …}` — **not** 404, **not** 500 (no-oracle empty) | T4 |

## E2E (E*) — newman through the gateway (extend the user-service matrix; `ENABLE_DIRECTORY=1`)

| ID | Flow | Asserts (the actual cut) | → Ticket |
|---|---|---|---|
| E-pre | Rig boots `ENABLE_DIRECTORY=1`; a `client_credentials` token for `catalog-directory` | user-service log shows `KeycloakUserDirectory` active (not `NoOp`); the token can call `view-users` in-network but is **denied** any write/admin scope. *As built (deep-review fix):* the denied-write half is **pinned in `run-team-matrix.sh`'s directory preflight** — read 200, create/update/delete all 403, red run on any violation — not a one-time manual check | T5 |
| E1 | bearer `GET` search `?q=<prefix matching a never-provisioned account>` — *as built (deep-review fix):* **`dora`**, the reserved credential-less probe account (carol turned out to be an isolation-matrix fixture; see the README fixture registry) | returns that realm account **though it has no `users`-table row** — proves it searches the directory, not the provisioned set | T6 (proves T2+T4+T5) |
| E2 | bearer `GET` search `?q=` (blank) | **empty** items | T6 (proves the blank-`q` no-enumerate edge) |
| E3 | bearer `GET` search `?q=<broad>&limit=1000` | at most **50** items (the hard clamp holds end-to-end) | T6 (proves the clamp) |

## Headline proof

**E1** — a search returns a **never-provisioned** realm account (one with no `users`-table row). That is
the slice's entire reason to exist: the picker can now reach anyone in the directory, not just the
provisioned subset. **I2b** (every Keycloak error edge → empty, never throws) + **I3a** (bare adopter gets
`NoOp`) are the load-bearing fail-closed / lean-starter proofs; **I4b** (200-empty, never an error) is the
no-oracle contract at the HTTP boundary.
