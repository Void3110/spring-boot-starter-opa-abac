/**
 * The RFC 9470 step-up challenge, client side.
 *
 * The server answers a supervisor's read of production content with `401` + a
 * `WWW-Authenticate` challenge naming what would satisfy it (ADR 0030). This module turns that
 * header into something the console can act on, and remembers the window the server advertised.
 *
 * **Deliberately dependency-free.** `parseChallenge` is a pure function over a string, so it is
 * unit-testable without a DOM, a fetch, or a token — and so that this module can never form an
 * import cycle with `api.ts`. (`StepUpRequiredError` lives next to its `ApiError` base in `api.ts`
 * for exactly that reason: a subclass evaluated at module load must see its base, and a cycle would
 * make that depend on which module the bundler happened to enter first — passing in tests and
 * throwing in the app.)
 */

/** What the server said would satisfy the challenge. */
export interface Challenge {
  /** The RFC 6750 error code — `insufficient_user_authentication` for a step-up. */
  error: string
  /** The server's own words, rendered verbatim in the locked panel. */
  description: string
  /** The authentication-context class the server wants, e.g. `aal2`. */
  acrValues: string
  /** How fresh the authentication must be, in seconds. */
  maxAge: number
}

/**
 * Where the last-seen `max_age` is remembered, so the elevation chip can count down a window it
 * **learned from the server** rather than one hardcoded in the client.
 *
 * <p>The `stepUp.` prefix is load-bearing: oidc-client-ts's `clearStaleState()` sweeps everything
 * under its own `oidc.` prefix on every `signinRedirect()` — which is precisely when we redirect for
 * a step-up, so a key parked there would be wiped at the exact moment it is needed.
 */
export const STEP_UP_MAX_AGE_KEY = 'stepUp.maxAge'

