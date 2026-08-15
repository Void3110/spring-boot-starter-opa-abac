---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T1: realm: the claims fix, the level-2 TOTP flow, and the seeded factor

**Status:** ✅ DONE

## What shipped

All four deliverables land in `infra/keycloak/realm-export.json` — declarative, fixture-only, and
nothing else in the realm moved (structurally diffed against `HEAD`: 18 users before and after, roles
byte-identical, four of the six clients untouched):

- **The claims fix** — `basic` + `acr` appended to `defaultClientScopes` of **`catalog-spa` and
  `catalog-gateway` only**. ADR 0030 §Context's diagnosis re-confirmed live before the edit: the realm
  defines **no** `clientScopes`, so the literal four-name list *replaced* Keycloak's built-in
  assignment and left `basic` (which carries the built-in `auth_time` session-note mapper) and `acr`
  assigned to no client in the realm. Restoring them is the entire fix — the custom
  `oidc-usersessionmodel-note-mapper` the 08-13 probe validated is a redundant contingency (Mulch
  mx-a85001) and does **not** ship.
- **The ACR-to-LoA map** — realm attribute `acr.loa.map` = `{"aal1":1,"aal2":2}` (the shape Keycloak's
  admin console writes: a JSON *string* value, spike-verified, not assumed).
- **The browser flow `browser-stepup`**, bound as `browserFlow`, with two conditional
  level-of-authentication subflows and one `authenticatorConfig` each:

  ```
  browser-stepup (topLevel)
    auth-cookie                      ALTERNATIVE 10
    identity-provider-redirector     ALTERNATIVE 20
    → browser-stepup forms           ALTERNATIVE 30
        → browser-stepup level-1     CONDITIONAL 10
            conditional-level-of-authentication  REQUIRED 10  cfg step-up-level-1 {loa-condition-level:1, loa-max-age:36000}
            auth-username-password-form          REQUIRED 20
        → browser-stepup level-2     CONDITIONAL 20
            conditional-level-of-authentication  REQUIRED 10  cfg step-up-level-2 {loa-condition-level:2, loa-max-age:300}
            auth-otp-form                        REQUIRED 20
  ```

  **`loa-max-age: 300` mirrors `step_up.json`'s `max_age`** (T2) — one window, stated twice, cross-
  referenced in the docs because JSON holds no comments.
- **`sup-anna`'s seeded `otp` credential** — fixed fixture secret `stepupdemofixture123`, committed on
  purpose (a demo realm whose every fixture password is already committed), labelled as such in
  `userLabel`. Exported shape, transplanted verbatim:

  ```json
  { "type": "otp",
    "userLabel": "…",
    "secretData":     "{\"value\":\"stepupdemofixture123\"}",
    "credentialData": "{\"subType\":\"totp\",\"digits\":6,\"counter\":0,\"period\":30,\"algorithm\":\"HmacSHA1\"}" }
  ```

  The realm's `otpPolicy*` fields are pinned explicitly (`totp` / `HmacSHA1` / 6 digits / 30 s /
  look-ahead 1) so T5's offline RFC 6238 computation has a written contract instead of a default it
  would have to re-discover. **The HMAC key is the secret's raw ASCII bytes** — not base32-decoded —
  measured by an accepted login, not reasoned about.
- **Doc delta** — `infra/README.md` gains *Step-up elevation → The realm changes (and the down-first
  re-import they force)*: the four-row change table, the one-window cross-reference to
  `step_up.json`, the two measured Keycloak behaviours below, the `down`-first command block, and the
  note that ROPC is untouched. (T6 owns that section's step-up **matrix** subsection; this ticket
  wrote only the realm note.)

## Tests

No unit-testable surface — the ticket's whole deliverable is realm configuration, and its proof is
I5 (below). `opa test infra/opa/policies/` **308/308** and `./gradlew build` green, both unchanged by
this ticket (no `.rego`, no `.java`, no `.yml` touched); the local Sonar gate does not apply (zero
changed `.java` files).

## Architecture review + refactor

Path: **inline self-review** (the ★ gate as the prompt defines it — this is a config-only ticket, no
code to lens).

