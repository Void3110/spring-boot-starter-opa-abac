// @vitest-environment jsdom
//
// U8 — the on-demand refresh is single-flight.
//
// The realm rotates refresh tokens (ADR 0035 §7): each one is single-use, and Keycloak answers a
// second use with `invalid_grant` AND detaches the session. A catalog view fans out several reads at
// once, each asking freshUser() for a valid token; oidc-client-ts does not de-duplicate signinSilent(),
// so without a shared in-flight refresh every caller after the first would replay the spent token and
// sign the user out mid-page.
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { getUser, signinSilent } = vi.hoisted(() => ({ getUser: vi.fn(), signinSilent: vi.fn() }))

vi.mock('oidc-client-ts', () => ({
  UserManager: class {
    getUser = getUser
    signinSilent = signinSilent
  },
  WebStorageStateStore: class {},
}))

import { freshUser } from './auth'

const EXPIRED = { expired: true, refresh_token: 'refresh-1', access_token: 'stale' }
const RENEWED = { expired: false, refresh_token: 'refresh-2', access_token: 'fresh' }

/** Every caller has read the store and reached the refresh decision. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

/** A refresh the test finishes by hand, so the callers provably overlap it. */
function pendingRefresh() {
  let finish!: (outcome: { user?: unknown; error?: Error }) => void
  const promise = new Promise((resolve, reject) => {
    finish = ({ user, error }) => (error ? reject(error) : resolve(user))
  })
  return { promise, finish }
}

describe('U8 — freshUser() refreshes once, however many callers', () => {
  beforeEach(() => {
    getUser.mockReset().mockResolvedValue(EXPIRED)
    signinSilent.mockReset()
  })

  it('N concurrent callers on an expired token share ONE refresh and get the same renewed user', async () => {
    const refresh = pendingRefresh()
    signinSilent.mockReturnValue(refresh.promise)

    const callers = Array.from({ length: 5 }, () => freshUser())
    await settle()
    refresh.finish({ user: RENEWED })

    const users = await Promise.all(callers)
    expect(signinSilent).toHaveBeenCalledTimes(1)
    for (const user of users) expect(user).toBe(RENEWED)
  })

  it('a failed refresh answers null to every caller, and the next call starts a new one', async () => {
    const refresh = pendingRefresh()
    signinSilent.mockReturnValueOnce(refresh.promise)

    const callers = Array.from({ length: 3 }, () => freshUser())
    await settle()
    refresh.finish({ error: new Error('invalid_grant') })

    expect(await Promise.all(callers)).toEqual([null, null, null])
    expect(signinSilent).toHaveBeenCalledTimes(1)

    signinSilent.mockResolvedValueOnce(RENEWED)
    expect(await freshUser()).toBe(RENEWED)
    expect(signinSilent).toHaveBeenCalledTimes(2)
  })

  it('a valid token is returned as is, with no refresh', async () => {
    getUser.mockResolvedValue(RENEWED)

    expect(await freshUser()).toBe(RENEWED)
    expect(signinSilent).not.toHaveBeenCalled()
  })
})
