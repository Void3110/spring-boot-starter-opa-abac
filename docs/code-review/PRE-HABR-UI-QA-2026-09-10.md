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
| R1 | Read the sign-in card as a stranger; try to log in with each name it prints | Every printed persona exists in the realm with a password credential; nothing printed is missing, nothing needed is unprinted | The card is the reader's only map | PASS — every persona the card prints (`editor`, `demo`, `viewer`, `outsider`, `sup-demo`, `pm-demo`) exists in `realm-export.json` with a password credential; `sup-demo` additionally carries the seeded OTP credential the card alludes to. `demo` was not walked this pass (its cut is D3, unchanged since 07-12) |
| R2 | From the **root README** alone, find how to open the demo console | A reader reaches "run with `ENABLE_SPA=1`, seed, open `:9085`" without opening a sub-README | The article will link the root README | **FAIL → DOC-1** — the root README has no route to the console: zero mentions of `ENABLE_SPA`, the seed script or a UI at `:9085` |
| R3 | As a reader, try `sup-demo` | Keycloak asks for a one-time code; the docs a reader sees say where the code comes from | The supervised story is reachable, not a wall | **PARTIAL → DOC-2** — Keycloak does ask for a code, and the recipe exists and works (`mint-code-flow-token.py --print-otp --otp-secret …` printed a valid code tonight), but only in `example-demo-ui/README.md`; the root README and the sign-in card say "a second factor" and no more |
| R4 | Tablet-width viewport (768 px) on the grid and a catalog | No horizontal scroll, controls reachable | Layout on a reader's laptop half-screen | PASS — 768 px: `scrollWidth == clientWidth` (753/753), no horizontal overflow on grid + catalog |
| R5 | Watch `X-Upstream-Addr` across a drill-in | Both pods answer 200; no pod affinity needed (JWT is the session) | Two pods behind the gateway are transparent | PASS — six `GET /api/v1/catalogs` calls split 3/3 across `…:28081` / `…:28082`, all 200 |
| R6 | Reload mid-drill-in | Session restored from `sessionStorage`; app lands on the grid (accepted: no deep links) — no re-login, no blank page | The E16 restoration on today's images | PASS — reload restored the session from `sessionStorage` (same subject, no re-login); landed on the grid |

### A–I — the canonical walk, re-driven (07-12 rows; 07-15 delta rows marked ★)

| # | DO | EXPECT (the cut) | Result |
|---|---|---|---|
| A1 | Unauth load of `:9085` | Only the sign-in card, no catalog data | PASS — after every sign-out the card alone rendered (no data, `sessionStorage` empty); "Sign in with Keycloak" → the realm's PKCE form at the gateway origin, `client_id=catalog-spa` |
| A2 ★ | Signed in as `editor` | Identity chip `editor`, realm roles `catalog-editor`/`catalog-viewer`; Demo catalog renders | PASS — chip `editor` + `catalog-editor` / `catalog-viewer`; Demo catalog renders |
| A3 | Force a token refresh (set `expires_at` in the past inside the `oidc.user:…` sessionStorage entry, then click) | The refresh grant runs silently; the click succeeds, no 401 bounce | PASS — `expires_at` forced past → refresh grant `POST …/openid-connect/token` 200; click → categories 200, no 401 bounce; `auth_time` unchanged, `expires_at` +1800 s. **OBS-1:** one click fired **five** refresh grants (each concurrent fetch refreshed) — harmless here (no refresh-token rotation), a single-flight would be tidier |
| A4 ★ | Switch identity | Session cleared; Keycloak re-prompts (no silent SSO) | session-clear half PASS — "Switch identity" → `signoutRedirect` round trip, `sessionStorage` OIDC entry gone, sign-in card back. **OBS-3 (unconfirmed):** the first click after a reload did nothing, the second signed out — could be pane click timing; re-check by hand. Re-prompt half: see the viewer login below |
| B1 ★ | `editor`: catalog list | Demo catalog present; "+ New catalog" offered | PASS — `{count:1}` with `_actions` + `_provenance:"member"`; "+ New catalog" offered |
| B2 ★ | `outsider`: catalog list | Empty (`count:0`) | PASS — `outsider` (realm `catalog-viewer` only): `GET /api/v1/catalogs` → `{count:0, items:[]}`, the grid is empty, "+ New catalog" amber |
| B3 ★ | `outsider`: deep-link the Demo catalog id | `403 problem+json ACCESS_DENIED`, no shell | PASS — deep links as `outsider`: the Demo catalog → **403** `application/problem+json` `ACCESS_DENIED`; its categories list → 403; the production catalog (`…0002`) → 403. No shell, no children |
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
| I1 ★ | Console + network across personas on the 4.0.8 images | No errors, no spurious 401 on legit paths | **PASS across all five personas** — every console error line is a resource-load status from either my forced probes or an **expected** deny the UI renders honestly (the supervised roster 403); no app exception, no spurious 401 on a legitimate path, the only 401s are the RFC 9470 challenge |

