// @vitest-environment jsdom
//
// How many requests does opening ONE catalog make?
//
// This exists because the number is easy to get wrong by observation: `main.tsx` wraps the app in
// <StrictMode>, and React double-invokes effects in development, so every count taken by watching
// the dev-tools network tab is DOUBLE the real one. A backlog note recorded "teams x2, users x2,
// tag-definitions x2" from such a measurement; only ONE of those three was a real duplicate.
//
// The mount here is deliberately NOT wrapped in StrictMode, so these numbers are the production
// fan-out.
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const lookupTeamByTarget = vi.fn()
const listTeamTagDefinitions = vi.fn()
const listAllUsers = vi.fn()

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api')
  return {
    ...actual,
    lookupTeamByTarget,
    listTeamTagDefinitions,
    listAllUsers,
    getCatalog: vi.fn().mockResolvedValue({ id: 'cat-1', name: 'Production', _actions: {} }),
    listCategories: vi.fn().mockResolvedValue({ items: [], count: 0 }),
  }
})

const { CatalogDetail } = await import('./App')

const CATALOG = { id: 'cat-1', name: 'Production', _actions: {} } as never
const TEAM = { id: 'team-1', name: 'Production team', members: [], _actions: {} }

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  vi.clearAllMocks()
  lookupTeamByTarget.mockResolvedValue({ items: [TEAM], count: 1 })
  listTeamTagDefinitions.mockResolvedValue({ items: [] })
  listAllUsers.mockResolvedValue([])
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})
afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

describe('opening one catalog', () => {
  it('resolves the governing team exactly ONCE', async () => {
    await act(async () => {
      root.render(
        <CatalogDetail
          catalog={CATALOG}
          mySubject="user-1"
          elevated={false}
          passive={false}
          onVerify={() => {}}
          onHome={() => {}}
          onOpenCategory={() => {}}
        />,
      )
    })

    // The tag dictionary and the team panel both need the governing team. Resolving it twice is
    // one wasted round trip per catalog open, and it is the ONLY real duplicate of the three the
    // backlog recorded.
    expect(lookupTeamByTarget).toHaveBeenCalledTimes(1)
    expect(lookupTeamByTarget).toHaveBeenCalledWith('catalog', 'cat-1')
  })

  it('lists the tag dictionary and the user directory once each', async () => {
    await act(async () => {
      root.render(
        <CatalogDetail
          catalog={CATALOG}
          mySubject="user-1"
          elevated={false}
          passive={false}
          onVerify={() => {}}
          onHome={() => {}}
          onOpenCategory={() => {}}
        />,
      )
    })

    // These two were never duplicated in production — the x2 in the backlog note was StrictMode's
    // dev-only double-invoke. Pinned so a future refactor cannot quietly make them real duplicates.
    expect(listTeamTagDefinitions).toHaveBeenCalledTimes(1)
    expect(listAllUsers).toHaveBeenCalledTimes(1)
  })
})
