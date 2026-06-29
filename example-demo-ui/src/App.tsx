import { useEffect, useState, useCallback } from 'react'
import {
  type AuthUser,
  completeLogin,
  currentUser,
  describeUser,
  login,
  logout,
} from './auth'
import {
  type Catalog,
  type Category,
  type Product,
  ApiError,
  deleteCategory,
  deleteProduct,
  getCatalog,
  listCatalogs,
  listCategories,
  listProducts,
  updateCategory,
  updateProduct,
} from './api'
import { ActionBadges, ActionButtons, Breadcrumbs, Logo, RoleChips } from './components'

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

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const resolve = async (): Promise<AuthUser | null> => {
      if (params.has('code') && params.has('state')) {
        const user = await completeLogin()
        window.history.replaceState({}, '', '/')
        return user
      }
      return currentUser()
    }
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
          Demo identities: editor (admin) · demo (editor) · viewer (read) · outsider (none) — password = username
        </p>
      </div>
    </div>
  )
}

// A tiny navigation stack: the catalog grid, a catalog's categories, a category's products.
type View =
  | { kind: 'catalogs' }
  | { kind: 'catalog'; catalog: Catalog }
  | { kind: 'category'; catalog: Catalog; category: Category }

function Console({ user }: { user: AuthUser }) {
  const { username, roles } = describeUser(user)
  const [view, setView] = useState<View>({ kind: 'catalogs' })

  return (
    <div className="mx-auto flex h-full max-w-5xl flex-col">
      <header className="flex items-center justify-between border-b border-[var(--color-line)] px-6 py-4">
        <button className="flex items-center gap-3" onClick={() => setView({ kind: 'catalogs' })}>
          <Logo />
          <span className="font-semibold">Catalog Console</span>
        </button>
        <div className="flex items-center gap-4">
          <div className="text-right">
            <div className="text-sm font-medium">{username}</div>
            <RoleChips roles={roles} />
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
        {view.kind === 'catalogs' && (
          <CatalogGrid onOpen={(catalog) => setView({ kind: 'catalog', catalog })} />
        )}
        {view.kind === 'catalog' && (
          <CatalogDetail
            catalog={view.catalog}
            onHome={() => setView({ kind: 'catalogs' })}
            onOpenCategory={(category) =>
              setView({ kind: 'category', catalog: view.catalog, category })
            }
          />
        )}
        {view.kind === 'category' && (
          <CategoryDetail
            catalog={view.catalog}
            category={view.category}
            onHome={() => setView({ kind: 'catalogs' })}
            onUp={() => setView({ kind: 'catalog', catalog: view.catalog })}
          />
        )}
      </main>
    </div>
  )
}

// Small async-data helper: load on mount + expose a reload.
function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = useCallback(() => {
    setError(null)
    fn()
      .then(setData)
      .catch((e) =>
        setError(e instanceof ApiError ? `${e.status} — ${e.message}` : String(e)),
      )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  useEffect(load, [load])
  return { data, error, reload: load }
}

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--color-line)] bg-[var(--color-surface)] p-4 shadow-sm">
      {children}
    </div>
  )
}

function CatalogGrid({ onOpen }: { onOpen: (c: Catalog) => void }) {
  const { data, error } = useAsync(() => listCatalogs(), [])
  if (error) return <ErrorBox label="catalogs" message={error} />
  if (!data) return <Loading what="catalogs" />
  // Lead with the seeded Demo catalog.
  const items = [...data.items].sort((a, b) =>
    a.name === 'Demo catalog' ? -1 : b.name === 'Demo catalog' ? 1 : 0,
  )
  return (
    <div>
      <SectionHead title="Catalogs" hint="Open a catalog to see its categories — that's where the affordances get rich. Catalog lists themselves aren't enriched." />
      <div className="grid gap-3 sm:grid-cols-2">
        {items.map((c) => (
          <button key={c.id} onClick={() => onOpen(c)} className="text-left">
            <Card>
              <div className="flex items-center justify-between">
                <span className="font-medium">{c.name}</span>
                <span className="text-xs text-[var(--color-brand-ink)]">open →</span>
              </div>
              {c.description && (
                <div className="mt-0.5 text-sm text-[var(--color-muted)]">{c.description}</div>
              )}
            </Card>
          </button>
        ))}
      </div>
    </div>
  )
}

