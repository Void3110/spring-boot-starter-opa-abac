---
tags:
  - status/active
  - type/qa
  - area/demo-ui
---

# Pre-Habr UI QA — the demo console as a reader will meet it (2026-09-10)

> **Purpose.** The Habr series goes live on 2026-09-11 and readers will run the example rig and poke
> the console. This pass re-drives the committed browser cases against **today's** rig — images built
> from `main` at `976c3f5` (Boot **4.0.8**, the #122 policy guards), APISIX 3.11.0, Keycloak 26.3.2,
> OPA 1.10.1, two catalog pods — and adds a **reader's-seat** group: what a stranger with only the
> public docs would try first. Method as before: `DO → EXPECT (the cut) → PROVES`; every EXPECT names
> a contrast, never "a 200". Prior records: [[PRE-PUBLISH-UI-QA-2026-07-12]] (A–I, the canonical
> walk), [[PRE-PUBLISH-UI-QA-2026-07-15]] (the 1.1.0 delta), and the supervisor slice's E10–E21 in
> `docs/to-do/implemented/SPA-CHALLENGE-UX/10-QA-TEST-CASES.md`.

## Preconditions

1. **Rig posture:** `ENABLE_SPA=1 ENABLE_MCP=1 ENABLE_TRACING=1 ./deploy.sh up --pods 2` after
   `./profile.sh up`; all 13 containers healthy; OPA restarted after the last policy merge (it does
   not watch its mount — Mulch `opa-abac-rig-deploy-ops`, 2026-09-10). Demo data seeded: the three
   `d3110000-…-000{1,2,3}` catalogs exist (`Demo catalog` untagged, `Demo Production Catalog`
   `env=production`, `Demo Open Catalog`); the persisted DB also holds 12 e2e leftover catalogs
   governed by other teams — useful as B5's cross-tenant material, invisible to every demo persona.
2. **The pane:** `.claude/launch.json` `demo-ui-packaged` → `http://localhost:9085` (E20).
3. **Login handling (security).** The maintainer performs every Keycloak login; the agent drives the
   *authenticated* SPA. The pane restored an `editor` session on open, so the editor-side cases run
   first; each identity switch is a handoff.
4. **Personas** (realm `catalog-demo`, password = username, as printed on the sign-in card):
   `editor` = team owner · `demo` = `demo-editor` (READ/WRITE/TAG) · `viewer` = `demo-viewer` (READ) ·
   `outsider` = no team · `pm-demo` = production member · `sup-demo` = supervisor (password **+ TOTP**).
   The team's only tag-gated role is `alice-role` (`region:[apac]` AND `sensitivity:[public]`, ALL_OF).

## Cases

### R — the reader's seat (new this pass)

| # | DO | EXPECT (the cut) | PROVES | Result |
|---|---|---|---|---|
| R1 | Read the sign-in card as a stranger; try to log in with each name it prints | Every printed persona exists in the realm with a password credential; nothing printed is missing, nothing needed is unprinted | The card is the reader's only map | |
| R2 | From the **root README** alone, find how to open the demo console | A reader reaches "run with `ENABLE_SPA=1`, seed, open `:9085`" without opening a sub-README | The article will link the root README | |
| R3 | As a reader, try `sup-demo` | Keycloak asks for a one-time code; the docs a reader sees say where the code comes from | The supervised story is reachable, not a wall | |
| R4 | Tablet-width viewport (768 px) on the grid and a catalog | No horizontal scroll, controls reachable | Layout on a reader's laptop half-screen | PASS — 768 px: `scrollWidth == clientWidth` (753/753), no horizontal overflow on grid + catalog |
| R5 | Watch `X-Upstream-Addr` across a drill-in | Both pods answer 200; no pod affinity needed (JWT is the session) | Two pods behind the gateway are transparent | PASS — six `GET /api/v1/catalogs` calls split 3/3 across `…:28081` / `…:28082`, all 200 |
| R6 | Reload mid-drill-in | Session restored from `sessionStorage`; app lands on the grid (accepted: no deep links) — no re-login, no blank page | The E16 restoration on today's images | PASS — reload restored the session from `sessionStorage` (same subject, no re-login); landed on the grid |

### A–I — the canonical walk, re-driven (07-12 rows; 07-15 delta rows marked ★)

| # | DO | EXPECT (the cut) | Result |
|---|---|---|---|
| A2 ★ | Signed in as `editor` | Identity chip `editor`, realm roles `catalog-editor`/`catalog-viewer`; Demo catalog renders | PASS — chip `editor` + `catalog-editor` / `catalog-viewer`; Demo catalog renders |
| A3 | Force a token refresh (set `expires_at` in the past inside the `oidc.user:…` sessionStorage entry, then click) | The refresh grant runs silently; the click succeeds, no 401 bounce | PASS — `expires_at` forced past → refresh grant `POST …/openid-connect/token` 200; click → categories 200, no 401 bounce; `auth_time` unchanged, `expires_at` +1800 s. **OBS-1:** one click fired **five** refresh grants (each concurrent fetch refreshed) — harmless here (no refresh-token rotation), a single-flight would be tidier |
| A4 ★ | Switch identity | Session cleared; Keycloak re-prompts (no silent SSO) | session-clear half PASS — "Switch identity" → `signoutRedirect` round trip, `sessionStorage` OIDC entry gone, sign-in card back. **OBS-3 (unconfirmed):** the first click after a reload did nothing, the second signed out — could be pane click timing; re-check by hand. Re-prompt half: see the viewer login below |
| B1 ★ | `editor`: catalog list | Demo catalog present; "+ New catalog" offered | PASS — `{count:1}` with `_actions` + `_provenance:"member"`; "+ New catalog" offered |
| B2 ★ | `outsider`: catalog list | Empty (`count:0`) | |
| B3 ★ | `outsider`: deep-link the Demo catalog id | `403 problem+json ACCESS_DENIED`, no shell | |
| B5 | Any persona: the 12 leftover e2e catalogs | Never listed, never openable | PASS — 26 catalogs in the DB, 12 e2e leftovers: list `count:1`; deep link `GET /catalogs/0e47b922-…` → **403** `problem+json` |
| C1 ★ | Drill Demo catalog → EMEA → products | Tree + breadcrumbs; products render | PASS — Catalogs / Demo catalog / EMEA region; Demo widget + Demo gadget render |
| C2 | Tag-gated visibility, live: give `viewer` the `alice-role`; as `viewer` open the Demo catalog; then (as `editor`) tag APAC `sensitivity=public`, re-check | Root still opens (default exemption); **both** categories gone (untagged/mismatch = non-matching); after the tag, **APAC only** returns — and it was never a client-side hide (network shows the filtered list) | |
| C4 ★ | A paginated list | `{count,page,perPage,items}`, count subject-relative, `_actions` per item | PASS — `{count,page,perPage,items}` on catalogs and products; `_actions` per item |
| D1 ★ | `editor`: catalog + a product | All mutation affordances present; `_actions` all true on the wire | PASS — catalog and both products: `_actions` view/update/delete/assign-tags all `true` on the wire; all buttons present |
| D2 ★ | `viewer`: the **same** resources | Mutations locked; `_actions` false on the wire | |
| D4 ★ | `editor` (owner): team panel | Role dropdowns, transfer-ownership, remove, add member, ROLES, TAG KEYS | PASS — per-member role dropdowns (9 roles), transfer-ownership, remove, + Add member, ROLES (9), TAG KEYS (3) |
| E1 ★ | `viewer`: "+ New catalog" and mutate buttons | Amber = client prediction, labeled honestly | |
| E3 ★ | `viewer`: force a create past the amber | Server 403, nothing created | |
| F1 | `editor`: create a category, then delete it | 201 + appears; delete removes it | create PASS — `POST …/categories` **201**, "QA sandbox" appears with its tag-on-create `region:apac`; delete: pending (kept for C2) |
| F2 | Tag editor on catalog, category, product | Dictionary-driven on all three types | PASS — dictionary-driven editor on the **catalog** (root) and a **category**: `sensitivity` select, `region` toggles, `env` shown **operator-managed (locked)**; assigning `sensitivity=internal` → `PUT …/categories/{id}` 200, tree shows `sensitivity:internal`. Product editor: same component (products list shows assign-tags), not re-opened |
| F3 | Force an illegal tag value | `422 TAG_VALUE_ILLEGAL`, readable in the UI | PASS — forced `tags:{region:["mars"]}` → **422** `TAG_VALUE_ILLEGAL`, detail names `[emea, amer, apac]`; forced `abac_deny` → **422** "Unknown tag key" (the I2 fence) |
| F5 | Define a TAG KEY as owner | Allowed; the new key appears in editors | PASS — owner sees "+ Define tag key"; defining `qa-scratch` ENUM·SINGLE `a|b` → `POST …/tag-definitions` **201**, listed as `team` with edit/✕. **OBS-2:** the ✕ click produced no DELETE (a confirm the pane swallowed?) — removed via the API instead |
| G1 | Owner: directory picker, search `alice` | Live results from the Keycloak-backed directory | PASS as configured / **finding R-G1** — `GET /api/v1/users/search?q=alice` 200 → "No directory accounts match." The rig runs **without** `ENABLE_DIRECTORY=1`, and so will every reader following the SPA recipe: the add-member picker is a dead end for them, and by the no-oracle contract the UI cannot say why |
| G3 | `viewer`: team management controls | Absent / "Not allowed for your role" | |
| H1 ★ | Any denial on the wire | `application/problem+json`, typed `errorCode`, UTC `timestamp` | PASS — `application/problem+json`, `errorCode:"ACCESS_DENIED"`, `type:/problems/access-denied`, UTC `timestamp` |
| H2 | A validation error | Typed code, human-readable | PASS — empty name → **400** `VALIDATION_FAILED`, `detail:"name: size must be between 1 and 200"` |
| I1 ★ | Console + network across personas on the 4.0.8 images | No errors, no spurious 401 on legit paths | editor part PASS — console errors are exactly the four forced probes (403/400/422/422); no app error, no spurious 401 |

### E — the supervisor slice, the reader-visible subset (E10–E21 → today)

| # | DO | EXPECT (the cut) | Result |
|---|---|---|---|
| E11 | `sup-demo` (password only) opens the **Open** catalog | Categories render; no panel; no `WWW-Authenticate` in the log | |
| E10 | `sup-demo` opens the **Production** catalog | The locked panel with the server's `error_description`, `acr_values=aal2`, `max_age=300`, [Verify]; after the OTP, back **on the same catalog**, contents render, chip `Elevated · m:ss` | |
| E15 | `pm-demo` opens the Production catalog | No badge, no amber, no chip; categories render (a member's read needs no elevation) | |
| E19 | `editor`/`viewer` on the pre-existing surfaces | Every amber/red idiom as before; nothing new outside supervised rows | |

Out of scope (proven by the suites, not by hand): the newman matrices, `opa test`, the MCP surface.
