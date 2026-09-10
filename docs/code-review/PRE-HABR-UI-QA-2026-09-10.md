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
| B4 | `editor` vs `viewer`, the **same** category list | Identical rows, different buttons | PASS — both see `count:3` (EMEA, APAC, the sandbox); editor `_actions` all true, `demo-viewer` view-only |
| B5 | Any persona: the 12 leftover e2e catalogs | Never listed, never openable | PASS — 26 catalogs in the DB, 12 e2e leftovers: list `count:1`; deep link `GET /catalogs/0e47b922-…` → **403** `problem+json` |
| C1 ★ | Drill Demo catalog → EMEA → products | Tree + breadcrumbs; products render | PASS — Catalogs / Demo catalog / EMEA region; Demo widget + Demo gadget render |
| C2 | Tag-gated visibility, live: give `viewer` the `alice-role`; as `viewer` open the Demo catalog; then (as `editor`) tag APAC `sensitivity=public`, re-check | Root still opens (default exemption); **both** categories gone (untagged/mismatch = non-matching); after the tag, **APAC only** returns — and it was never a client-side hide (network shows the filtered list) | **PASS, both halves** — (1) root opens (`view` true), categories `count:0`, "No categories visible to you in this catalog."; direct `GET` APAC (`region:apac` only) → 403. (2) APAC tagged `sensitivity=public` (operator path): list `count:1` = **APAC only** with `_actions` all true (the role holds WRITE/TAG and the tags now match); direct `GET`: APAC **200**, EMEA (`region:emea`) **403**, the sandbox (`apac` + `internal`) **403**. Untagged and mismatching children are equally invisible; the cut is the server's list, not a client hide. Console confirms: the CATEGORIES block lists **APAC region · region:apac · sensitivity:public** with view/update/delete/assign-tags, nothing else. Fixtures restored afterwards (viewer → `demo-viewer`, APAC → `region:apac` only) |
| C4 ★ | A paginated list | `{count,page,perPage,items}`, count subject-relative, `_actions` per item | PASS — `{count,page,perPage,items}` on catalogs and products; `_actions` per item |
| D1 ★ | `editor`: catalog + a product | All mutation affordances present; `_actions` all true on the wire | PASS — catalog and both products: `_actions` view/update/delete/assign-tags all `true` on the wire; all buttons present |
| D2 ★ | `viewer`: the **same** resources | Mutations locked; `_actions` false on the wire | catalog half PASS — as `viewer` (holding `alice-role` for C2): YOUR ACTIONS ✓view ✕update ✕delete ✕assign-tags, 🔒 assign-tags; wire `_actions` `{view:true, update:false, delete:false, assign-tags:false}`; root `PUT` → **403**. Categories half (as `demo-viewer`): all three rows carry `_actions` `{view:true, update:false, delete:false, assign-tags:false}` on the wire — the UI locks follow `_actions` |
| D4 ★ | `editor` (owner): team panel | Role dropdowns, transfer-ownership, remove, add member, ROLES, TAG KEYS | PASS — per-member role dropdowns (9 roles), transfer-ownership, remove, + Add member, ROLES (9), TAG KEYS (3) |
| E1 ★ | `viewer`: "+ New catalog" and mutate buttons | Amber = client prediction, labeled honestly | PASS — "+ New catalog" amber with the tooltip "Your realm roles lack catalog-editor — creating will answer 403. Left usable on purpose…" |
| E3 ★ | `viewer`: force a create past the amber | Server 403, nothing created | **FAIL for the role under test → DEF-1** — the forced `POST …/categories` as `viewer` holding `alice-role` (WRITE + ALL_OF tag requirement) answered **201**, not 403. Re-run with the canonical `demo-viewer` (READ only): forced `POST …/categories` → **403** `application/problem+json` `ACCESS_DENIED`; forced `POST …/products` under EMEA → **403**. **PASS for the canonical persona**; the 201 is DEF-1, a tag-requiring WRITE role |
| F1 | `editor`: create a category, then delete it | 201 + appears; delete removes it | create PASS — `POST …/categories` **201**, "QA sandbox" appears with its tag-on-create `region:apac`; delete: pending (kept for C2) |
| F2 | Tag editor on catalog, category, product | Dictionary-driven on all three types | PASS — dictionary-driven editor on the **catalog** (root) and a **category**: `sensitivity` select, `region` toggles, `env` shown **operator-managed (locked)**; assigning `sensitivity=internal` → `PUT …/categories/{id}` 200, tree shows `sensitivity:internal`. Product editor: same component (products list shows assign-tags), not re-opened |
| F3 | Force an illegal tag value | `422 TAG_VALUE_ILLEGAL`, readable in the UI | PASS — forced `tags:{region:["mars"]}` → **422** `TAG_VALUE_ILLEGAL`, detail names `[emea, amer, apac]`; forced `abac_deny` → **422** "Unknown tag key" (the I2 fence) |
| F5 | Define a TAG KEY as owner | Allowed; the new key appears in editors | PASS — owner sees "+ Define tag key"; defining `qa-scratch` ENUM·SINGLE `a|b` → `POST …/tag-definitions` **201**, listed as `team` with edit/✕. **OBS-2:** the ✕ click produced no DELETE (a confirm the pane swallowed?) — removed via the API instead |
| G1 | Owner: directory picker, search `alice` | Live results from the Keycloak-backed directory | PASS as configured / **finding R-G1** — `GET /api/v1/users/search?q=alice` 200 → "No directory accounts match." The rig runs **without** `ENABLE_DIRECTORY=1`, and so will every reader following the SPA recipe: the add-member picker is a dead end for them, and by the no-oracle contract the UI cannot say why |
| G3 | `viewer`: team management controls | Absent / "Not allowed for your role" | PASS as **honest deny**, not absence — the team panel offers the role selectors to a non-owner; re-tiering `demo` → `member` → `PUT …/members/{id}` **403** and the red inline "✕ change-role → member failed: 403 — Access denied"; `GET …/role-definitions` → 403 for the viewer |
| H1 ★ | Any denial on the wire | `application/problem+json`, typed `errorCode`, UTC `timestamp` | PASS — `application/problem+json`, `errorCode:"ACCESS_DENIED"`, `type:/problems/access-denied`, UTC `timestamp` |
| H2 | A validation error | Typed code, human-readable | PASS — empty name → **400** `VALIDATION_FAILED`, `detail:"name: size must be between 1 and 200"` |
| I1 ★ | Console + network across personas on the 4.0.8 images | No errors, no spurious 401 on legit paths | editor + viewer parts PASS — console errors are exactly the four forced probes (403/400/422/422); no app error, no spurious 401 |

