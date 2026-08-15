---
tags:
  - status/planned
  - type/architecture
  - area/abac
  - area/keycloak
  - area/catalog-service
---

# SPA-CHALLENGE-UX — 00-DESIGN

> The **collaborative follow-up** to slice C ([[STEP-UP-ELEVATION]]): the demo SPA learns to
> *consume* the RFC 9470 challenge C ships — catch the `401`, explain it in the server's own words,
> drive the Keycloak re-authentication, land back where the user was, and show the bounded window
> honestly — plus the visibility the supervisor story has lacked in the console since slice A
> (which rows are supervised, which are production). Settled 2026-08-15 (grill-me: ten forks, all
> recorded in §Considered-and-rejected). **Not an autonomous slice**: a mini package + a
> collaborative build (§10) — the roadmap's standing decision.

## The feature in one paragraph

Today the whole step-up round trip is proven only by the scripted token miner: `sup-anna` at
`aal1` reads a production child through the gateway, gets `401` + `WWW-Authenticate: Bearer
error="insufficient_user_authentication", acr_values="aal2", max_age="300"`, mints an `aal2` token
with one TOTP, and reads it for five minutes. The console cannot do any of that — a supervisor
opening a production catalog in the SPA sees a bare `401 — Re-authentication with…` error box, and
nothing in the console tells them which catalogs are theirs by supervision rather than membership,
or which are production. This slice closes both gaps: the catalog service adds an additive
`_provenance` affordance to catalog rows (server truth, the `_actions` idiom); the SPA parses the
challenge into an **inline locked panel** showing the server's parameters and a **[Verify]** button;
[Verify] runs the **full-page redirect** re-auth with exactly the miner's measured parameters, the
drill-in location riding the OIDC `state`; the callback restores the location and retries **once**;
a header **elevation chip** shows `acr` + a countdown whose window is *learned from the challenge*,
never hardcoded; and **amber row badges** predict "needs verification" from `_provenance` +
`tags.env` + the chip — amber because the client predicts, the server decides. The demo world
gains its own supervisor persona with a seeded TOTP so a fresh rig tells the A/B/C story end to end
in the browser, and the whole thing is validated by a committed UI case list run as an adversarial
Browser-pane pass.

## What this builds on, and what it must not break

- **The challenge contract is fixed** (C, ADR 0030 §5–7 as amended): the advice emits
  `401` + `WWW-Authenticate` with `error`, `error_description`, `acr_values`, `max_age` — the params
  from the policy's `deny_reason`, never local config
  (`AbstractProblemAdvice.challengeFor`); the problem body carries `errorCode: STEP_UP_REQUIRED`
  and a `detail` that is **the same string** as `error_description` by construction. Only four
  operations declare the `401`: `listCategories`, `getCategory`, `listProducts`, `getProduct`
  (`catalog-api.yaml`). `listCatalogs` never challenges. This slice changes **none** of it.
- **The policy owns the window**: `max_age + skew` is enforced server-side; the reason advertises
  `max_age` only. The client never re-derives elevation for a *decision* — it only predicts (amber).
- **The SPA's auth is redirect-only, single-origin, PKCE** (`auth.ts`): `authority` =
  `${origin}/realms/catalog-demo`, `redirect_uri` = `${origin}/`, tokens in `sessionStorage`,
  no silent-renew iframe, on-demand refresh (`freshUser`). `prompt: 'login'` is set **globally**
  on the `UserManager`. The packaged SPA is served through APISIX with **no deep links** (only `/`,
  `/index.html`, `/assets/*` reach nginx — `infra/spa/default.conf`) and the callback already
  `replaceState`s to `/`. Any location carrier other than the OIDC `state` breaks the packaged rig.
- **The `catalog-spa` client already mints the claims**: `basic` + `acr` are in its
  `defaultClientScopes` (C's T1), so an SPA access token carries `acr` and numeric `auth_time`; the
  realm's `acr.loa.map` is `{"aal1":1,"aal2":2}`. Nothing realm-side changes for the *challenge*
  — only the new demo personas (§8).
- **The `_actions` idiom is the affordance precedent** (ADR 0016): a marker interface stamped by
  `x-implements` in the OpenAPI spec, a `ResponseBodyAdvice` mutating DTOs in place after the page
  envelope exists (reaching items structurally through `getItems()`), **absent-when-not-computed**
  bought by `@JsonInclude` on the *interface* getter — never a fabricated default. `_provenance`
  copies the mechanism, example-side (§2).
- **The two-leg list** (`CatalogListAuthorizer`, ADR 0029): membership `M` and supervised
  `S \ M`; the per-row leg identity exists as a local `List<UUID>` (copied to a set only inside the audit
  method, which is guarded by two early returns) and is discarded at the return. The GET path has **no leg concept** — provenance there lives
  in the resolved role's `attributes.provenance` (`"membership"` | `"supervised"`, the ADR 0031
  stamp), which the manager computes and only the privileged-read audit reads.
