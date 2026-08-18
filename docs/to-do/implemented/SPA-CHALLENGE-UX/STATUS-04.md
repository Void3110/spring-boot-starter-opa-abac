---
tags:
  - status/done
  - type/project
  - area/abac
  - area/catalog-service
---

# SPA-CHALLENGE-UX — STATUS-04 — T4: the elevation chip and the row badges

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-17

## Record

### What landed

- `stepup.ts` (still dependency-free) — `loaOf`, `elevationClaims`, `chipState`, `isElevated`,
  `formatRemaining`, `badgesFor`, `forgetChallengeWindow`. All the arithmetic lives here, so all the
  components own is *when* to ask.
- `auth.ts` — `elevationOf(user)`; `login()`/`logout()` now forget the learned window.
- `api.ts` — `Catalog._provenance?: 'member' | 'supervised'` (T1's field, now typed client-side).
- `components.tsx` — `ElevationChip`, `CatalogBadges`.
- `App.tsx` — `useElevationChip` (a 1 s tick), the chip in the header, badges on the catalogs grid
  and the detail card.
- `index.css` — a first-class **`--color-warn`** token. Amber earns its own colour because it means
  something the existing palette cannot say: allow/deny are the *server's* answers, amber is the
  *client's prediction*, and anything amber may turn out wrong without anything being broken.

### The bug the browser pass found — a member seeing an elevation chip

Signed in as `pm-demo` after `sup-demo`, the header showed amber **"not elevated"**. A member must
see *nothing* new (K13): they never need elevation, so a chip is noise about a mechanism that does
not apply to them.

Cause: `stepUp.maxAge` lives **outside** oidc-client-ts's `oidc.` prefix on purpose — that is what
stops `clearStaleState()` sweeping it at [Verify] time (T2's trap 8). The same property means
oidc-client-ts does not clear it on **logout** either, so `sup-demo`'s window survived the identity
switch and made the next identity's console claim something about her session.

**The same shape as T3's `passive` bug: session-scoped state outliving the session it described.**
Fixed by clearing the window in `login()` and `logout()` — and pointedly *not* in `stepUp()`, where
surviving the redirect is the whole point. That asymmetry is now a unit test, because it is exactly
the kind of thing a later "tidy-up" would collapse.

### Acceptance

| Cell | Result |
|---|---|
| **U4** — the claims decode is tolerant | ✅ 10 cases (acr/auth_time absent, non-numeric, null, undecodable token, unknown acr → 0) |
| **U5** — the window math + chip state | ✅ 8 cases incl. the at-zero boundary and "a negative never renders" |
| **U6** — the badge predicate | ✅ 8 cases incl. member+production, supervised+elevated, staging, absent-provenance, multi-valued `env` |
| **E13** — expiry is reactive | ✅ chip flipped amber at zero, **rendered categories stayed on screen**, and a *new* fetch past `max_age + skew` was challenged |
| **E15** — members and viewers see nothing new | ✅ `pm-demo`: no chip, no supervised badge, production badge **neutral not amber**, production contents open with no ceremony (after the fix above) |
| **E16** — the defensive no-window state | ✅ deleting `stepUp.maxAge` degraded the chip to **`Elevated (aal2)`**, no countdown, no fabricated number |
| **E17(a)** — badge-vs-server honesty, the adversarial cell | ✅ **the headline observation** — see below |
| **E17(d)** — the grid's badges | ✅ `supervised` on both demo catalogs, amber `production · verify to open` on `…0002` only, and only while not elevated |
| `npm run lint` · `npm test` · `npm run build` | ✅ **63 tests** |

### E17(a), observed — prediction and truth diverging, with truth winning

Drilled to `max_age=5, skew=30` to open a 30-second gap between the *advertised* boundary and the
*enforced* one, then elevated. The round trip outlived the 5 s window, so:

- the chip read amber **`elevation lapsed`**;
- **the categories rendered anyway** — the server answered **200** inside its skew (confirmed in the
  network log);
- the row badge still read amber `production · verify to open`.

So the client predicted "you will need to verify", the server disagreed, and the console **showed the
contents while keeping its own prediction honestly amber** — it hid nothing on the strength of a
guess. That gap is deliberate (the chip's zero is `max_age`; the server refuses only past
`max_age + skew`), and this is the cell that proves the UI does not pre-empt the server.

### Left for T6 (stated, not silently dropped)

- **E17(b)/(c)** — need DevTools **Local Overrides** to strip `env` / delete `_provenance` from a
  response, which the Browser pane cannot drive (same limitation as T3's E18). The *predicate* is
  covered exhaustively by U6, including the absent-`_provenance` case; what is unproven is only the
  end-to-end interception.
- **E14** — "refresh does not extend": needs a fresh elevation plus an `expires_at` edit, i.e. a full
  re-auth dance; the API half is already proven by the step-up matrix's E9h.
- **E19** — the no-regression walk over the pre-existing surfaces belongs in the fresh-eyes pass.
- The sign-in card still lists only `editor · demo · viewer · outsider` (carried from T2/T3) — the
  supervisor story is invisible to a first-time reader until that line names `sup-demo`/`pm-demo`.

The rig's drill was **restored** (OPA restart; `max_age 300`, `skew 30` re-verified with a real
decision probe, not `/health`).
