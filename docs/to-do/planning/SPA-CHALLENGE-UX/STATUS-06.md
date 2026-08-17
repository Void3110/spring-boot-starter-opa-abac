---
tags:
  - status/done
  - type/project
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — STATUS-06 — T6: the pane pass, the launch entry, the ratchet, close-out

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-17

## Record

### What landed

- `.claude/launch.json` — the **attach** entry for the packaged SPA (`url: http://localhost:9085`
  + `port: 9085`, no command) beside the Vite `demo-ui` entry. **E20.**
- `App.tsx` — the sign-in card now names `sup-demo` and `pm-demo` (the carried-over T2→T3→T4 nit).
  Taken rather than deferred because without it the whole supervised story is invisible to a
  first-time reader: the locked panel on the production catalog reads as a bug rather than the point.
- `10-QA-TEST-CASES.md` — the **technique ratchet**: E17(b)/(c) and E18 no longer say "DevTools
  Local Overrides" (which the Browser pane cannot drive) but the `window.fetch` patch that it can,
  and E18 now enumerates the eight variants with the **correct** expectation for each.

### The technique that closed three inherited cells

T3 and T4 both parked cells as "needs DevTools **Local Overrides**, which the Browser pane cannot
drive". That constraint was **wrong**, and it cost this slice three cells for two tickets.

The app issues a plain `fetch` (`api.ts`) and reads the challenge off
`res.headers.get('WWW-Authenticate')`. So a `window.fetch` patch installed in the page — wrap the
real fetch, then hand back a `new Response(body, {status, statusText, headers})` with the header or
JSON body edited — drives **exactly** the code path a Local Override would, with none of the
tooling. It is a rig technique, not a hack around the test: the assertion is still "what does the UI
show when the wire says X".

Two gotchas, both measured: the challenge's `max_age` is **quoted** on the wire
(`max_age="300"`, so a `max_age=\d+` regex silently matches nothing), and the list envelope key is
**`items`**, not `content`.

### The one place the spec had to be read, not assumed

E18's case text says the corruptions produce "a plain 401 error box". Read literally that is wrong
for one of its own four: a **backslash-escaped quote** is a legal RFC 7235 `quoted-pair`, and
`parseAuthParams` has a deliberate, commented branch that unescapes it. Asserting it is *refused*
would have been asserting a bug into place. Measured: it parses, the panel renders, and the
description shows `…production content "escaped" tail` with the quotes unescaped. The QA row now
says so.

### Acceptance

