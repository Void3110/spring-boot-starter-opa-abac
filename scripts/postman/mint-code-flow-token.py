#!/usr/bin/env python3
"""Mint a catalog-demo access token through the scripted PKCE **authorization code** flow.

Why this exists at all: every other runner mints with ROPC (`mint_token()` in the shell
runners), and ROPC **structurally cannot carry `auth_time`** — there is no authentication
*event* in a direct grant, so Keycloak has no login instant to report (ADR 0030 §Context).
The step-up control is `now - auth_time <= max_age + skew`, so the step-up cells need a real
browser-shaped login. This script is that login, scripted: stdlib only, no venv, no
`requests`, no browser.

    # an ordinary (LoA 1) token for anna
    ./mint-code-flow-token.py --user sup-anna

    # an ELEVATED (LoA 2) token: acr_values=aal2 essential + max_age=0 -> TOTP prompt,
    # answered offline from the realm's seeded fixture secret
    ./mint-code-flow-token.py --user sup-anna --acr aal2 --cookie-jar /tmp/anna.jar

    # the loop-prevention negative: re-auth WITHOUT max_age, reusing the same SSO session.
    # Keycloak hands back a token with the SAME (stale) auth_time — no re-authentication
    # happened, so nothing about the freshness control moved. ADR 0030 §7, measured.
    ./mint-code-flow-token.py --user sup-anna --acr aal2 --no-max-age --cookie-jar /tmp/anna.jar

The access token goes to **stdout** (nothing else does), so a runner can capture it with
`TOKEN="$(./mint-code-flow-token.py ...)"`. Every diagnostic goes to stderr.

Two things about this rig that are easy to get wrong:

1. **Secure cookies over plain http.** Keycloak sets `AUTH_SESSION_ID` / `KEYCLOAK_IDENTITY`
   with the `Secure` flag. A well-behaved http client silently withholds them on a plain-http
   URL, and Keycloak then answers the *login-actions* POST with a misleading
   `400 "Restart login cookie not found"` — which reads like a flow bug and is not one.
   This script drives the `Cookie` header **by hand** and persists the jar with `secure=False`
   on purpose (Mulch `opa-abac-rig-deploy-ops` mx-afd666 / mx-a85001).

2. **The issuer follows the Host header.** This script connects to Keycloak's *host* port but
   presents the *in-network* authority (`--issuer`, default `http://keycloak:8888`), so the
   token it mints carries the same `iss` every other runner's in-network ROPC token does.
   That is why the miner needs no container on the compose network. The flip side is that
   every URL Keycloak then renders (form actions, redirects) names `keycloak:8888`, which is
   unreachable from the host — so this script rebases each one onto `--keycloak` before
   following it. The step-up runner's E9 preflight asserts both halves: the `iss` value, and
   a 200 through the gateway (with a tampered-token 401 as the control).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_KEYCLOAK = os.environ.get("KEYCLOAK_BASE_URL", "http://localhost:28888")
# The authority APISIX discovers and validates. Presented as the Host header so the minted
# token's `iss` matches it — see ForcedCookieSession.request().
DEFAULT_ISSUER = os.environ.get("KEYCLOAK_ISSUER_BASE_URL", "http://keycloak:8888")
DEFAULT_REALM = os.environ.get("KEYCLOAK_REALM", "catalog-demo")
DEFAULT_CLIENT = os.environ.get("KEYCLOAK_CLIENT", "catalog-spa")
DEFAULT_REDIRECT = "http://localhost:3000/cb"
# The realm export seeds this TOTP secret on sup-anna. It is public on purpose: a demo realm
# whose every fixture password is already committed. The HMAC key is the secret's raw ASCII
# bytes (measured against an accepted login — Keycloak does NOT base32-decode `secretData`).
DEFAULT_OTP_SECRET = "stepupdemofixture123"

FORM_ACTION_RE = re.compile(r'<form[^>]+action="([^"]+)"', re.I)
FORM_ERROR_RE = re.compile(
    r'<span[^>]*id="input-error[^"]*"[^>]*>(.*?)</span>'
    r'|kc-feedback-text"[^>]*>(.*?)<', re.S | re.I)
MAX_FORM_STEPS = 10
MAX_OTP_SUBMISSIONS = 3
TOTP_PERIOD = 30


def totp(secret: str, at: float | None = None, period: int = TOTP_PERIOD, digits: int = 6) -> str:
    """RFC 6238 TOTP over HMAC-SHA1 — the realm's `otpPolicy*` settings, written out.

    `infra/keycloak/realm-export.json` pins otpPolicyType=totp, HmacSHA1, 6 digits, period 30,
    so this computation has a written contract rather than a default it has to rediscover.
    """
    counter = int((time.time() if at is None else at) // period)
    mac = hmac.new(secret.encode(), struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = mac[-1] & 0x0F
    code = struct.unpack(">I", mac[offset:offset + 4])[0] & 0x7FFFFFFF
    return str(code % (10 ** digits)).zfill(digits)


def wait_for_next_totp_window(period: int = TOTP_PERIOD) -> None:
    """Block until the current TOTP window rolls over.

    Keycloak enforces **one-time use** per TOTP code (`UserCredentialManager` refuses a code
    already consumed for the credential), so two elevations inside the same 30-second window
    fail with a bare "Invalid authenticator code." on the re-rendered form — which looks exactly
    like a wrong secret and is not. The step-up e2e elevates several times in quick succession
    (E2, the drill's positive control, E3), so this is a *normal* path here, not an edge case.
    """
    now = time.time()
    remaining = period - (now % period) + 1.0
    print(f"    ... the TOTP code for this window is already spent; waiting "
          f"{remaining:.0f}s for the next one", file=sys.stderr)
    time.sleep(remaining)


class ForcedCookieSession:
    """A minimal http session that sends its cookies unconditionally.

    Deliberately *not* `http.cookiejar`'s send path: that one honours the `Secure` flag and
    would withhold Keycloak's login cookies over plain http (gotcha 1 in the module docstring).
    `http.cookiejar` is still used for the on-disk jar, so `--cookie-jar` files are ordinary
    Mozilla-format cookie files.
    """

    def __init__(self, base: str, issuer: str, timeout: float = 20.0) -> None:
        self.base = base.rstrip("/")
        self.issuer = issuer.rstrip("/")
        self.timeout = timeout
        self.cookies: dict[str, str] = {}

    # --- jar persistence -----------------------------------------------------
    def load(self, path: str) -> None:
        if not path or not os.path.exists(path):
            return
        jar = http.cookiejar.MozillaCookieJar(path)
        try:
            jar.load(ignore_discard=True, ignore_expires=True)
        except (http.cookiejar.LoadError, OSError) as error:
            # An empty or unreadable jar is a cold cache, not a failure — `mktemp` hands out an
            # empty file, which the Netscape parser rejects outright. Starting fresh is safe: the
            # only thing lost is SSO reuse, and the caller that DEPENDS on reuse (E3) asserts the
            # auth_time equality itself, so a silently-cold jar cannot make that cell pass.
            print(f"    ... starting a fresh cookie jar ({error})", file=sys.stderr)
            return
        for cookie in jar:
            self.cookies[cookie.name] = cookie.value or ""

    def save(self, path: str) -> None:
        if not path:
            return
        host = urllib.parse.urlparse(self.base).hostname or "localhost"
        jar = http.cookiejar.MozillaCookieJar(path)
        for name, value in self.cookies.items():
            jar.set_cookie(http.cookiejar.Cookie(
                version=0, name=name, value=value, port=None, port_specified=False,
                domain=host, domain_specified=False, domain_initial_dot=False,
                path="/", path_specified=True,
                # secure=False ON PURPOSE: the jar is a local test artifact, and a stdlib
                # client re-reading it must be willing to send these over plain http.
                secure=False, expires=int(time.time()) + 86400,
                discard=False, comment=None, comment_url=None, rest={}))
        jar.save(ignore_discard=True, ignore_expires=True)

    # --- transport -----------------------------------------------------------
    def _absorb(self, response) -> None:
        for header in response.headers.get_all("Set-Cookie") or []:
            name, _, rest = header.partition("=")
            self.cookies[name.strip()] = rest.split(";", 1)[0]

    def request(self, url: str, data: dict | None = None) -> tuple[int, dict, str]:
        # THE ISSUER-PARITY MOVE. Keycloak 26 derives its frontend URL — and therefore the `iss`
        # claim — from the request's Host header (`KC_HOSTNAME_STRICT=false` is what decides this;
        # the compose file's `KC_HOSTNAME_URL` is not a Keycloak 26 option and does nothing).
        # Minting through the host port with the host's own Host header would stamp
        # `iss: http://localhost:28888`; connecting to the same port while presenting the
        # IN-NETWORK authority stamps the issuer every other runner's ROPC token carries.
        # Measured on this rig (2026-08-14): APISIX validates the SIGNATURE against the realm
        # JWKS and does not itself enforce `iss`, so a host-issuer token happens to pass today
        # too — parity is taken here by construction rather than left to the validator's
        # leniency, and it is what lets the miner run from the host with no container on the
        # compose network. `--issuer <the connect URL>` opts out.
        headers = {"User-Agent": "opa-abac-code-flow-miner",
                   "Host": urllib.parse.urlparse(self.issuer).netloc}
        if self.cookies:
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        request = urllib.request.Request(url, data=body, headers=headers)

        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_args, **_kwargs):  # follow them by hand
                return None

        opener = urllib.request.build_opener(NoRedirect)
        try:
            response = opener.open(request, timeout=self.timeout)
            self._absorb(response)
            return response.status, dict(response.getheaders()), response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as error:
            self._absorb(error)
            return error.code, dict(error.getheaders()), error.read().decode("utf-8", "replace")

    def rebase(self, url: str) -> str:
        """Point a Keycloak-rendered absolute URL back at the host port we can actually reach.

        The flip side of the Host-header move above: every URL Keycloak renders (form actions,
        redirects) now names the in-network authority `keycloak:8888`, which does not resolve
        from the host. Only Keycloak's own URLs are rebased — the redirect URI is matched
        before this is ever called.
        """
        parsed = urllib.parse.urlparse(url)
        if not parsed.scheme:
            return urllib.parse.urljoin(self.base + "/", url)
        if f"{parsed.scheme}://{parsed.netloc}" == self.base:
            return url
        rebased = self.base + parsed.path
        return rebased + ("?" + parsed.query if parsed.query else "")


def decode_claims(token: str) -> dict:
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def mint(args) -> dict:
    session = ForcedCookieSession(args.keycloak, args.issuer, timeout=args.timeout)
    if args.cookie_jar:
        session.load(args.cookie_jar)

    auth_url = f"{session.base}/realms/{args.realm}/protocol/openid-connect/auth"
    token_url = f"{session.base}/realms/{args.realm}/protocol/openid-connect/token"

    verifier = base64.urlsafe_b64encode(os.urandom(40)).decode().rstrip("=")
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")

    params = {
        "client_id": args.client,
        "response_type": "code",
        "scope": args.scope,
        "redirect_uri": args.redirect_uri,
        "state": base64.urlsafe_b64encode(os.urandom(9)).decode(),
        "nonce": base64.urlsafe_b64encode(os.urandom(9)).decode(),
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    if args.acr:
        # Both halves matter: `acr_values` is what the realm's level-2 condition reads, and the
        # essential `claims` request is what makes Keycloak refuse to hand back a weaker acr
        # rather than quietly downgrading (RFC 9470's "the client asks for a level").
        params["acr_values"] = args.acr
        params["claims"] = json.dumps({"id_token": {"acr": {"essential": True, "values": [args.acr]}}})
    if args.max_age is not None:
        # max_age=0 forces a real re-authentication, which is what makes `auth_time` fresh.
        # Omitting it (--no-max-age) is E3's whole point: the SSO session answers instead, and
        # the token comes back carrying the ORIGINAL login instant.
        params["max_age"] = str(args.max_age)

    status, headers, body = session.request(f"{auth_url}?{urllib.parse.urlencode(params)}")

    code = None
    submitted_otps: set[str] = set()
    for _ in range(MAX_FORM_STEPS):
        if status in (301, 302, 303, 307, 308):
            location = headers.get("Location", "")
            if location.startswith(args.redirect_uri.split("?")[0]):
                query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
                if "error" in query:
                    raise SystemExit(
                        f"authorization refused: {query.get('error', [''])[0]} "
                        f"{query.get('error_description', [''])[0]}")
                code = query.get("code", [None])[0]
                break
            status, headers, body = session.request(session.rebase(location))
            continue

        match = FORM_ACTION_RE.search(body)
        if not match:
            raise SystemExit(
                f"expected a login form or a redirect, got status {status}:\n{body[:600]}")
        action = session.rebase(html.unescape(match.group(1)))
        # Discriminate on the form's own input NAMES, never on the word "password" appearing
        # somewhere in the page — the OTP page carries a "Forgot password?" link, so a naive
        # substring test answers the OTP form with a username and password.
        if 'name="otp"' in body:
            # The OTP page re-rendering IS the error signal — Keycloak answers a rejected code
            # with the same form plus an inline message, at status 200.
            for groups in FORM_ERROR_RE.findall(body):
                text = re.sub(r"<[^>]+>", "", "".join(groups)).strip()
                if text:
                    print(f"    ... Keycloak rejected the last code: {text}", file=sys.stderr)
            if len(submitted_otps) >= MAX_OTP_SUBMISSIONS:
                raise SystemExit(
                    f"the TOTP secret was rejected {len(submitted_otps)} times across as many "
                    f"windows — check --otp-secret against the realm export's seeded credential")
            otp = totp(args.otp_secret)
            if otp in submitted_otps:
                wait_for_next_totp_window()
                otp = totp(args.otp_secret)
            submitted_otps.add(otp)
            form = {"otp": otp, "login": "Sign In"}
            print("    ... answering the TOTP prompt", file=sys.stderr)
        elif 'name="password"' in body:
            form = {"username": args.user, "password": args.password, "credentialId": ""}
            print("    ... answering the password form", file=sys.stderr)
        else:
            raise SystemExit(f"unrecognised form at status {status}:\n{body[:800]}")
        status, headers, body = session.request(action, data=form)

    if not code:
        raise SystemExit(f"no authorization code after {MAX_FORM_STEPS} steps (status {status})")

    status, _, body = session.request(token_url, data={
        "grant_type": "authorization_code",
        "client_id": args.client,
        "code": code,
        "redirect_uri": args.redirect_uri,
        "code_verifier": verifier,
    })
    if status != 200:
        raise SystemExit(f"token exchange failed with {status}: {body[:600]}")

    if args.cookie_jar:
        session.save(args.cookie_jar)
    return json.loads(body)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Mint a catalog-demo access token via the scripted PKCE code flow.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="The access token is printed to stdout; everything else goes to stderr.")
    parser.add_argument("--user", default="sup-anna", help="realm persona (default: sup-anna)")
    parser.add_argument("--password", default=None,
                        help="password (default: the same string as --user, the fixture convention)")
    parser.add_argument("--otp-secret", default=DEFAULT_OTP_SECRET,
                        help="the seeded TOTP secret used to answer a level-2 prompt")
    parser.add_argument("--acr", default=None, metavar="AAL",
                        help="request this acr (e.g. aal2) as an ESSENTIAL claim; without it the "
                             "flow is an ordinary login and settles at aal1")
    parser.add_argument("--max-age", type=int, default=None, metavar="SECONDS",
                        help="OIDC max_age (default: 0 when --acr is given, absent otherwise)")
    parser.add_argument("--no-max-age", action="store_true",
                        help="never send max_age — the loop-prevention negative: an existing SSO "
                             "session answers and auth_time stays STALE")
    parser.add_argument("--cookie-jar", default=None, metavar="FILE",
                        help="persist/reuse the Keycloak SSO session in a Mozilla-format cookie "
                             "file (cross-invocation SSO reuse is what E3/E9c measure)")
    parser.add_argument("--keycloak", default=DEFAULT_KEYCLOAK,
                        help=f"Keycloak base URL to CONNECT to (default: {DEFAULT_KEYCLOAK})")
    parser.add_argument("--issuer", default=DEFAULT_ISSUER,
                        help="the authority presented as the Host header, and therefore the `iss` "
                             f"the token carries (default: {DEFAULT_ISSUER} — the one APISIX "
                             "validates; pass --issuer <the connect URL> to mint a host-issuer token)")
    parser.add_argument("--realm", default=DEFAULT_REALM)
    parser.add_argument("--client", default=DEFAULT_CLIENT,
                        help=f"public PKCE client (default: {DEFAULT_CLIENT})")
    parser.add_argument("--redirect-uri", default=DEFAULT_REDIRECT,
                        help="must match a redirect URI registered on the client")
    parser.add_argument("--scope", default="openid")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--print-otp", action="store_true",
                        help="print the current TOTP code for --otp-secret and exit. The realm's "
                             "DIRECT-GRANT flow also demands a code from any identity that owns a "
                             "factor, so the ROPC runners need this for sup-anna — and the secret "
                             "and the RFC 6238 parameters stay in one place")
    parser.add_argument("--show-claims", action="store_true",
                        help="print the decoded access-token claims to stderr")
    parser.add_argument("--field", default="access_token",
                        help="token-response field(s) to print, comma-separated, one per line "
                             "(default: access_token)")
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    if args.print_otp:
        print(totp(args.otp_secret))
        return 0
    if args.password is None:
        args.password = args.user
    if args.no_max_age:
        if args.max_age is not None:
            raise SystemExit("--max-age and --no-max-age are mutually exclusive")
        args.max_age = None
    elif args.max_age is None and args.acr:
        args.max_age = 0

    described = f"acr={args.acr or '(none)'} max_age={'(omitted)' if args.max_age is None else args.max_age}"
    print(f"==> code flow for {args.user} ({described}) against {args.keycloak}", file=sys.stderr)

    tokens = mint(args)
    claims = decode_claims(tokens["access_token"])
    print(f"    minted: acr={claims.get('acr')!r} auth_time={claims.get('auth_time')!r} "
          f"iat={claims.get('iat')!r} iss={claims.get('iss')!r}", file=sys.stderr)
    if args.show_claims:
        print(json.dumps(claims, indent=2, sort_keys=True), file=sys.stderr)

    # FAIL CLOSED, in a test tool's own terms: never hand back a token that does not satisfy the
    # level the caller asked for. Keycloak is *asked* for an essential `acr`, but if the realm's
    # level-2 condition ever stopped firing it would answer with an ordinary aal1 token and this
    # script would happily print it — and a step-up cell asserting a **deny** would then pass for
    # the wrong reason (unelevated denies are exactly what C's negatives look like). Checking the
    # minted claim turns that silent degradation into a loud abort.
    if args.acr and claims.get("acr") != args.acr:
        raise SystemExit(
            f"asked for acr={args.acr!r} but the minted token carries acr={claims.get('acr')!r} — "
            f"refusing to hand back an unelevated token")

    # `--field` takes a COMMA-SEPARATED list, printed one per line. One login, several fields: the
    # refresh-preservation check needs the access token AND the refresh token FROM THE SAME
    # AUTHENTICATION EVENT — two invocations would be two logins with two different `auth_time`s,
    # and comparing across them proves nothing (measured: it reads as a laundered elevation).
    fields = [f.strip() for f in args.field.split(",") if f.strip()]
    for field in fields:
        value = tokens.get(field)
        if not value:
            raise SystemExit(f"the token response carried no '{field}' field")
        print(value)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
