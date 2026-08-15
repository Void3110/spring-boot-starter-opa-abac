---
tags:
  - status/planned
  - type/project
  - area/abac
  - area/keycloak
  - area/catalog-service
---

# SPA-CHALLENGE-UX — QA test cases

> Concrete cases; each becomes a ticket's *Acceptance*. U = unit (JUnit for the service;
> **vitest** for the SPA's pure seams), I = integration (Testcontainers Postgres — never H2; the
> in-process OPA stub), E = e2e — **E30–E33 through the gateway with newman** (asserts the actual cut;
> every `pm.test` throws), **E10+ through the packaged SPA in the Browser pane** (an adversarial
> pass against the committed list; each cell states what is *observed*, not what is intended).
> Amber is a client prediction and is never asserted as authorization truth — the server's status is.

## Unit (U*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| U1 | The challenge parser accepts the emitter's exact form | `Bearer error="insufficient_user_authentication", error_description="…", acr_values="aal2", max_age="300"` → `{error, description, acrValues:"aal2", maxAge:300}`; params in any order; scheme and param names case-insensitive (`BEARER`, `Max_Age`); quoted-string escapes unescaped; a description containing commas and spaces survives | T2 |
| U2 | The parser refuses a challenge the client cannot follow | missing `max_age`, missing `acr_values`, non-numeric or negative `max_age`, a non-`Bearer` scheme, an empty header → `null` (the caller falls back to a plain `ApiError`) — the client mirror of the advice's no-half-formed-challenge rule | T2 |
| U3 | `request()` classifies 401s honestly | a 401 with body `errorCode: STEP_UP_REQUIRED` + a parseable header → `StepUpRequiredError` carrying the challenge; a 401 with **no** body → the existing "session expired" `ApiError`; a 401 with `STEP_UP_REQUIRED` but an unparseable header → plain `ApiError` (401); a 403 with a body → plain `ApiError` unchanged; `errorCode` is captured **without** reordering `detail ?? errorCode` | T2 |
| U4 | The token-claims decode is tolerant | a token with `acr:"aal2"` + numeric `auth_time` → both; `acr` absent / `auth_time` absent / non-numeric `auth_time` / an undecodable token → the corresponding fields `undefined`, never a throw; LoA lookup uses the app's map (`aal1→1`, `aal2→2`, unknown → 0) | T4 |
| U5 | The window math + chip state | (`loa≥2`, window known, now < `auth_time+max_age`) → `elevated` with the remaining seconds; at/after → `lapsed`; (`loa≥2`, no window) → `elevated-unknown-window`; (`loa<2`, a challenge seen) → `not-elevated`; (`loa<2`, none seen) → `hidden`; a **negative** remaining never renders (clamped) | T4 |
| U6 | The badge predicate | `(provenance, env, chip)` → amber iff `supervised ∧ production ∧ chip ≠ elevated`; member+production → not amber; supervised+production+elevated → not amber; supervised+staging → not amber; `_provenance` absent → no supervised badge and never amber (absence is not "member", it is "unknown") | T4 |
| U7 | The provenance advice stamps from the memo and omits on absence | (JUnit, the advice in isolation) a `CatalogPage` body + a request memo `{ids}` → items in the set `"supervised"`, others `"member"`; a memo that is **present but empty** → every item `"member"`; **no memo attribute** → every item's `_provenance` **absent** (the getter returns `null` and serializes to nothing — `@JsonInclude(NON_NULL)` verified on serialized bytes); a body that is not a catalog shape → untouched | T1 |
| U8 | The GET derivation maps the stamp and never throws | a single `Catalog` body **on the GET handler**: the supplier's role stamped `supervised` → `"supervised"`; `membership` → `"member"`; no stamp / an unknown value / `RoleResolutionException` / an empty `Optional` → **absent**; the exception is swallowed and the body is otherwise byte-identical; the same single-`Catalog` body returned by **create** or **update** → untouched (no lookup call — asserted on the supplier mock) | T1 |

## Integration (I*)

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| I1 | The list labels both legs (Testcontainers, `SupervisedListIT`'s personas) | the mixed persona's page: their membership rows `_provenance:"member"`, their supervised rows `"supervised"`, on exact ids; the memberless supervisor's page: all `"supervised"`; a plain member's page: all `"member"`; the field is **absent from no row** on the happy path | T1 |
| I2 | The degrade branches label honestly | an agent-marked call (`act_chain` present) → membership rows only, each `"member"`; the membership-role outage → the supervised-only page, each `"supervised"`; the supervised source down → membership rows only, each `"member"`; a **plain member's page** (the supervised set is empty) → the memo is present-but-empty and every row is `"member"` (not absent); a **missing memo attribute** (the authorizer's query path bypassed — a controller test that serves a `CatalogPage` without it) → the field absent on every row and the page otherwise unchanged (`_actions` still present where enriched) | T1 |
| I3 | List and GET agree | for a supervised catalog: the list row says `"supervised"` **and** `GET /catalogs/{id}` says `"supervised"`; for a membership catalog: both `"member"`; a GET whose resolved role carries **no `provenance` stamp** (the only omit state reachable on the shipped memo-on path — a lookup that throws at the gate is a 403, and the advice's identical-key call replays the gate's outcome) → **absent**, still 200 with the metadata | T1 |
| I4 | The spec + codegen carry the field | the generated `Catalog` DTO implements the new marker; `_provenance` serializes under that exact key; a `Catalog` with `provenance == null` serializes **without** the key (bytes) — the same assertion style as `MultiRootEnrichmentIT`'s `has("_actions") == false` | T1 |

