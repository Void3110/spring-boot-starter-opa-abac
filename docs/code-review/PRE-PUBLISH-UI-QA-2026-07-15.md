---
tags:
  - status/active
  - type/qa
  - area/demo-ui
---

# Pre-Publish UI QA (1.1.0 delta) — the demo SPA

> **Purpose.** The human-facing smoke test before **1.1.0**. Unlike the full 1.0 pass
> ([[PRE-PUBLISH-UI-QA-2026-07-12]], all A–I PASS), this is a **delta** run: the only library changes
> since v1.0.0 are this session's Sonar-triage fixes — the `HttpOpaClient.isSafePath` fail-closed
> hardening (a linear-scan replacement for the recursive `SAFE_PATH` regex + a 512-char cap) and
> mechanical cleanups (incl. `ResourceResolutionSupport` class→record, catch-var/method-ref edits).
> So the goal is narrow: **confirm the observable authorization behavior did not regress** on the paths
> those changes touch — the OPA client (allow/compile/allowAll), the gate's resource resolution, and
> every fail-closed edge.
>
> **Method.** Same `DO → EXPECT (the cut) → PROVES` as the 1.0 pass. Maintainer performs each Keycloak
> login (never enter credentials in the browser); the agent drives the *authenticated* SPA and verifies
> the cut + inspects network responses.

## Why this subset (delta rationale)

| Changed code | What it could break | Cases that would catch it |
|---|---|---|
| `HttpOpaClient.isSafePath` (path validation → OPA `/v1/data` + `/v1/compile`) | a *widened* path accepted (wrong doc addressed) or a *legit* path rejected (spurious deny) | **A2** (any allow round-trips), **B1/B3** (allow + deny both real), **D1/D2** (`_actions` per identity) |
| `ResourceResolutionSupport` class→record (gate resource resolution wiring) | the gate stops resolving instance attributes → wrong decision | **D1/D2/D4** (affordances reflect the resolved instance), **B3** (single-GET gate) |
| `CompileResponseParser.mapOperator` (negated membership → `yield null`) | residual widens on a negated `in` | **C4** (list is subject-relative), **B2** (outsider empty list) |
| catch-var/method-ref mechanical edits across the security path | a swallowed/rethrown exception changes a fail-closed edge | **E3/H1/H3** (deny stays deny; RFC-7807 contract) |

Cases **not** re-run (unchanged since 1.0, proven 2026-07-12 + automated suite): the full F write/tag
matrix, G self-service loop, C2/C3 tag-row-filtering. Re-verify only if a delta case surfaces something.

## Preconditions

- Rig up **SPA mode**, current library code: `ENABLE_SPA=1 ./deploy.sh build && ENABLE_SPA=1 ./deploy.sh up --pods 2`,
  then `scripts/postman/seed-demo-data.sh`. Packaged demo served through APISIX at **http://localhost:9085**.
- Personas (password == username, realm `catalog-demo`): **`editor`** = team owner (full control plane),
  **`demo`** = L20 editor (read/write/tag, no grant/mgmt), **`viewer`** = L10 read-only, **`outsider`** =
  no membership (sees nothing). Seeded fixtures: **Demo catalog** → EMEA / APAC categories → 2 products.

---

## Delta cases

| # | DO | EXPECT (the cut) | PROVES | Result |
|---|----|------------------|--------|--------|
| A1 | Load `:9085` unauthenticated | Redirect to Keycloak PKCE; no catalog pre-login | Gateway bearer-only | |
| A2 | Log in as `editor` | Demo catalog renders; identity chip `editor` | OIDC round-trip + **an OPA allow actually succeeds** (isSafePath accepts a legit path) | |
| B1 | As `editor`, view catalog list | Demo catalog **present** | Member allow — the compile/list path returns rows | |
| B2 | As `outsider`, view catalog list | List **empty** | Membership sole path; residual/compile doesn't widen | |
| B3 | As `outsider`, deep-link the Demo catalog id | **403 problem+json**, no shell/children | Single-GET gate fails closed (resource resolution + OPA deny) | |
| D1 | As `editor`, open a product | Edit/Delete/Assign-tags **present** | `_actions` = resolved WRITE+TAG (record conversion intact) | |
| D2 | As `viewer`, open the **same** product | Those buttons **absent** | Affordance mirrors the real grant — the OPA cut per identity | |
| E3 | As `viewer`, force a write the client ambers | Server **403**, nothing created | Amber ≠ enforcement; deny stays deny after the catch-var edits | |
| H1 | Inspect any denial (devtools) | `application/problem+json` + typed `errorCode` | RFC-7807 contract unchanged | |
| C4 | As `editor`, a paginated list | `{count,page,perPage,items}`, count subject-relative, `_actions` per item | Pagination + residual compose; parser change didn't widen | |
| I1 | Console/network across all personas | No errors; no spurious 401/deny on legit paths | isSafePath accepts every real path (no over-rejection) | |

---

# EXECUTED RESULTS — 2026-07-15

**Executed by:** agent-driven browser session (in-app browser) against the live rig; maintainer
authorized use of the throwaway demo test accounts for login.
**Rig:** `ENABLE_SPA=1 ./deploy.sh up --pods 2` (2 catalog + 2 usermgmt pods, Keycloak realm
`catalog-demo`, APISIX `:9085`, OPA), **both service images force-rebuilt** with the current library
code (catalog + usermgmt — usermgmt is lazy-built, so it was rebuilt explicitly). SPA on Vite `:3000`
proxying `/api`,`/realms`,`/resources` → `:9085`. Seeded via `scripts/postman/seed-demo-data.sh`.
**Verdict:** **all delta cases PASS. Zero defects.** No security, authorization, or regression defect.
The 1.1.0 library changes are transparent to the observable authorization behavior.

