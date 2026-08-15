---
tags:
  - status/done
  - type/project
  - area/abac
  - area/opa
  - area/spring
---

# STATUS — T5: the code-flow token miner

**Status:** ✅ DONE

## What shipped

**`scripts/postman/mint-code-flow-token.py`** — stdlib-only python3 (`urllib` + `http.cookiejar` +
a regex form parse + `hmac`-based RFC 6238 TOTP; no venv, no `requests`), the non-ROPC token path
ADR 0030 §Context made a first-class deliverable. It drives the PKCE authorization-code flow
against the rig's Keycloak, answers the login form and — when asked for a level — the TOTP prompt
from the realm's seeded fixture secret. The **access token goes to stdout and nothing else does**,
so a runner captures it with `TOKEN="$(./mint-code-flow-token.py …)"`; every diagnostic is stderr.

The frozen contract, exactly as the package pins it:

| Flag | Behaviour |
|---|---|
| *(none)* | plain code flow → `acr: aal1` + a numeric `auth_time` |
| `--acr aal2` | `acr_values` **plus** an essential `acr` claim **plus** `max_age=0` → the level-2 subflow demands TOTP → `acr: aal2` with a **fresh** `auth_time` |
| `--no-max-age` | never sends `max_age` — the loop-prevention tool: an existing SSO session answers, `acr` stays `aal2`, `auth_time` is the **original** instant |
| `--cookie-jar FILE` | the SSO session persisted as a Mozilla-format `http.cookiejar` file, so a **later invocation** reuses it (cross-invocation reuse is what E3/E9c measure) |

Plus the seams a runner needs: `--user` (password defaults to the username, the fixture
convention), `--otp-secret`, `--keycloak` (connect URL), `--issuer` (the authority presented as
`Host`), `--realm`, `--client`, `--redirect-uri`, `--max-age N`, `--show-claims`, `--field`.

**Doc delta** — `docs/guides/E2E-TESTING.md` gains *The code-flow token path (when ROPC is
structurally wrong)*: why ROPC cannot carry `auth_time`, the flag table, the issuer discussion
below, the three handled gotchas, and E9's contract.

## Tests

No unit surface — the deliverable is a rig-facing script and its proof is **E9** (below), run
against the live rig. Nothing else in the repo changed, so the standing gates are unmoved and were
re-confirmed rather than re-derived: `opa test infra/opa/policies/` **367/367**; `./gradlew build`
green; the local Sonar gate does not apply (**zero** changed `.java` files). No existing runner,
collection or `mint_token()` was touched — `git status` showed exactly the new script and the
guide.

## Architecture review + refactor

Path: **inline self-review** (the ★ gate). This ticket adds no library, service or policy code, so
the lenses that matter are the tool's own honesty.