- **The fixture-id registry and the reserved persona families** (`scripts/postman/README.md`):
  `sup-anna` is *matrix-owned* — the supervised-scope and step-up runners each **delete every
  reporting edge they manage before seeding**, the reporting-edges bootstrap is **REPLACE**, and
  she must stay a member of no team. She is also the only identity with a seeded TOTP. A demo seed
  cannot borrow her (§8).
- **The house honesty idiom**: amber = a client prediction, red = a server-computed denial
  (`teams.tsx` states it verbatim); denied actions are locked-but-visible; every `pm.test` and
  every UI cell asserts the actual cut.

## The slice boundary

**In**: the `_provenance` affordance on catalog list items + the single-catalog GET (§2, ADR 0033);
the challenge parser + `StepUpRequiredError` seam (§3); the inline locked panel (§4); the [Verify]
redirect with state-carried location, restoration, one retry (§5); the elevation chip with the
learned window (§6); the row badges (§7); the demo supervisor persona + seed block + registry
row (§8); the committed UI QA cases run through the Browser-pane loop, and the packaged-gateway
launch entry (§9); the living-doc sync (§11).

**Out, deliberately**:
- **Any library change.** `Enrichable` gains no supervised noun; the manager exposes no role; the
  advice contract is untouched. If a library seam is wanted later (a "resolved role" request
  attribute), it is its own additive slice.
- **`_provenance` on categories/products** — children cannot differ from their catalog; the SPA
  propagates the catalog's provenance on drill-in.
- **A popup re-auth**, silent renew, or a BFF — the demo stays redirect-only and sessionStorage-based
  (its README already says "do not copy verbatim to production").
- **Client-side self-censorship at expiry** — the client never hides data it legitimately read.
- **Scripted browser automation** (Playwright etc.) — the pane loop + a committed case list is the
  bar; a unit runner for the pure seams is in (§9).
- **The Maven Central cut** — 1.2.0 waits for this slice (fork 10), then ships the whole story.

## The design

### 1. One semantic, two derivations: what `_provenance` means

`_provenance` on a catalog answers *"by which access path is this row in front of you?"* —
`"member"` (a membership grant, possibly alongside supervision — membership always wins) or
`"supervised"` (the disjoint supervised path, `S \ M`). It is an **affordance, not enforcement**
(the ADR 0016 stance): a client uses it to *explain* and *predict*, never to decide.

It is computed by **different means on the two paths, and the design pins that they agree**:

- **List** — from the leg: a row whose id is in the supervised set is `"supervised"`; every other
  row is `"member"`. The set is the same `S \ M` the authorizer built the query from.
- **GET** — from the stamp: the role the gate resolved on the catalog (`(subject, "catalog", id)`)
  carries `attributes.provenance`; `"supervised"` ⇒ `"supervised"`, `"membership"` ⇒ `"member"`.

They agree by construction: the user-service synthesizes the supervised role **only** on the
non-membership branch (ADR 0029), so a catalog is in `S \ M` iff its resolved role is stamped
`supervised`. **I3** pins the agreement on both personas.

