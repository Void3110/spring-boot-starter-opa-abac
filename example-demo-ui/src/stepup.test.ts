import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { parseChallenge, lastChallengeWindow, STEP_UP_MAX_AGE_KEY } from './stepup'

// The header the rig actually emits, captured off the wire (scripts/postman/run-step-up-matrix.sh's
// E1a and the demo world's E31d). Pinning the REAL bytes rather than a hand-written approximation is
// the point: this is the contract between the policy's emitter and this parser.
const LIVE =
  'Bearer error="insufficient_user_authentication", ' +
  'error_description="A second factor is required to read production content", ' +
  'acr_values="aal2", max_age="300"'

describe('U1 — the parser accepts the emitter\'s exact form', () => {
  it('parses the live challenge', () => {
    expect(parseChallenge(LIVE)).toEqual({
      error: 'insufficient_user_authentication',
      description: 'A second factor is required to read production content',
      acrValues: 'aal2',
      maxAge: 300,
    })
  })

  it('accepts the parameters in any order', () => {
    const shuffled =
      'Bearer max_age="300", acr_values="aal2", ' +
      'error_description="A second factor is required to read production content", ' +
      'error="insufficient_user_authentication"'
    expect(parseChallenge(shuffled)).toEqual(parseChallenge(LIVE))
  })

  it('matches the scheme and the parameter names case-insensitively (RFC 7235)', () => {
    const shouty = 'BEARER Error="e", Acr_Values="aal2", Max_Age="300"'
    expect(parseChallenge(shouty)).toMatchObject({ acrValues: 'aal2', maxAge: 300, error: 'e' })
  })

  it('keeps a description containing commas and spaces intact', () => {
    // The reason this parser is a tokenizer and not `header.split(',')`: the description is free text
    // and the server's wording is rendered verbatim in the locked panel.
    const commas =
      'Bearer error="insufficient_user_authentication", ' +
      'error_description="Verify again, with a second factor, to read this", ' +
      'acr_values="aal2", max_age="300"'
    expect(parseChallenge(commas)?.description).toBe(
      'Verify again, with a second factor, to read this',
    )
  })

  it('unescapes a quoted-pair', () => {
    // DEFENSIVE: the emitter's isSafeParameter() refuses quotes and backslashes, so it never produces
    // this. Kept so nobody simplifies the parser down to whatever today's peer happens to emit.
    const escaped = 'Bearer error_description="a \\"quoted\\" word", acr_values="aal2", max_age="300"'
    expect(parseChallenge(escaped)?.description).toBe('a "quoted" word')
  })

  it('accepts unquoted token values', () => {
    expect(parseChallenge('Bearer acr_values=aal2, max_age=300')).toMatchObject({
      acrValues: 'aal2',
      maxAge: 300,
    })
  })
})

describe('U2 — the parser refuses a challenge the client cannot follow', () => {
  // Each of these would otherwise become a [Verify] button that cannot possibly work. Returning null
  // is what makes the caller fall back to a plain, honest error — the client mirror of the server's
  // "no half-formed challenge" rule.
  const unfollowable: Array<[string, string | null | undefined]> = [
    ['missing max_age', 'Bearer error="e", acr_values="aal2"'],
    ['missing acr_values', 'Bearer error="e", max_age="300"'],
    ['non-numeric max_age', 'Bearer acr_values="aal2", max_age="soon"'],
    ['negative max_age', 'Bearer acr_values="aal2", max_age="-5"'],
    ['fractional max_age', 'Bearer acr_values="aal2", max_age="30.5"'],
    ['empty max_age', 'Bearer acr_values="aal2", max_age=""'],
    ['empty acr_values', 'Bearer acr_values="", max_age="300"'],
    ['a non-Bearer scheme', 'Basic acr_values="aal2", max_age="300"'],
    ['a scheme with no parameters', 'Bearer'],
    ['an unterminated quoted-string', 'Bearer acr_values="aal2, max_age="300"'],
    ['a parameter with no value', 'Bearer acr_values, max_age="300"'],
    ['an empty header', ''],
    ['whitespace only', '   '],
    ['a missing header (null)', null],
    ['a missing header (undefined)', undefined],
  ]

  it.each(unfollowable)('returns null for %s', (_label, header) => {
    expect(parseChallenge(header)).toBeNull()
  })

  it('accepts max_age=0 — zero is a real window, not a missing one', () => {
    // The boundary that must NOT be swept up by the falsy checks above.
    expect(parseChallenge('Bearer acr_values="aal2", max_age="0"')).toMatchObject({ maxAge: 0 })
  })
})

describe('the learned window', () => {
  const store = new Map<string, string>()

  beforeEach(() => {
    store.clear()
    vi.stubGlobal('sessionStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('is stored OUTSIDE the oidc. prefix', () => {
    // oidc-client-ts's clearStaleState() sweeps its own `oidc.` prefix on every signinRedirect —
    // which is exactly what [Verify] does — so a key parked there would be wiped at the moment it is
    // needed. This assertion is the guard on that.
    expect(STEP_UP_MAX_AGE_KEY.startsWith('oidc.')).toBe(false)
    expect(STEP_UP_MAX_AGE_KEY).toBe('stepUp.maxAge')
  })

  it('reads back what was remembered, and null when nothing was', () => {
    expect(lastChallengeWindow()).toBeNull()
    store.set(STEP_UP_MAX_AGE_KEY, '300')
    expect(lastChallengeWindow()).toBe(300)
  })

  it('never returns a fabricated number from a corrupt value', () => {
    store.set(STEP_UP_MAX_AGE_KEY, 'not-a-number')
    expect(lastChallengeWindow()).toBeNull()
  })

  it('survives sessionStorage being unavailable', () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw new Error('storage disabled')
      },
      setItem: () => {
        throw new Error('storage disabled')
      },
    })
    expect(() => lastChallengeWindow()).not.toThrow()
    expect(lastChallengeWindow()).toBeNull()
  })
})
