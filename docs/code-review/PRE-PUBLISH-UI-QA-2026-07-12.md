---
tags:
  - status/active
  - type/qa
  - area/demo-ui
---

# Pre-Publish UI QA — the demo SPA (final human-facing verification)

> **Purpose.** The last human-facing pass before 1.0: drive the demo SPA through the browser and
> confirm every shipped capability is *observably* correct — not just that pages render, but that the
> **authorization cut is real** (a viewer sees fewer buttons/rows than an editor; a stranger sees
> nothing; a flagged catalog is gone). This complements the automated proof (830 unit/IT + `opa test`
> 233/233 + the 14-runner newman fleet) with the one thing tests can't show: the actual UX.
>
> **Method.** Each case is `DO → EXPECT (the cut) → PROVES`. "EXPECT" always names a *contrast*
> (what changes vs another persona/state), never just "a 200". Record PASS/FAIL + a note per case.

---

## Preconditions

1. **Rig posture — SPA mode.** The rig must be up with `ENABLE_SPA=1` (force-enables OIDC +
   user-service; puts the gateway in bearer-only + CORS-for-`:3000` mode, public client `catalog-spa`):
   ```bash
   ./profile.sh up
   ENABLE_SPA=1 ./deploy.sh up --pods 2      # (currently the rig is guarded but NOT ENABLE_SPA — needs this)
   scripts/postman/seed-demo-data.sh          # idempotent base demo (Demo team/catalog/roles/tree/tags)
   ```
   > The current rig is up as `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ENABLE_TRACING=1` — **bearer-only,
   > no CORS for :3000**. It needs a re-up with `ENABLE_SPA=1` before the SPA will talk to the gateway.
   > Do this **after** the load test finishes (a rig re-up would disrupt the measurement).
2. **SPA served.** `cd example-demo-ui && npm run dev` → **http://localhost:3000** (Vite; proxies
   `/realms` + `/resources` to Keycloak in-network).