**Present-but-empty ≠ absent.** The list's memo is the supervised id set **including the empty
set** — an empty set means *every row on this page is membership* and each row is labeled
`"member"`; only a **missing** memo attribute (a response that never passed through the two-leg
authorizer's query path) makes the advice omit. The advice tests attribute *presence*, never
emptiness. The authorizer's five `Page.empty(pageable)` early returns (no scope, no subject…) never
reach the write-site — acceptable, they carry zero rows.

**Absent-when-not-computed, on every degrade branch** — the value is omitted (never `null`, never
a default) when: the request carries the agent claim (the supervised leg is skipped — rows are all
membership, and *are* labeled `"member"`); the membership leg failed and the list degraded to
supervised-only (rows are labeled `"supervised"` — the set is exact); the supervised source is
down (an empty supervised set — the surviving rows are `"member"`); on GET, the role re-lookup
throws or returns no stamp (**omit**). The one branch that *cannot* be labeled honestly is a GET
whose stamp is neither vocabulary value — omit. Recorded limitation U42 (mixed subject with
partial-eval off) does not affect the list label: the label reads the id set, not the judging role.

### 2. The wire + the seam (example-side; ADR 0033)

- **Spec**: `Catalog` gains `_provenance` — `type: string`, `readOnly: true`, the vocabulary
  `member` | `supervised` stated **in the `description`**, next to `_actions`. **No `enum:` key at
  all**: under this generator (`spring`, 7.14) an inline `enum:` — even one meant as documentation —
  generates a per-DTO nested enum type (`ProblemDetail.ErrorCodeEnum` is the live precedent) whose
  getter cannot satisfy the marker interface's `String getProvenance()`, i.e. a compile failure.
  There is no `configOptions` knob to suppress it. The wire vocabulary is enforced by the emitter,
  the spec documents it in prose. `CatalogPage.items` `$ref`s `Catalog`, so one edit covers
  list + GET.
- **Marker**: a second example-side interface, `CatalogProvenanceCarrier` (name indicative),
  appended to the `Catalog` schema's `x-implements` list, declaring
  `@JsonInclude(NON_NULL) @JsonProperty("_provenance") String getProvenance()` / a setter — the
  `Enrichable` absence idiom for a scalar. `Enrichable` itself is untouched (a library type; a
  domain noun does not belong on it).
- **Leg memo**: `CatalogListAuthorizer` writes the supervised id set (`supervisedIds`, **including
  the empty list**) to a **request attribute** (the repo's `RequestContextHolder` + `SCOPE_REQUEST`
  idiom — `RequestAttributesResourceCache`, `MemoizingRoleDefinitionSupplier.requestMemo()`; no
  `@RequestScope` bean) **unconditionally in `authorizedPage`, immediately before the query**
  (`abacQuery.findAuthorized(...)`) — **not** inside `auditSupervisedRead`, whose two early returns
  (`supervisedIds.isEmpty()`, `onPage.isEmpty()`) would leave a plain member's page, a
  supervised-source outage, an agent-marked call and a mixed page with no supervised row on it all
  memo-less. Nothing else about the authorizer changes; its return type stays `Page<CatalogEntity>`.
- **Advice**: an example-side `CatalogProvenanceAdvice` (`ResponseBodyAdvice`) that (a) for a paged
  body, reads the memo and stamps each `Catalog` item (present-but-empty ⇒ all `"member"`; absent ⇒
  omit); (b) for a single `Catalog` body **on the GET handler only** (`getCatalog` — checked by the
  handler method / HTTP verb; `createCatalog` also returns a bare `Catalog` but its gate is
  type-level with a `null` resource id, so a lookup by the new id would be a guaranteed memo **miss**
  and a real user-service round-trip on every create; `updateCatalog` is left alone as well — the
  affordance is a read-side label), calls the role supplier's `lookup(subject, "catalog", id)` — a
  **request-memo hit** under the default-on `opa.abac.resolve-memo.enabled` (the memoizing decorator
  wraps the bean; the manager's key for a catalog GET is `(subject, "catalog", catalogId)` because a
  root's ancestor list is empty) — and maps the stamp; (c) never throws (any failure ⇒ omit); (d) is
  independent of `ActionEnrichmentAdvice` (each stamps its own property); (e) reads the subject the
  way the two existing advices/authorizers do (`SecurityContextHolder` → `AbacAuthentication` — a
  private static helper each carries; there is no injectable one, and this slice adds none).
- **The memo-off cost is documented**, not engineered around: with `resolve-memo.enabled=false`
  the GET derivation is one extra role round-trip; the example ships memo-on.

### 3. The SPA seam: `StepUpRequiredError`

`api.ts#request()` reads no response headers today, and its `detail = body.detail ?? body.errorCode`
collapses the code into a string — `STEP_UP_REQUIRED` never reaches a caller. The seam is one site:
inside the `!res.ok` branch, capture `body.errorCode` *separately* (do not reorder the `??`), read
`res.headers.get('WWW-Authenticate')`, and when `status === 401 && errorCode === 'STEP_UP_REQUIRED'`
throw `StepUpRequiredError extends ApiError` carrying the parsed **challenge**
`{ error, description, acrValues, maxAge }`. A `401` **without** a body stays the existing "session
expired" path (a gateway 401 has no problem body — the two are distinguishable by construction).
Every `instanceof ApiError` consumer keeps working.

**Why the header is even readable**: dev (Vite proxy) and packaged (nginx behind APISIX) are both
**same-origin**, so `WWW-Authenticate` reaches `fetch` without CORS exposure. The gateway's CORS
block exposes only `X-Upstream-Addr,Location`; a **cross-origin** adopter would see the `401` +
`STEP_UP_REQUIRED` body but a `null` header — and the seam would honestly degrade it to a plain
error, i.e. the feature would look broken with no error anywhere. `WWW-Authenticate` is therefore
added to `expose_headers` on all three routes (one word × three sites in `init-routes.sh`) and the
reason is stated in the SPA README: single-origin is why RFC 9470 works here.

The **parser** is a pure function over the header value (RFC 9470 / RFC 7235 `auth-param` list:
quoted-strings, case-insensitive scheme and param names, `max_age` numeric); an unparseable header
or a missing `max_age`/`acr_values` degrades to a **plain** `ApiError` — the client's mirror of the
advice's "no half-formed challenge" rule (a challenge the client cannot follow is shown as a plain
error, not a broken [Verify]).

### 4. The 401 moment: the inline locked panel

The two drill-in contents areas — categories under a catalog, products under a category — are the
only surfaces that can challenge. When their loader throws `StepUpRequiredError`, the contents area
renders a persistent **locked panel** instead of the error box: title *"Production contents —
fresh second factor required"*, the server's `error_description` verbatim, the parameters as
plain facts (`acr_values`, `max_age` in seconds), and **[Verify]**. Nothing modal; the header,
breadcrumbs and the metadata card stay usable; the panel simply re-renders whenever the next load
challenges again. Categories/products **inside** a challenged catalog inherit its provenance
context — the panel is the same component at both levels.

### 5. [Verify]: the redirect, the state, the one retry

- **The call** — `userManager.signinRedirect({ acr_values, max_age: 0, extraQueryParams: { claims },
  state })` where `acr_values` is **the challenge's**, `max_age` is **`0`** (the miner's measured
  recipe — `mint-code-flow-token.py`: `0` forces a real re-authentication independent of the
  advertised window and of the realm's per-level `loa-max-age`, so `auth_time` is fresh; echoing
  the challenge's own `max_age` would *also* re-authenticate — the challenge only fires past
  `max_age + skew` — `0` simply removes the reasoning; the loop ADR 0030 §7 warns about and the
  matrix's E3 negative measures comes from **omitting** `max_age` entirely, and `0` satisfies §7's
  "the client MUST forward `max_age`" as a strict superset), and `claims` is
  the essential-`acr` request `{"id_token":{"acr":{"essential":true,"values":[acr]}}}` (a voluntary
  `acr_values` can silently under-deliver — ADR 0030 §7, `mint-code-flow-token.py`; `claims` is not
  in `SigninRedirectArgs`, hence `extraQueryParams`). All four are per-call arguments in
  oidc-client-ts 3.5 (`ExtraSigninRequestArgs`); no library change.
