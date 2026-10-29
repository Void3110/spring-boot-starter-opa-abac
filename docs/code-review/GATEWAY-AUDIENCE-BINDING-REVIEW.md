---
tags:
  - status/active
  - type/review
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# GATEWAY-AUDIENCE-BINDING — Code Review

> **Verdict**: Round 1 — **BLOCK** from all three reviewers (fail-closed synthesis). Every confirmed finding
> was fixed on the branch; round 2 reviews the fix commit. See [Rounds](#rounds).
> **Scope**: The whole slice — T1 APISIX 3.11 → 3.19, T2 audience binding and the Lua issuer guard's deletion,
> T3 the MCP OAuth surface (the host agent client, RFC 9728 discovery, refresh rotation, the SPA single-flight
> refresh, the miner's `--discover`), T4 docs and the Claude Code runbook, and the QA-E13 fix commit (backlog
> 27/28).
> **Branch**: `feature/void3110/gateway-audience-binding` vs `main` (18 commits, 57 files, +3982/−196 before the
> review).

## How it was reviewed

**Round 1 — three independent reviewers on one identical brief** (maintainer's choice of composition,
2026-10-29, in place of the multi-lens workflow the routing defaults to):
- **Opus**, a Claude subagent: 291k tokens, 67 tool calls.
- **Codex CLI `gpt-6-astra`** and **`gpt-6.1-sol`**, on the IDP recipe: `env -i codex exec
  --ignore-user-config -s read-only -c model_reasoning_effort=xhigh`, over clean single-commit clones.

The brief gave each reviewer the same material, and changed nothing on disk:
- clean single-commit clones of the branch tip (`dc138d5`) and of the merge base (`e5f5e49`);
- the full diff;
- the upstream sources the slice relies on, at their exact tags: APISIX 3.19/3.11 `openid-connect.lua`,
  Keycloak 26.3.2 `RealmManager`/`AudienceProtocolMapper`, the MCP authorization spec 2025-11-25;
- six ordered attack surfaces.

**All three returned BLOCK.** The orchestrator verified every finding against the source before it counted, and
re-ranked where the evidence did not support the reviewer's severity. Nothing was dismissed.

## Summary

The edge contract itself held in every reviewer's checked-clean list:
- on a freshly deployed route, every bearer meets the plugin's issuer allowlist and audience check before
  anything is forwarded;
- the deleted Lua guard did nothing the plugin does not;
- the new unauthenticated route is exactly two `GET` paths;
- the host agent's identity wiring agrees across the realm, YAML, compose and runner.

What the reviewers found lies around that core: a regression in the browser-flow session (High), a route that
escapes the contract across a flag flip (Medium), a documentation overclaim about the host's narrowing
(Medium), a set of validation gaps, a test helper that could not fail on a transport regression, and stale text.

## Findings (round 1)

| # | Sev | Finding | Raised by | Status |
|---|---|---|---|---|
| 1 | **High** | The browser-flow `session.secret` defaulted to a **public constant**. APISIX 3.11 generated a random one; 3.19 requires one, and T1 supplied `catalog-demo-local-session-secret`. The session lives in the cookie, encrypted and MACed under that secret alone, and the plugin forwards a session's access token unchecked. Anyone who has read the repo can forge a session naming any token. | Opus F1 | Fixed |
| 2 | Medium | **Retained user-service routes escape the contract.** The one-armed `ENABLE_USER_SERVICE` block re-PUT its routes only when the flag was on. A re-up with the flag off left them on an *earlier* deploy's plugins. After `ENABLE_OIDC=0` that meant no `openid-connect`; after a pre-ADR-0035 deploy, no audience check. Raised as critical; re-ranked Medium because it is reachable only across a flag flip without `./deploy.sh down`, which wipes etcd. | sol F1, astra F1 | Fixed |
| 3 | Medium | **The guide overclaimed the host's narrowing** ("hands it less than their own reach, by construction"). On the REST API the host's token carries the gateway's one audience and is decided as the person, writes included — ADR 0028 §2's accepted bound. STATUS-03 said the same. | Opus F2 | Fixed (docs) |
| 4 | Low | **22 tool-call cells could not fail on a transport regression.** `mcpAllowed`/`mcpDenied` asserted the tool result, never the HTTP status or SSE framing. sol reproduced a `500` carrying the expected denial passing E15. Raised against E15's two cells; the helper is shared by every cell, so it was fixed there. | sol F5 | Fixed |
| 5 | Low | `ProtectedResourceDocument` accepted a malformed authority — `http://:9085/mcp`, `http://localhost:bad/mcp`, port `99999`. `java.net.URI` falls back to a registry-based authority. | sol F3, astra F2 | Fixed |
| 6 | Low | `resource` accepted a query, and either value accepted userinfo. | Opus F7 | Fixed |
| 7 | Low | Under `spring.main.lazy-initialization=true` the document's validation was deferred to the first request, and the off-WARN was lost. | astra F3 | Fixed |
| 8 | Low | The miner's `--discover` sent the code and the PKCE verifier to whatever endpoints the metadata named — off-origin (Opus F6) and over plain http to a remote host (sol F2, raised as high; re-ranked Low: a local-rig test tool). Its S256 check matched a substring of a string field (sol F4). | Opus F6, sol F2, sol F4 | Fixed |
| 9 | Low | Realm-wide rotation signs out a second SPA tab ("Stale token"), and can race the gateway's own browser-flow session renewal. Documented nowhere. | Opus F3 | Documented + backlog 29 |
| 10 | docs | Stale descriptions of the deleted issuer check: the miner comment, E2E-TESTING (two passages), DEMO-CONSOLE-WALKTHROUGH, nine runner headers. | Opus F4, astra F5 | Fixed |
| 11 | docs | POC-ROADMAP still said "designed", "never its audience", "3.18.0". | Opus F5, astra F6 | Fixed |
| 12 | docs | ABAC-AUTHORIZATION gave one refusal body for two cases (no `aud` vs a mismatched one). | sol F6, astra F7 | Fixed |
| 13 | docs | E2E-TESTING's "OIDC-only" recipe ran the membership rig, because `ENABLE_SPA` and `ENABLE_DIRECTORY` default on. Predates the slice. | astra F4 | Fixed |

## The fixes

1. **`init-routes.sh`: the session secret is random per deploy** (`head -c 32 /dev/urandom | base64`) unless
   `APISIX_OIDC_SESSION_SECRET` pins it. The comments and ADR 0035 §1 now say the session path is safe **only
   while that secret is secret**.
2. **`init-routes.sh`: the user-service block also runs whenever either of its routes exists**
   (`usermgmt_route_exists`), so a retained route always carries this deploy's plugin set.
3. **[[AGENT-TOOL-AUTHORIZATION]] and the runbook:** the narrowing holds on `/mcp`. On the REST API the host's
   token is the person's own; backlog 25 is what would close it. STATUS-03 is amended in place, with the
   correction marked.
4. **The agent collection's `mcpAllowed`/`mcpDenied`** assert `200` and `text/event-stream` inside their own
   test, which covers all 22 cells.
5. **`ProtectedResourceDocument`:**
   - it requires a host and a port ≤ 65535;
   - it refuses userinfo on both values, and a query on `resource`;
   - the bean is `@Lazy(false)`.
   Seven new U3 cases, plus a lazy-initialization test. **That test's falsifier was measured:** with
   `@Lazy(false)` removed it fails.
6. **The miner:**
   - the MCP URL and the issuer must be https or loopback http (`require_secure_transport`);
   - both grant endpoints must be on the issuer's origin;
   - `code_challenge_methods_supported` must be a list containing `S256`.
7. **Backlog 29** names the rotation limits, and the guide and `infra/README` say them.
8. **The docs findings 10–13.**

## Fail-closed verification

- **The bearer path:** a missing `aud`, an empty `[]` and a mismatched `aud` are all refused (plugin source
  :1265–1300). A foreign issuer is refused in `bearer_jwt_verify` (`valid_issuers`). Every cased `Bearer`
  spelling reaches the same checks.
- **The session path:** made safe again by fix 1. Before it, a forged session was the one way past the
  contract on a browser-flow rig.
- **Flag flips:** every flag-gated route block is now either two-armed or refreshed while it exists (fix 2).
  `/mcp` is bearer-only in every posture.
- **The document:** off is `404`; half, malformed or ambiguous fails startup, eagerly; it is never
  request-derived.

## Security audit

- **The new unauthenticated surface** is exactly two `GET` paths at the gateway and in the app's chain. Nginx
  normalization and Spring's firewall stop encoded or dot-segment variants (Opus checked).
- **The error bodies:** fixed earlier in this slice (backlog 27).
- **Secrets:** the session-secret constant was the one committed secret with security weight. The realm's
  client secrets and the admin key are documented rig fixtures.
- **Clean-room:** no private names, hosts or identifiers (all three reviewers).
- **Published API:** `opa-abac-*` changed only in the `trustForwardedJwt` javadoc.

## Concurrency & idempotency

- **The SPA refresh** is single-flight per tab (U8). The cross-tab and gateway-session limits are backlog 29.
- **Rotation:** replays converge on refusal, never on a second valid token (E9k, E16).

## Wiring & sibling sweep

- **Fix 2** swept every flag-gated route block in `init-routes.sh`: SPA static, Keycloak, MCP and
  `mcp-protected-resource` are two-armed; the user-service block is now refreshed.
- **Fix 4** went into the shared helpers rather than the two cells the finding named, so every tool-call cell
  is covered.
- **Fix 10** swept nine runners' headers, but not four other places whose wording differed:
  `run-resilience-matrix.sh`, `run-tests.sh`, `local.postman_environment.example.json` and
  AUTONOMOUS-IMPLEMENTATION-FLOW's rig truths. Round 2 found all four and they are fixed.
  `run-resource-resolution-matrix.sh` is left to the separate session rewriting it.

## What's done right

Every reviewer independently checked these and found them correct:
- the plugin-side issuer check is at least as strong as the deleted guard, and also covers `X-Access-Token`;
- the realm's per-client mappers are correct, and no `clientScopes` key drops the built-ins;
- `azp` is copied and the startup wiring check falsifies its absence;
- the new e2e cells assert exact bodies, names and codes;
- the published modules are untouched.

## Test results

All runs are on fresh images and a re-imported realm, 2026-10-29.

- **`./gradlew build`:** green, 1528 tests, 0 failures. `example-mcp-server` 93/0: U3 gained 7 cases, plus the
  lazy-initialization test.
- **The local Sonar gate on the changed `.java`:** three findings, all standing by-design classes on lines that
  predate the review (Mulch mx-302e78): S112 and S4502 on `SecurityConfig`, S1075 on `CatalogTools.CATALOGS_PATH`.
  Nothing on the changed code.
- **The full suite on three rigs:** 787 newman assertions, 1 failed, plus the SPA smoke 4/4.
  - The failure: agent cell E17's second request, the unknown-session `404`, answered **`504` after 60 s**.
  - The two agent-matrix re-runs on the same images: **110/0 both times**.
  - Twelve back-to-back 400→404 pairs through the gateway: all answered in milliseconds.
  - That makes the **second** occurrence of the intermittent 60 s `504` on `/mcp` (Mulch, `opa-abac-rig-deploy-ops`).
    The first was E7, the first request after a pod recreate; this one followed no recreate, so the keep-alive
    hypothesis recorded then does not cover it. It is recorded as a recurring rig flake with its mechanism
    unknown, and round 2 is asked to look for one.
  - `run-resource-resolution-matrix.sh` is still red for its pre-existing ADR 0034 fixture reason.
- **The retained-route drill (fix 2), measured:**
  - Deploy with the user-service and **no** OIDC: `usermgmt-teams openid-connect= False` (the precondition).
  - Re-up with OIDC and **without** the user-service flag, no `down`: both routes now carry
    `openid-connect` + `audience={required, match_with_client_id}`.
  - A `catalog-directory` bearer on `/api/v1/teams` → `403 {"error":"mismatched audience"}`.
- **The session-secret drill (fix 1):** on the browser-flow rig the stored secret is 44 characters and **differs
  across two consecutive deploys**; neither is the old constant.
- **QA E12 re-run on the browser-flow rig:**
  - `/mcp` is bearer-only and the other routes are browser-flow with `session`;
  - the passthrough and documents route are present;
  - a token-less `POST /mcp` → `401`, a token-less catalog read → `302`;
  - the discovery login succeeds (it is now under the https-or-loopback and same-origin checks);
  - a directory bearer → `403 mismatched audience`.
- **Lints:** `check-collection-conformance.py` 18 clean; `check-shell-guards.py` 28 clean;
  `verify-package.sh` PACKAGE OK.

## Round 2 — the same three on the fix commit (`d9eac71`)

Each reviewer judged every round-1 fix CLOSED, PARTIAL or WRONG.
- **Verdicts:** gpt-6.1-sol **BLOCK**; gpt-6-astra and Opus **APPROVE-WITH-FIXES**. Fail-closed synthesis: BLOCK.
- **Round-1 fixes:** no fix was judged WRONG. Fixes 2, 4, 8 and 10 were judged PARTIAL by at least two reviewers,
  and the findings below are those gaps. Opus also judged 6 PARTIAL (R2-3).
- **Cost:** Opus 310k tokens; the Codex legs are outside the Claude pool.

| # | Sev | Finding | Raised by | Status |
|---|---|---|---|---|
| R2-1 | Medium | Fix 2's lookup read any non-200 — a 5xx, an unreachable Admin API — as "route absent", silently skipping the refresh. sol raised it as critical; it needs the Admin API to fail mid-deploy after succeeding for every route before it. | sol F1, astra F1 | Fixed: only `404` is absence, any other answer aborts the deploy (probed with a fake `curl`: 200 / 404 / 503 / 000) |
| R2-2 | Medium | Fix 2's retained-only refresh also re-PUT the upstream with the default two nodes, overwriting a single-pod `USERMGMT_NODES` choice. | sol F2, astra F2 | Fixed: a retained-only refresh rewrites the routes and leaves the upstream alone |
| R2-3 | Low | Fix 5/6 edges: an RFC 3986 reg-name host with `_` was refused (`java.net.URI` reports no host for it), port 0 was accepted, and an `authorization-server` with a query was accepted. | Opus F2, F3 | Fixed: reg-name fallback grammar, ports 1–65535, the query refused on both values. New cases, valid ones included (underscore host, IPv6, `:443`, trailing-slash and pathless issuers) |
| R2-4 | Low | Fix 8 was too strict: the origin comparison was by spelling (an explicit `:443` or a host's case read as foreign), and loopback was a literal list (`[0:0:0:0:0:0:0:1]`, `localhost.` refused). | sol F3, F4, astra F4 | Fixed: normalized `(scheme, host, effective port)`, and `ipaddress.is_loopback` + the trailing dot. Probed both ways |
| R2-5 | Low | Fix 4 was incomplete: a 200 SSE answer with an empty body or a JSON-RPC `error` still passed `mcpAllowed`. | Opus F1, sol F5, astra F3 | Fixed: both helpers also require a parsed JSON-RPC `result` and no `error`. Probed against the collection's own helpers: a real result PASS; empty, error and 500 FAIL |
| R2-6 | docs | Fix 3 overcorrected: "on the REST API the token is the person's own" ignores that the supervised path and step-up deny any `act_chain` call. | sol F6, Opus F4 | Fixed: membership reach is the person's; the supervised path and step-up stay human-only; the user-service reads no `act_chain` |
| R2-7 | docs | Fix 10 was incomplete: `run-resilience-matrix.sh`, `run-tests.sh`, `local.postman_environment.example.json` and AUTONOMOUS-IMPLEMENTATION-FLOW still described the old issuer check, and this note claimed a full sweep. | astra F5, Opus F5 | Fixed, and the sweep sentence corrected |
| R2-8 | low, pre-existing | `scripts/load/run-load.sh`'s baseline pass re-runs `init-routes.sh` without the SPA/MCP flags, so the two load passes no longer see a byte-identical gateway (ADR 0021 §2). It is the 2026-09-11 SPA default, not this slice. | Opus F6 | Follow-up task (out of this branch, by the proportionality clamp) |

**The open question, the intermittent 60 s `504` on `/mcp`: no mechanism found by any reviewer.** All three read
the exact-version bytecode:
- the transport answers the Accept `400` before reading the body;
- Tomcat 11.0.24 marks a `400` connection-dropping (`statusDropsConnection`) and sends `Connection: close`, so no
  half-read keep-alive connection survives it;
- the 60 s matches APISIX's default upstream read timeout, with no retries for a `POST`.

Opus's common factor, inference only: both failing requests needed a *new* upstream connection — after a pod
recreate, and after a `Connection: close` — through Docker Desktop's forwarding to `host.docker.internal:28093`.
Its proposed falsifiers:
- read APISIX's `error.log` at the next occurrence;
- point `mcp-pool` at the in-network `mcp:8080`;
- raise `server.tomcat.keep-alive-timeout` above the gateway's idle timeout.

All three are recorded in Mulch with the occurrence.

**Round-2 test results** (fresh images, a re-imported realm, 2026-10-29):
- **`./gradlew build`:** green, 1534 tests, 0 failures; `example-mcp-server` 99/0.
- **The local Sonar gate:** it first flagged two real issues in the new reg-name regex — S5998, an alternation
  inside `+`, and S6353, `[0-9]` for `\d`. Both were fixed with a possessive quantifier and `\w` / `\d` /
  `\p{XDigit}`, and the tests re-run. Then only the three standing classes remained.
- **The full suite on three rigs: 787 newman assertions, 0 failed**, plus the SPA smoke 4/4.
  - The agent matrix ran 110/0 with the stricter helpers, so all 22 tool-call cells carry a real JSON-RPC result.
  - `run-resource-resolution-matrix.sh` is still red, pre-existing.
  - The rig's MCP image may predate the regex tweak above. The rig's values are `localhost` URLs, which never
    reach the reg-name fallback, and the unit tests cover it.
- **The retained-route drill, from a single-pod `USERMGMT_NODES` deploy with no OIDC:**
  - precondition `openid-connect= False`;
  - after the flag-off re-up, both routes carry the audience block, and the log reads "refreshing their
    plugins (upstream left as is)";
  - **the upstream is still `{"host.docker.internal:28090": 1}`**;
  - a directory bearer → `403 mismatched audience`.
- **The session-secret drill:** the secret changed across deploys.
- **QA E12:** `/mcp` → `401`, the catalog → `302`, the discovery login and the directory refusal as before.

## Round 3 — the same three on `64950c2`

- **Verdicts: APPROVE-WITH-FIXES from all three.** No reviewer blocked.
- **Round-2 fixes:** R2-1, R2-2 and R2-5 were judged CLOSED by all three. Opus judged R2-3 and R2-4 CLOSED, and
  both Codex legs judged them PARTIAL (R3-1, R3-2 below). R2-7 was PARTIAL with all three (R3-3), and astra also
  marked R2-6 PARTIAL (R3-4).
- **Cost:** Opus 302k tokens.

| # | Sev | Finding | Raised by | Status |
|---|---|---|---|---|
| R3-1 | Low | R2-4's `origin_of` equated two *distinct* origins: it stripped a trailing dot (`example.org.`) and gave an explicit `:0` the default port. Codex raised it as medium. Re-ranked Low on Opus's evidence: a trailing-dot FQDN is the same DNS name, and port 0 cannot be connected to, so no grant reached another host. Fixed anyway, because exact origin equality is cheap. | sol F1, astra F1 | Fixed: the dot and `:0` are kept; only an omitted port takes the default. Probed: `example.org.` and `:0` are now different, while `:443`, host case and IPv6 spellings still match |
| R3-2 | Low | The reg-name fallback's `\d{1,5}` refused a zero-padded port (`my_host:000080`) and an empty port (`my_host:`). Both are valid RFC 3986, and the server-authority arm accepts them. | sol F2, astra F2 | Fixed: the port is parsed after stripping leading zeros, and an empty port is the default. New cases: `000080` and empty accepted; `00000` and `065536` refused |
| R3-3 | docs | Four more stale gateway descriptions: the root README, the miner's `--help`, `run-isolation-matrix.sh`, and `run-spa-auth-smoke.sh`. The last said any client's token is accepted, the opposite of this slice's headline. | sol F3, astra F3, Opus F1–F3 | Fixed. A whole-tree sweep leaves only "issuer must match what APISIX validates" comments, which are accurate |
| R3-4 | docs | STATUS-03's in-place correction still said the REST token is "the person's", without the supervised and step-up exceptions. | astra F4 | Fixed |
| R3-5 | low, pre-existing | Four Mulch records (mx-c5a27d, mx-91fa5d, mx-05b2c1, mx-5537aa) still say a host-port token is refused, a 3.11-era truth. | Opus F4 | A current-truth reference recorded and linked to all four. The records themselves are 3.11-era measurements, so they are not rewritten |

**Round-3 test results** (2026-10-29):
- **`./gradlew build`:** green, 1537 tests, 0 failures, including the three new document cases.
- **Sonar:** only the three standing classes.
- **On a rebuilt MCP image:** the agent matrix 110/0. Its E14 discovery login went through the new origin
  check: answered at OIDC appended, with `aud`, `azp`, `act_chain` and `iss` as before.
- **`run-spa-auth-smoke.sh`:** 4/4.
- **The browser-flow rig:** the discovery login succeeds there too.
- **Not re-run:** the full suite. Round 3 changed no gateway, realm or policy file, only the miner, the document
  validator, and docs/comments.

Round 3 fixed things, so by the loop rule it is **not** terminal: round 4 verifies these fixes.

## Rounds

| Round | Reviewers | Verdict | Outcome |
|---|---|---|---|
| 1 | Opus · Codex gpt-6-astra · Codex gpt-6.1-sol | BLOCK ×3 | 13 findings confirmed, all fixed or documented |
| 2 | the same three, on `d9eac71` | BLOCK (sol) · APPROVE-WITH-FIXES ×2 | 7 findings fixed; 1 pre-existing one to a follow-up task |
| 3 | the same three, on `64950c2` | APPROVE-WITH-FIXES ×3 | 2 Low + 2 docs fixed; 1 pre-existing Mulch item recorded |
