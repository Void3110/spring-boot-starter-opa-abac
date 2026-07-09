import { useCallback, useEffect, useState } from 'react'
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
  type TagDefinition,
  type Tags,
  createCategory,
  createProduct,
  deleteCategory,
  deleteProduct,
  ensureUser,
  getCatalog,
  listCatalogs,
  listCategories,
  listProducts,
  listTeamTagDefinitions,
  lookupTeamByTarget,
  updateCategory,
  updateProduct,
} from './api'
import {
  ActionBadges,
  ActionButtons,
  Breadcrumbs,
  Logo,
  RoleChips,
  errText,
  useAsync,
} from './components'
import { CreateCatalogPanel, TeamPanel } from './teams'

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
  const mySubject = user.profile.sub
  const [view, setView] = useState<View>({ kind: 'catalogs' })

  // Provision the identity's user-service profile row on login: memberships (and owning a new
  // catalog's team) key on that row, and a first-time identity doesn't have one yet. A failure
  // must stay visible — team creation hard-requires the row, so a swallowed failure would turn
  // every later "create catalog" step 2 into an unexplained 400.
  const [profileError, setProfileError] = useState<string | null>(null)
  const provision = useCallback(() => {
    setProfileError(null)
    ensureUser(mySubject, username).catch((e) => setProfileError(errText(e)))
  }, [mySubject, username])
  useEffect(provision, [provision])

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
            {/* These are the IdP's coarse labels from the access token — what actually drives the
                affordances is the per-catalog TEAM role (membership → OPA). The label keeps the
                two from being read as one thing. */}
            <div className="flex items-center justify-end gap-1.5">
              <span className="text-[10px] uppercase tracking-wide text-[var(--color-muted)]">
                Keycloak realm roles
              </span>
              <RoleChips roles={roles} />
            </div>
          </div>
          <button
            onClick={() => logout()}
            className="rounded-lg border border-[var(--color-line)] px-3 py-1.5 text-sm transition-colors hover:bg-[var(--color-canvas)]"
          >
            Switch identity
          </button>
        </div>
      </header>
      {profileError && (
        <div
          className="flex items-center gap-2 border-b border-[var(--color-line)] px-6 py-2 text-xs"
          style={{ background: '#fef3c7', color: '#b45309' }}
        >
          Profile provisioning failed ({profileError}) — creating catalogs/teams won't work until it
          succeeds.
          <button onClick={provision} className="font-medium underline">
            Retry
          </button>
        </div>
      )}
      <main className="flex-1 overflow-auto px-6 py-6">
        {view.kind === 'catalogs' && (
          <CatalogGrid onOpen={(catalog) => setView({ kind: 'catalog', catalog })} />
        )}
        {view.kind === 'catalog' && (
          <CatalogDetail
            catalog={view.catalog}
            mySubject={mySubject}
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

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--color-line)] bg-[var(--color-surface)] p-4 shadow-sm">
      {children}
    </div>
  )
}