- **`prompt: 'login'` moves** from the `UserManager` constructor to the initial `login()` call
  (identity switching keeps forcing the form); the step-up call sends **no `prompt`** — `max_age=0`
  is the re-auth trigger. The prompt sequence Keycloak shows under `max_age=0` on an existing SSO
  session is **settled before T3 is built, from the miner's stderr** (run it against a populated
  cookie jar — it prints which forms it answered; the runner's E3 (`--no-max-age`) mint is exactly that call), then
  *recorded* by **E10** on the happy path. C's STATUS-01 pinned `loa-max-age` at 36000 on level 1
  precisely so the password is *not* re-asked on every step-up; whether OIDC `max_age=0` overrides
  that per-level memory is the open question the miner answers in one command.
- **The state** — `{ v: 1, catalogId, categoryId?, stepUp: true }` (ids only; the SPA's `View`
  embeds whole objects, which must not round-trip through the IdP). `redirect_uri` stays `${origin}/`.
- **Restoration** — the callback resolves in `App`; the restored location threads into `Console`
  as its initial view: `getCatalog(catalogId)` (never challenges) → the catalog view; if
  `categoryId`, a new `api.ts#getCategory(catalogId, categoryId)` (the OpenAPI operation exists,
  the client function does not; **one** direct read, not list-then-find) → the category view. Any of
  the restored loads may challenge; **none triggers a redirect** — the restored view's own load is
  the retry, and a challenged `getCategory` leaves the user on the catalog view with the panel. Note
  the load-bearing library fact: `User.toStorageString()` **omits `state`**, so `user.state` exists
  only on the callback's page load — it cannot leak into later loads (do not "fix" this by
  persisting it).