/** RFC 7230 `tchar` — the characters a bare token (a param name, or an unquoted value) may use. */
const TCHAR = /[A-Za-z0-9!#$%&'*+\-.^_`|~]/

/**
 * Parse a `WWW-Authenticate` value into a challenge the client can actually follow, or `null`.
 *
 * <p>`null` is the client's mirror of the server's "no half-formed challenge" rule: a challenge
 * missing `acr_values` or `max_age` — or one we cannot parse at all — is shown as a **plain** error
 * rather than as a [Verify] button that cannot work. Returning `null` is a normal outcome, not an
 * error path.
 *
 * <p>Handles the whole RFC 7235 `auth-param` grammar rather than splitting on commas, because
 * `error_description` is free text that routinely **contains commas and spaces**. Scheme and
 * parameter names are matched case-insensitively per the RFC.
 */
export function parseChallenge(header: string | null | undefined): Challenge | null {
  if (!header) return null
  const trimmed = header.trim()
  const split = /^(\S+)\s+([\s\S]*)$/.exec(trimmed)
  if (!split) return null
  if (split[1].toLowerCase() !== 'bearer') return null

  const params = parseAuthParams(split[2])
  if (!params) return null

  const acrValues = params['acr_values']
  const rawMaxAge = params['max_age']
  // Both are required: without acr_values we do not know what to ask for, and without max_age we do
  // not know how fresh it must be. Either one missing makes the challenge unfollowable.
  if (!acrValues || rawMaxAge === undefined) return null
  // Digits only — which also rejects a negative, a float, and `NaN`-by-way-of-empty-string, all of
  // which would otherwise become a nonsense countdown.
  if (!/^\d+$/.test(rawMaxAge)) return null
  const maxAge = Number(rawMaxAge)
  if (!Number.isFinite(maxAge)) return null

  return {
    error: params['error'] ?? '',
    description: params['error_description'] ?? '',
    acrValues,
    maxAge,
  }
}

/**
 * The `auth-param` list: `name=token` or `name="quoted string"`, comma-separated, any order.
 * Returns `null` on anything malformed — a half-read header must not become a half-formed challenge.
 */
function parseAuthParams(input: string): Record<string, string> | null {
  const params: Record<string, string> = {}
  let i = 0
  const skipWs = () => {
    while (i < input.length && (input[i] === ' ' || input[i] === '\t')) i++
  }

  while (i < input.length) {
    skipWs()
    // Empty list elements are legal in an RFC 7230 `#rule` ("a,,b"), so a stray comma is skipped.
    if (input[i] === ',') {
      i++
      continue
    }
    if (i >= input.length) break

    const nameStart = i
    while (i < input.length && TCHAR.test(input[i])) i++
    if (i === nameStart) return null
    const name = input.slice(nameStart, i).toLowerCase()

    skipWs()
    if (input[i] !== '=') return null
    i++
    skipWs()

    let value: string
    if (input[i] === '"') {
      i++
      let out = ''
      while (i < input.length && input[i] !== '"') {
        // A quoted-pair: the backslash escapes the next character, including a quote. The emitter's
        // isSafeParameter() refuses quotes and backslashes so it never produces one — this branch is
        // DEFENSIVE, and kept so nobody "simplifies" the parser down to whatever the emitter happens
        // to emit. A parser that trusts its peer is a parser that breaks on the first other peer.
        if (input[i] === '\\' && i + 1 < input.length) {
          out += input[i + 1]
          i += 2
        } else {
          out += input[i]
          i++
        }
      }
      if (i >= input.length) return null // unterminated quoted-string
      i++ // consume the closing quote
      value = out
    } else {
      const valueStart = i
      while (i < input.length && TCHAR.test(input[i])) i++
      if (i === valueStart) return null
      value = input.slice(valueStart, i)
    }

    params[name] = value

    skipWs()
    if (i < input.length && input[i] !== ',') return null
    if (i < input.length) i++
  }

  return params
}

/**
 * Remember the window the server just advertised, so the chip can count down a **learned** number.
 *
 * <p>Never throws: sessionStorage is unavailable in a few real browser configurations (private
 * modes, storage-partitioned iframes), and losing the countdown must not lose the challenge.
 */
export function rememberChallengeWindow(maxAge: number): void {
  try {
    sessionStorage.setItem(STEP_UP_MAX_AGE_KEY, String(maxAge))
  } catch {
    /* no session storage — the chip degrades to "elevated, window unknown" (T4) */
  }
}

/**
 * Forget the learned window — called when the SESSION ends or a different identity signs in.
 *
 * <p>The key deliberately lives outside oidc-client-ts's `oidc.` prefix so `clearStaleState()`
 * cannot sweep it at [Verify] time. The cost of that is it survives a *logout* too, which nobody
 * wants: a supervisor's leftover window would make the next identity's console claim "not elevated"
 * about a mechanism that does not apply to them. So the two lifecycle points that really do end the
 * session clear it explicitly — and the step-up redirect, pointedly, does not.
 */
export function forgetChallengeWindow(): void {
  try {
    sessionStorage.removeItem(STEP_UP_MAX_AGE_KEY)
  } catch {
    /* no session storage — nothing to forget */
  }
}

/** The last window the server advertised this session, or `null` when none was seen (or storage is off). */
export function lastChallengeWindow(): number | null {
  try {
    const raw = sessionStorage.getItem(STEP_UP_MAX_AGE_KEY)
    if (raw === null || !/^\d+$/.test(raw)) return null
    return Number(raw)
  } catch {
    return null
  }
}

// --- elevation: what the live token says, and what the chip may claim about it -----------------

/**
 * The app-side Level-of-Authentication map. **Display only** — the server has its own copy in the
 * policy data and is the one that decides. Keeping a second copy here is what lets the console
 * *predict*; it must never be mistaken for enforcement, and an `acr` this map does not know is 0
 * (unknown reads as "not elevated", the fail-closed direction for a prediction).
 */
const LOA: Record<string, number> = { aal1: 1, aal2: 2 }

/** The LoA an `acr` value maps to, or 0 for absent/unknown. */
export function loaOf(acr: string | undefined): number {
  return acr === undefined ? 0 : (LOA[acr] ?? 0)
}

/** The elevation-relevant claims of an access token. Both are absent on tokens minted without the
 *  realm's `basic`/`acr` client scopes, which is why every field is optional. */
export interface ElevationClaims {
  acr?: string
  authTime?: number
}

/**
 * Decode `acr` and `auth_time` from an access token. **Never throws**: an undecodable token, a
 * missing claim, or a non-numeric `auth_time` each yield `undefined` for that field rather than an
 * error — the chip is decoration, and a token this cannot read simply means "nothing to show".
 */
export function elevationClaims(accessToken: string | undefined): ElevationClaims {
  if (!accessToken) return {}
  try {
    const segment = accessToken.split('.')[1]
    if (!segment) return {}
    const payload = JSON.parse(
      atob(segment.replace(/-/g, '+').replace(/_/g, '/')),
    ) as Record<string, unknown>
    const acr = typeof payload.acr === 'string' ? payload.acr : undefined
    // auth_time is seconds-since-epoch. A string, a null, or a NaN is not a time we can count from.
    const rawAuthTime = payload.auth_time
    const authTime =
      typeof rawAuthTime === 'number' && Number.isFinite(rawAuthTime) ? rawAuthTime : undefined
    return { ...(acr !== undefined ? { acr } : {}), ...(authTime !== undefined ? { authTime } : {}) }
  } catch {
    return {}
  }
}

/** What the header chip shows. Five states, and `hidden` is the default for everyone else. */
export type ChipState =
  /** Nothing to say: not elevated, and no challenge has been seen this session. */
  | { kind: 'hidden' }
  /** Elevated, and we know the window the server advertised: count it down. */
  | { kind: 'elevated'; remaining: number }
  /** Elevated, but no window is known — say so rather than invent a number. */
  | { kind: 'elevated-unknown-window' }
  /** The advertised window has run out. A PREDICTION: content is never hidden on this. */
  | { kind: 'lapsed' }
  /** Not elevated, but production is in play (a challenge was seen this session). */
  | { kind: 'not-elevated' }

/**
 * The chip, from the live token plus the learned window.
 *
 * <p>`remaining` is `auth_time + max_age − now`, **clamped at zero** — a negative number never
 * renders. The countdown shows the *advertised* boundary, not the enforced one: the policy's `skew`
 * may still admit a read a few seconds past zero. That gap is exactly why the flip is amber and why
 * nothing on screen is hidden when it happens — the chip is a prediction, the next fetch is the
 * truth.
 */
export function chipState(input: {
  loa: number
  authTime?: number
  window: number | null
  challengeSeen: boolean
  nowSeconds: number
}): ChipState {
  const { loa, authTime, window, challengeSeen, nowSeconds } = input
  if (loa >= 2) {
    if (window === null || authTime === undefined) {
      return { kind: 'elevated-unknown-window' }
    }
    const remaining = authTime + window - nowSeconds
    return remaining > 0 ? { kind: 'elevated', remaining: Math.floor(remaining) } : { kind: 'lapsed' }
  }
  return challengeSeen ? { kind: 'not-elevated' } : { kind: 'hidden' }
}

/** Whether the chip is claiming elevation — the input to the amber badge prediction. */
export function isElevated(chip: ChipState): boolean {
  return chip.kind === 'elevated' || chip.kind === 'elevated-unknown-window'
}

/** `m:ss`, for the countdown. */
export function formatRemaining(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds))
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, '0')}`
}

// --- row badges -------------------------------------------------------------------------------

export interface BadgeState {
  /** This row is held by supervision, not membership. */
  supervised: boolean
  /** This row's governing tier is production. */
  production: boolean
  /** PREDICTION: opening this row's contents will ask for a second factor. */
  amber: boolean
}

/**
 * What to mark a catalog row with, from data the client already has.
 *
 * <p>The amber rule is `supervised ∧ production ∧ ¬elevated`, and each conjunct earns its place:
 * a **member's** production catalog is never amber (their read needs no elevation at all), a
 * supervised **staging** catalog is never amber (no tier to satisfy), and a supervised production
 * catalog **while elevated** is not amber (the second factor is already in hand).
 *
 * <p><b>Absent `_provenance` is UNKNOWN, not "member".</b> It yields no supervised badge and never
 * amber — the client cannot predict from a value the server declined to compute, and guessing here
 * would put a "verify to open" warning on rows nobody needs to verify for.
 */
export function badgesFor(input: {
  provenance: string | undefined
  env: string | string[] | undefined
  elevated: boolean
}): BadgeState {
  const supervised = input.provenance === 'supervised'
  const env = input.env
  const production = env === 'production' || (Array.isArray(env) && env.includes('production'))
  return { supervised, production, amber: supervised && production && !input.elevated }
}