## Coverage summary

| Group | Result |
|---|---|
| **A** Auth/session (A1 A2 A4) | **PASS** — unauth shows no data · `editor` login round-trips · switch-identity clears session |
| **B** Tenant isolation (B1 B2 B3) | **PASS** — member sees catalog · outsider empty list (`count:0`) · outsider deep-link single-GET → **403** |
| **C** Hierarchy + pagination (C1 C4) | **PASS** — catalog→EMEA/APAC→products drill-in · `{count,page,perPage,items}` envelope, count subject-relative |
| **D** Affordances (D1 D2 D4) | **PASS** — editor all-✓ · viewer read-only on the **same** resource (wire-confirmed) · owner control plane present |
| **E** Predicted-deny UX (E1 E3) | **PASS** — amber "+ New catalog"/mutate = client prediction · forced viewer create → server **403** (amber ≠ enforcement) |
| **H** Error contract (H1) | **PASS** — every denial `application/problem+json` with typed `errorCode:ACCESS_DENIED` + UTC `timestamp` |
| **I** Regression (I1) | **PASS** — console error-free across all personas + writes + denials on Boot-4/Java-25 images |

## Case-by-case (as executed)

| # | Result | Evidence |
|---|--------|----------|
| A1 | PASS | Unauth `:3000` → only "Sign in with Keycloak"; no catalog data. Console clean. |
| A2 | PASS | Login `editor` → Demo catalog renders; chip `editor` + realm roles catalog-editor/catalog-viewer. **An OPA allow round-tripped** (`GET /catalogs` 200) → `isSafePath` accepts a legit path. |
| A4 | PASS | "Switch identity" → session cleared → Keycloak re-prompts (no SSO auto-login). |
| B1 | PASS | `editor` (member) sees Demo catalog + "+ New catalog". |
| B2 | PASS | `outsider` → CATALOGS empty; `GET /catalogs` = `{count:0,items:[]}`. Membership sole path; no widening. |
| B3 | PASS | `outsider` direct single-GET `/catalogs/{demo-id}` (bearer from sessionStorage) → **403 problem+json ACCESS_DENIED**. The single-GET gate (OPA `allow()` via `isSafePath`→`/v1/data`) **fails closed**. |
| C1 | PASS | `editor` drills catalog → EMEA/APAC categories → "products →"; tree + breadcrumbs render. |
| C4 | PASS | List = `{count:1,page:0,perPage:100,items:[…]}`; count subject-relative; each item carries `_actions`. |
| D1 | PASS | `editor` catalog `_actions`={view,update,delete,assign-tags all **true**} (wire-confirmed); categories all four buttons. |
| D2 | PASS | `viewer`, **same** catalog: `_actions`={view:true, update/delete/assign-tags **false**} (wire-confirmed); categories 🔒 on all mutations. **The OPA cut is real per-identity** — the record-conversion resource resolution is intact. |
| D4 | PASS | `editor`/owner: per-member role dropdowns (9 roles), Transfer-ownership (owner-only), Remove, +Add member, ROLES(9), TAG KEYS(2). `viewer`: all collapsed / "Not allowed for your role". |
| E1 | PASS | `viewer` "+ New catalog" amber ⚠ "realm roles lack catalog-editor — creating will answer 403; left usable on purpose"; mutate buttons 🔒 "Not allowed for your role" = client prediction. |
| E3 | PASS | `viewer` forced category create → `POST /categories` → **403 Forbidden**, nothing created. Amber ≠ enforcement; server is authority. |
| H1 | PASS | 403 body = `{type,title,status,detail,instance,errorCode:"ACCESS_DENIED",timestamp}` at `application/problem+json`. **`timestamp` is a valid UTC `…Z` instant** — directly confirms the S8688 `OffsetDateTime.now(ZoneOffset.UTC)` edit in `ProblemDetailFactory`. |
| I1 | PASS | Browser console error-log empty across editor + viewer + outsider, incl. the writes and the two 403s. |

**Deferred (unchanged since 1.0, proven 2026-07-12 + automated suite):** A3 (token refresh — long-idle),
C2/C3 (tag-requirement row filtering — needs extra seeding; `opa test`+ITs), full F write/tag matrix, the
G self-service loop, G4 (transfer as non-owner — same owner-only fence as the D4 gates).

## Delta conclusion

Every case that exercises this session's library changes confirms correct behavior:
- **`HttpOpaClient.isSafePath`** (the fail-closed fix) — allow paths round-trip (A2/B1/D1) **and** deny
  paths deny (B2/B3/E3). No over-rejection, no widening.
- **`ResourceResolutionSupport` class→record** — the gate resolves per-instance attributes correctly;
  `_actions` differs editor-vs-viewer on the identical resource (D1/D2, wire-confirmed).
- **`ProblemDetailFactory` S8688 (UTC timestamp)** — the live 403 error contract carries a correct `…Z`
  instant (H1).
- **Mechanical catch-var / method-ref edits** — fail-closed edges hold; every denial is a clean typed
  403 (E3/H1/B3), console error-free (I1).

**No publish blocker.** The 1.1.0 delta is transparent to the observable authorization behavior.
