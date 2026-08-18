// @vitest-environment jsdom
//
// The restoration effect's DEFENSIVE arm — the one branch of the step-up flow that was verified by
// reading rather than by running.
//
// After a completed verification the console re-reads the view the challenge interrupted: the
// catalog, then the category if the user was a level deeper. The arm under test is what happens when
// that category read is STILL refused. Landing on the grid there would throw away the catalog already
// in hand and strand the user one level further out than the verification they just finished, so the
// effect keeps them on the catalog and lets its own load re-raise the challenge into the passive
// panel. The state is the structurally unreachable E12(b) — a verification that completed and did not
// help — which is why no e2e cell reaches it and why the happy path alone was proven live.
//
// Mutation-proven on the arm that matters: making the defensive branch land on the grid, or
// re-throwing the challenge as a hard failure, each fails a test here. Two other mutations of the
// effect survive and are EQUIVALENT rather than uncovered — the initial view is already
// `{ kind: 'catalogs' }`, so dropping the grid fallback changes nothing observable, and with
// `restore === null` a fall-through throws on property access before any call is made.
//
// This file is the ONLY jsdom one in the suite. The rest is pure-seam by design (no DOM, ~30ms), and
// the docblock above keeps it that way: jsdom is constructed for this file, not for the suite.
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const getCatalog = vi.fn()
const getCategory = vi.fn()

// Only the two reads the effect makes are behavioural; the rest of the module is stubbed so mounting
// Console does not fan out into the real fetch layer. `StepUpRequiredError` is kept REAL — the arm
// under test turns on `instanceof`, and a stubbed class would make the test pass for the wrong reason.
vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api')
  return {
    ...actual,
    getCatalog,
    getCategory,
    ensureUser: vi.fn().mockResolvedValue(undefined),
    listCatalogs: vi.fn().mockResolvedValue({ items: [], count: 0 }),
    listCategories: vi.fn().mockResolvedValue({ items: [], count: 0 }),
    listProducts: vi.fn().mockResolvedValue({ items: [], count: 0 }),
    listTeamTagDefinitions: vi.fn().mockResolvedValue([]),
    lookupTeamByTarget: vi.fn().mockResolvedValue(null),
  }
})

const { Console } = await import('./App')
import type { StepUpState } from './auth'
const { StepUpRequiredError, ApiError } = await import('./api')

const CATALOG = { id: 'cat-1', name: 'Production', _actions: {} }
// The real OIDC `state` shape (auth.ts StepUpState) — `v`/`stepUp` included so the test
// binds to the actual contract rather than a convenient subset.
const RESTORE: StepUpState = { v: 1, stepUp: true, catalogId: 'cat-1', categoryId: 'cty-1' }

// A user shaped the way `describeUser`/`useElevationChip` read it. No token is decoded here.
const USER = {
  profile: { sub: 'user-1', preferred_username: 'sup-demo', realm_access: { roles: [] } },
  access_token: '',
  expires_at: Math.floor(Date.now() / 1000) + 3600,
} as never

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  vi.clearAllMocks()
  window.sessionStorage.clear()
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

async function mount(restore: StepUpState | null) {
  await act(async () => {
    root.render(<Console user={USER} restore={restore} />)
  })
}

describe('the restoration effect', () => {
  it('lands on the CATEGORY when the restored read succeeds', async () => {
    getCatalog.mockResolvedValue(CATALOG)
    getCategory.mockResolvedValue({ id: 'cty-1', name: 'Widgets', _actions: {} })

    await mount(RESTORE)

    expect(getCatalog).toHaveBeenCalledWith('cat-1')
    expect(getCategory).toHaveBeenCalledWith('cat-1', 'cty-1')
    expect(container.textContent).toContain('Widgets')
  })

  it('stays on the CATALOG when the restored category is still refused', async () => {
    getCatalog.mockResolvedValue(CATALOG)
    getCategory.mockRejectedValue(
      new StepUpRequiredError(401, 'step up', {
        error: 'insufficient_user_authentication',
        description: 'a fresher factor is required',
        acrValues: 'aal2',
        maxAge: 300,
      }),
    )

    await mount(RESTORE)

    // The catalog already read is KEPT — the whole point of the arm. Landing on the grid would
    // strand the user one level further out than the verification they just completed.
    expect(container.textContent).toContain('Production')
    // …and it is not the grid: the grid renders no catalog heading for a page of zero catalogs.
    expect(getCategory).toHaveBeenCalledTimes(1)
  })

  it('falls back to the GRID when the catalog read itself fails', async () => {
    // Distinct from the arm above on purpose: the catalog is metadata-only and does not challenge,
    // so a failure there is genuine (deleted, revoked, offline) and the drill-in really is lost.
    getCatalog.mockRejectedValue(new ApiError(404, 'gone'))

    await mount(RESTORE)

    expect(container.textContent).not.toContain('Production')
    expect(getCategory).not.toHaveBeenCalled()
  })

  it('does nothing at all when this load is not a step-up callback', async () => {
    await mount(null)

    expect(getCatalog).not.toHaveBeenCalled()
    expect(getCategory).not.toHaveBeenCalled()
  })
})