- **The loop guard** — the panel knows the last challenge was already answered because
  `user.state.stepUp === true` on this page load; a **post-callback** `StepUpRequiredError` renders
  the panel in its **passive** variant: the added honest notice *"verification did not unlock
  production contents"* and [Verify] still available — the UI **never auto-redirects**. A manual
  click clears the passive flag. Structurally loop-free; the client analogue of the policy's
  agent-loop discipline.
- **Cancel at Keycloak** — the user lands on `/` without a code (Keycloak's error redirect or a plain
  navigation): the app treats it as an ordinary load; the location was in the *unused* state, so
  the drill-in is lost — accepted (redirect-only demo), recorded as **E12**.

### 6. The elevation chip

A header chip next to the role chips, parsed from the **live** access token: `acr` and `auth_time`
(the same payload decode `describeUser` already does; tolerant of absence — tokens from clients
without the `basic`/`acr` scopes carry neither). Rendering:

- `acr` maps to LoA ≥ 2 **and** a window is known ⇒ **"Elevated · m:ss"** (green-ish/brand),
  counting down `auth_time + max_age − now`, then flips **amber "elevation lapsed"** at zero;
- LoA ≥ 2 and **no** window known ⇒ **"Elevated (aal2)"**, no countdown — honest, never a
  guessed number. This branch is **defensive, not a normal path**: the token and the window key
  live in the same per-tab `sessionStorage`, written by the same flow, cleared together — a new tab
  has no session at all (it signs in at `aal1`, chip hidden). It is reachable only by deleting the
  key by hand (**E16**), and it exists so the chip can never invent a number;
- LoA < 2 and a challenge **has** been seen this session ⇒ amber **"not elevated"** — meaningful
  because production is in play; otherwise **no chip** (members and viewers see nothing new — K13).

**The window is learned**: the last challenge's `max_age` is kept in `sessionStorage`
(`stepUp.maxAge` — **not** under the `oidc.` prefix, which `clearStaleState()` sweeps at
[Verify] time), written by the `request()` seam (the parser stays pure), surviving the redirect. Never `300` in source. The
countdown shows the *advertised* boundary; the policy's `skew` may still admit a read a few seconds
past zero — which is exactly why the flip is amber (a prediction) and content is never hidden (§7
of the grill: **reactive expiry** — chip flips, rendered content stays, the next fetch simply
challenges again).

### 7. Row badges

On the catalogs grid and the catalog detail card, from data the client already has:

- `_provenance === 'supervised'` ⇒ a neutral **"supervised"** badge (the `RoleChips` grey);
  member rows carry nothing (the default state stays visually the default).
- `tags.env === 'production'` ⇒ a **"production"** tag chip (`TagLine` idiom).
- supervised **and** production **and** the chip is not "Elevated" ⇒ the production chip is
  **amber "production · verify to open"** — the prediction; a member's production catalog is
  **never** amber (their read needs no elevation — K13); a supervised production catalog while
  elevated is not amber.

Amber here is a client prediction from three inputs; the server's `401` is the truth and the panel
is where it lands. Predicted-and-wrong (amber but 200, or plain but 401) is a UI cell (**E17**),
not a failure of enforcement.

### 8. The demo world: `sup-demo` / `pm-demo`

The seed cannot use `sup-anna` (matrix-owned; edges wiped by two runners; her TOTP mint would make
the seed depend on the miner). The realm gains **two fixture users**, and the seed owns them
exclusively — registered next to the reserved families:

- **`sup-demo`** — realm role `unit-supervisor` (the UX-only marker), `catalog-viewer`, password
  = username, and a seeded **`otp` credential** in the exported shape anna's uses (`subType totp`,
  6 digits, period 30, HmacSHA1) with its **own** fixture secret. Never bound to any team.