function CatalogGrid({ onOpen }: { onOpen: (c: Catalog) => void }) {
  const { data, error, reload } = useAsync(() => listCatalogs(), [])
  if (error) return <ErrorBox label="catalogs" message={error} />
  if (!data) return <Loading what="catalogs" />
  // Lead with the seeded Demo catalog.
  const items = [...data.items].sort((a, b) =>
    a.name === 'Demo catalog' ? -1 : b.name === 'Demo catalog' ? 1 : 0,
  )
  return (
    <div>
      <SectionHead title="Catalogs" hint="Open a catalog to see its categories — that's where the affordances get rich. Catalog lists themselves aren't enriched." />
      <CreateCatalogPanel onCreated={reload} />
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
  mySubject,
  onHome,
  onOpenCategory,
}: {
  catalog: Catalog
  mySubject: string
  onHome: () => void
  onOpenCategory: (c: Category) => void
}) {
  // The single GET is enriched (unlike the list) — fetch it for the catalog's own _actions.
  const full = useAsync(() => getCatalog(catalog.id), [catalog.id])
  const cats = useAsync(() => listCategories(catalog.id), [catalog.id])
  // The tag dictionary (global + this catalog's team keys) drives the tag pickers. Resolved via
  // the governing team because only the team-scoped listing is gateway-exposed; a catalog without
  // a resolvable team just renders the forms without tag fields.
  const tagDefs = useAsync(async () => {
    const teams = await lookupTeamByTarget('catalog', catalog.id)
    const team = teams.items[0]
    if (!team) return null
    return listTeamTagDefinitions(team.id)
  }, [catalog.id])
  const [tagEditing, setTagEditing] = useState<string | null>(null)

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

      <TeamPanel
        catalogId={catalog.id}
        mySubject={mySubject}
        onDictionaryChanged={tagDefs.reload}
      />

      <div className="mt-6">
        <SectionHead title="Categories" hint="Each category's action buttons reflect your role. Try update/delete — a denied one is locked. Creating is OPA-decided too: the form is offered to everyone and a denied submit answers honestly." />
        <InlineCreatePanel
          label="New category"
          fields={[
            { key: 'name', placeholder: 'Category name', required: true },
            { key: 'description', placeholder: 'Description (optional)', grow: true },
          ]}
          tagDefs={tagDefs.data?.items}
          onCreate={async (v, tags) => {
            // Tags ride the create only when set: tag-on-create asks the TYPE-LEVEL assign-tags
            // decision on top of the create gate (Phase 6.5) — an empty map shouldn't ask it.
            await createCategory(catalog.id, {
              name: v.name.trim(),
              description: v.description?.trim() || undefined,
              tags: Object.keys(tags).length > 0 ? tags : undefined,
            })
            cats.reload()
          }}
        />
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
                  opens={{ 'assign-tags': () => setTagEditing(tagEditing === cat.id ? null : cat.id) }}
                  onAct={async (verb) => {
                    // PUT is a full replace — echo name AND tags/parent, or the server reads the
                    // absence as "clear tags" / "re-parent to root" (the delta dispatch).
                    if (verb === 'update')
                      await updateCategory(catalog.id, cat.id, {
                        name: cat.name,
                        description: `edited @ ${new Date().toISOString()}`,
                        parentId: cat.parentId ?? null,
                        tags: cat.tags ?? {},
                      })
                    else if (verb === 'delete') await deleteCategory(catalog.id, cat.id)
                    cats.reload()
                  }}
                />
                {tagEditing === cat.id && tagDefs.data && (
                  <TagEditor
                    defs={tagDefs.data.items}
                    initial={cat.tags ?? {}}
                    onClose={() => setTagEditing(null)}
                    onSave={async (tags) => {
                      // Content echoed unchanged — this PUT asks exactly the assign-tags decision.
                      await updateCategory(catalog.id, cat.id, {
                        name: cat.name,
                        description: cat.description ?? undefined,
                        parentId: cat.parentId ?? null,
                        tags,
                      })
                      cats.reload()
                    }}
                  />
                )}
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
      <SectionHead title={`Products in ${category.name}`} hint="Product affordances mirror your role just like categories — including create, which OPA decides on submit." />
      <InlineCreatePanel
        label="New product"
        fields={[
          { key: 'name', placeholder: 'Product name', required: true },
          { key: 'price', placeholder: 'Price in USD (e.g. 19.99)', required: true },
        ]}
        onCreate={async (v) => {
          const priceCents = Math.round(Number(v.price) * 100)
          if (!Number.isFinite(priceCents) || priceCents < 0) {
            throw new Error('price must be a non-negative number like 19.99')
          }
          await createProduct(catalog.id, category.id, {
            name: v.name.trim(),
            priceCents,
            currency: 'USD',
          })
          prods.reload()
        }}
      />
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
                      // PUT is a full replace — echo the price fields so they aren't nulled.
                      priceCents: p.priceCents,
                      currency: p.currency,
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

/**
 * The tag-picker rows, driven by the live tag dictionary: ENUM keys render their closed value set
 * (chips for MULTI, a select for SINGLE); STRING keys render free text (comma-separated for MULTI,
 * normalized to an array at submit via {@link normalizeTags}). Unknown keys can't be typed at all —
 * but the server validates against the dictionary anyway (422, nothing silently dropped).
 */
function TagFields({
  defs,
  value,
  onChange,
}: {
  defs: TagDefinition[]
  value: Tags
  onChange: (next: Tags) => void
}) {
  const set = (key: string, v: string | string[] | undefined) => {
    const next = { ...value }
    if (v === undefined || v === '' || (Array.isArray(v) && v.length === 0)) delete next[key]
    else next[key] = v
    onChange(next)
  }
  if (defs.length === 0) return null
  return (
    <div className="mt-2 grid gap-1.5">
      {defs.map((def) => {
        const current = value[def.key]
        return (
          <div key={def.key} className="flex flex-wrap items-center gap-2">
            <span className="w-24 text-xs font-medium text-[var(--color-muted)]">{def.key}</span>
            {def.valueType === 'ENUM' && def.cardinality === 'MULTI' && (
              <div className="flex flex-wrap gap-1">
                {(def.allowedValues ?? []).map((v) => {
                  const arr = Array.isArray(current) ? current : []
                  const selected = arr.includes(v)
                  return (
                    <button
                      key={v}
                      type="button"
                      onClick={() => set(def.key, selected ? arr.filter((x) => x !== v) : [...arr, v])}
                      className="rounded-md border px-2 py-0.5 text-xs transition-colors"
                      style={
                        selected
                          ? { borderColor: 'var(--color-brand)', color: 'var(--color-brand-ink)', background: '#eef2ff' }
                          : { borderColor: 'var(--color-line)', color: 'var(--color-muted)' }
                      }
                    >
                      {v}
                    </button>
                  )
                })}
              </div>
            )}
            {def.valueType === 'ENUM' && def.cardinality === 'SINGLE' && (
              <select
                value={typeof current === 'string' ? current : ''}
                onChange={(e) => set(def.key, e.target.value || undefined)}
                className="rounded-md border border-[var(--color-line)] bg-transparent px-2 py-1 text-xs"
              >
                <option value="">— none —</option>
                {(def.allowedValues ?? []).map((v) => (
                  <option key={v} value={v}>
                    {v}
                  </option>
                ))}
              </select>
            )}
            {def.valueType === 'STRING' && (
              <input
                value={Array.isArray(current) ? current.join(', ') : (current ?? '')}
                onChange={(e) => set(def.key, e.target.value)}
                placeholder={def.cardinality === 'MULTI' ? 'comma, separated' : (def.valuePattern ?? 'value')}
                className="rounded-md border border-[var(--color-line)] bg-transparent px-2 py-1 text-xs"
              />
            )}
          </div>
        )
      })}
    </div>
  )
}

/** MULTI keys typed as free text become arrays; empties drop out — the shape the validator expects. */
function normalizeTags(defs: TagDefinition[], value: Tags): Tags {
  const out: Tags = {}
  for (const [k, v] of Object.entries(value)) {
    const def = defs.find((d) => d.key === k)
    if (def?.cardinality === 'MULTI' && typeof v === 'string') {
      const arr = v.split(',').map((s) => s.trim()).filter(Boolean)
      if (arr.length > 0) out[k] = arr
    } else if (Array.isArray(v) ? v.length > 0 : v.trim() !== '') {
      out[k] = v
    }
  }
  return out
}

/**
 * The assign-tags editor behind a category card's `assign-tags` button. Saving PUTs the category
 * with its content echoed UNCHANGED and only the tags different — so the server's delta dispatch
 * asks exactly the assign-tags decision (a TAG-capable role succeeds; a denied one answers 403
 * honestly, right here).
 */
function TagEditor({
  defs,
  initial,
  onSave,
  onClose,
}: {
  defs: TagDefinition[]
  initial: Tags
  onSave: (tags: Tags) => Promise<void>
  onClose: () => void
}) {
  const [value, setValue] = useState<Tags>(initial)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await onSave(normalizeTags(defs, value))
      onClose()
    } catch (e) {
      setError(errText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-3 rounded-lg border border-dashed border-[var(--color-line)] p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          Assign tags — validated against the dictionary, decided by OPA
        </span>
        <button onClick={onClose} className="text-xs text-[var(--color-muted)] hover:underline">
          close
        </button>
      </div>
      <TagFields defs={defs} value={value} onChange={setValue} />
      <div className="mt-2 flex items-center gap-2">
        <button
          disabled={busy}
          onClick={save}
          className="rounded-md bg-[var(--color-brand)] px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? '…' : 'Save tags'}
        </button>
        {error && <span className="text-xs text-[var(--color-deny)]">assign-tags failed: {error}</span>}
      </div>
    </div>
  )
}

/**
 * Inline create affordance for child resources (categories/products). Deliberately NOT keyed off
 * `_actions`: enrichment probes only instance-scoped verbs on the resource itself (affordance
 * honesty — Enrichable.abacActions), while child-create is a type-level decision resolved against
 * the parent. So the form is offered to everyone and a denied POST answers honestly — the same
 * posture as the team panel.
 */
function InlineCreatePanel({
  label,
  fields,
  tagDefs,
  onCreate,
}: {
  label: string
  fields: { key: string; placeholder: string; required?: boolean; grow?: boolean }[]
  tagDefs?: TagDefinition[]
  onCreate: (values: Record<string, string>, tags: Tags) => Promise<void>
}) {
  const [open, setOpen] = useState(false)
  const [values, setValues] = useState<Record<string, string>>({})
  const [tags, setTags] = useState<Tags>({})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reset = () => {
    setOpen(false)
    setValues({})
    setTags({})
    setError(null)
  }

  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      await onCreate(values, normalizeTags(tagDefs ?? [], tags))
      reset()
    } catch (e) {
      setError(errText(e))
    } finally {
      setBusy(false)
    }
  }

  const ready = fields.every((f) => !f.required || (values[f.key] ?? '').trim().length > 0)

  if (!open)
    return (
      <button
        onClick={() => setOpen(true)}
        className="mb-3 rounded-md border border-[var(--color-line)] px-2.5 py-1 text-xs font-medium text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
      >
        + {label}
      </button>
    )

  return (
    <div className="mb-4 rounded-xl border border-dashed border-[var(--color-line)] bg-[var(--color-surface)] p-4">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          {label} — OPA decides on submit
        </span>
        <button onClick={reset} className="text-xs text-[var(--color-muted)] hover:underline">
          close
        </button>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        {fields.map((f, i) => (
          <input
            key={f.key}
            autoFocus={i === 0}
            value={values[f.key] ?? ''}
            onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
            placeholder={f.placeholder}
            className={`rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm${f.grow ? ' min-w-48 flex-1' : ''}`}
          />
        ))}
        <button
          disabled={busy || !ready}
          onClick={submit}
          className="rounded-md bg-[var(--color-brand)] px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? '…' : 'Create'}
        </button>
      </div>
      {tagDefs && tagDefs.length > 0 && (
        <div className="mt-3">
          <span className="text-xs font-medium uppercase tracking-wide text-[var(--color-muted)]">
            Tags (optional — tag-on-create asks the assign-tags decision too)
          </span>
          <TagFields defs={tagDefs} value={tags} onChange={setTags} />
        </div>
      )}
      {error && (
        <div className="mt-2 text-xs text-[var(--color-deny)]">create failed: {error}</div>
      )}
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
