---
tags:
  - status/done
  - type/project
  - area/abac
  - area/keycloak
---

# SPA-CHALLENGE-UX — STATUS-03 — T3: the locked panel, [Verify], restoration, one retry

> Filled in as the ticket is built (collaboratively). Records what was **measured** (spike results,
> Keycloak's observed prompt sequence, the pane cells' observations) — not what was intended.

**Status:** ✅ done — 2026-08-17

## Record

### The prerequisite spike — ANSWERED

**Question (from the package):** under OIDC `max_age=0` on an existing SSO session, does Keycloak
re-ask the *password*, or only the second factor? C's STATUS-01 pinned `loa-max-age=36000` on level 1
precisely so the password would not be re-asked on every step-up.

**Answer: it re-asks BOTH — password, then one-time code.** Measured with
`mint-code-flow-token.py` against a populated cookie jar, reading its stderr:

| run | cookie jar | request | forms answered | `auth_time` |
|---|---|---|---|---|
| 1 | empty | `--acr aal2 --max-age 0` | password + TOTP | `…323` (new) |
| 2 | **populated** | `--acr aal2 --max-age 0` | **password + TOTP** | `…341` (new) |
| control | **populated** | `--acr aal2 --no-max-age` | **none** | `…341` (**unchanged**, `iat` newer) |
| 3 | populated | `--acr aal2 --max-age 5` (stale) | **password + TOTP** | `…412` (new) |

The **control is what makes runs 2 and 3 mean anything**: with `--no-max-age` the same jar answered
from the SSO session with *no forms at all* and returned the **same `auth_time`** with a newer `iat`.
So the session was genuinely live and reusable, and the two forms in runs 2/3 were caused by
`max_age`, not by a missing session.

**Two consequences.** (1) OIDC `max_age` **overrides** the realm's per-level `loa-max-age` memory —
level 1 is re-executed regardless. (2) Run 3 shows a **non-zero** `max_age` behaves identically, so
echoing the challenge's own `max_age` would buy nothing: this **confirms the design's `max_age: 0`
pin** rather than challenging it, and `0` is simply the value with no reasoning attached.
**E10's script therefore says: password, then OTP.** Confirmed again in the browser — the password
form appears pre-filled with "Please re-authenticate to continue", then the one-time-code form.

### What landed

- `auth.ts` — `prompt: 'login'` **moved** from the `UserManager` constructor to `login()` (as a
  constructor default it would have ridden on the step-up request too, where the trigger must be
  `max_age`); `stepUp(acrValues, location)`; `stepUpStateOf(user)` which validates the returned state.
- `api.ts` — `getCategory(catalogId, categoryId)`.
- `components.tsx` — `useAsync` additionally returns `cause` (the thrown value beside the rendered
  string; every existing consumer is unaffected); `LockedPanel` with its passive variant.
- `App.tsx` — the callback captures the step-up state; `Console` restores the drill-in from ids;
  the passive guard; both contents areas render the panel instead of the error box for a challenge.
- `example-demo-ui/README.md` — the round trip, the parameter table, the measured prompt sequence,
  the loop guard, and the accepted cancel limitation.

### The defect the browser pass found (and unit tests could not)

`passive` was initialised from "this page load is a callback" and **never cleared**, so after one
verification it stuck for the whole session: every later challenge the user walked into *fresh*
claimed *"verification did not unlock production contents"* about a verification never attempted for
it — the console lying about its own history. **Only observable on the second challenge**, which is
why nothing before the browser pass caught it.

Fixed by routing every user-initiated navigation through a `navigate()` that clears the flag, while
the restoration effect keeps `setView` — so passive means exactly "the read you just verified for is
still refused", which is only ever true of the view the callback restored. Re-verified live: a fresh
navigation to the same catalog now renders the ordinary panel.

### E12(b) is NOT REACHABLE on this rig — measured, not assumed

The cell asks for a completed [Verify] that leaves the read *still refused*, rendering the passive
panel. It cannot be produced here, and the drill cannot manufacture it:

| drill | freshly elevated token | result |
|---|---|---|
| `max_age=0, skew=0` | — | **403, no challenge header** (the UI correctly shows the plain error box, not a panel) |
| `max_age=0, skew=2` | immediately after elevation | **200** |
| `max_age=0, skew=2` | 5 s later | 401 + a well-formed challenge |
| `max_age=300, skew=30` (shipped) | — | 200 |

The reason is structural: a completed re-authentication sets `auth_time = now`, and the restored load
lands ~1–2 s later, so **any window with a non-zero skew is satisfied** (observed even at a 2-second
window — the round trip beat it). Shrink the window to zero and the server stops challenging
altogether and plainly denies — correctly, since a challenge nothing can satisfy is an invitation to
loop. The cell's alternative route (a wrong-account sign-in) does not produce it either: `pm-demo` is
a *member* (200), and an unrelated persona fails the catalog read itself, which lands on the grid.

**So the passive variant is defensive code for a state the shipped policy cannot reach.** Kept
deliberately — a tighter deployment, clock drift between pods, or a stricter `required_acr` could
produce it, and the cost is one boolean — but it is now *recorded* as unreachable rather than
implied to be covered. **For T6:** either accept it as documented-defensive, or drive it by pinning
`required_acr` to an unsatisfiable value; the passive *negative* (it must NOT appear on a fresh
challenge) is verified and is the assertion that actually protects users.

### Acceptance

| Cell | Result |
|---|---|
| **E10** — the challenge round trip, catalog level | ✅ panel with the server's sentence + `acr_values aal2` / `max_age 300s`; [Verify] → **password → OTP** → back on the same catalog with its categories. Network log: `…0002/categories` **401 → 200**, one retry, no second redirect |
| **E10** — the same round trip from a **category** view | ✅ panel at the category level; [Verify] → back on **that category** with its product (the `categoryId` rode the state) |
| **E11** — the open catalog needs no ceremony | ✅ `…0003/categories` **200**, no panel, no `WWW-Authenticate` — verified while elevation was *lapsed* |
| **E12(a)** — cancel → grid, no loop | ✅ abandoning at Keycloak loads the grid, session intact, drill-in lost (accepted); **no redirect for 35 s** |
| **E12(b)** — passive panel after a failed verification | ⚠️ **not reachable — see above.** The passive *negative* is verified: no passive notice on a fresh challenge (this is what the defect above was) |
| **E18** — parser negatives on the wire | ⚠️ **partial.** DevTools Local Overrides is not drivable from the Browser pane. The equivalent was observed live: a refusal that is *not* a followable challenge (the 403 above) renders the **plain error box**, never a broken [Verify]. U1/U2 cover the parser negatives exhaustively (21 cases). **Left to T6** with a human doing the override step |
| `npm run lint` · `npm test` · `npm run build` | ✅ 35 tests |

Live observations worth keeping: `stepUp.maxAge` **survived the `signinRedirect`** while the `oidc.`
keys were swept (trap 8 proven on the wire, not just in a unit test), the callback cleaned its own
`?code=` from the URL, and the panel displayed the **drill's** `max_age` (5s, then 0s) rather than a
cached 300 — the window really is learned per challenge.

### Notes for T4

- The chip's window source is live and correct: `sessionStorage['stepUp.maxAge']`.
- The sign-in card still lists only `editor · demo · viewer · outsider` (carried over from T2).
- The rig's drill was **restored** by restarting OPA; shipped values re-verified (`max_age 300`,
  `skew 30`) with a real decision probe, not `/health`.