- **`pm-demo`** — `catalog-editor`, password = username; the report.
- **`sup-demo`'s `sub` is resolved from the Keycloak admin API**, not minted: the seed uses the
  `catalog-directory` service account (`client_credentials`; it already carries `view-users`) and
  `GET /admin/realms/catalog-demo/users?username=sup-demo&exact=true` → `.id` — the exact recipe
  `run-team-matrix.sh` uses for the never-provisioned `dora`. No TOTP in the seed, no miner
  dependency, no rig flag (`ENABLE_DIRECTORY` gates the user-service bean, not the client). Keycloak's
  **direct-grant** flow demands an OTP from any identity that has one (measured in C), so a
  password-only ROPC for `sup-demo` would `invalid_grant` — which is why the lookup path exists.
  **Optionally**, both users carry a pinned `id` in the realm export so their `sub` survives a
  `deploy.sh down` + `up` re-import (the class of breakage the smoke runner just fixed for `demo`) —
  no user in the export pins an `id` today, so this is a **spike** (create on the live rig, `kc.sh
  export`, transplant; record in STATUS-05 whether the pin is honoured). Either way the seed
  **converges**: `bootstrap/users` is find-or-create by subject and the edge is a REPLACE, so a
  re-run after a re-import (new `sub` or not) yields the same world.