- **Fail-closed.** The realm can now *mint* a second factor; it grants nothing. No role, no scope
  beyond the two claim-carrying built-ins, no new client, no new user. A persona without an `otp`
  credential who is pushed to LoA 2 hits Keycloak's own credential-setup path and never reaches
  `acr: aal2` — a supervisor who cannot satisfy level 2 simply never elevates. Until T2 lands,
  nothing reads `acr`/`auth_time` at all, so the change is decision-inert by construction.
- **The named widening, and why it cannot happen.** "A realm-export edit that widens an existing
  client's scopes beyond the two named": verified structurally, not by eye — a script diffed the old
  and new export client-by-client and printed *every* differing field. Exactly two clients differ,
  exactly in `defaultClientScopes`, exactly by `basic` and `acr`. `catalog-directory` and all three
  `catalog-agent-*` clients are byte-identical, which matters for T2/T6: the agent clients keep
  minting `act_chain` and keep **not** minting `auth_time`, so the "elevated agent" token stays
  unmintable on this rig.
- **The second widening worth naming** (not in the prompt's list, found here): the new browser flow
  **drops** the built-in `browser` flow's Kerberos leg, organization subflow and
  `conditional-user-configured` 2FA subflow. Kerberos was `DISABLED` in the built-in flow, the realm
  defines no organizations, and the dropped conditional-2FA leg would have made things *worse*, not
  better — see the decision below. Nothing in the demo depends on any of the three; measured, not
  assumed: `demo` (no factor) still logs in with password alone and lands at `acr: aal1`.
- **Wiring.** Every value has a named consumer: `acr`/`auth_time` → T2's `elevated` and T4's
  `attribute-claims`; `acr.loa.map` → mirrored by `data.step_up.loa`; `loa-max-age 300` → mirrored by
  `step_up.max_age`; the seeded secret → T5's miner. The non-happy paths are exercised in I5 itself
  (an ordinary login must *not* be pushed to level 2; ROPC must *not* gain `auth_time`).
- **Refactor applied:** one, and it was structural rather than cosmetic — the flow was restructured
  from a single level-2 subflow to the level-1 + level-2 pair after the first probe run failed (first
  decision below). Nothing else was churned.

## Integration / e2e

**I5 — green, twice: once against a throwaway container built only from the edited export, then
against the real rig after a `./deploy.sh down` re-import** (the discipline this ticket documents;
Keycloak alone, per part 0's boundary). The probe is a throwaway in-ticket script (scratchpad, not
committed — T5 productionises it as `mint-code-flow-token.py`): scripted PKCE code flow, stdlib only,
carrying the recorded Secure-cookie-over-plain-http workaround (the Cookie header is driven by hand,
or Keycloak answers a misleading 400 "Restart login cookie not found" — mx-afd666).

```
[a] plain code flow                       acr='aal1'  auth_time=1786693471 (int)
[b] acr_values=aal2 essential + max_age=0 acr='aal2'  auth_time=1786693473 (int)   ← after the TOTP prompt
[c] refresh_token grant on (b)            acr='aal2'  auth_time=1786693473  iat=1786693475
[d] ROPC on catalog-gateway               acr='aal1'  auth_time absent

PASS (a) auth_time present and numeric on the plain code flow
PASS (a) acr == aal1
PASS (b) acr == aal2 after TOTP
PASS (b) auth_time fresh after the step-up re-auth
PASS (c) refresh PRESERVES auth_time
PASS (c) refresh advances iat (so only auth_time is the control)
PASS (d) ROPC still mints (200)
PASS (d) ROPC still carries NO auth_time (structural — ADR 0030 §Context)
```

(c) re-asserts the keystone the whole slice rests on: **a refresh cannot launder the authentication
instant** — `iat` advances, `auth_time` does not.

## Decisions

- **Seam deviation — the flow needs a level-1 conditional subflow the decomposition did not name.**
  `01-DECOMPOSITION.md` T1 asks for "the conditional **Level-2** subflow"; built exactly that, the
  first probe run demanded TOTP on a *plain* code flow. Cause, read off the shipped class rather than
  guessed (`javap -c` on `org.keycloak.keycloak-services-26.3.2.jar`): `ConditionalLoaAuthenticator`
  logs *"Condition '%s' evaluated to true due the user not yet reached any authentication level in
  this session"* — the condition matches whenever the session's current LoA is unset, **whatever the
  client requested**. So a lone level-2 condition fires on every fresh login. Wrapping the password in
  a **level-1** conditional subflow (`loa-condition-level: 1`) is what lets an ordinary login settle at
  LoA 1 and leaves level 2 to clients that actually ask. Recorded as a planning gap, not absorbed
  silently. A realm-level `default.acr.values: ["aal1"]` was tried first and does **not** help (the
  no-level-yet branch runs before any requested-LoA comparison); it was reverted rather than left as
  a cargo-cult knob.
- **`loa-max-age` is mandatory on *both* conditions.** `LoAUtil.getMaxAgeFromLoaConditionConfiguration`
  falls back to **0** when the key is absent ("Invalid max age configured … Fallback to 0"), i.e. the
  level is never remembered — which would re-prompt for the password on every step-up. Level 1 is
  therefore pinned at `36000` (Keycloak's own legacy fallback constant for
  `loa-store-in-user-session`), level 2 at the design's `300`.
- **The built-in `conditional-user-configured` 2FA subflow is deliberately absent** from
  `browser-stepup`. Left in, it would prompt `sup-anna` for TOTP at *every* login while the LoA
  machinery still recorded level 1 — a factor demanded that elevates nothing, which is strictly worse
  than either alternative. The level-2 condition is the only path to a factor here.
- **A partial `authenticationFlows` block does not suppress Keycloak's built-in flows** (26.3.2,
  measured on a clean import): declaring three custom flows still leaves `browser`, `registration`,
  `direct grant`, `reset credentials`, `clients`, `docker auth` and `first broker login` created, so
  every other flow binding keeps resolving. This is why the export gains ~150 lines rather than the
  full ~700-line built-in flow dump a "flows are all-or-nothing" reading would have forced.
