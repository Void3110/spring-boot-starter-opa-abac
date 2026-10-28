---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# STATUS — T1: APISIX 3.11.0 → 3.19.0, behavior-preserving

**Status:** ✅ DONE (2026-10-28, collaborative build)

## What shipped

- `infra/compose.apisix.yaml`: `apache/apisix:3.19.0-debian`.
- `infra/apisix/init-routes.sh`: **one shared `openid-connect` configuration** (`OIDC_CONF`), built once after the
  bearer-only switch and used by all three authenticating routes (the catalog catch-all, the user-service routes,
  `/mcp`) — the three inline copies are gone, so T2's audience change lands in one place and the copies can no
  longer drift. It adds:
  - `claim_validator.issuer.valid_issuers` = the three rig authorities, rendered from the existing
    `ISSUER_ALLOWLIST` as a JSON array (`ISSUER_VALID_JSON`);
  - `session.secret` only when `bearer_only` is `false` (`OIDC_SESSION_SECRET`, from `APISIX_OIDC_SESSION_SECRET`
    with a ≥16-character development default).
- opentelemetry: the collector, resource, batch-processor, `trace_id_source` and `set_ngx_var` settings moved from
  `infra/apisix/config.yaml` `plugin_attr` (deleted) to plugin metadata, written by `init-routes.sh`
  (`PUT /apisix/admin/plugin_metadata/opentelemetry`); the script exits with the Admin API's answer if it is
  rejected.
- `config.yaml`'s comments now say what `serverless-pre-function` is for (the `/internal/*` block, and the issuer
  guard until T2) and where the opentelemetry settings live.
- Unchanged by design: the Lua issuer guard (T2 deletes it), `ssl_verify: false` on every block, the routes, the
  realm, `etcd` `v3.5.16`, `PERFORMANCE.md`'s historical 3.11.0 rig row.

## Tests

- `bash -n infra/apisix/init-routes.sh`; `scripts/checks/check-shell-guards.py` → 28 scripts clean.
- The shared fragment rendered in both modes and parsed as JSON before the rig run: `ENABLE_SPA=1` → `bearer_only:
  true`, no `session`; `ENABLE_SPA=0` → `bearer_only: false`, `session` present; three `valid_issuers` in both.

## Architecture review + refactor

Path: inline review in the collaborative session (no separate reviewer — the slice's first whole-delivery review is
the multi-lens `/deep-review` after T4). Found and applied: the three byte-identical `openid-connect` blocks were
the drift risk this slice's later tickets would multiply (T2 edits all three, T3 diverges `/mcp`), so they became
one fragment. Fail-closed check: `valid_issuers` only narrows (a token whose `iss` is not one of the three is
refused 401 by the plugin itself, before the guard); the metadata PUT fails the deploy rather than leaving the
gateway untraced; `session.secret` is a rig value, not a credential. Nothing else substantive.
**Corrected by the slice review (2026-10-29):** that last judgment was wrong. The secret keys the browser-flow
session cookie, whose access token the gateway forwards unchecked, so a committed default let anyone forge a
session. It is now random per deploy ([[GATEWAY-AUDIENCE-BINDING-REVIEW]] finding 1).

## Integration / e2e

Run as one local sequence (a log per step), 2026-10-28, on rebuilt images (`ENABLE_MCP=1 ./deploy.sh build`
+ the user-service image — every layer cached: the images already carried the current sources).

- **QA E1 — the full suite, three rigs:** **769 newman assertions, 0 failed**, plus the SPA smoke 4/4 —
  rig 1 (resilience stub): `run-resilience-matrix.sh` 5/0; rig 2 (`ENABLE_MCP=1`, seeded): run-tests 22/0, run-matrix
  21/0, action-enrichment 19/0, agent-tool 92/0, demo-world 176/0, filter 29/0, hierarchy-list 18/0, hierarchy 4/0,
  isolation 20/0, pagination 27/0, permission-categories 31/0, production-tier 73/0, step-up 69/0, supervised-scope
  55/0, tag 46/0, team 24/0, spa-auth-smoke 4/4; rig 3 (OIDC-only): run-tests 19/0, run-matrix 19/0.
  **One runner red, pre-existing — `run-resource-resolution-matrix.sh`:** its fixture creates
  `{"tags":{"region":["emea"]}}` categories as the bootstrapped tag-gated writer under an untagged catalog root, and
  the catalog service answers **403 `ACCESS_DENIED`** (an application `problem+json` — the gateway admitted and
  forwarded the token). That is ADR 0034's tag-gated create (TAG-GATED-CREATE, 2026-09-11), which the runner (last
  changed 2026-08-15) predates. **Proven independent of T1:** with T1 stashed, on `main`'s APISIX 3.11.0 gateway,
  the runner fails identically (`KeyError: 'id'` on the same create). Spun off as its own task; not fixed here.
- **QA E2 — traces:** after a gateway request, Jaeger's services =
  `["apisix","catalog-management-service","jaeger","keycloak","opa"]`, with an `apisix` trace present.
- **QA E3 — the browser-flow rig** (`ENABLE_SPA=0 ENABLE_MCP=1`): every `openid-connect` route accepted —
  `catalog-all`, `mcp`, `usermgmt-teams`, `usermgmt-users` each `bearer_only=false`, `session` present,
  3 `valid_issuers`; a token-less `GET /api/v1/catalogs` → **302** to the realm's login.

## Decisions

- **One shared `openid-connect` fragment** instead of three copies (above) — an implementation choice inside T1's
  deliverables, recorded because T2/T3 build on it.
- **`run-resource-resolution-matrix.sh` stays red in this slice's proofs** as a recorded pre-existing failure
  (proportionality: it is broken on `main`, by an earlier slice, for a reason unrelated to the gateway). Every later
  ticket's "full suite green" reads as "green except this runner, unless its fix has landed".

## Commit

`feat(gateway): APISIX 3.19.0, behavior-preserving — valid_issuers + session.secret in one shared openid-connect config, opentelemetry as plugin metadata (GATEWAY-AUDIENCE-BINDING T1)`