### E — the supervisor slice, the reader-visible subset (E10–E21 → today)

| # | DO | EXPECT (the cut) | Result |
|---|---|---|---|
| E11 | `sup-demo` (password only) opens the **Open** catalog | Categories render; no panel; no `WWW-Authenticate` in the log | |
| E10 | `sup-demo` opens the **Production** catalog | The locked panel with the server's `error_description`, `acr_values=aal2`, `max_age=300`, [Verify]; after the OTP, back **on the same catalog**, contents render, chip `Elevated · m:ss` | |
| E15 | `pm-demo` opens the Production catalog | No badge, no amber, no chip; categories render (a member's read needs no elevation) | |
| E19 | `editor`/`viewer` on the pre-existing surfaces | Every amber/red idiom as before; nothing new outside supervised rows | |

Out of scope (proven by the suites, not by hand): the newman matrices, `opa test`, the MCP surface.

## Findings (running log — every observation lands here, verdicts at the end)

Severity vocabulary: **DEF** = a defect in the app/rig · **DOC** = a reader-facing documentation gap ·
**OBS** = an observation worth a hand check, not a defect on the evidence so far.

| # | Class | What | Evidence | Proposed action |
|---|---|---|---|---|
| DOC-1 | DOC | The **root README never mentions the demo console**: its "run the full secured rig" block starts the rig without `ENABLE_SPA=1`, never seeds, never says `:9085` has a UI. The recipe lives only in `example-demo-ui/README.md` and `infra/README.md` | `grep -c ENABLE_SPA\|seed-demo-data\|demo-ui README.md` → 0 | Add a short "Try the demo console" subsection to the root README: `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2` · `scripts/postman/seed-demo-data.sh` · open `http://localhost:9085` · the personas. The article will link the root README |
| DOC-2 | DOC | **`sup-demo` needs a one-time code and the root README does not say where it comes from.** The realm seeds her an OTP credential whose secret is raw bytes (a phone authenticator cannot enrol it); the only reader-facing recipe is one paragraph in `example-demo-ui/README.md` (`mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234`) | realm export: `sup-demo` credentials `password` + `otp`; README.md: 0 hits for print-otp | Print the recipe where the reader is stuck: on the sign-in card next to `sup-demo`, and in the root README's new subsection |
| R-G1 | DOC | **The documented SPA recipe leaves the identity directory off**, so the add-member picker answers "No directory accounts match." to every search — a reader trying to add `alice` (the README's own G2 example) hits a dead end, and by the no-oracle contract the UI cannot say why | `GET /api/v1/users/search?q=alice` → 200, empty; rig env `OPA_ABAC_DIRECTORY_KEYCLOAK_ENABLED=false` | Recommend `ENABLE_DIRECTORY=1` in the demo recipe (it force-enables its prerequisites), or a one-line hint in the picker when the directory is disabled by configuration (config is knowable client-side; the no-oracle rule is about search results, not about the feature flag) |
| DEF-1 | DEF | **A tag-requiring WRITE role can create resources it cannot read, in places it cannot read.** `alice-role` (WRITE on catalog/category/product, `required_tags` `region:[apac]` ∧ `sensitivity:[public]`) as `viewer`: `POST …/categories` (no tags) → **201**; `POST …/categories/{EMEA}/products` — EMEA is `region:emea`, a category this role is **denied** to `GET` → **201**; the creator's own untagged category → `GET` **403** (write-only spawn). Root `PUT` → 403 and every instance read/mutation stays tag-gated, so the gap is exactly the **type-level** verbs: `category.rego` / `product.rego` open `create` (and type-level `assign-tags`) through `is_type_level_request` + `list_inheritable_grant`, whose comment says it "only OPENS the gate" for a LIST whose rows SQL then cuts — but for `create` nothing cuts afterwards, and the clause carries **no `tags_satisfied`** conjunct. The create input also carries no parent tags to match against (`roleResourceType='catalog'`, `resourceType='category'`, attributes = the tag-on-create payload at most) | JS-forced calls with the viewer session, 2026-09-10; `data.category.inheritable` = `{catalog:true, category:true}`; probe rows deleted afterwards | **Not a Habr blocker** (the role must already hold WRITE; the created rows are invisible to the creator and ordinary for everyone else) but it contradicts the stated contract "everything below the root stays tag-gated" and ADR 0009's "a role may act on resources whose tags match". Fix on a follow-up branch: for a type-level `create`, require the **parent's** tags (the resolved `roleResource` / the category for a product) to satisfy the role's requirement, and require the tag-on-create payload to satisfy it too (a creator must be able to read what it creates); keep `list` on the coarse gate. Ratchet: an `opa test` per policy + a newman cell in the tag matrix (tag-gated writer: create under a mismatching parent → 403, matching → 201) |
| OBS-1 | OBS | One click after token expiry fired **five** refresh grants (each concurrent fetch saw `expired` and refreshed) | A3: 5× `POST …/openid-connect/token` 200 in one interaction | Harmless on this realm (no refresh-token rotation); a single-flight refresh promise would be tidier and would survive a realm that revokes reused refresh tokens |
| OBS-2 | OBS | The team tag key's **✕ (delete) control fired no request** in the pane; removed via `DELETE /teams/{id}/tag-definitions/{key}` (204) instead | F5: no DELETE in the network log after the click | Hand-check whether it is a `confirm()` the pane swallowed (then not a defect) or a dead control |
| OBS-3 | OBS | The **first click on "Switch identity" after a reload did nothing**; the second signed out cleanly | A4: no end-session request after click 1; storage cleared after click 2 | Hand-check once; likely pane click timing on a re-rendering header |
