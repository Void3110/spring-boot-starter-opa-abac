---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
  - area/deploy
  - area/mcp
---

# STATUS — T4: Docs, the runbook, the backlog

**Status:** ✅ DONE (2026-10-29, collaborative build; QA E13 executed by the maintainer)

## What shipped

- **The trust posture, stated in full** — [[ABAC-AUTHORIZATION]] §Signature-trust posture:
  - "A validating gateway" verifies four things: the **signature, expiry, issuer and audience**. A table
    says what each one stops.
  - The example's APISIX `claim_validator` block is the reference, with the per-client audience mapper.
  - The stale "a `verifySignature` mode is reserved" sentence is gone; the property no longer exists.
- **One-line pointers to it:**
  - README step 3;
  - [[TWO-LAYER-AUTHORIZATION]] §Signature trust between the layers — its own stale `verifySignature` sentence
    dropped, and the "issuer allow-list" edge guard now described as the plugin's issuer + audience checks;
  - the `trustForwardedJwt` property javadoc in `OpaAbacProperties.java`;
  - `docs/prompts/FROM-EXAMPLE-TO-YOUR-APP.md`, where the pitfall "the gateway checks signature, expiry and
    issuer, not audience" is now false and is rewritten.
- **[[E2E-TESTING]]:** the in-network token caveat rewritten to what the gateway enforces now:
  - the signature and expiry, the three authorities (the MCP host's discovery login among the gateway-origin
    users), and the audience;
  - the Lua guard's history in one sentence;
  - what the E9 preflight pins, E9i/j and the per-client check included.
- **[[AGENT-TOOL-AUTHORIZATION]] §MCP OAuth discovery** (new):
  - the document and its failure contract (table);
  - why it is not Spring Security's filter;
  - the gateway's three pieces, and Keycloak ignoring `resource` (measured);
  - the host as an agent and its profile;
  - refresh rotation and the SPA's single-flight;
  - **the forwarded bearer as the documented deviation**, with RFC 8693 exchange named as the successor;
  - the four cells.
  The persona table gains the `agent-host` row. The E12 drill text says "added to". The rig commands say
  E1–E16, and the scope boundary now separates the example's discovery path from the library's scope.
- **[[MCP-HOST-LOGIN-RUNBOOK]]** (new): Claude Code against the rig — what happens, the two commands, what you
  should see, troubleshooting. It tells the reader to believe `claude mcp --help` over the page.
- **`infra/README.md`**:
  - "what the gateway checks on every bearer": the issuer, the audience with the per-client mapper, and
    `catalog-directory` as the standing negative;
  - refresh rotation;
  - the gateway path list gains the two documents and the passthrough's `ENABLE_MCP` arm;
  - a stale "the token is realm-scoped, so APISIX validates it regardless of which client minted it" corrected.
- **`infra/compose.keycloak.yaml` header:** a token-shape note — the per-client audience mapper, why the
  export has no `clientScopes`, the host client, rotation, the down-first re-import.
- **`CHANGELOG.md` `[Unreleased] — 1.5.0`:** a **Documentation** section. The trust posture now names issuer and
  audience, with no library behavior change; and the example's MCP OAuth discovery. The heading is untouched.
- **`scripts/postman/README.md`:** the agent-matrix row (E12 "added to", E13–E16), the step-up row (the E9a–k
  token-edge preflight), the miner's `--discover`, and the token paragraph.
- **Root `CLAUDE.md` and `README.md`:** the agent matrix is E1–E16.
- **`ENGINEERING-BACKLOG`:**
  - **25** RFC 8693 token exchange at the MCP server;
  - **26** a library seam for protected-resource metadata, at its second consumer;
  - **27** and **28**, the two findings of the real-client login (below): the transport's stack-trace error
    bodies, and the tools' missing MCP annotations.
- **ADR 0035:** status → Accepted (implemented 2026-10-29, release 1.5.0); the index row gains its Status and
  Area cells, which the row lacked.

## Tests

