import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// api.ts refreshes the token on demand before every call; the real auth module drags in
// oidc-client-ts and a browser session, neither of which this seam is about.
vi.mock('./auth', () => ({ freshUser: async () => ({ access_token: 'test-token' }) }))

import { ApiError, StepUpRequiredError, getCatalog } from './api'
import { STEP_UP_MAX_AGE_KEY } from './stepup'

const CHALLENGE =
  'Bearer error="insufficient_user_authentication", ' +
  'error_description="A second factor is required to read production content", ' +
  'acr_values="aal2", max_age="300"'

/** A minimal stand-in for the parts of Response that request() touches. */
function response(opts: {
  status: number
  body?: unknown
  headers?: Record<string, string>
  statusText?: string
}) {
  return {
    ok: opts.status >= 200 && opts.status < 300,
    status: opts.status,
    statusText: opts.statusText ?? 'Unauthorized',
    headers: new Headers(opts.headers ?? {}),
    json: async () => {
      if (!('body' in opts)) throw new SyntaxError('no JSON body')
      return opts.body
    },
  }
}

function respondWith(opts: Parameters<typeof response>[0]) {
  vi.stubGlobal('fetch', vi.fn(async () => response(opts)))
}

describe('U3 — request() classifies 401s honestly', () => {
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

  it('a 401 + STEP_UP_REQUIRED + a parseable challenge throws StepUpRequiredError', async () => {
    respondWith({
      status: 401,
      body: { detail: 'A second factor is required to read production content', errorCode: 'STEP_UP_REQUIRED' },
      headers: { 'WWW-Authenticate': CHALLENGE },
    })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown).toBeInstanceOf(StepUpRequiredError)
    expect(thrown.status).toBe(401)
    expect(thrown.challenge).toEqual({
      error: 'insufficient_user_authentication',
      description: 'A second factor is required to read production content',
      acrValues: 'aal2',
      maxAge: 300,
    })
    // Still an ApiError: a caller that knows nothing about step-up shows it in the ordinary error box.
    expect(thrown).toBeInstanceOf(ApiError)
  })

  it('and remembers the window the server advertised', async () => {
    respondWith({
      status: 401,
      body: { detail: 'nope', errorCode: 'STEP_UP_REQUIRED' },
      headers: { 'WWW-Authenticate': CHALLENGE },
    })

    await getCatalog('c1').catch(() => undefined)

    expect(store.get(STEP_UP_MAX_AGE_KEY)).toBe('300')
    // Never under the oidc. prefix, which clearStaleState() sweeps on every signinRedirect.
    expect([...store.keys()].some((k) => k.startsWith('oidc.'))).toBe(false)
  })

  it('a 401 with NO body keeps the existing "session expired" path', async () => {
    // A gateway 401 carries no problem body — the two 401s are distinguishable by construction.
    respondWith({ status: 401 })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown).toBeInstanceOf(ApiError)
    expect(thrown).not.toBeInstanceOf(StepUpRequiredError)
    expect(thrown.message).toContain('session expired')
  })

  it('a 401 + STEP_UP_REQUIRED with an UNPARSEABLE challenge degrades to a plain ApiError', async () => {
    // Better an honest 401 than a [Verify] button that cannot work. This is the wire half of U2.
    respondWith({
      status: 401,
      body: { detail: 'Production content needs a second factor', errorCode: 'STEP_UP_REQUIRED' },
      headers: { 'WWW-Authenticate': 'Bearer error="insufficient_user_authentication"' },
    })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown).toBeInstanceOf(ApiError)
    expect(thrown).not.toBeInstanceOf(StepUpRequiredError)
    expect(thrown.status).toBe(401)
  })

  it('a 401 + STEP_UP_REQUIRED with NO challenge header at all degrades likewise', async () => {
    // The cross-origin adopter's shape: the body arrives, the header is stripped to null by CORS.
    respondWith({ status: 401, body: { detail: 'nope', errorCode: 'STEP_UP_REQUIRED' } })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown).not.toBeInstanceOf(StepUpRequiredError)
    expect(thrown.status).toBe(401)
  })

  it('a 403 with a body is unchanged', async () => {
    respondWith({
      status: 403,
      body: { detail: 'Access denied', errorCode: 'ACCESS_DENIED' },
      statusText: 'Forbidden',
    })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown).toBeInstanceOf(ApiError)
    expect(thrown).not.toBeInstanceOf(StepUpRequiredError)
    expect(thrown.status).toBe(403)
    expect(thrown.message).toBe('Access denied')
  })

  it('captures errorCode WITHOUT reordering `detail ?? errorCode` — the message is still the detail', async () => {
    // The trap this guards: swapping the `??` operands to reach errorCode would make every error
    // message read "STEP_UP_REQUIRED" instead of the server's sentence. `detail` is always present on
    // a problem+json body, so it would also permanently shadow the code if read only through `detail`.
    respondWith({
      status: 401,
      body: { detail: 'A second factor is required to read production content', errorCode: 'STEP_UP_REQUIRED' },
      headers: { 'WWW-Authenticate': CHALLENGE },
    })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown.message).toBe('A second factor is required to read production content')
    expect(thrown.message).not.toBe('STEP_UP_REQUIRED')
  })

  it('falls back to errorCode when the body carries no detail (unchanged behaviour)', async () => {
    respondWith({ status: 422, body: { errorCode: 'TAG_NOT_DEFINED' }, statusText: 'Unprocessable' })

    const thrown = await getCatalog('c1').catch((e) => e)

    expect(thrown.message).toBe('TAG_NOT_DEFINED')
  })

  it('a successful read is untouched', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        statusText: 'OK',
        headers: new Headers(),
        json: async () => ({ id: 'c1', name: 'Demo catalog' }),
      })),
    )

    await expect(getCatalog('c1')).resolves.toEqual({ id: 'c1', name: 'Demo catalog' })
  })
})
