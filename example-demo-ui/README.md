# example-demo-ui

A browser SPA that logs in via Keycloak (Authorization Code + PKCE) and exercises the
catalog / user-management ABAC through the APISIX gateway, rendering the `_actions`
affordance map live so you can watch a resource's allowed verbs change as you switch
identity.

**This is demo scaffolding.** It is not published to npm, not part of the Gradle build,
and not a production reference for how to build a secure SPA. It exists to make the
library's authorization decisions *visible* in a browser.

## Run

The SPA is served two ways:

- **Packaged demo** — `ENABLE_SPA=1 ./deploy.sh up --pods 2` builds the SPA and serves it
  through APISIX at the gateway origin (`http://localhost:9085`). Single-origin: APISIX
  proxies Keycloak's `/realms/*` and `/resources/*` in-network, and Keycloak rewrites all
  advertised URLs to that origin, so authority / issuer / redirect all agree — no
  `/etc/hosts`, no CORS.
- **Dev** — `npm run dev` runs Vite on `:3000` and proxies `/realms` + `/resources` to
  `:9085`. Seed the demo data first with `scripts/postman/seed-demo-data.sh`.

Sign in as `editor` / `demo` / `viewer` / `outsider` (password == username) and watch the
Demo catalog card's affordances change per identity.

For the supervisor story, sign in as **`sup-demo`** (password == username, plus a TOTP —
`scripts/postman/mint-code-flow-token.py --print-otp --otp-secret spachallengedemo1234` prints a
code): she supervises `pm-demo` and is a member of no team, so both her catalogs are reachable by
*derivation*. The untagged one opens with no ceremony; the `env=production` one asks for a fresh
second factor. `pm-demo`, a **member** of the very same catalogs, reads production with no
elevation at all.

## Tests

```bash
npm run lint   # tsc -b --noEmit — types only
npm test       # vitest run — the pure seams
```

`npm test` covers the seams worth testing away from a browser: the challenge parser and `request()`'s
error classification. No DOM or component tests — the UI itself is validated by a committed
case list run in the Browser pane (`docs/to-do/.../10-QA-TEST-CASES.md`, the E10+ cells), which is
where UI truth actually lives.

## The step-up challenge seam

When a supervisor reads production content, the server answers `401` with an RFC 9470
`WWW-Authenticate` challenge naming what would satisfy it (ADR 0030). `src/stepup.ts` parses that
header — a real RFC 7235 `auth-param` tokenizer, because `error_description` is free text that
contains commas — and `api.ts`'s `request()` turns a `401` + `STEP_UP_REQUIRED` + a *followable*
challenge into a typed `StepUpRequiredError` carrying it.

Three things about that are deliberate:

- **A challenge the client cannot follow degrades to a plain error.** Missing `acr_values` or
  `max_age`, a scheme that is not `Bearer`, an unparseable header → the parser returns `null` and the
  caller gets an ordinary `ApiError`. Better an honest 401 than a [Verify] button that cannot work —
  the client's mirror of the server's "no half-formed challenge" rule.
- **A bodiless `401` stays the "session expired" path.** A gateway 401 carries no problem body, so
  the two are distinguishable by construction rather than by guessing.
- **The advertised `max_age` is remembered under `stepUp.maxAge`** — deliberately *not* under the
  `oidc.` prefix, which oidc-client-ts's `clearStaleState()` sweeps on every `signinRedirect()`, i.e.
  exactly when [Verify] redirects. The elevation chip counts down a window **learned from the
  server**, never one hardcoded here.

**Why the header is readable at all:** both deployments above are single-origin, so
`WWW-Authenticate` reaches `fetch` without CORS exposure. A cross-origin adopter would need it in the
gateway's `expose_headers` — it is there (`infra/apisix/init-routes.sh`) precisely so this reads a
header rather than a `null` and silently degrades a working feature.

## [Verify]: the round trip