- **The spike ran against the live rig's Keycloak** (admin REST API: scope assignment → realm
  attribute → `POST /authentication/flows/browser/copy` → subflow + condition + OTP form → requirement
  PUTs → condition config), and the shapes were transplanted from a **live
  `POST /partial-export`**, never written from memory — including the exported executions' legacy
  misspelled `autheticatorFlow` twin field, which is kept alongside `authenticatorFlow`. The live
  realm was then discarded by the `down` re-import, leaving the export the single source of truth.
- **Known, accepted side effect:** ROPC tokens now carry `acr: aal1` (they carried none before). `acr`
  alone is never a control here — the freshness half of `elevated` is what bites, and ROPC still
  carries no `auth_time` at all (asserted in I5(d), not assumed). No matrix asserts token claim sets.
- **I5(d) amendment (2026-08-14, maintainer-directed, orchestrator-recorded).** The original I5(d)
  cell measured `editor` — a persona without a factor — so "ROPC is untouched" missed that the
  direct-grant flow demands a code from any identity **owning** a factor: a plain ROPC mint for
  `sup-anna` answers `invalid_grant / "Invalid user credentials"` (part 1's cross-part escalation,
  STATUS-06). Re-measured on the factored persona against the live rig's Keycloak, in-network via
  the runners' own mint path: **(a)** plain ROPC for `sup-anna` → `invalid_grant`, reproduced;
  **(b)** ROPC + `otp=` (the miner's `--print-otp`, next-window retry for one-time use) → mints;
  **(c)** the minted token carries `acr: aal1`, **no `auth_time`**, and no `act_chain` — so the
  security property I5(d) exists for stands on the right persona too: without `auth_time`,
  `elevated` is undefined and the production deny holds; ROPC cannot launder elevation even for a
  factored supervisor. The realm is deliberately unchanged (no direct-grant exemption for fixture
  factors): the behavior is Keycloak's own factored-identity rule, and the harness-side `otp=`
  repair (T6) keeps the fixture secret + RFC 6238 parameters in one place.

## Commit

`feat(step-up-elevation): realm claims fix, the conditional level-2 TOTP flow, and anna's seeded
factor (T1)` — the four realm-export changes + the `infra/README.md` realm note + this STATUS note, on
`feature/void3110/step-up-elevation`.
