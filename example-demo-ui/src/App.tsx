import { useEffect, useState, useCallback } from 'react'
import {
  type AuthUser,
  completeLogin,
  currentUser,
  describeUser,
  login,
  logout,
} from './auth'
import { type Catalog, listCatalogs, ApiError } from './api'

type AuthState =
  | { phase: 'loading' }
  | { phase: 'anonymous' }
  | { phase: 'authenticated'; user: AuthUser }
  | { phase: 'error'; message: string }

// Guard against React 19 StrictMode double-invoking the bootstrap effect: a PKCE authorization code
// is single-use, so the second run would fail with "Code not valid". Resolve the redirect exactly
// once at module scope; the effect awaits the shared promise.
let bootstrap: Promise<AuthUser | null> | null = null

export function App() {
  const [auth, setAuth] = useState<AuthState>({ phase: 'loading' })

  // On load: complete the PKCE redirect if we came back with ?code=, else restore any session.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const resolve = async (): Promise<AuthUser | null> => {
      if (params.has('code') && params.has('state')) {
        const user = await completeLogin()
        window.history.replaceState({}, '', '/') // strip ?code=&state= from the URL
        return user
      }
      return currentUser()
    }
    // Memoize at module scope so StrictMode's second invocation reuses the same promise
    // instead of re-consuming the single-use authorization code.
    bootstrap ??= resolve()
    bootstrap
      .then((user) => setAuth(user ? { phase: 'authenticated', user } : { phase: 'anonymous' }))
      .catch((e) => setAuth({ phase: 'error', message: e instanceof Error ? e.message : String(e) }))
  }, [])

  if (auth.phase === 'loading') return <Splash>Restoring session…</Splash>
  if (auth.phase === 'error')
    return (
      <Splash>
        <span className="text-[var(--color-deny)]">Auth error: {auth.message}</span>
        <button className="mt-4 underline" onClick={() => login()}>
          Try logging in
        </button>
      </Splash>
    )
  if (auth.phase === 'anonymous') return <LoginScreen />
  return <Console user={auth.user} />
}

function Splash({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 text-[var(--color-muted)]">
      {children}
    </div>
  )
}

function LoginScreen() {
  return (
    <div className="flex h-full items-center justify-center p-6">
      <div className="w-full max-w-md rounded-2xl border border-[var(--color-line)] bg-[var(--color-surface)] p-8 shadow-sm">
        <div className="mb-6 flex items-center gap-3">
          <Logo />
          <div>
            <h1 className="text-lg font-semibold">Catalog Console</h1>
            <p className="text-sm text-[var(--color-muted)]">ABAC authorization with OPA</p>
          </div>
        </div>
        <p className="mb-6 text-sm leading-relaxed text-[var(--color-muted)]">
          Sign in to browse the product catalog. What you can see and do is decided per-resource by
          Open Policy Agent — the buttons you get are exactly the actions you’re allowed.
        </p>
        <button
          onClick={() => login()}
          className="w-full rounded-lg bg-[var(--color-brand)] px-4 py-2.5 font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)]"
        >
          Sign in with Keycloak
        </button>
        <p className="mt-6 text-center text-xs text-[var(--color-muted)]">
          Demo identities: viewer · editor · outsider · demo (password = username)
        </p>
      </div>
    </div>
  )
}

function Console({ user }: { user: AuthUser }) {
  const { username, roles } = describeUser(user)
  return (
    <div className="mx-auto flex h-full max-w-5xl flex-col">
      <header className="flex items-center justify-between border-b border-[var(--color-line)] px-6 py-4">
        <div className="flex items-center gap-3">
          <Logo />
          <span className="font-semibold">Catalog Console</span>
        </div>
        <div className="flex items-center gap-4">
          <div className="text-right">
            <div className="text-sm font-medium">{username}</div>
            <div className="text-xs text-[var(--color-muted)]">{roles.join(' · ') || 'no roles'}</div>
          </div>
          <button
            onClick={() => logout()}
            className="rounded-lg border border-[var(--color-line)] px-3 py-1.5 text-sm transition-colors hover:bg-[var(--color-canvas)]"
          >
            Switch identity
          </button>
        </div>
      </header>
      <main className="flex-1 overflow-auto px-6 py-6">
        <CatalogList />
      </main>
    </div>
  )
}

function CatalogList() {
  const [catalogs, setCatalogs] = useState<Catalog[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const page = await listCatalogs()
      setCatalogs(page.items)
    } catch (e) {
      if (e instanceof ApiError) setError(`${e.status} — ${e.message}`)
      else setError(e instanceof Error ? e.message : String(e))
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  if (error)
    return (
      <div className="rounded-lg border border-[var(--color-line)] bg-[var(--color-surface)] p-6 text-sm">
        <span className="text-[var(--color-deny)]">Failed to load catalogs: {error}</span>
      </div>
    )
  if (!catalogs) return <p className="text-sm text-[var(--color-muted)]">Loading catalogs…</p>
  if (catalogs.length === 0)
    return <p className="text-sm text-[var(--color-muted)]">No catalogs visible to you.</p>

  return (
    <div>
      <h2 className="mb-1 text-sm font-semibold uppercase tracking-wide text-[var(--color-muted)]">
        Catalogs
      </h2>
      <p className="mb-4 text-xs text-[var(--color-muted)]">
        Each card shows the <code>_actions</code> the policy grants you on that catalog.
      </p>
      <div className="grid gap-3 sm:grid-cols-2">
        {catalogs.map((c) => (
          <div
            key={c.id}
            className="rounded-xl border border-[var(--color-line)] bg-[var(--color-surface)] p-4 shadow-sm"
          >
            <div className="font-medium">{c.name}</div>
            {c.description && (
              <div className="mt-0.5 text-sm text-[var(--color-muted)]">{c.description}</div>
            )}
            <ActionBadges actions={c._actions} />
          </div>
        ))}
      </div>
    </div>
  )
}

/** The visible payoff of Phase 6: render each allowed/denied action as a badge. */
function ActionBadges({ actions }: { actions?: Record<string, boolean> }) {
  if (!actions || Object.keys(actions).length === 0)
    return (
      <div className="mt-3 text-xs italic text-[var(--color-muted)]">no affordances enriched</div>
    )
  return (
    <div className="mt-3 flex flex-wrap gap-1.5">
      {Object.entries(actions).map(([verb, allowed]) => (
        <span
          key={verb}
          className="inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium"
          style={{
            color: allowed ? 'var(--color-allow)' : 'var(--color-deny)',
            background: allowed ? '#f0fdf4' : '#fef2f2',
          }}
        >
          {allowed ? '✓' : '✕'} {verb}
        </span>
      ))}
    </div>
  )
}

function Logo() {
  return (
    <div
      className="flex h-9 w-9 items-center justify-center rounded-lg font-bold text-white"
      style={{ background: 'var(--color-brand)' }}
    >
      A
    </div>
  )
}