- `./gradlew build` (the javadoc edit): BUILD SUCCESSFUL — 10 tasks executed, the starter's tests among them.
- The local Sonar gate on the changed `.java`: the two standing by-design findings on `SecurityConfig`
  (S112, S4502 — Mulch mx-302e78); nothing on `OpaAbacProperties.java`.
- `scripts/planning/verify-package.sh GATEWAY-AUDIENCE-BINDING`: PACKAGE OK.
- Every wikilink added resolves. The only unresolved ones in the touched files predate this ticket: the ADR
  index's `NNNN-...` template, and two escaped-pipe table cells in E2E-TESTING.

## Architecture review + refactor

Path: inline, docs only. Every claim the docs make was measured in T1–T3 (STATUS-01…03), and nothing was written
from the design alone. The one exception is the runbook's step list, which is what QA E13 verifies.

## Integration / e2e

- **QA E13 — a real MCP client logs in (manual), PASSED 2026-10-29.**
  - Setup: **Claude Code 2.1.286** in a scratch directory, `claude mcp add --transport http catalog
    http://localhost:9085/mcp --client-id catalog-mcp-host --callback-port 33418` (local scope, that directory only).
    The rig was `ENABLE_MCP=1 ./deploy.sh up --pods 2` + `seed-demo-data.sh`.
  - The maintainer ran `/mcp` → `catalog` → authenticate, and logged in as `demo` in the browser.
  - Observed in the client: **"Authentication successful. Connected to catalog."**; `Tools for catalog — 3 tools:
    get_catalog, list_catalogs, list_categories`. Exactly the three low-risk reads.
  - The gateway's access log (`opa-abac-apisix`) recorded the whole path, in order:
    1. At startup, `POST /mcp` → **401**, then `GET /.well-known/oauth-protected-resource/mcp` → **200**; the
       client then showed "needs authentication".
    2. On authenticate, the path-inserted document again → 200; RFC 8414 path-inserted → **401**; OIDC
       path-inserted → **401**; OIDC appended → **200**. That is the same order the miner walks (E14), and the
       same two skips.
    3. `GET /realms/catalog-demo/protocol/openid-connect/auth` with `client_id=catalog-mcp-host`,
       `code_challenge_method=S256`, `redirect_uri=http://localhost:33418/callback` and
       **`resource=http://localhost:9085/mcp`**.
    4. `POST …/token` → 200.
    5. Then `POST /mcp` 400, 200 (`initialize`), 202 (`notifications/initialized`), 200, 200, 200.
  - **Two findings, both out of this ticket's scope, both backlogged:**
    - **27 — the first post-login `POST /mcp` answered `400` with a ~29 KB body.** Reproduced: a `POST` whose
      `Accept` lacks `text/event-stream` gets Spring AI 2.0.1's `WebMvcStreamableServerTransportProvider` answer
      "Invalid Accept headers". The transport serializes the whole `McpError`, a **117-frame `stackTrace`**
      included, and its `404` "Session not found" does the same. Only an authenticated caller reaches it; the
      gateway answers a token-less one `401`. Claude Code recovered on its own.
    - **28 — Claude Code labels the three tools "destructive · open-world".** `@McpTool`'s annotation defaults are
      `destructiveHint=true`, `openWorldHint=true`, `readOnlyHint=false` (`javap -v`, spring-ai-mcp-annotations
      2.0.1), and the example's tools set none.
  - The runbook was corrected from this run: the discovery order, the authorize parameters, the label, and the
    harmless `400`.

## Decisions

- **The runbook is its own page** under `docs/guides/`, not a section of the agent guide. It is
  procedural, for a person at a terminal, and the guide links it.
- **`infra/compose.keycloak.yaml`'s issuer note** was corrected in T2. T4 adds the token-shape paragraph only.

## Commit

`docs(gateway-audience-binding): the trust posture names issuer and audience; MCP OAuth discovery and the Claude Code runbook; backlog 25–26 (GATEWAY-AUDIENCE-BINDING T4)`