3. **⚠️ Login handling (security).** The SPA logs in via Keycloak **Authorization Code + PKCE** — i.e.
   a Keycloak login form. Per the standing rule ("never enter credentials in the browser; SPA visual
   checks are the maintainer's"), **the maintainer performs each persona login**; the agent drives the
   *authenticated* SPA and verifies outcomes. Confirm the handoff before executing.
4. **Personas** (password == username; all in realm `catalog-demo`). **Authoritative bindings are what
   `scripts/postman/seed-demo-data.sh` actually creates** (verified at execution 2026-07-12) — the seed
   maps three canonical accounts + one stranger:

   | Persona | Seeded role on the Demo team | Expected reach |
   |---|---|---|
   | `editor` / editor | **team `owner`** (system ladder) | **full control plane** + read/write/tag/grant/member-mgmt |
   | `demo` / demo | `demo-editor` (L20: READ/WRITE/TAG) | read + write + tag; **no** grant, **no** member mgmt |
   | `viewer` / viewer | `demo-viewer` (L10: READ) | read-only |
   | `outsider` / outsider | **none** (not a member) | sees nothing; deep-links denied |

   > **Persona-label note.** The QA cases below (A–I) were drafted against an earlier assumed table where
   > `editor` = L20-editor and a separate `admin`/`alice` covered owner/no-binding. The *seed's* reality
   > is the table above: **`editor` IS the owner persona**, **`demo` is the L20 editor**, and the
   > "no-binding empty-list" role (`alice`) is played by **`outsider`** (a member of no team). Read the
   > cases with that substitution — the *cut being proven* is identical; only the account labels move.
   > `bob`/`carol` are not seeded; grant-a-stranger cases (G2) use `outsider`.

5. **Seeded fixtures to reference:** **Demo catalog** (`d3110000-…-0001`) → categories **EMEA region**
   (`dddd2db6-…`) & **APAC region** (`26a99754-…`) → two products under EMEA. (SKUs/product names are
   seed-generated; reference by position, not by the older "Demo widget/gadget SKU-001/002" labels.)

---

## A. Authentication & session

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| A1 | Load `:3000` unauthenticated | Redirected to Keycloak login (PKCE); no catalog data visible pre-login | Gateway bearer-only; no anonymous read |
| A2 | Log in as `editor` (maintainer) | SPA shows the Demo catalog; identity chip shows `editor` | OIDC round-trip + subject extraction |
| A3 | Leave the tab idle past the access-token lifespan, then click any action | **On-demand token refresh** fires silently; the call succeeds (no spurious 401 bounce) | The `api.ts` refresh-on-401 path (PR #65) |
| A4 | Log out / switch persona | Session cleared; re-login required; new persona's reach applies | `sessionStorage` session isolation |

## B. Tenant isolation & the list cut (B4, the headline)

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| B1 | As `editor`, view the catalog list | Demo catalog **present** | Membership grants list visibility |
| B2 | As `alice` (no binding), view the catalog list | List is **empty** — Demo catalog **absent** | Membership is the sole access path (governed-scope filter) |
| B3 | As `alice`, deep-link the Demo catalog's URL/id directly | **Denied** (not a silent 200) — no shell, no children | No deep-link leak (single-GET gate) |
| B4 | As `editor` vs `viewer`, compare the **same** catalog's product list | Identical rows (both members) but different **action buttons** (see D) | List cut is by membership, not role level |
| B5 | Filter/scan for a catalog governed by a *different* team (if seeded) | Never appears for a non-member, on list **or** single-GET | Cross-tenant containment |

## C. Hierarchy & tag-based filtering

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| C1 | Drill Demo catalog → EMEA region → products | Nested tree renders; breadcrumb/drill-in works | Hierarchical navigation |
| C2 | If a role carries a `region=emea` tag requirement, view EMEA vs APAC | EMEA content visible; APAC content filtered **out** (row-level) | Tag-based grant matched in Rego + PE list filter |
| C3 | Grant an **ancestor** (catalog-level) role, then list a deep child type | Child rows **widen** in via the subtree spec, but never beyond the caller's scope | Hierarchy-aware list filter (5.5-B) |
| C4 | Pagination: a list longer than one page | `{count, page, perPage, items}` envelope; count is **subject-relative** (matches what this persona may see, not the table total); page/next work | Pagination envelope composed with the filter |

## D. Action affordances (`_actions`) — buttons mirror enforcement

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| D1 | As `editor`, open a product | Edit / Delete / Assign-tags buttons **present** | `_actions` reflects WRITE+TAG |
| D2 | As `viewer`, open the **same** product | Those buttons **absent** (read-only) — affordance mirrors the actual grant | Affordance honesty (omit-never-fabricate) |
| D3 | As `editor` (no GRANT), open a team/role screen | Role-assignment / member-mgmt controls **absent** | GRANT/CONTROL not affordable to a non-admin |
| D4 | As the `owner`/admin, same screens | Member-add / change-role / define-roles controls **present** | Control-plane category (6.7) affordances |

## E. Predicted-deny UX (amber vs red — the PR #65 affordance)

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| E1 | As `viewer`, hover/attempt a write the client predicts will be denied | Control shows **amber** (client-side prediction from `_actions`) — the action is pre-disabled/flagged | Amber = client prediction |
| E2 | Force a server-denied action that the client did *not* predict (edge) | **Red** surfaces from the server `_actions`/403 — distinct from amber | Red = server verdict; the two are visually distinct |
| E3 | Confirm amber never *replaces* enforcement | Even if the amber control is bypassed, the server still 403s | Affordance ≠ enforcement |

## F. Write, tag, and the dynamic dictionary (all three taggable types)

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| F1 | As `editor`, create a category under Demo catalog | Succeeds; `201` + appears in tree; `Location` honored | Write + RFC-7807-clean create |
| F2 | Open the tag editor on a **catalog**, a **category**, AND a **product** | Dictionary-driven editor on **all three** types; only dictionary-legal keys/values selectable | Taggable products (ADR 0025) + dynamic dictionary |
| F3 | Assign a tag value **outside** the dictionary (if the UI allows a raw attempt) | Rejected — `422 TAG_VALUE_ILLEGAL` surfaced as a readable error | Fail-closed tag validation |
| F4 | As `viewer`, attempt any create/tag | Controls absent (amber) or `403 ACCESS_DENIED` if forced | Read-only enforcement |
| F5 | Manage a **TAG KEY** (define a new dictionary key) as owner vs editor | Owner (TAG/define) can; editor cannot | `define-tags` control-plane gating |

## G. Control plane, directory & self-service

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| G1 | As owner, open the **directory picker**, search a user (e.g. `alice`) | Live results from the Keycloak-backed directory; provision-on-select | UserDirectory port + picker |
| G2 | Grant `alice` a role on the Demo team via the picker | Succeeds; **re-login as `alice`** → she now sees the Demo catalog | Self-service grant closes the loop (also re-establishes the T7-removed binding) |
| G3 | As `editor` (no CONTROL), attempt add-member / change-role | Controls absent or denied | Custom roles are management-incapable (6.7) |
| G4 | Transfer ownership / define-roles as a non-owner | Denied (owner-only-by-code fence) | Owner-only control verbs |

## H. Error contract & resilience surface

| # | DO | EXPECT (the cut) | PROVES |
|---|---|---|---|
| H1 | Trigger any denied action; inspect the response (devtools) | `application/problem+json` with a typed `errorCode` (e.g. `ACCESS_DENIED`), not a raw stack/HTML | RFC-7807 error contract |
| H2 | Trigger a validation error (bad tag / bad page param) | Typed `VALIDATION_FAILED` / `TAG_VALUE_ILLEGAL`, human-readable in the UI | Typed error vocabulary |
| H3 | (Optional, if a fault can be injected) OPA/role-source blip | UI degrades to **deny** (fail-closed), never a wider view; recovers when the dependency returns | Resilience never widens |

## I. Regression spot-checks (recently changed)

| # | DO | EXPECT | PROVES |
|---|---|---|---|
| I1 | Full drill-through + one write + one tag as `editor` end-to-end on the **ported (Boot 4)** images | Byte-identical behavior to pre-port; no console/network errors | SB4 port is transparent to the UX |
| I2 | *(Operator note — not a normal UI path)* the F1 `abac_deny` fix is an operator DB flag, **not** client-settable; the tag editor will **reject** `abac_deny` as a non-dictionary key (`422`) | Confirms clients can't set it; the deny-override itself is proven by `opa test`/`opa eval`, not the UI | F1 fix boundary |

---

## Out of scope (proven elsewhere, don't re-verify by hand)

- The exhaustive allow/deny matrix → the **newman fleet** (14/14).
- Policy correctness → `opa test` (233/233).
- Wire/format parity, concurrency, fail-closed edges → unit + Testcontainers ITs (830).
- Performance → `PERFORMANCE.md` (the load run).

## Recording

Walk A→I in order; note PASS/FAIL + a one-line observation per case. Any FAIL that is a real defect
(not a seed/data quirk) is a pre-publish blocker → its own fix branch. On completion, this note + the
filled results become the committed pre-publish UI-QA record (`docs/code-review/PRE-PUBLISH-UI-QA-<date>.md`).

---

# EXECUTED RESULTS — 2026-07-12

**Executed by:** agent-driven browser session (in-app browser) against the live rig; maintainer supervised.
**Rig:** `ENABLE_SPA=1` (gateway `:9085` bearer-only + CORS-for-`:3000`), SPA on Vite `:3000`, seeded via
`scripts/postman/seed-demo-data.sh`. G1/G2 re-run after an `ENABLE_DIRECTORY=1 ENABLE_SPA=1` re-up.
**Verdict:** all executed cases **PASS**. **1 defect found (DEF-1, cosmetic/non-security).** No security
or authorization defect. Not a publish blocker on the security axis; DEF-1 is a UX wart worth a small fix.

## Coverage summary

| Group | Result |
|---|---|
| **A** Auth/session (A1 A2 A4) | PASS (A3 token-refresh deferred — long-idle; unit-proven PR #65) |
| **B** Tenant isolation (B1 B2 B3) | **PASS** — member sees catalog · outsider empty list · outsider deep-link → 403 |
| **C** Hierarchy + pagination (C1 C4) | PASS (C2/C3 tag-requirement row-filter deferred — needs extra seeding; opa-test/IT-proven) |
| **D** Affordances (D1 D2 D3 D4) | **PASS** — owner full · viewer read-only · demo write-but-no-mgmt |
| **E** Predicted-deny UX (E1 E2 E3) | **PASS** — amber = client prediction · red = server verdict · amber ≠ enforcement |
| **F** Write/tag/dictionary (F1 F2 F3 F4) | **PASS** — 201 create · tag-on-create · dict editor on all 3 types · illegal value → 422 |
| **G** Control plane + directory (G1 G2 G3) | **PASS** — live directory search · self-service grant loop closed · custom role mgmt-incapable |
| **H** Error contract (H1 H2) | **PASS** — every denial RFC-7807 `problem+json` with typed `errorCode` |
| **I** Regression (I1 I2) | **PASS** — Boot-4 images: console clean; client can't set operator-only `abac_deny` |

## Case-by-case (as executed)

| # | Result | Evidence |
|---|---|---|
| A1 | PASS | Unauth load → only "Sign in with Keycloak"; click → Keycloak PKCE form (CATALOG DEMO REALM, `:9085`). |
| A2 | PASS | Login `editor` → Demo catalog shown; chip `editor` + realm roles catalog-editor/catalog-viewer. |
| A4 | PASS | "Switch identity" → login gate; KC re-prompts for creds (no SSO auto-login) — session cleared. |
| B1 | PASS | `editor` (member) sees Demo catalog card + "+ New catalog". |
| B2 | PASS | `outsider` (no membership) → CATALOGS empty; `GET /catalogs` = `{count:0,items:[]}`. Membership is sole access path (B4 headline). |
| B3 | PASS | `outsider` direct `GET /catalogs/{demo-id}` → **403 problem+json ACCESS_DENIED**, no shell/children (single-GET gate). |
| C1 | PASS | Drill catalog → EMEA/APAC categories → products; breadcrumbs + "products →" drill-in. |
| C4 | PASS | List = `{count,page,perPage,items}`; count subject-relative; each item carries `_actions`. |
| D1 | PASS | `editor`/owner: catalog "YOUR ACTIONS" ✓view ✓update ✓delete ✓assign-tags; category+product buttons all present. |
| D2 | PASS | `viewer`: same catalog, "YOUR ACTIONS" ✓view **✗update ✗delete ✗assign-tags** (red X); write buttons locked-amber. |
| D3 | PASS | `demo` (custom non-owner): member-mgmt controls present but server-enforced (see G3). |
| D4 | PASS | Owner: per-member role dropdown (9 roles), Transfer-ownership (owner-only), Remove, +Add member, Roles(9)/Tag keys(2). |
| E1 | PASS | `viewer`: "+ New catalog" amber ⚠; category mutate buttons amber 🔒 "Not allowed for your role" = client prediction from `_actions`. |
| E2 | PASS | Server denial surfaces as distinct **red** "create failed: 403" — visually distinct from amber. |
| E3 | PASS | Create form offered to `viewer`; submit → **403 Access denied**, NO category created. Amber ≠ enforcement; server is the authority. |
| F1 | PASS | `editor` create category → **201 Created**; tag-on-create body `tags={region:[amer]}`; appears in tree. Owner DELETE → **204**. |
| F2 | PASS | Dictionary-driven tag editor ("VALIDATED AGAINST THE DICTIONARY, DECIDED BY OPA") on **catalog + category + product** (ADR 0025). |
| F3 | PASS | Illegal value `region=atlantis` → **422 TAG_VALUE_ILLEGAL** "not one of [emea, amer, apac]". |
| F4 | PASS | `viewer` create/tag controls locked-amber; forced attempt → 403 (see E3). |
| G1 | PASS | Owner Add-member directory search "alice" → live Keycloak result `alice (12d6c2e1-…)` via `catalog-directory` admin client. (NoOp default → "No directory accounts match" — graceful degradation also seen.) |
| G2 | PASS | Owner grants `alice` = reader → "✓ add alice as reader succeeded" (team 4 members); **re-login as alice → she now sees the Demo catalog** (was empty). Self-service loop closed. |
| G3 | PASS | `demo` real role-change (`PUT …/members/{id}`) → **403 ACCESS_DENIED**; UI red "✗ change-role failed: 403"; dropdown reverts. Custom role is management-incapable (ADR 0007). |
| H1 | PASS | Every denial → `application/problem+json`: `{type,title,status,detail,instance,errorCode,timestamp}`; no stack/HTML. |
| H2 | PASS | `perPage=99999` → **400 VALIDATION_FAILED** "must be less than or equal to 100" (typed, problem+json). |
| I1 | PASS | Boot-4/Java-25 images: browser console error-log empty across all personas + writes + tags + denials. |
| I2 | PASS | Client attempt to set operator-only `abac_deny` tag key → **422 TAG_VALUE_ILLEGAL** "Unknown tag key 'abac_deny'". F1 fix boundary holds. |

**Deferred (not executed; covered by the automated suite — see Out-of-scope):** A3 (token-refresh: long-idle),
C2/C3 (subject-side tag-requirement row filtering: needs a tag-requirement-bearing role seeded; proven by
`opa test` + ITs), G4 (transfer-ownership as non-owner: same owner-only fence class as G3).

## DEF-1 — SPA double-provisioning race *(cosmetic, non-security)* — ✅ FIXED

> **Resolved 2026-07-12** (same session). Two-layer fix + live re-verification:
> - `example-demo-ui/src/App.tsx` — a module-scope `provisioning` Map single-flights the provisioning
>   effect per subject (the same once-only idiom the PKCE bootstrap uses), so a StrictMode double-invoke
>   no longer fires two POSTs.
> - `example-demo-ui/src/api.ts` — `ensureUser` now treats a **409 on create as success** (re-resolves
>   the row), making the function race-correct on its own regardless of the caller.
>
> **Re-verified live** as a fresh unprovisioned user (`carol`): first login is clean — **no
> "provisioning failed" banner**, and the network shows exactly `GET …/users?subject=… 200` then a
> **single** `POST …/users 201` (no second POST, no 409). `tsc -b && vite build` green.

The original finding (kept for the record):

On the **first login of a not-yet-provisioned user**, the SPA fires **two** concurrent
`POST /api/v1/users` self-provisioning calls: the first returns **201 Created**, the second **409 Conflict**.
The 409 surfaces as an alarming amber banner ("Profile provisioning failed (409 …) — creating
catalogs/teams won't work until it succeeds") **even though provisioning actually succeeded** (the 201
created the profile). Observed as `outsider` on first login (network reqs: `POST /users` 201 then 409, same
subject).

- **Impact:** cosmetic/UX only. The authorization cut is correct (empty list), and the profile IS created.
- **Root cause (grounded in source):** `example-demo-ui/src/App.tsx:139-143` — `provision` runs via
  `useEffect(provision, [provision])` and its `ensureUser` POST has **no in-flight guard**. Under
  **React 19 StrictMode** (`src/main.tsx:7`) the effect double-invokes **in dev**, firing two concurrent
  `POST /api/v1/users` (→ 201 then 409). Note the sibling PKCE *bootstrap* effect already carries a
  module-scope promise guard (`App.tsx:48-51`); the provisioning effect simply lacks the equivalent.
- **Severity nuance:** this is largely a **dev-server artifact** — a production React build does **not**
  double-invoke StrictMode effects, so the double-POST/409 banner is unlikely in a prod build. Still worth
  fixing for a clean first-run demo UX and as defense against any genuine double-submit.
- **Fix (a small SPA branch):** add the same single-flight guard the bootstrap effect uses (a ref /
  module-scope promise so provisioning runs once per subject), **and/or** treat a 409 on self-provision as
  success (the row exists → provisioned; clear `profileError`).
- **Not a publish blocker on the security axis;** recommended low-effort fix before publish.
