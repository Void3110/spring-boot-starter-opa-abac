---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STEP-UP-ELEVATION — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit, I = integration
> (Testcontainers Postgres — never H2; in-process HttpServer OPA stub — no WireMock),
> E = e2e (asserts the actual cut — status codes, header params, body codes, ids, log lines —
> and per the E2E-TESTING assertion-style convention, every `pm.test` callback throws).
> Rego cases run with **pinned clocks** (`time.now_ns` overridden) wherever the window matters.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | `elevated` undefined on missing `acr` | supervised + production root + fresh `auth_time` but **no `acr`** attribute → deny (in both child files) — the LoA lookup leaves `elevated` undefined | T2 |
| U2 | `elevated` undefined on missing `auth_time` | supervised + production root + `acr: aal2` but **no `auth_time`** → deny — the arithmetic leaves `elevated` undefined | T2 |
| U3 | `elevated` undefined on an unmapped `acr` value | `acr: "gold"` (not in `data.step_up.loa`) → deny; `acr: "aal1"` (mapped, below 2) → deny | T2 |
| U4 | Fresh `aal2` opens production | supervised + `{env:"production"}` + `acr: aal2` + `auth_time` within the window → **allow**, on the instance shape **and** the coarse type-level gate, in both child files | T2 |
| U5 | A stale `auth_time` denies | same as U4 but `now − auth_time > max_age + skew` (pinned clock) → deny | T2 |
| U6 | The window boundary is `<=` | `now − auth_time == max_age + skew` exactly → allow; one second past → deny | T2 |
| U7 | The unproven tier is elevation-proof | supervised + **absent** `root_attributes` + fresh `aal2` → **deny** in both files (the absent clause carries no elevation conjunct — ADR 0030 Amendment 2) | T2 |
| U8 | Sole-blocker `deny_reason` matrix | `deny_reason` **present** (with `type`/`required_acr`/`max_age` from data) iff supervised + production + not elevated + `granted` + no other deny; **absent** for: a write verb (not granted), a member (provenance conjunct), an untagged/staging root (no step-up deny), and when any other deny fires | T2 |
| U9 | The agent deny + the presence-test | supervised + `act_chain` key present → deny in **all three** files, any tier (incl. `{env:"staging"}` and `{}`); `act_chain: false` and `act_chain: []` → **still denied** (presence, not truthiness); a member + `act_chain` → **unaffected** (provenance-scoped) | T2 |
| U10 | Agents never see a challenge (constructed input) | supervised + production + fresh `aal2` + `act_chain` present → deny **and** `deny_reason` undefined (the agent deny is "another deny", so sole-blocker suppresses it). This input is deliberately **constructed** — on the rig the combination is unmintable (agent clients are ROPC-only); the policy contract is pinned here | T2 |
| U11 | One deletion guard per new clause per file | deleting any one of: the `not elevated` conjunct, the `stepup_denied` factoring, the `deny_reason` rule, the agent deny (per file) — each fails at least one test in that file; B's amended production-clause guards re-measured (record failing-test names in STATUS-02) | T2 |
| U12 | `decide()` default delegates | a minimal `OpaClient` implementor overriding only `allow()`: `decide()` returns `OpaDecision(allow(), null)` — old implementors compile and behave unchanged | T3 |
| U13 | `HttpOpaClient.decide` parses both fields | `{"result":{"allow":false,"deny_reason":{"type":…,"required_acr":…,"max_age":…}}}` → the typed reason; `{"result":{"allow":false}}` → null reason; `{"result":{"allow":true}}` → allow + null reason; `{"result":{"allow":true,"deny_reason":{…}}}` (contradictory) → allow + the reason **dropped** — and `allow()`'s behavior byte-identical on all four | T3 |
| U14 | A malformed reason drops, never throws | `deny_reason` with wrong types / missing fields / non-object shape → `OpaDecision(allow, null)`; no exception escapes; transport/5xx/non-JSON → the existing fail-closed `false` with null reason | T3 |
| U15 | `ResilientOpaClient.decide` is overridden and fail-closed | the happy path passes the delegate's reason through under the guard; breaker-open, transport failure, and retries-exhausted each → `OpaDecision(false, null)` — **never a fabricated reason**; and the override exists (a test that fails if the class reverts to the inherited default — e.g. asserting the guarded call count) | T3 |
| U16 | The manager's decision shapes | `decide()` yields a reason → `StepUpRequiredDecision` (granted=false, reason carried); allow → granted decision; plain deny → today's plain `AuthorizationDecision` — byte-identical to the pre-C shape | T4 |
| U17 | The advice's 401/403 matrix | `StepUpRequiredDecision` with a complete reason → **401** + `WWW-Authenticate` carrying `error="insufficient_user_authentication"`, `acr_values` and `max_age` **from the reason** + problem+json `STEP_UP_REQUIRED`; a plain denied result → the existing 403 unchanged | T4 |
| U18 | No half-formed challenge | `StepUpRequiredDecision` whose reason has any null field → the plain **403** path (no `WWW-Authenticate`) — the §7 loop guard | T4 |
| U19 | Audit emission + isolation | `STEP_UP_CHALLENGED` on the 401 path with **T4's challenge-event fields** (subject, resource type+id, governing root, challenge params — no `acr`/`auth_time`); `PRIVILEGED_READ` on the granted+supervised+production path with **the ADR 0030 §8 list** (subject, governing root, resource, access path, `acr`, `auth_time` verbatim), incl. an **array-shaped `env`** case (the normalization mirrors `root_env_values`); elevation never re-derived app-side; an exception thrown inside emission is swallowed and the decision/response is unchanged | T4 |
| U20 | Claims ingestion is config + type-preserved | with `attribute-claims: [acr, auth_time, act_chain]`, the extracted subject carries `acr` as a string, `auth_time` **numeric**, and the `act_chain` key when present (array value preserved); absent claims stay absent (no null entries) | T4 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | The 401 challenge over HTTP | MockMvc against the catalog app with a recording OPA stub returning `allow:false` + a complete `deny_reason`: a supervised production child read answers **401**, the `WWW-Authenticate` params echo the stub's reason, the body carries `STEP_UP_REQUIRED` | T4 |
| I2 | The plain-deny contrast | same rig, the stub returns `allow:false` with **no** reason → **403**, no `WWW-Authenticate`, the existing `ACCESS_DENIED` body — byte-identical to pre-C | T4 |
| I3 | The supervised-leg agent guard | Testcontainers: a subject whose attributes carry the `act_chain` key lists catalogs → the supervised leg is skipped (membership-only rows; for a memberless supervisor, the empty page); the same subject **without** `act_chain` → the two-leg union; a member with `act_chain` → their membership rows unchanged | T4 |
| I4 | Audit events on the wire path | the I1 flow emits `STEP_UP_CHALLENGED` (T4's challenge-event fields) and an elevated-allow flow emits `PRIVILEGED_READ` (the ADR 0030 §8 list), both captured off logger `opa.abac.audit` (log captor) | T4 |
| I5 | The realm mints the claims | post-T1 re-import: the probe script against Keycloak — plain code flow → access token with numeric `auth_time` (+ the LoA-1 acr); `acr_values=aal2` (essential) + `max_age` → the TOTP prompt → `acr: aal2` + fresh `auth_time`; a refresh grant **preserves** `auth_time` | T1 |

## E2E (E*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E1 | The challenge cell | anna at `aal1` reads a production child through the gateway → **401**; `WWW-Authenticate` params asserted (`error`, `acr_values="aal2"`, `max_age="300"`); body `errorCode: STEP_UP_REQUIRED` | T6 |
| E2 | The elevation round trip + audit | the miner mints `aal2` (TOTP) → the same read → **200** on the exact seeded ids; then the log-grep: both audit events present in the catalog pod's log | T6 |
| E3 | The loop-prevention negative | **inside the drill's shrunk window** (a fresh session is too young to prove anything): elevate under the override, wait out the window, re-auth **without** `max_age` reusing the miner's persisted **cookie jar** → SSO reuse → the token's `auth_time` equals the stale one → the read is still **401** — ADR 0030 §7's "the client MUST forward max_age", measured | T6 |
| E4 | Fingerprinting negatives | an out-of-unit supervisor on the same production catalog → **403** with **no** `WWW-Authenticate`; elevated anna's `PUT` on a production child → **403**, no challenge (the read-only ceiling is not an elevation problem) | T6 |
| E5 | Members unaffected | the catalog's owner reads production contents at plain `aal1` → **200**, no challenge, honest `_actions` — byte-identical to B's E6 cells | T6 |
| E6 | The agent cells | through the MCP surface: an **agent-client** token for anna's subject (`act_chain` present; ROPC — never elevatable) in tool calls → **403** plain (no challenge) on production **and** non-production supervised content; agent `list_catalogs` as anna → membership-only (the empty page). The "elevated agent" contract is U10's constructed-input proof | T6 |
| E7 | The freshness drill | `PUT /v1/data/step_up/max_age` body `5` (**leaf path** — a whole-document PUT clobbers `loa`/`skew` and 401s vacuously) → the **positive control** (a fresh elevation still 200s under the override) → wait **> 35s** (`max_age + skew` under the override) → the previously-elevated token's read answers **401** again → OPA restart restores the file data (EXIT trap) | T6 |
| E8 | Non-regression enumeration + the C-flips | the ten named runners — each run and green, or skipped with the reason recorded in STATUS-06. **Production-tier's seven enumerated cells (E2a–E2d, E4b, E4c, E5d) are rewritten by T6 to assert C's 401 + challenge shape** (a stronger assertion); every other cell in every collection passes **unmodified** — A's matrix wholly, and anna's non-production `aal1` reads exactly as B shipped them | T6 |
| E9 | The miner's contract | mints (a) `aal1` + numeric `auth_time`, (b) `aal2` via TOTP, (c) `--no-max-age` after (b) → `auth_time` unchanged from (b); a minted token passes the gateway (issuer parity — a member read 200s) | T5 |