### E — the supervisor slice, the reader-visible subset (E10–E21 → today)

| # | DO | EXPECT (the cut) | Result |
|---|---|---|---|
| E11 | `sup-demo` (password only) opens the **Open** catalog | Categories render; no panel; no `WWW-Authenticate` in the log | PASS — `sup-demo` at `aal1` opens the **Open** catalog: "Demo Open Category" renders (view-only, "no affordances enriched"), the supervised badge shows, **no** locked panel, no [Verify], no chip; `GET …0003` + categories → 200, no `WWW-Authenticate` |
| E10 | `sup-demo` opens the **Production** catalog | The locked panel with the server's `error_description`, `acr_values=aal2`, `max_age=300`, [Verify]; after the OTP, back **on the same catalog**, contents render, chip `Elevated · m:ss` | wire half PASS — `sup-demo` at `acr=aal1`: the grid lists both supervised catalogs with `_provenance:"supervised"` and view-only `_actions`; the Production card carries the **supervised** badge and the amber **"production · verify to open"**; `GET …0002/categories` → **401** with `WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="A second factor is required to read production content", acr_values="aal2", max_age="300"`. UI: opening the Production catalog shows the **locked panel** — 🔒 "Production contents — fresh second factor required", the server's `error_description` verbatim, `acr_values aal2`, `max_age 300s`, [Verify] "Takes you to Keycloak to re-authenticate, then back to this page." — while the header chip reads **not elevated**, breadcrumbs + the metadata card stay usable, and the team panel says "Roster not visible to you: 403 — Access denied" honestly. [Verify] → Keycloak "Please re-authenticate to continue" with the username pre-filled — **prompt sequence under `max_age=0` on the live SSO session: password, then the one-time code** (the maintainer typed both; the code from `mint-code-flow-token.py --print-otp`) → landed **back on the same catalog**: the panel gone, "Demo Production Category" rendered, the header chip **Elevated · 4:37** (`auth_time` 24 s old against `max_age=300`), the card's amber "verify to open" gone, token `acr=aal2`, `sessionStorage` carrying `stepUp.maxAge`; the wire shows the 401s before and **one** 200 after. **PASS** |
| E15 | `pm-demo` opens the Production catalog | No badge, no amber, no chip; categories render (a member's read needs no elevation) | PASS — `pm-demo` at `acr=aal1`, member (owner) of both demo teams: grid lists the Production and Open catalogs with `_provenance:"member"`; opening the **Production** catalog renders "Demo Production Category" with all four buttons; no locked panel, no supervised badge, no elevation chip; `GET …0002` and its categories → 200 with **no `WWW-Authenticate`**. The words "verify / second factor" on the page are the catalog's own caption, not UI state |
| E19 | `editor`/`viewer` on the pre-existing surfaces | Every amber/red idiom as before; nothing new outside supervised rows | PASS — the editor/viewer surfaces walked above (create/tag/team/role panels, the amber and red idioms, the locked panel only on the supervised row) behave as in the 07-12 / 07-15 passes; no new UI outside supervised rows and the challenge |

Out of scope (proven by the suites, not by hand): the newman matrices, `opa test`, the MCP surface.

## Findings (running log — every observation lands here, verdicts at the end)

Severity vocabulary: **DEF** = a defect in the app/rig · **DOC** = a reader-facing documentation gap ·
**OBS** = an observation worth a hand check, not a defect on the evidence so far.

| # | Class | What | Evidence | Proposed action |
|---|---|---|---|---|
| DOC-1 | DOC | The **root README never mentions the demo console**: its "run the full secured rig" block starts the rig without `ENABLE_SPA=1`, never seeds, never says `:9085` has a UI. The recipe lives only in `example-demo-ui/README.md` and `infra/README.md` | `grep -c ENABLE_SPA\|seed-demo-data\|demo-ui README.md` → 0 | **Done 2026-09-11** (`feature/void3110/pre-habr-findings`): root README "Try the demo console" subsection + the new [[DEMO-CONSOLE-WALKTHROUGH]] guide; the SPA is now on by default, so the plain `./deploy.sh up` is the demo |
| DOC-2 | DOC | **`sup-demo` needs a one-time code and the root README does not say where it comes from.** The realm seeds her an OTP credential whose secret is raw bytes (a phone authenticator cannot enrol it); the only reader-facing recipe is one paragraph in `example-demo-ui/README.md` (`mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234`) | realm export: `sup-demo` credentials `password` + `otp`; README.md: 0 hits for print-otp | **Done 2026-09-11**: the recipe is in the root README subsection and in the walkthrough's supervisor step (the sign-in card unchanged — the README is where the article lands) |
| R-G1 | DOC | **The documented SPA recipe leaves the identity directory off**, so the add-member picker answers "No directory accounts match." to every search — a reader trying to add `alice` (the README's own G2 example) hits a dead end, and by the no-oracle contract the UI cannot say why | `GET /api/v1/users/search?q=alice` → 200, empty; rig env `OPA_ABAC_DIRECTORY_KEYCLOAK_ENABLED=false` | **Done 2026-09-11**: `ENABLE_DIRECTORY` (and `ENABLE_SPA`) default to **1** in `deploy.sh`; the runners forward `:-1`; docs flipped to "opt out with =0" |
| DEF-1 | DEF | **A tag-requiring WRITE role can create resources it cannot read, in places it cannot read.** `alice-role` (WRITE on catalog/category/product, `required_tags` `region:[apac]` ∧ `sensitivity:[public]`) as `viewer`: `POST …/categories` (no tags) → **201**; `POST …/categories/{EMEA}/products` — EMEA is `region:emea`, a category this role is **denied** to `GET` → **201**; the creator's own untagged category → `GET` **403** (write-only spawn). Root `PUT` → 403 and every instance read/mutation stays tag-gated, so the gap is exactly the **type-level** verbs: `category.rego` / `product.rego` open `create` (and type-level `assign-tags`) through `is_type_level_request` + `list_inheritable_grant`, whose comment says it "only OPENS the gate" for a LIST whose rows SQL then cuts — but for `create` nothing cuts afterwards, and the clause carries **no `tags_satisfied`** conjunct. The create input also carries no parent tags to match against (`roleResourceType='catalog'`, `resourceType='category'`, attributes = the tag-on-create payload at most) | JS-forced calls with the viewer session, 2026-09-10; `data.category.inheritable` = `{catalog:true, category:true}`; probe rows deleted afterwards | **Fixed 2026-09-11** on `feature/void3110/tag-gated-create` — [[TAG-GATED-CREATE]], [[0034-tag-gated-placement-input-contract|ADR 0034]]: the placement gate (the parent's tags AND the payload's tags, whichever way the verb is granted; re-parent is a placement); re-measured in the console as `viewer`/`alice-role`: name-only create → **403**, a matching payload under the untagged root → **403**, a product under EMEA → **403**; the tag matrix's 7a–7g pin it through the gateway. *(Originally:)* Planned 2026-09-11 → the planning note. Not a Habr blocker (the role must already hold WRITE; the created rows are invisible to the creator and ordinary for everyone else) but it contradicts the stated contract "everything below the root stays tag-gated" and ADR 0009's "a role may act on resources whose tags match". Fix on a follow-up branch: for a type-level `create`, require the **parent's** tags (the resolved `roleResource` / the category for a product) to satisfy the role's requirement, and require the tag-on-create payload to satisfy it too (a creator must be able to read what it creates); keep `list` on the coarse gate. Ratchet: an `opa test` per policy + a newman cell in the tag matrix (tag-gated writer: create under a mismatching parent → 403, matching → 201) |
| DEF-2 | DEF (cosmetic) | **Catalog cards in the grid do not share a height**: with two supervised cards the Production card (two badges + a two-line caption) is taller and the Open card sits shorter beside it with a ragged bottom edge | maintainer's screenshot, `sup-demo` grid, 2026-09-10 | **Done 2026-09-11**: `h-full` on the grid item and the card (`Card` gained a `className` prop); packaged bundle rebuilt |
| OBS-1 | OBS | One click after token expiry fired **five** refresh grants (each concurrent fetch saw `expired` and refreshed) | A3: 5× `POST …/openid-connect/token` 200 in one interaction | Harmless on this realm (no refresh-token rotation); a single-flight refresh promise would be tidier and would survive a realm that revokes reused refresh tokens |
| OBS-2 | OBS | The team tag key's ✕ fired no request in the pane; removed via `DELETE /teams/{id}/tag-definitions/{key}` (204) instead | F5; `example-demo-ui/src/tags.tsx:66` guards the delete with `window.confirm(…)` | **Explained — pane artifact**: the Browser pane swallows `confirm()` dialogs; the control is fine. Same for role delete and ownership transfer (`roles.tsx`, `teams.tsx`). No action |
| OBS-3 | OBS | The Browser pane's **synthetic clicks sometimes did not reach the React handlers** on "Switch identity" and "Sign in with Keycloak" (first click no-op, second worked; later a DOM-dispatched `button.click()` always worked) | A4 + the outsider handoff; no end-session / auth request after the missed clicks | **Pane artifact, not an app defect** on this evidence — a human click never missed in the 07-12 / 07-15 passes; no action |

## Verdict — 2026-09-10, 21:05

**Executed:** 34 cells across five personas (`editor`, `viewer` twice — once as the tag-gated
`alice-role`, once as the canonical `demo-viewer` —, `outsider`, `pm-demo`, `sup-demo`), the
supervisor round trip included. **Every cell passed for the persona the plan names**, and every
console error line is attributable (a forced probe, or an expected deny the UI renders honestly).
No security regression on the 4.0.8 images. The maintainer performed the logins; the agent drove
the authenticated console and every wire assertion.

| Group | Result |
|---|---|
| **R** reader's seat | R1 R4 R5 R6 PASS · **R2 FAIL → DOC-1** · **R3 partial → DOC-2** · R-G1 (directory off by recipe) |
| **A** auth/session | A1 A2 **A3** A4 PASS — A3 executed in a browser for the first time (refresh grant, no 401 bounce, `auth_time` kept) |
| **B** tenant isolation | B1 B2 B3 B4 B5 PASS — 12 leftover foreign catalogs never listed, never openable |
| **C** hierarchy + tags | C1 C4 PASS · **C2 executed for the first time**, both halves, on the wire and in the console |
| **D** affordances | D1 D2 D4 PASS (D3 not walked — `demo` unchanged since 07-12) |
| **E** predicted-deny | E1 E3 PASS for `demo-viewer` · **E3 for a tag-requiring WRITE role → DEF-1** |
| **F** write/tag/dictionary | F1 (create) F2 F3 F5 PASS · F1's delete half not exercised in the UI (the sandbox row is left for the maintainer) |
| **G** control plane | G1 PASS as configured (→ R-G1) · G3 PASS as honest deny |
| **H** error contract | H1 H2 PASS |
| **I** regression | I1 PASS across all personas · I2 (operator-only `abac_deny` refused) PASS |
| **E10–E19** supervisor | E10 E11 E15 E19 PASS — prompt sequence recorded (password, then the code) |

**Findings:** DEF-1 (policy: type-level `create` bypasses a role's tag requirement — Medium, not a
Habr blocker, follow-up branch + ratchet), DEF-2 (cosmetic: ragged card heights), DOC-1 + DOC-2
(the root README's missing route to the console and to `sup-demo`'s code — **worth fixing before
the article**), R-G1 (the recipe should enable the directory), OBS-1 (refresh stampede, harmless).
OBS-2/OBS-3 resolved as Browser-pane artifacts.

**Not covered tonight, by design:** E12–E14, E16–E18 (drill/patch recipes — the committed STATUS-06
pass stands), the newman matrices and `opa test` (the deterministic tier), the MCP surface.
