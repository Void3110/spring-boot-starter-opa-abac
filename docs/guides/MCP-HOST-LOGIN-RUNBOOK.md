---
tags:
  - status/active
  - type/guide
  - area/mcp
  - area/keycloak
  - area/security
---

# Runbook — logging a real MCP host (Claude Code) in to the rig

The example MCP server advertises how to log in (RFC 9728 protected-resource metadata), and the realm carries a
public client for MCP hosts, `catalog-mcp-host`. This page walks a person at a terminal through connecting
**Claude Code** to the rig's `/mcp` endpoint and logging in as a demo user. The scripted equivalent is the agent
matrix's E14 (the miner's `--discover`); this is the same flow driven by a real client.

> **Believe `claude mcp --help` over this page.** The flags below are the ones Claude Code 2.1.286 documents
> (`--transport`, `--client-id`, `--callback-port`), and the walkthrough was executed with that version on
> 2026-10-29. If your version names them differently, the help text is right and this page is stale.

## What happens

1. Claude Code calls `POST http://localhost:9085/mcp` with no token and gets `401` — the gateway's `/mcp` route
   is bearer-only.
2. It reads `http://localhost:9085/.well-known/oauth-protected-resource/mcp`, which names the resource and the
   authorization server: `http://localhost:9085/realms/catalog-demo`.
3. It reads that server's metadata. The spec's first two URLs (RFC 8414 and OIDC path-inserted) land on the
   gateway's catch-all and answer `401`; the third, OIDC appended, answers. Claude Code then opens your browser
   on the realm's login page — through the gateway's `/realms/*` passthrough — with `client_id=catalog-mcp-host`,
   PKCE S256 and `resource=http://localhost:9085/mcp`, and receives the code on its loopback callback,
   `http://localhost:33418/callback`.
4. The token it gets is an **agent** token: `azp: catalog-mcp-host`, `act_chain: ["agent-host"]`,
   `aud: catalog-gateway`. The MCP server's tool-gate narrows it to the `agent-host` profile: reads only, at the
   low risk tier.

## Steps

```bash
ENABLE_MCP=1 ./deploy.sh up --pods 2     # the MCP server, the gateway route, the realm with catalog-mcp-host
scripts/postman/seed-demo-data.sh        # binds `demo` to READ/WRITE/TAG on the demo catalog
```

```bash
claude mcp add --transport http catalog http://localhost:9085/mcp --client-id catalog-mcp-host --callback-port 33418
```

`catalog-mcp-host` is a **public** client, with no secret, so do not pass `--client-secret`. The callback port is
fixed because the realm only accepts the redirect URIs it has registered: `http://localhost:33418/callback`
and `http://127.0.0.1:33418/callback`.

Then, inside Claude Code, run `/mcp`, choose `catalog` and authenticate. Log in as **`demo` / `demo`** in the
browser window that opens.

## What you should see

- `/mcp` reports **"Authentication successful. Connected to catalog."**, and the server lists exactly **three**
  tools: `get_catalog`, `list_catalogs`, `list_categories`. `get_product` is in the host's profile but is a
  medium-risk tool, so the low risk cap hides it — and denies it, should the client call it anyway.
- Claude Code labels the three **"destructive · open-world"**. They are reads. The demo tools declare no MCP tool
  annotations, so the client shows the specification's defaults (`destructiveHint` and `openWorldHint` true) —
  backlog item 28. The hints are advisory either way: what the tool-gate enforces is each tool's declared
  category and risk tier.
- Asking Claude to list the catalogs returns the ones `demo` can read: the demo catalog the seed created.

If the tool list is **empty**, `demo` has no membership. Re-run the seed. An empty list is the honest answer for
a principal with no ceiling, not a broken login.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The browser shows Keycloak's "Invalid parameter: redirect_uri" | The client ran its callback on a different port. Re-add with `--callback-port 33418`. |
| Claude Code reports it cannot discover authorization | `GET http://localhost:9085/.well-known/oauth-protected-resource/mcp` answers `404` — the MCP server runs without `EXAMPLE_MCP_OAUTH_PROTECTED_RESOURCE_*`. Bring the rig up with `ENABLE_MCP=1`. |
| Every call answers `403 {"error":"mismatched audience"}` | The realm predates the `gateway-audience` mapper. Run `./deploy.sh down`, then up again, so Keycloak re-imports the realm. |
| A tool call is denied `tool-gate-actor-required` | The token carries no `act_chain`: the realm's `catalog-mcp-host` lost its actor mapper. Re-import the realm. |
| The gateway log shows one `POST /mcp` answering `400` right after the login | Expected, and harmless: Claude Code 2.1.286's first request carried an `Accept` header without `text/event-stream`, which the streamable transport refuses. The next request is the real `initialize` (`200`). (That `400`'s body carries a full stack trace — backlog item 27.) |

To remove the server again: `claude mcp remove catalog`.

## Related

- [[AGENT-TOOL-AUTHORIZATION]] §MCP OAuth discovery — the document's contract, the host client and its profile,
  refresh rotation, and the forwarded-bearer deviation.
- [[0035-audience-bound-gateway-tokens|ADR 0035]] — the decision record.