| Cell | Result |
|---|---|
| **E20** — the launch entry | ✅ attaches at `:9085` with **no process started**; the Vite `demo-ui` entry still starts. See the harness note below |
| **E10** — the round trip, catalog level | ✅ panel → [Verify] → **password then OTP** → back on the same catalog, categories rendered. Network: `…0002/categories` **401 → 200**, one retry, no second redirect. The step-up request carried `max_age=0`, `acr_values=aal2` and **no `prompt`** — T3's constructor→`login()` move proven on the wire |
| **E10** — the same round trip from a **category** view | ✅ challenged at `…/categories/…/products`, [Verify] → landed back on **that category** with its product (`categoryId` rode the state) |
| **E11** — the open catalog needs no ceremony | ✅ `…0003` catalog + categories + products all **200**, no challenge, while unelevated |
| **E12(a)** — cancel → grid, no loop | ✅ abandoning at Keycloak loads the grid, session intact; **no automatic redirect observed for 37 s** |
| **E12(b)** — passive panel after a failed verification | ⚠️ **structurally unreachable — five routes now ruled out.** See below |
| **E13** — expiry is reactive | ✅ drilled to `max_age=5`; already-rendered categories **stayed on screen** past the boundary (age 60 s > 35 s), and only the **fresh** `listProducts` was refused — 401 with a fresh challenge, panel at the category level |
| **E14** — refresh does not extend | ✅ **closed** — see the `jti` evidence below |
| **E15** — members and viewers see nothing new | ✅ `pm-demo`: no chip, no supervised badge, `production` badge **neutral not amber**, `…0002/categories` **200** with no ceremony. `viewer`: sees only its own member catalog, no new UI, `stepUp.maxAge` absent |
| **E16** — reload and the defensive no-window state | ✅ reload → grid (drill-in lost, accepted), chip `Elevated · 2:46` because the window key survived; deleting `stepUp.maxAge` → `Elevated (aal2)`, **no countdown, no fabricated number**, and `…0002`'s contents still load |
| **E17(a)** — badge-vs-server honesty | ✅ **reproduced** — chip amber `elevation lapsed` and row badge amber, **while the categories rendered anyway** (server answered inside its 30 s skew). The console showed the contents and kept its own prediction honestly amber |
| **E17(b)** — `env` stripped | ✅ **closed** — no amber chip, no `env:production` tag, and the locked panel **still renders**: the server's 401 is the truth |
| **E17(c)** — `_provenance` absent | ✅ **closed** — zero `supervised` badges, no amber, contents still behave as the server decides |
| **E17(d)** — the grid's badges | ✅ `supervised` on both demo catalogs, amber `production · verify to open` on `…0002` only, and only while not elevated |
| **E18** — parser negatives on the wire | ✅ **closed** — 7 corruptions → plain error box; the legal escaped-quote → panel, unescaped; patch removed → genuine panel returns |
| **E19** — nothing regressed | ✅ `viewer`/`editor` walked: `✓/✕` ticks, `🔒` locked buttons, roster + escalation ladder, ROLES/TAG KEYS accordions, tag chips, `⚠ + New catalog` predicted-deny amber for non-`catalog-editor` vs unlocked for `editor`. Session-expired path: `401 — session expired — use "Switch identity" to sign in again` — a bare 401 never becomes a challenge panel |
| **E21** — the pass + the ratchet | ✅ this document |
| `npm run lint` · `npm test` · `npm run build` | see Gates below |

### E14, closed — with better evidence than the cell asked for

The cell wanted "the countdown continues from the same `auth_time`". What the run actually caught:

| | before | after |
|---|---|---|
| `jti` | `onrtac:8904bd70-…` | `onrtrt:e41024e7-…` |
| `auth_time` | 1786983769 | **1786983769 (unchanged)** |
| `iat` | 1786983769 | 1786983863 (+94 s) |
| `acr` | aal2 | aal2 |
| chip | `Elevated · 4:01` | `Elevated · 3:16` (**continued**, did not reset) |

The `jti` **prefix** is the proof the cell was really missing: Keycloak names the grant in it —
`onrtac` = authorization_**c**ode, `onrtrt` = **r**efresh **t**oken. So the refresh grant genuinely
ran (not a cache hit), and it did **not** move `auth_time`. That is K8's "refreshing does not
silently extend", end to end in the UI; the API half was already E9h.

### E12(b) — five routes ruled out, accepted as documented-defensive

STATUS-03 ruled out two routes. This pass ruled out three more, two of them new measurements:

| route | measured result |
|---|---|
| `max_age=0, skew=0` | server **stops challenging** — plain 403, no header (STATUS-03) |
| `max_age=0, skew=2` | restore beat the window → 200 (STATUS-03) |
| **`max_age=0, skew=1`** | **restore beat it too** → categories rendered (this pass) |
| **`required_acr="aal3"` + `loa.aal3=3`** | Keycloak refuses the request outright: **`Invalid parameter: claims`**. It never re-authenticates, so there is no completed verification to be passive about (this pass) |
| wrong-account sign-in | `pm-demo` is a member (200); an unrelated persona fails the catalog read itself (STATUS-03) |

The structural reason stands: a completed re-auth sets `auth_time = now` and the restored load lands
**sub-second** later, so any non-zero skew is satisfied; a zero window makes the server stop
challenging, correctly. **Accepted as documented-defensive.** The assertion that actually protects
users is the passive **negative** — the panel must NOT claim a failed verification on a fresh
challenge — and that was observed **on every one of ~12 fresh challenges in this pass**
(`passive: false` throughout), which is the T3 bug's regression guard.