- **The seed's supervised block** (idempotent, additive, the seed's own style): `pm-demo` bootstrapped
  by subject; two catalogs in the seed's `d311…` family — `…0002` **"Demo Production Catalog"** and
  `…0003` **"Demo Open Catalog"** — each with a category + a product created **through the gateway**
  with `pm-demo`'s ROPC token; two teams (`Demo Production Team`, `Demo Open Team`), `pm-demo`
  bound `owner` on both; the reporting edge `sup-demo → [pm-demo]` via
  `POST /internal/bootstrap/reporting-edges` (REPLACE — safe: the seed is the only writer for
  `sup-demo`); the operator tag `env=production` on `…0002` via the catalog service's
  `POST /internal/bootstrap/resource-tags` on its **published port** (the gateway 404s `/internal/*`
  — the seed's config block gains `CATALOG_SERVICE`), response asserted. The `env` dictionary entry
  is Liquibase-seeded (`0008`), so the write validates without setup.
- **Preflight**: if `pm-demo`'s ROPC mint fails, the realm has not been re-imported since the
  persona landed — say so and point at `./deploy.sh down && … up`.
- **Registry**: a `d311…` row (the demo seed's whole family, incl. `…0001` which was never
  registered) + a persona line: `sup-demo`/`pm-demo` belong to `seed-demo-data.sh`; no matrix may
  bind, grant on, or add an edge for them; the reserved `sup-*`/`pm-*` family text gains that
  carve-out.

`sup-demo` signs in through the SPA (PKCE, `catalog-spa`), sees exactly two supervised catalogs,
opens the open one without ceremony (K11), hits the panel on the production one (K8), and answers
one TOTP.

### 9. Validation: the committed case list + the Browser-pane loop

- **API truth stays where it is** — the step-up matrix (58 cells) and the new `_provenance` IT/e2e
  cells (**I1–I4**, **E30** — T1; **E31–E33** — T5) pin the wire. **UI truth** is the committed **Browser-pane case
  list** (**E10+** in [[10-QA-TEST-CASES]]) — happy path with TOTP; cancel → passive panel;
  post-callback 401 notice; expiry flip + reactive lock; refresh does not extend; member/viewer
  non-regression; badges vs server; reload/state restore; the parser negatives — run as an
  **adversarial** pass (fresh eyes, trying to make the UI lie), findings **ratcheted**: API-level →
  newman cells, UI-only → the case list's regression rows. Human QA is the final pass only.
- **The pure seams get a unit runner**: the challenge parser, the token-claims decode, the window
  math and the badge predicate are pure functions and are where the bugs hide; **vitest** is added
  to `example-demo-ui` (a devDependency in a module outside the Gradle build; `npm test` joins
  `npm run lint` as the module's gate — **U1–U6**). No component/DOM tests; no Playwright.
- **`.claude/launch.json`** already has the Vite dev entry (`demo-ui`, :3000, proxying to :9085);
  it gains an **attach** entry for the **packaged** SPA through the gateway (`url` +
  `port: 9085`, no command) — QA truth is the packaged path (no deep links, the gateway's issuer
  allowlist, the real nginx). **The rig for every pane cell and for T5/T6's matrix runs is
  `ENABLE_SPA=1 ENABLE_MCP=1 ./deploy.sh up --pods 2` — both flags on the same `up`**: `deploy.sh`
  tears the MCP stack down on an `up` without `ENABLE_MCP=1` and the SPA stack down without
  `ENABLE_SPA=1`, and the step-up matrix's preflight hard-fails without the MCP server (its E6).

### 10. Ship shape

A **mini package**: this note, [[01-DECOMPOSITION]] (T1–T6, ordered), [[10-QA-TEST-CASES]],
`STATUS-NN` stubs for the per-ticket record — **no autonomous-implementation prompt, no
orchestrator**. `verify-package.sh` is run for its mechanical value with gates **[1] (the prompt
file) and [5] (prompt invariants) failing by design**; [2]–[4], [6]–[9] must pass. Built together
on one branch, ticket by ticket, `/deep-review` before the PR; the ADR is written **up front**
(the repo's convention) as [[0033-catalog-provenance-affordance|ADR 0033]].

### 11. Living docs touched in-branch

`docs/guides/REST-API-DESIGN.md` (the enrichment/affordance section gains `_provenance`),
`docs/guides/E2E-TESTING.md` (the seed's supervised world; the packaged-SPA QA path),
`scripts/postman/README.md` (registry row + persona carve-out), `infra/README.md` (the two personas,
down-first re-import), `example-demo-ui/README.md` (the challenge flow, the chip, the badges, the
unit runner), `docs/to-do/planning/USER-STORIES.md` (K16 — the console story), and the roadmap's
Phase 10 line.

## Considered and rejected (the grill, 2026-08-15)

- **Challenge-only scope** (Q1) — rejected: the demo tells its story only at the moment of denial;
  the visibility is cheap and is what the supervisor path has lacked since A. **Wire provenance
  in** (the third option) — the badge is server truth, not a guess.
- **A `_supervised` boolean** (Q2) — rejected: "absent" would mean both *member* and *not
  computed*, losing the honesty distinction `_actions` established. **`_provenance` on all three
  types** — rejected: children cannot differ from their catalog; redundant serializer work.
- **A modal on 401** (Q3) — rejected: dismiss/re-open state, hides the page; **auto-redirect** —
  rejected: hides the RFC 9470 mechanics the demo exists to show and risks a redirect loop.
- **Popup re-auth / popup-with-redirect-fallback** (Q4) — rejected: blockers, a second window in
  QA automation, a callback page to serve, double the surface for a demo whose point is the
  contract.
- **Bounded auto re-challenge / treat post-callback 401 as session error** (Q5) — rejected: the
  first bounces a canceling user against their will; the second is factually wrong (the session is
  fine) and buries the feature's own failure story.
- **Hardcode `300` in the SPA / no countdown** (Q6) — rejected: client-copies-policy drift, or the
  loss of the window story's most legible artifact.
- **Proactive lock or proactive re-verify prompt at expiry** (Q7) — rejected: the client would be
  enforcing a decision it does not own, wrong by up to the skew in both directions.
- **Reuse the matrix with `KEEP_FIXTURES=1` / a dedicated `seed-supervised.sh`** (Q8) — rejected:
  a demo world that depends on running a test suite, or two seeds to keep coherent. **Reuse
  `sup-anna`** (Q8′, surfaced by the registry) — rejected in both variants: a fourth claimant on a
  persona two runners wipe, or the seed and the matrices overwriting each other's world.
- **An ad-hoc pane session / scripted browser automation** (Q9) — rejected: nothing ratchets, or a
  new toolchain for a demo UI.
- **A full decompose + autonomous run / no package at all** (Q10) — rejected: overrules the
  standing collaborative decision where autonomous runs waste rounds (look-and-feel), or leaves the
  rationale in a chat log. **Release 1.2.0 before this slice** — rejected: one coherent epic story
  in the release notes and README is worth one collaborative slice.
- **`_provenance` derived on GET by a library seam** — rejected for this slice: a "resolved role"
  request attribute in the manager is a library change; the memoized re-lookup costs nothing on the
  default config and keeps the slice example-only.
