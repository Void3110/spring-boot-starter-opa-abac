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