## E2E — newman, through the gateway (E30–E33)

> Numbered from **E30** so they never collide with `run-step-up-matrix.sh`'s own collection ids
> (`E1`…`E9x`); inside the runner they land as its next free block (`E10`, the `_provenance` cells).
> Rig: `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2` — the step-up matrix's preflight needs
> the MCP server, and `deploy.sh` tears down whichever of the SPA/MCP stacks its flag is missing.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E30 | The `_provenance` cells join the step-up matrix | anna's catalogs page: the seeded production and open catalogs each `_provenance:"supervised"` on their exact ids; `editor`'s (the report's) page: the same ids `"member"`; a GET by anna on the production catalog → `"supervised"` (200 — the root read is metadata-only, never a challenge) | T1 |
| E31 | The demo world exists after the seed | `seed-demo-data.sh` on a fresh (re-imported) rig: `sup-demo`'s catalogs page (ROPC needs her TOTP — Keycloak's direct-grant flow demands one: `mint_token … otp=$(mint-code-flow-token.py --print-otp --otp-secret <demo>)`, the runner's anna idiom) lists exactly `d311…0002` and `d311…0003`, both `"supervised"`; `d311…0002` carries `env:"production"`; a category + a product exist under each; re-running the seed changes nothing (idempotent — counts and ids identical) | T5 |
| E32 | The seed and the matrices coexist | run `run-supervised-scope-matrix.sh` **then** `run-step-up-matrix.sh` **then** re-list as `sup-demo`: the two demo catalogs are still there and still `"supervised"` — no matrix cleared `sup-demo`'s edge or unbound `pm-demo`; conversely anna's page is unaffected by the seed (the exact-id assertions in both matrices still pass) | T5 |
| E33 | The seed converges across a re-import | `deploy.sh down` + `up` (the realm re-imports) then re-run the seed: `sup-demo`'s page is exactly the two demo catalogs again, both `"supervised"`; no duplicate teams/catalogs; the seed resolved `sup-demo`'s `sub` from the admin API without minting her. **Recorded, not asserted**: whether the two `sub`s were stable (the optional `id` pin honoured) or new (the seed re-bootstrapped by subject and REPLACEd the edge) — either way the world converged | T5 |

## E2E — the packaged SPA in the Browser pane (E10+)

> Precondition for every cell: `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2` (post-realm
> re-import; **both flags on the same `up`** — an `up` missing either flag tears that stack down) +
> `seed-demo-data.sh`; the pane at `http://localhost:9085`; TOTP codes computed from the demo secret
> with the **existing** `mint-code-flow-token.py --print-otp --otp-secret <demo-secret>`. Two rig
> recipes cells lean on: the **window drill** = `PUT http://localhost:28181/v1/data/step_up/max_age`
> with body `5` (the **leaf** path — a PUT to `/v1/data/step_up` clobbers `loa`/`skew` and makes the
> drill vacuous), **restored by restarting the `opa-abac-opa` container** (not a reverse PUT — a rig
> left in drill state advertises `max_age=5` to every later cell); and **response-header rewriting**
> = Chrome DevTools **Local Overrides** (not a two-click operation — named so nobody improvises). Cells are run **adversarially**: the
> operator tries to make the UI lie (predict wrong, hide a 401, extend a window, leak a challenge to
> a member) and records what the UI actually shows against what the server actually answered.
> E10–E12 + E18 are T3's own surface (they pass before the chip/badges exist); E13–E17 + E19 need
> T4; **E20–E21 are the pass itself** (T6), re-running everything fresh-eyes.

| ID | Case | Asserts | → Ticket |
|---|---|---|---|
| E10 | The challenge round trip | sign in as `sup-demo` (password only); open `…0002` → the contents area shows the **locked panel** (not the error box) with the server's `error_description` verbatim, `acr_values=aal2`, `max_age=300` as plain facts, and [Verify]; the header/breadcrumbs/metadata card stay usable; click [Verify] → Keycloak (**record the exact prompt sequence** under `max_age=0` on the existing SSO session — password then OTP, or OTP only; settled from the miner's stderr before T3 and confirmed here) → land back **on the same catalog** (the state carried `catalogId`) with its categories rendered — one automatic load, no second redirect; from a **category** view the same round trip lands back on that category (the state carried `categoryId`) | T3 |
| E11 | The open catalog needs no ceremony | as `sup-demo` at `aal1`, open `…0003` → categories render, no panel, no `WWW-Authenticate` in the network log; drilling into its category renders products likewise | T3 |
| E12 | Cancel → passive panel, never a loop | on `…0002`'s panel click [Verify], then **abandon** at Keycloak (navigate back to `/`): the app loads at the grid (the location was in the unused state — recorded, accepted); open `…0002` again → the panel; now complete [Verify] in a way that leaves the read still refused (a stale window via the drill override, or a wrong-account sign-in) so the restored load 401s again → the panel renders **passive**: the *"verification did not unlock production contents"* notice + [Verify]; **no automatic redirect happens** (observed for ≥ 30 s); a manual click re-runs the flow | T3 |
| E13 | Expiry is reactive | after E10, shrink the window with the drill (`max_age` → `5`, leaf PUT), re-elevate so the chip counts down from 5 s: the chip flips **amber "elevation lapsed"** at zero; the rendered categories **stay on screen**; **wait past `max_age + skew` = 35 s (the enforced boundary — the chip's zero is `max_age` only; the in-between window is E17(a)'s)**, then navigate into a category (a fresh `listProducts`) → the locked panel; the network log shows the 401 with a fresh challenge; nothing was hidden client-side before the server said so; **restore** the drill (restart `opa-abac-opa`) | T4 |
| E14 | Refresh does not extend | after E10, force an access-token refresh — the realm's `accessTokenLifespan` is 1800 s and `freshUser()` refreshes only when `expired`, so: in devtools set `expires_at` to a past epoch inside the `oidc.user:…` sessionStorage entry, then trigger any fetch (the refresh grant runs) → the chip's countdown **continues from the same `auth_time`** (does not reset); the token's `auth_time` in the decoded payload is unchanged (K8's "refreshing does not silently extend"; the API half is already proven by the runner's E9h) | T4 |
| E15 | Members and viewers see nothing new | sign in as `pm-demo` (a member of both demo teams) → **no** supervised badge, **no** amber on `…0002` even though it is production, **no** elevation chip; open `…0002` → categories render (a member's production read needs no elevation — K13); sign in as `viewer` → likewise nothing new; the network log shows no `WWW-Authenticate` for either | T4 |
| E16 | Reload and the defensive no-window state | mid-drill-in on `…0002` (elevated), reload the tab → the session is restored from sessionStorage and the app lands on the **grid** (accepted: no deep links, the drill-in is React state, `User.toStorageString()` omits `state`), the chip **Elevated · m:ss** because the window key survived; then **delete `stepUp.maxAge` from sessionStorage in devtools** → the chip degrades to **Elevated (aal2)** with no countdown — never a fabricated number — and `…0002`'s contents still load (the server's window is what matters). A genuinely **new tab** has no session at all (per-tab store): it signs in fresh at `aal1`, chip hidden — recorded, not a defect | T4 |
| E17 | Badge-vs-server honesty (the adversarial cell) | make the prediction wrong on purpose and check the truth wins: (a) as `sup-demo` elevated, let the window lapse so the chip is amber, then open `…0002` **inside the policy's skew** — the server may still answer 200: the UI shows the contents and the chip stays amber (prediction, honestly labeled); (b) strip `env` client-side (devtools) so no amber shows, open `…0002` at `aal1` → the panel still renders (the server's 401 is the truth); (c) a catalog whose `_provenance` is absent (intercept the response and delete the key) shows **no** supervised badge and **no** amber, and its contents behave as the server decides; (d) the grid as `sup-demo` shows the **supervised** badge on both demo catalogs and the amber **production · verify to open** chip on `…0002` only while not elevated | T4 |
| E18 | The parser negatives on the wire | with DevTools **Local Overrides**, rewrite the response header on the 401 (drop `max_age`; change the scheme; corrupt the quoting; inject a backslash-escaped quote): the UI shows a **plain** 401 error box, **not** a broken [Verify] panel; remove the override → the panel returns. Confirms U1's defensive cases + U2 end to end | T3 |
| E19 | Nothing regressed for the existing console flows | as `editor`/`demo`/`viewer` walk the pre-existing surfaces (create/tag/team/role panels, action buttons, the "session expired" path by clearing sessionStorage): every existing amber/red idiom renders as before; the only new UI is on supervised rows and on a challenge; `npm run lint` and `npm test` are green | T4 |
| E20 | The launch entry | the `.claude/launch.json` **attach** entry (`url` + `port: 9085`, no command) opens the packaged SPA at `http://localhost:9085` with no dev server running; the Vite `demo-ui` entry still works with the rig up | T6 |
| E21 | The adversarial pass + the ratchet | E10–E19 re-run **fresh-eyes** against the finished branch, adversarially (see the section note); every finding is recorded in STATUS-06 with its ratchet target — API-level → a newman cell (named, added to the matrix in the same branch), UI-only → a new numbered row in this table; the pass ends with a re-run of the ratcheted cells and a human final look | T6 |
