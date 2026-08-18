---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
---

# SPA-CHALLENGE-UX — STATUS-02 — T2: the SPA challenge seam (parser, error, classification, vitest)

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-17

## Record

### What landed

- **`example-demo-ui/src/stepup.ts`** — `parseChallenge(header): Challenge | null`, a real RFC 7235
  `auth-param` tokenizer (quoted-strings with escapes, any order, case-insensitive scheme and names,
  `max_age` digits-only); `STEP_UP_MAX_AGE_KEY` + `rememberChallengeWindow` / `lastChallengeWindow`.
  **Zero imports** — see the placement note below.
- **`src/api.ts`** — `StepUpRequiredError extends ApiError { challenge }`, and `request()` now
  captures `body.errorCode` **separately**, reads `WWW-Authenticate`, and on
  `401` + `STEP_UP_REQUIRED` + a *followable* challenge throws the typed error (remembering the
  advertised window on the way). A bodiless `401` keeps the "session expired" path; an unfollowable
  challenge degrades to a plain `ApiError`.
- **vitest** — `vitest@4.1.10` devDependency, `npm test` → `vitest run`, two spec files.
- **`infra/apisix/init-routes.sh`** — `WWW-Authenticate` added to `expose_headers` on all three
  routes, with the reason written above the first CORS block.
- **`example-demo-ui/README.md`** — a *Tests* section and a *step-up challenge seam* section.

### One deliberate deviation from the decomposition, and why

The decomposition places `StepUpRequiredError` in `stepup.ts`. It is in **`api.ts`**, directly under
its `ApiError` base. The reason is a real hazard, not a preference: a subclass is evaluated at
module-load time and must see its base *then*. `stepup.ts` importing `ApiError` from `api.ts` while
`api.ts` imports the parser back is an **import cycle**, and its resolution depends on which module
the bundler enters first — entering via `stepup.ts` (what a unit test does) initialises `api.ts`
fully and works, while entering via `api.ts` (what the app does) hits `ApiError` in the temporal dead
zone and throws. That is the worst failure shape available: green tests, broken app.

Keeping the error classes together also keeps `instanceof` reasoning in one file, and leaves
`stepup.ts` genuinely dependency-free — which is what makes the parser testable without a DOM, a
fetch or a token. The module's stated purpose (the pure, unit-testable seam) is met more fully this
way, so the deviation is in the decomposition's spirit even where it departs from its letter.

### Measured — the parser is pinned to the real bytes

The tests assert against the header the rig **actually emits**, captured off the wire in T1/T5 rather
than hand-written:

```
Bearer error="insufficient_user_authentication",
  error_description="A second factor is required to read production content",
  acr_values="aal2", max_age="300"
```

Two boundaries worth naming: **`max_age="0"` parses to a real window** (zero must not be swept up by
a falsy check — T3 sends `max_age=0` on the redirect), and a **fractional or negative** `max_age` is
refused (digits-only), because either would become a nonsense countdown in T4's chip.

### Measured — the CORS exposure, live

After re-running `init-routes.sh`, a cross-origin request now carries both:

```
HTTP/1.1 401
WWW-Authenticate: Bearer error="insufficient_user_authentication", …, acr_values="aal2", max_age="300"
Access-Control-Expose-Headers: X-Upstream-Addr,Location,WWW-Authenticate
```

This changes nothing for *this* demo — both deployments are single-origin, so the header always
reached `fetch`. It exists so a cross-origin adopter does not get the 401 and the `STEP_UP_REQUIRED`
body with a `null` header and a feature that looks broken with nothing in any log to explain it. On
the **MCP route** the exposure is **inert by construction** — the supervised path is human-only, so
an agent receives a plain 403 and never a challenge (the step-up matrix's E6 block) — and is listed
there only for uniformity across the three routes.

### The falsifier

Making exactly the mistake the trap warns about — reordering to `body.errorCode ?? body.detail`
instead of capturing the code separately — fails **2 of 35** tests: the guard that the thrown message
is still the server's sentence, and the 403 cell whose message would silently become `ACCESS_DENIED`.
Restored after. (Reaching the code by reordering is the tempting one-line version of this change, and
it would have quietly degraded every error message in the console.)

### Acceptance

| Cell | Result |
|---|---|
| **U1** — the parser accepts the emitter's exact form (order, case, escapes, commas in the description) | ✅ 6 cases |
| **U2** — it refuses a challenge the client cannot follow | ✅ 15 unfollowable shapes + the `max_age=0` boundary |
| **U3** — `request()` classifies 401s honestly | ✅ 9 cases |
| `npm run lint` (`tsc -b --noEmit`, test files included) | ✅ |
| `npm test` | ✅ **35 passed** |
| `npm run build` (the packaged bundle) | ✅ |
| the console still loads clean, no UI change | ✅ (packaged SPA at `:9085`, zero console errors) |

### Notes for the tickets that follow

- **T3** consumes `StepUpRequiredError` (from `./api`, beside `ApiError`) and its `.challenge`.
- **T4** reads the learned window with `lastChallengeWindow()` — already written by `request()` on
  every challenge, so the chip never needs a hardcoded number.
- Small UI nit spotted while checking the packaged console, **left for T3/T4**: the sign-in card's
  hint lists `editor · demo · viewer · outsider` and does not mention `sup-demo` / `pm-demo`. The
  supervisor story is invisible to a first-time reader until that line names them.
- **Pre-existing** `npm audit` highs (`nanoid`, `postcss`, both transitive via `vite`) were already in
  the committed lockfile before this ticket — not introduced by vitest, and out of scope here.