### Findings, and where each one ratcheted

| # | finding | ratchet |
|---|---|---|
| 1 | **The parser's escaped-quote case is the opposite of what E18's prose implied.** | QA row E18 rewritten to state the per-variant expectation; U1/U2 already encode it correctly, so no code change |
| 2 | **`teams`, `users` and `tag-definitions` are each fetched twice on one catalog open.** `CatalogDetail`'s `tagDefs` and the TeamPanel independently resolve the governing team. Deps are `[catalog.id]` and the burst is once per mount — **T4's 1 Hz chip tick is not the cause** (explicitly checked, since that would have made it ours) | **Recorded follow-up.** Pre-existing surface, not one T1–T5 own — T6's rule says anything larger is a follow-up, not a T6 change |
| 3 | **An `acr` the realm does not define is a hard error at Keycloak**, not a downgrade: an operator who sets `required_acr` to an unknown value gives users a Keycloak error page, not a graceful client message | **Recorded follow-up** (operator note for the step-up docs). Out of the SPA's reach — the SPA never sees the response |
| 4 | Keycloak interposes its **"Do you want to log out?"** interstitial when `sessionStorage` was wiped, because there is no `id_token_hint` | Observation only — standard RP-initiated-logout behaviour, not a defect |
| 5 | `preview_start` resolves `.claude/launch.json` from the **session root**, not the repo | Noted below + Mulch. The repo entry (the E20 deliverable) is correct as written |

### Harness note on E20 (cost real time — recorded so it does not again)

`preview_start` reads `.claude/launch.json` from the **session's primary working directory**. A
session rooted at the umbrella workspace therefore does **not** see this repo's file: the request for
the new entry silently fell back to the only entry it knew and **started the Vite dev server**. The
repo's entry is correct and is what a repo-rooted session uses; verification here needed the same
entry mirrored into the umbrella `.claude/launch.json` (outside this repo, uncommitted). Once
mirrored: attaches at `:9085`, `"Attached the preview to the configured url; no process was started"`.

### Rig state at close-out

Drilled three times (`max_age=5`; `required_acr=aal3` + `loa.aal3=3`; `max_age=0, skew=1`) and
**restored by restarting `opa-abac-opa`**, verified with a **real decision probe**
(`POST /v1/data/catalog/allow`), not `/health`: `max_age 300`, `skew 30`, `required_acr aal2`, and
`loa` back to two entries — the injected `aal3` swept by the restart, which a reverse PUT would have
left behind.

### Gates (close-out, 2026-08-17)

| gate | result |
|---|---|
| `./gradlew build` | ✅ (no Java/Kotlin changed in T6 — `example-demo-ui` is not a Gradle module) |
| `opa test` | ✅ **389/389** (no policy work in this slice) |
| `./.sonar-local/sonar-local.sh` | ✅ **CLEAN — 0 open findings** on changed files |
| `npm run lint` (tsc `--noEmit`) | ✅ |
| `npm test` (vitest) | ✅ **63 tests, 3 files** |
| `npm run build` | ✅ 38 modules, 328 kB / 94.5 kB gzip |
| `run-demo-world-matrix.sh` | ✅ **green, 0 failures** — demo-world **31**, idempotency **6**, supervised-scope **42 + 6**, step-up **25 + 11 + 16 + 8 + 1 + 3 + 5**, coexistence **11** |
| `run-tests.sh` (smoke) | ✅ **22** |
| E10–E21 pane pass | ✅ this document |

Identical to the last full green recorded at T1 — the slice closes on the same numbers it opened on,
with the SPA rebuilt and redeployed (`npm run build` + `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up
--pods 2`, both flags **exported**, so neither the SPA nor the MCP stack was torn down mid-run).

`run-demo-world-matrix.sh --convergence` (E33) remains a **separate** pass after a realm re-import
and re-seed — not run here, as at T1.