A `StepUpRequiredError` renders a **locked panel** in the contents area that was refused — the
server's own sentence, `acr_values` and `max_age` as plain facts, and one [Verify] button. Nothing
modal, and **no automatic redirect ever**: the header, breadcrumbs and the resource's metadata card
stay usable, because the caller is not locked out of the resource, only out of its contents.

[Verify] calls `signinRedirect` with:

| | | |
|---|---|---|
| `acr_values` | from the challenge | what the server said it wants |
| `max_age` | **`0`** | forces a genuinely fresh authentication, so `auth_time` is new |
| `claims` (via `extraQueryParams`) | essential `acr` | a bare `acr_values` is only *voluntary* and can be silently under-delivered |
| `state` | `{v:1, catalogId, categoryId?, stepUp:true}` | **ids only** — the state travels through a third party |

**`max_age: 0` is deliberate, and zero is not "no window".** Omitting `max_age` is what causes the
infinite loop: Keycloak happily answers from the existing SSO session, returns the *same* stale
`auth_time`, and the read 401s again forever. Echoing the challenge's own `max_age` re-authenticates
too — measured to produce the identical prompt sequence — so `0` simply removes the reasoning.

**Measured on this realm:** a `max_age`-triggered re-authentication re-asks **both** factors —
password *then* one-time code — even on a live SSO session. OIDC `max_age` overrides the realm's
per-level `loa-max-age` memory, and a non-zero value behaves the same way. So [Verify] costs a full
re-login, not just a second factor.

**Coming back**, the callback reads `user.state` and restores the drill-in: `getCatalog(catalogId)`,
then `getCategory(catalogId, categoryId)` if we were a level deeper — one direct read each, never
list-then-find. That restored load **is** the single automatic retry. Either read may itself be
challenged and **neither triggers another redirect**; a challenged category simply leaves you on the
catalog with the panel.

**The loop guard.** On the page load that came back from a verification, a step-up refusal renders
the panel's *passive* variant — it says verification did not unlock the contents and waits. Any
deliberate navigation clears that flag, so a challenge you walk into fresh never claims you already
tried. No code path calls the step-up redirect without a click, so this is structurally loop-free
however the server behaves.

`User.toStorageString()` omits `state`, so `user.state` exists only on the callback's page load and
cannot leak into later ones. **Do not "fix" that by persisting it** — it is what makes the guard work.

**Accepted limitation:** cancelling at Keycloak loses the drill-in. The location lived in the state
of an authorization request that was never completed, so the app loads at the catalog grid. This is a
redirect-only demo with no deep links; restoring it would mean persisting navigation somewhere the
step-up flow does not need.

## ⚠️ Security caveat — do NOT copy the token handling to production

This SPA stores its OIDC tokens in **`sessionStorage`** (`src/auth.ts`). `sessionStorage`
is per-tab and cleared when the tab closes, which is a *little* safer than `localStorage`
(persistent, shared across tabs) — but it is **still readable by any same-origin
JavaScript**. An XSS anywhere on this origin can exfiltrate the live access token *and* the
refresh token while the tab is open. That trade-off is acceptable for a local demo with no
real data; it is **not** an acceptable production pattern.

For a real SPA, keep tokens out of JS-readable web storage entirely:

- hold the **access token in memory** (a module variable / app state), never in
  `localStorage` or `sessionStorage`; and
- put the **refresh token behind a Backend-For-Frontend** (an `httpOnly`, `Secure`,
  `SameSite` cookie the browser JS can't read) or a service worker — so no script can
  read or exfiltrate it.

Add a Content-Security-Policy to shrink the XSS surface in the first place. None of that is
wired here because it would obscure what this demo is *for* (showing ABAC decisions), not
because it is optional in production.

Flagged by the Phase 7.0.5 baseline security review
(`docs/code-review/PHASE-7-BASELINE-SECURITY-REVIEW.md`, finding 2).
