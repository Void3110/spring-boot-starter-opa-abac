import { describe, expect, it } from 'vitest'
import {
  badgesFor,
  chipState,
  elevationClaims,
  formatRemaining,
  isElevated,
  loaOf,
} from './stepup'

/** Build a JWT-shaped string with the given payload (signature irrelevant — nothing verifies here). */
function token(payload: Record<string, unknown>): string {
  const b64 = btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${b64}.signature`
}

describe('U4 — the token-claims decode is tolerant', () => {
  it('reads acr and a numeric auth_time', () => {
    expect(elevationClaims(token({ acr: 'aal2', auth_time: 1786973341 }))).toEqual({
      acr: 'aal2',
      authTime: 1786973341,
    })
  })

  // Tokens minted without the realm's basic/acr client scopes carry neither claim — the state slice C
  // had to fix. The chip must degrade, never throw.
  it.each([
    ['acr absent', { auth_time: 1786973341 }, { authTime: 1786973341 }],
    ['auth_time absent', { acr: 'aal2' }, { acr: 'aal2' }],
    ['auth_time non-numeric', { acr: 'aal2', auth_time: 'yesterday' }, { acr: 'aal2' }],
    ['auth_time null', { acr: 'aal2', auth_time: null }, { acr: 'aal2' }],
    ['neither', {}, {}],
  ])('%s → the field is simply absent', (_label, payload, expected) => {
    expect(elevationClaims(token(payload))).toEqual(expected)
  })

  it.each([
    ['an undecodable token', 'not-a-jwt'],
    ['a token with no payload segment', 'header'],
    ['an empty string', ''],
    ['undefined', undefined],
  ])('%s → {} and never a throw', (_label, value) => {
    expect(() => elevationClaims(value as string | undefined)).not.toThrow()
    expect(elevationClaims(value as string | undefined)).toEqual({})
  })

  it('maps acr through the app-side LoA table, unknown → 0', () => {
    expect(loaOf('aal1')).toBe(1)
    expect(loaOf('aal2')).toBe(2)
    // Unknown reads as NOT elevated — the fail-closed direction for a prediction.
    expect(loaOf('aal3')).toBe(0)
    expect(loaOf(undefined)).toBe(0)
  })
})

describe('U5 — the window math and the chip state', () => {
  const base = { authTime: 1000, window: 300, challengeSeen: true, nowSeconds: 1000 }

  it('elevated with a known window counts down', () => {
    expect(chipState({ ...base, loa: 2, nowSeconds: 1100 })).toEqual({
      kind: 'elevated',
      remaining: 200,
    })
  })

  it('flips to lapsed at zero and stays there after', () => {
    // AT the boundary is already lapsed: remaining must be strictly positive to still read "elevated".
    expect(chipState({ ...base, loa: 2, nowSeconds: 1300 })).toEqual({ kind: 'lapsed' })
    expect(chipState({ ...base, loa: 2, nowSeconds: 5000 })).toEqual({ kind: 'lapsed' })
  })

  it('never renders a negative remaining', () => {
    const state = chipState({ ...base, loa: 2, nowSeconds: 9999 })
    expect(state.kind).toBe('lapsed')
    expect(state).not.toHaveProperty('remaining')
  })

  it('elevated with NO window says so instead of inventing a number', () => {
    expect(chipState({ ...base, loa: 2, window: null })).toEqual({ kind: 'elevated-unknown-window' })
    // Same when the token carries no auth_time to count from.
    expect(chipState({ ...base, loa: 2, authTime: undefined })).toEqual({
      kind: 'elevated-unknown-window',
    })
  })

  it('not elevated with a challenge seen this session → "not elevated"', () => {
    expect(chipState({ ...base, loa: 1, challengeSeen: true })).toEqual({ kind: 'not-elevated' })
  })

  it('not elevated and no challenge seen → hidden (members and viewers see nothing new)', () => {
    expect(chipState({ ...base, loa: 1, challengeSeen: false })).toEqual({ kind: 'hidden' })
    expect(chipState({ ...base, loa: 0, challengeSeen: false })).toEqual({ kind: 'hidden' })
  })

  it('formats the countdown as m:ss and clamps', () => {
    expect(formatRemaining(300)).toBe('5:00')
    expect(formatRemaining(61)).toBe('1:01')
    expect(formatRemaining(9)).toBe('0:09')
    expect(formatRemaining(-5)).toBe('0:00')
  })

  it('only the two elevated states count as elevated for the badge prediction', () => {
    expect(isElevated({ kind: 'elevated', remaining: 10 })).toBe(true)
    expect(isElevated({ kind: 'elevated-unknown-window' })).toBe(true)
    expect(isElevated({ kind: 'lapsed' })).toBe(false)
    expect(isElevated({ kind: 'not-elevated' })).toBe(false)
    expect(isElevated({ kind: 'hidden' })).toBe(false)
  })
})

describe('U6 — the badge predicate', () => {
  it('amber iff supervised ∧ production ∧ not elevated', () => {
    expect(badgesFor({ provenance: 'supervised', env: 'production', elevated: false })).toEqual({
      supervised: true,
      production: true,
      amber: true,
    })
  })

  it("a MEMBER's production catalog is never amber", () => {
    // K13: a member's production read needs no elevation at all, so warning them would be a lie.
    expect(badgesFor({ provenance: 'member', env: 'production', elevated: false })).toEqual({
      supervised: false,
      production: true,
      amber: false,
    })
  })

  it('a supervised production catalog while ELEVATED is not amber', () => {
    expect(badgesFor({ provenance: 'supervised', env: 'production', elevated: true }).amber).toBe(
      false,
    )
  })

  it('a supervised STAGING catalog is not amber', () => {
    expect(badgesFor({ provenance: 'supervised', env: 'staging', elevated: false })).toEqual({
      supervised: true,
      production: false,
      amber: false,
    })
  })

  it('absent _provenance is UNKNOWN: no supervised badge, never amber', () => {
    // Absence must not collapse into "member" — but it must not predict either.
    expect(badgesFor({ provenance: undefined, env: 'production', elevated: false })).toEqual({
      supervised: false,
      production: true,
      amber: false,
    })
  })

  it('reads a multi-valued env tag', () => {
    // The tag model allows string OR string[]; a single-valued key is the common case but not the rule.
    expect(badgesFor({ provenance: 'supervised', env: ['production'], elevated: false }).amber).toBe(
      true,
    )
    expect(badgesFor({ provenance: 'supervised', env: ['staging'], elevated: false }).amber).toBe(
      false,
    )
  })

  it('an untagged catalog is neither production nor amber', () => {
    expect(badgesFor({ provenance: 'supervised', env: undefined, elevated: false })).toEqual({
      supervised: true,
      production: false,
      amber: false,
    })
  })
})
