---
tags:
  - status/active
  - type/guide
  - area/demo-ui
---

# Prompt — "Run the demo for me"

Paste this into a coding agent that can run shell commands on your machine (Claude Code, Codex,
Cursor's agent, an Aider session …) from the repository root. It runs the rig, seeds the demo world,
drives the console persona by persona, and reports what it observed against what the repo claims.
Expected wall-clock: ~10 minutes the first time (three image builds), ~3 minutes after.

> Everything the prompt asks the agent to *verify* was measured on 2026-09-10 and is recorded in
> [[PRE-HABR-UI-QA-2026-09-10]]; the human-readable version of the same walk is
> [[DEMO-CONSOLE-WALKTHROUGH]].

```text
You are driving the spring-boot-starter-opa-abac example rig on my machine to show me its
authorization cut end to end. Work from the repository root. Report as you go; never claim a step
worked without showing the evidence (a command's output, an HTTP status, a JSON field).

PRECONDITIONS — check, do not assume
1. Docker (or podman) is running with ≥ 4 GB free; Node 20+ and npm are on PATH (the console bundle
   is built on the host). Java is NOT needed for the rig.
2. Ports 9085, 28081, 28082, 28090, 28092, 28181, 28888, 26686, 5433, 5434 are free.

BRING THE RIG UP
3. ./profile.sh up
4. ./deploy.sh up --pods 2          (the console and the identity directory are on by default)
   Wait for "==> Up." and show me the route summary line (it must say spa=1 and oidc=1).
5. scripts/postman/seed-demo-data.sh   — show the last 10 lines.
6. ./deploy.sh status               — every container healthy; the catalog pool lists two pods.

HOW TO DRIVE IT
The console is at http://localhost:9085 (sign in with Keycloak; password = username). Prefer a real
browser if you have one; otherwise do every step against the API with a token you mint in-network
exactly as the repo's runners do (see scripts/postman/run-tests.sh, the mint function) — the cut is
the same either way. Read every response body; the assertions below are about status codes and JSON
fields, not about pixels.

WALK — for each persona: DO it, then compare what you SEE with EXPECTED, and say PASS or DIFF
7. editor (the team owner)
   - GET /api/v1/catalogs → {count:1}, the row carries _actions all true and _provenance "member".
   - Open the Demo catalog → the categories list has EMEA region and APAC region; each row's _actions
     view/update/delete/assign-tags are all true.
   - Create a category with tags {"region":["apac"]} → 201; then try {"region":["mars"]} → 422 with
     errorCode TAG_VALUE_ILLEGAL naming the allowed values. Delete the category you created.
8. viewer (read-only member)
   - The same catalog: every _actions mutation is false, view is true.
   - POST a category → 403, content-type application/problem+json, errorCode ACCESS_DENIED.
   - PUT a member's role on the team → 403 (a custom role cannot manage the team).
9. outsider (member of no team)
   - GET /api/v1/catalogs → {count:0}. GET the Demo catalog by id → 403 problem+json, no body shell.
10. pm-demo (a member of the production team)
   - GET /api/v1/catalogs/d3110000-0000-0000-0000-000000000002/categories → 200, no WWW-Authenticate.
11. sup-demo (a supervisor, member of nothing) — password-only login at first
   - Both Demo Production Catalog and Demo Open Catalog are listed with _provenance "supervised".
   - The Open catalog's categories → 200.
   - The Production catalog's categories → 401 with a WWW-Authenticate header:
     Bearer error="insufficient_user_authentication", acr_values="aal2", max_age="300".
   - In the console: the locked panel + [Verify]. Click it; Keycloak asks for the password, then a
     one-time code — print one with:
       python3 scripts/postman/mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234
     After that: the same catalog's categories → 200; the chip reads "Elevated · m:ss". (If you are
     API-only, use the scripted code-flow miner in scripts/postman/run-step-up-matrix.sh for E9.)
12. Optional, the tag-gated role: as editor, set viewer's role to alice-role (requires region∈[apac]
   AND sensitivity∈[public]); as viewer the categories list is now {count:0} and a direct GET of
   APAC is 403; as editor tag APAC {"sensitivity":"public"}; as viewer exactly APAC returns. Restore
   viewer to demo-viewer and remove the tag when done.

REPORT
13. A table: step · persona · what you observed (status + the decisive field) · PASS/DIFF.
14. Any DIFF: quote the response and say which claim in docs/guides/DEMO-CONSOLE-WALKTHROUGH.md it
    contradicts. Do not "fix" anything — this is a demo run, not a change.
15. Finish with ./deploy.sh down only if I asked you to tear down; otherwise leave the rig running.
```

Why the prompt is shaped this way: every assertion names a **contrast** (a member vs an outsider, a
verdict vs a prediction) rather than "a 200"; the agent is told to prefer the wire over pixels; and
it is told what *not* to do — a demo run that starts patching things is no longer a demo run.