function CatalogDetail({
  catalog,
  onHome,
  onOpenCategory,
}: {
  catalog: Catalog
  onHome: () => void
  onOpenCategory: (c: Category) => void
}) {
  // The single GET is enriched (unlike the list) — fetch it for the catalog's own _actions.
  const full = useAsync(() => getCatalog(catalog.id), [catalog.id])
  const cats = useAsync(() => listCategories(catalog.id), [catalog.id])

  return (
    <div>
      <Breadcrumbs trail={[{ label: 'Catalogs', onClick: onHome }, { label: catalog.name }]} />
      <Card>
        <div className="text-lg font-semibold">{catalog.name}</div>
        {catalog.description && (
          <div className="mt-0.5 text-sm text-[var(--color-muted)]">{catalog.description}</div>
        )}
        <div className="mt-3 text-xs font-medium uppercase tracking-wide text-[var(--color-muted)]">
          Your actions on this catalog
        </div>
        <div className="mt-1.5">
          {full.error ? (
            <span className="text-xs text-[var(--color-deny)]">{full.error}</span>
          ) : (
            <ActionBadges actions={full.data?._actions} />
          )}
        </div>
      </Card>

      <div className="mt-6">
        <SectionHead title="Categories" hint="Each category's action buttons reflect your role. Try update/delete — a denied one is locked." />
        {cats.error && <ErrorBox label="categories" message={cats.error} />}
        {!cats.data && !cats.error && <Loading what="categories" />}
        {cats.data && cats.data.items.length === 0 && (
          <Empty>No categories visible to you in this catalog.</Empty>
        )}
        <div className="grid gap-3">
          {cats.data?.items.map((cat) => (
            <Card key={cat.id}>
              <div className="flex items-start justify-between">
                <div>
                  <button
                    onClick={() => onOpenCategory(cat)}
                    className="font-medium hover:text-[var(--color-brand-ink)] hover:underline"
                  >
                    {cat.name}
                  </button>
                  <TagLine tags={cat.tags} />
                </div>
                <button
                  onClick={() => onOpenCategory(cat)}
                  className="text-xs text-[var(--color-brand-ink)]"
                >
                  products →
                </button>
              </div>
              <div className="mt-3">
                <ActionButtons
                  actions={cat._actions}
                  onAct={async (verb) => {
                    // PUT is a full replace — send the existing name so it isn't nulled.
                    if (verb === 'update')
                      await updateCategory(catalog.id, cat.id, {
                        name: cat.name,
                        description: `edited @ ${new Date().toISOString()}`,
                      })
                    else if (verb === 'delete') await deleteCategory(catalog.id, cat.id)
                    else if (verb === 'assign-tags')
                      await updateCategory(catalog.id, cat.id, {
                        name: cat.name,
                        description: cat.description ?? '',
                      })
                    cats.reload()
                  }}
                />
              </div>
            </Card>
          ))}
        </div>
      </div>
    </div>
  )
}

function CategoryDetail({
  catalog,
  category,
  onHome,
  onUp,
}: {
  catalog: Catalog
  category: Category
  onHome: () => void
  onUp: () => void
}) {
  const prods = useAsync(() => listProducts(catalog.id, category.id), [catalog.id, category.id])
  return (
    <div>
      <Breadcrumbs
        trail={[
          { label: 'Catalogs', onClick: onHome },
          { label: catalog.name, onClick: onUp },
          { label: category.name },
        ]}
      />
      <SectionHead title={`Products in ${category.name}`} hint="Product affordances mirror your role just like categories." />
      {prods.error && <ErrorBox label="products" message={prods.error} />}
      {!prods.data && !prods.error && <Loading what="products" />}
      {prods.data && prods.data.items.length === 0 && (
        <Empty>No products in this category yet.</Empty>
      )}
      <div className="grid gap-3">
        {prods.data?.items.map((p: Product) => (
          <Card key={p.id}>
            <div className="flex items-center justify-between">
              <span className="font-medium">{p.name}</span>
              {p.priceCents != null && (
                <span className="text-sm text-[var(--color-muted)]">
                  {(p.priceCents / 100).toFixed(2)} {p.currency ?? ''}
                </span>
              )}
            </div>
            <div className="mt-3">
              <ActionButtons
                actions={p._actions}
                onAct={async (verb) => {
                  if (verb === 'update')
                    await updateProduct(catalog.id, category.id, p.id, {
                      name: p.name,
                      description: `edited @ ${new Date().toISOString()}`,
                    })
                  else if (verb === 'delete') await deleteProduct(catalog.id, category.id, p.id)
                  prods.reload()
                }}
              />
            </div>
          </Card>
        ))}
      </div>
    </div>
  )
}

// --- small presentational helpers --------------------------------------------
function SectionHead({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="mb-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-[var(--color-muted)]">
        {title}
      </h2>
      {hint && <p className="mt-0.5 text-xs text-[var(--color-muted)]">{hint}</p>}
    </div>
  )
}

function TagLine({ tags }: { tags?: Record<string, string | string[]> }) {
  if (!tags || Object.keys(tags).length === 0) return null
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {Object.entries(tags).flatMap(([k, v]) =>
        (Array.isArray(v) ? v : [v]).map((val) => (
          <span
            key={`${k}:${val}`}
            className="rounded bg-[var(--color-canvas)] px-1.5 py-0.5 text-xs text-[var(--color-muted)]"
          >
            {k}:{val}
          </span>
        )),
      )}
    </div>
  )
}

function Loading({ what }: { what: string }) {
  return <p className="text-sm text-[var(--color-muted)]">Loading {what}…</p>
}
function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-[var(--color-muted)]">{children}</p>
}
function ErrorBox({ label, message }: { label: string; message: string }) {
  return (
    <Card>
      <span className="text-sm text-[var(--color-deny)]">
        Failed to load {label}: {message}
      </span>
    </Card>
  )
}