- **Fail-closed, in a test tool's terms.** A miner cannot widen a decision; what it *can* do is
  hand back a token that is weaker than the caller believes and make a **deny** cell pass for the
  wrong reason — and unelevated denies are exactly what C's negatives look like, so that failure
  would be invisible. **The refactor this review applied:** the miner now decodes the minted token
  and **aborts** when `--acr` was requested and the token does not carry it, instead of printing an
  `aal1` token. Measured as a real risk rather than a hypothetical: Keycloak's essential-`acr`
  request is a *request*, and the realm-side condition that honours it is one config key away from
  not firing (T1's own first probe run proved that class of drift).
- **The named widening for this ticket, and why it cannot happen.** *A miner that silently
  degrades* — closed by the check above. *A miner that appears to elevate without a factor* — the
  only path to `acr: aal2` is the OTP form, and the wrong-secret case aborts (E9e asserts it, so
  the TOTP computation is proven load-bearing rather than decorative). *A token that only works
  because the gateway is lax* — E9d pairs the 200 with a **tampered-token 401 control**, so the
  200 is evidence the gateway accepted a valid token rather than that it validates nothing.
- **Wiring.** The named consumer is T6's runner; every flag has a cell that exercises it
  (`--acr` → E9b, `--no-max-age` + `--cookie-jar` → E9c, `--otp-secret` → E9e, `--issuer` → E9d),
  and each was exercised through its **non-happy path** as well: a spent TOTP code, a wrong secret,
  a tampered token, a host-issuer token.
- **Boundary.** `mint_token()` and every existing runner and collection are byte-identical; no
  dependency was added (the maintainer's bare shell runs it); no realm change (T1 owns those). The
  script is additive — nothing consumes it until T6.
- **Pattern reuse.** T1's throwaway I5 probe is the prototype and its shape is reused rather than
  reinvented: the forced-cookie session, the form discrimination **on input names** (the OTP page
  carries a "Forgot password?" link, so a substring test for "password" answers the OTP form with
  a username), the offline TOTP, and the redirect-following loop. The repo's stdlib-only script
  convention (`augment_problem_json.py`, the runners' inline python3) is kept.
- **Nothing else was refactored.** The two behavioural changes below came out of *measurement*,
  not taste, and both are recorded as decisions.

## Integration / e2e

**E9 — green, 11 assertions, against the up rig** (`./deploy.sh down` first — T1 changed the realm
— then `ENABLE_OIDC=1 ENABLE_USER_SERVICE=1 ./deploy.sh up --pods 2`). The check itself is a
throwaway in-ticket script (scratchpad, not committed — T6's runner is the permanent consumer):

```
(a) acr == aal1                                                        PASS
(a) auth_time present and NUMERIC (1786698662)                         PASS
(b) acr == aal2 after TOTP                                             PASS
(b) auth_time is FRESH (1786698664 > 1786698662)                       PASS
(c) auth_time UNCHANGED from (b) (1786698664) — SSO reuse, no re-auth   PASS
(c) iat advanced (1786698667 > 1786698664) — a genuinely new token      PASS
(c) acr still aal2 (the level is remembered, the INSTANT is not)        PASS
(d) iss == http://keycloak:8888/realms/catalog-demo                     PASS
(d) a member read through the gateway -> 200                            PASS
(d) control: a tampered token is REJECTED (401)                         PASS
(e) a wrong TOTP secret FAILS the mint                                  PASS
```

(c) is the cell the whole loop-prevention argument rests on: same cookie jar, no `max_age`, **no
prompt at all**, and the token comes back with the *original* `auth_time` and a newer `iat`. A
client that "re-authenticates" without forwarding `max_age` has moved nothing that the freshness
control reads.

## Decisions

- **Seam deviation — Keycloak enforces TOTP codes as ONE-TIME, and the step-up e2e trips it
  routinely.** Two elevations inside the same 30-second window fail with a bare *"Invalid
  authenticator code."* on the re-rendered form — byte-identical to what a wrong secret produces,
  and nothing in the decomposition anticipated it. It is not an edge case here: E2, the drill's
  positive control and E3 all elevate within a couple of minutes of each other. The miner now
  detects the re-rendered OTP form, **waits for the next TOTP window**, and retries (bounded at
  three submissions, so a genuinely wrong secret still aborts rather than looping). Measured, not
  reasoned: reproduced deliberately, and the wait path is exercised in a back-to-back pair.
- **Seam deviation — `KC_HOSTNAME_URL` does nothing on Keycloak 26.** `infra/compose.keycloak.yaml`
  sets it (and comments that it pins the issuer), but it is not a Keycloak 26 option; the issuer is
  derived from the request's `Host` header, which `KC_HOSTNAME_STRICT=false` permits. Minting
  through the host port therefore stamps `iss: http://localhost:28888`. The miner connects to the
  host port while presenting the **in-network** authority as `Host`, so its tokens carry the same
  `iss` every in-network ROPC token carries — and it needs no container on the compose network.
  Its flip side is handled explicitly: every URL Keycloak renders then names `keycloak:8888` and is
  rebased onto the connect URL before being followed.
- **…and the gateway does not actually enforce `iss`** (measured 2026-08-14): APISIX's
  `openid-connect` plugin validates the **signature** against the realm JWKS, so a host-issuer
  token passes today too — verified in both directions, with a tampered token 401-ing to prove the
  check is live at all. The miner takes issuer parity **by construction** rather than leaving it to
  the validator's leniency, and `--issuer <connect URL>` opts out. Stated because the guide's
  in-network caveat reads as though the issuer is the enforced thing; on this rig the enforced
  thing is the signature. *(The caveat section was left as T1/earlier prose owns it; the correction
  lives in the new section, cross-referenced.)*
- **The cookie jar is written with `secure=False` on purpose.** `http.cookiejar` is used for the
  file format only; the send path is hand-rolled, because the stdlib's would honour `Secure` and
  withhold Keycloak's login cookies over plain http — the recorded *"Restart login cookie not
  found"* 400 (mx-afd666 / mx-a85001).
- **The miner's row in `scripts/postman/README.md` rides T6**, which owns that file's registry and
  runner rows (subsection ownership, the B lesson). T5 owns only the E2E-TESTING section.

## Commit

`feat(step-up-elevation): the stdlib code-flow token miner (T5)` — `mint-code-flow-token.py` + the
E2E-TESTING code-flow section + this STATUS note, on `feature/void3110/step-up-elevation`.
