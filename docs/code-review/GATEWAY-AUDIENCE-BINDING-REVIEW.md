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
- **Fix 10** swept every runner's header except `run-resource-resolution-matrix.sh`, which a separate session is
  rewriting; its header line is left to that branch.

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

## Rounds

| Round | Reviewers | Verdict | Outcome |
|---|---|---|---|
| 1 | Opus · Codex gpt-6-astra · Codex gpt-6.1-sol | BLOCK ×3 | 13 findings confirmed, all fixed or documented |
