import { useCallback, useEffect, useState } from 'react'
import {
  type AuthUser,
  completeLogin,
  currentUser,
  describeUser,
  login,
  logout,
  elevationOf,
  stepUp,
  stepUpStateOf,
  type StepUpState,
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
  getCategory,
  StepUpRequiredError,
  listCatalogs,
  listCategories,
  listProducts,
  listTeamTagDefinitions,
  lookupTeamByTarget,
  updateCatalog,
  updateCategory,
  updateProduct,
} from './api'
import {
  ActionBadges,
  ActionButtons,
  Breadcrumbs,
  Logo,
  RoleChips,
  ElevationChip,
  CatalogBadges,
  errText,
  useAsync,
  LockedPanel,
} from './components'
import { CreateCatalogPanel, TeamPanel } from './teams'
import {
  type ChipState,
  type Challenge,
  badgesFor,
  chipState,
  isElevated,
  lastChallengeWindow,
  loaOf,
} from './stepup'

type AuthState =
  | { phase: 'loading' }
  | { phase: 'anonymous' }
  // `restore` is set only when THIS page load is the callback from a step-up redirect. It is not
  // persisted anywhere: User.toStorageString() omits `state`, so it exists for this load and no
  // other — which is what makes the passive guard below structurally loop-free.
  | { phase: 'authenticated'; user: AuthUser; restore?: StepUpState | null }
  | { phase: 'error'; message: string }

// Guard against React 19 StrictMode double-invoking the bootstrap effect: a PKCE authorization code
// is single-use, so the second run would fail with "Code not valid". Resolve the redirect exactly
// once at module scope; the effect awaits the shared promise.
let bootstrap: Promise<AuthUser | null> | null = null

// Same once-only concern for profile provisioning: a StrictMode-doubled effect (or two quick
// re-renders) would otherwise POST /users twice — 201 then a spurious 409. Keyed by subject so a
// persona switch provisions independently; each entry clears when its promise settles.
const provisioning = new Map<string, Promise<unknown>>()

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
      .then((user) =>
        setAuth(
          user
            ? { phase: 'authenticated', user, restore: stepUpStateOf(user) }
            : { phase: 'anonymous' },
        ),
      )
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
  return <Console user={auth.user} restore={auth.restore ?? null} />
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
        {/* Named explicitly: without these two the supervised story is invisible to a first-time
            reader — the production catalog's locked panel looks like a bug rather than the point. */}
        <p className="mt-1 text-center text-xs text-[var(--color-muted)]">
          Supervised demo: sup-demo (supervisor — verifies a second factor to read production) ·
          pm-demo (production member — no ceremony)
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

/**
 * The chip, recomputed once a second.
 *
 * <p>A tick rather than an event because there is nothing to listen to: the window runs out on wall
 * clock, not on anything the app does. One interval for the whole console; the arithmetic is pure
 * and unit-tested (U5), so all this owns is *when* to ask.
 *
 * <p>The window is re-read from sessionStorage on every tick on purpose — a challenge arriving
 * mid-session updates it, and deleting the key by hand must degrade the chip immediately (E16).
 */
function useElevationChip(user: AuthUser): ChipState {
  const [nowSeconds, setNowSeconds] = useState(() => Math.floor(Date.now() / 1000))
  useEffect(() => {
    const id = setInterval(() => setNowSeconds(Math.floor(Date.now() / 1000)), 1000)
    return () => clearInterval(id)
  }, [])
  const { acr, authTime } = elevationOf(user)
  const window = lastChallengeWindow()
  return chipState({
    loa: loaOf(acr),
    authTime,
    window,
    // The window key is written ONLY when a challenge arrives, so its presence IS "a challenge was
    // seen this session" — which is what makes the "not elevated" chip meaningful rather than noise
    // on every member's screen.
    challengeSeen: window !== null,
    nowSeconds,
  })
}

function Console({ user, restore }: { user: AuthUser; restore: StepUpState | null }) {
  const { username, roles } = describeUser(user)
  const mySubject = user.profile.sub
  const chip = useElevationChip(user)
  const elevated = isElevated(chip)
  const [view, setView] = useState<View>({ kind: 'catalogs' })
  // Restoring the drill-in the challenge interrupted. The state carried ids only, so the objects the
  // View needs are re-read here — getCatalog, then getCategory if we were a level deeper. Either read
  // may itself be challenged; NEITHER triggers a redirect. The restored view's own load IS the one
  // automatic retry, and a challenged category simply leaves the user on the catalog with the panel.
  const [restoring, setRestoring] = useState(restore !== null)
  // The loop guard: on the page load that came back FROM a verification, a step-up refusal means the
  // verification did not help. The panel says so and waits. No code path calls stepUp() without a
  // click, so this cannot become a redirect loop however the server behaves.
  const [passive, setPassive] = useState(restore !== null)

  useEffect(() => {
    if (!restore) return
    let cancelled = false
    void (async () => {
      try {
        const catalog = await getCatalog(restore.catalogId)
        if (cancelled) return
        if (restore.categoryId) {
          // ONE direct read, not list-then-find: a list call here could be challenged in its own
          // right and would make "exactly one automatic retry" false.
          try {
            const category = await getCategory(restore.catalogId, restore.categoryId)
            if (cancelled) return
            setView({ kind: 'category', catalog, category })
          } catch (e) {
            if (cancelled) return
            // A CHALLENGED category is not a failed restore — it is the passive case this whole
            // effect exists for. Landing on the grid here would throw away the catalog we already
            // read and strand the user one level further out than the verification they just
            // completed; the catalog's own `cats` load re-raises the challenge and renders the
            // passive panel, which is what the user is owed. Caught separately from the catalog
            // read above BECAUSE that one really is a hard failure (see below).
            if (e instanceof StepUpRequiredError) setView({ kind: 'catalog', catalog })
            else throw e
          }
        } else {
          setView({ kind: 'catalog', catalog })
        }
      } catch {
        // The catalog itself is metadata-only and does not challenge, so this is a genuine failure
        // (deleted, revoked, offline). Land on the grid rather than a dead end; the drill-in is lost.
        if (!cancelled) setView({ kind: 'catalogs' })
      } finally {
        if (!cancelled) setRestoring(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [restore])

  // Deliberate navigation clears the passive flag. Without this the flag — set for the page load
  // that came back from a verification — would stick for the WHOLE session, so every later challenge
  // the user walked into fresh would claim "verification did not unlock" about a verification they
  // never attempted for it. Passive means "the read you just verified for is still refused", and
  // that is only true of the view the callback restored; moving anywhere is a new question.
  // (Found in the browser pass, not by a unit test — the state is only wrong on the SECOND challenge.)
  const navigate = useCallback((next: View) => {
    setPassive(false)
    setView(next)
  }, [])

  // Answering a challenge is ALWAYS a deliberate click. `location` is where the user is right now,
  // so Keycloak brings them back to the same place.
  const verify = useCallback(
    (challenge: Challenge, location: { catalogId: string; categoryId?: string }) => {
      setPassive(false)
      void stepUp(challenge.acrValues, location)
    },
    [],
  )

  // Provision the identity's user-service profile row on login: memberships (and owning a new
  // catalog's team) key on that row, and a first-time identity doesn't have one yet. A failure
  // must stay visible — team creation hard-requires the row, so a swallowed failure would turn
  // every later "create catalog" step 2 into an unexplained 400.
  //
  // Single-flight per subject: React 19 StrictMode double-invokes effects in dev, which would fire
  // two concurrent ensureUser POSTs (201 then a 409 for the second) and flash a spurious "provisioning
  // failed" banner though provisioning succeeded. Share one in-flight promise per subject — the same
  // once-only idiom the PKCE bootstrap uses above. (ensureUser also treats a 409 as success on its own.)
  const [profileError, setProfileError] = useState<string | null>(null)
  const provision = useCallback(() => {
    setProfileError(null)
    let pending = provisioning.get(mySubject)
    if (!pending) {
      pending = ensureUser(mySubject, username).finally(() => provisioning.delete(mySubject))
      provisioning.set(mySubject, pending)
    }
    pending.catch((e) => setProfileError(errText(e)))
  }, [mySubject, username])
  useEffect(provision, [provision])

  return (
    <div className="mx-auto flex h-full max-w-5xl flex-col">
      <header className="flex items-center justify-between border-b border-[var(--color-line)] px-6 py-4">
        <button className="flex items-center gap-3" onClick={() => navigate({ kind: 'catalogs' })}>
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
              <span
                className="text-[10px] uppercase tracking-wide text-[var(--color-muted)]"
                title="From the IdP access token. In this demo a realm role carries exactly one power — catalog-editor may CREATE catalogs; everything else comes from per-catalog team membership."
              >
                Keycloak realm roles
              </span>
              <RoleChips roles={roles} />
              <ElevationChip state={chip} />
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
        {restoring && (
          <p className="p-6 text-sm text-[var(--color-muted)]">Returning you to where you were…</p>
        )}
        {!restoring && view.kind === 'catalogs' && (
          <CatalogGrid
            // catalog:create is the one realm-role-decided verb (the B4 narrow fallback), so the
            // SPA can predict the deny from the token it already parsed for the header chips.
            canCreate={roles.includes('catalog-editor')}
            elevated={elevated}
            onOpen={(catalog) => navigate({ kind: 'catalog', catalog })}
          />
        )}
        {!restoring && view.kind === 'catalog' && (
          <CatalogDetail
            catalog={view.catalog}
            mySubject={mySubject}
            elevated={elevated}
            passive={passive}
            onVerify={(challenge) => verify(challenge, { catalogId: view.catalog.id })}
            onHome={() => navigate({ kind: 'catalogs' })}
            onOpenCategory={(category) =>
              navigate({ kind: 'category', catalog: view.catalog, category })
            }
          />
        )}
        {!restoring && view.kind === 'category' && (
          <CategoryDetail
            catalog={view.catalog}
            category={view.category}
            passive={passive}
            onVerify={(challenge) =>
              verify(challenge, { catalogId: view.catalog.id, categoryId: view.category.id })
            }
            onHome={() => navigate({ kind: 'catalogs' })}
            onUp={() => navigate({ kind: 'catalog', catalog: view.catalog })}
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

function CatalogGrid({
  canCreate,
  elevated,
  onOpen,
}: {
  canCreate: boolean
  elevated: boolean
  onOpen: (c: Catalog) => void
}) {
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
      {/* The B4 story, stated where a new user first wonders about it: realm roles ≠ access. */}
      <p className="-mt-1 mb-3 max-w-3xl text-xs text-[var(--color-muted)]">
        Your <span className="text-[10px] uppercase tracking-wide">Keycloak realm roles</span>{' '}
        (top right) come from the IdP token and carry exactly <em>one</em> power in this demo:{' '}
        <code className="rounded bg-[var(--color-canvas)] px-1">catalog-editor</code> may create
        new catalogs. Which catalogs you see here — and every button inside — comes from
        per-catalog <em>team membership</em> instead (membership is the sole access path). An
        identity holding only{' '}
        <code className="rounded bg-[var(--color-canvas)] px-1">catalog-viewer</code> sees an
        empty list until a team admits it — try <em>outsider</em>.
      </p>
      <CreateCatalogPanel onCreated={reload} likelyDenied={!canCreate} />
      <div className="grid gap-3 sm:grid-cols-2">
        {items.map((c) => (
          <button key={c.id} onClick={() => onOpen(c)} className="text-left">
            <Card>
              <div className="flex items-start justify-between gap-3">
                {/* min-w-0 lets the badge row wrap inside the title block instead of squeezing the
                    "open →" affordance onto two lines; shrink-0 keeps that affordance intact. */}
                <span className="min-w-0 font-medium">
                  {c.name}
                  <CatalogBadges
                    badges={badgesFor({
                      provenance: c._provenance,
                      env: c.tags?.env,
                      elevated,
                    })}
                  />
                </span>
                <span className="shrink-0 whitespace-nowrap text-xs text-[var(--color-brand-ink)]">
                  open →
                </span>
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
  elevated,
  passive,
  onVerify,
  onHome,
  onOpenCategory,
}: {
  catalog: Catalog
  mySubject: string
  elevated: boolean
  passive: boolean
  onVerify: (challenge: Challenge) => void
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
  const [catalogTagEditing, setCatalogTagEditing] = useState(false)

  return (
    <div>
      <Breadcrumbs trail={[{ label: 'Catalogs', onClick: onHome }, { label: catalog.name }]} />
      <Card>
        <div className="text-lg font-semibold">
          {catalog.name}
          {/* Prefer the SINGLE GET's provenance over the grid row's: the row is what we navigated
              from and may be stale, while `full` is this catalog's own freshly-derived answer. */}
          <CatalogBadges
            badges={badgesFor({
              // `?? catalog._provenance` would be WRONG here: once `full` has loaded, an ABSENT
              // _provenance is the server declining to compute one, and substituting the grid
              // row's label would re-assert a badge the server just withheld. Only fall back
              // while `full` has not arrived yet.
              provenance: full.data ? full.data._provenance : catalog._provenance,
              env: (full.data ?? catalog).tags?.env,
              elevated,
            })}
          />
        </div>
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
        <TagLine tags={full.data?.tags} />
        {full.data?._actions?.['assign-tags'] !== undefined && (
          <div className="mt-2">
            <button
              disabled={!full.data._actions['assign-tags']}
              onClick={() => setCatalogTagEditing(!catalogTagEditing)}
              title={
                full.data._actions['assign-tags']
                  ? 'Edit this catalog’s tags'
                  : 'Not allowed for your role'
              }
              className="rounded-md border px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed"
              style={
                full.data._actions['assign-tags']
                  ? { borderColor: 'var(--color-line)', color: 'var(--color-brand-ink)', background: '#eef2ff' }
                  : { borderColor: '#fecaca', color: 'var(--color-deny)', background: '#fef2f2', opacity: 0.7 }
              }
            >
              {full.data._actions['assign-tags'] ? '' : '🔒 '}assign-tags
            </button>
            {/* The confusion-killer (ADR 0022): under the default root-read exemption, root tags do
                NOT gate members' visibility — say what they DO gate. */}
            <p className="mt-1 text-xs italic text-[var(--color-muted)]">
              Root tags gate this catalog's <em>mutations</em> for tag-requiring roles — members
              always see their team's catalog. With the exemption off (strict mode), they gate
              reads too.
            </p>
            {catalogTagEditing && tagDefs.data && full.data && (
              <TagEditor
                defs={tagDefs.data.items}
                initial={full.data.tags ?? {}}
                onClose={() => setCatalogTagEditing(false)}
                onSave={async (tags) => {
                  // Content echoed unchanged — the delta dispatch asks exactly catalog:assign-tags.
                  await updateCatalog(catalog.id, {
                    name: full.data!.name,
                    description: full.data!.description ?? undefined,
                    tags,
                  })
                  full.reload()
                }}
              />
            )}
            {catalogTagEditing && !tagDefs.data && (
              <p className="mt-2 text-xs text-[var(--color-deny)]">
                The tag dictionary isn't readable for this identity
                {tagDefs.error ? ` (${tagDefs.error})` : ''} — the editor needs it to offer legal
                values.
              </p>
            )}
          </div>
        )}
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
        {/* A step-up refusal is not an error to report, it is a step to take: the panel replaces the
            error box and explains what would satisfy the server. Any OTHER failure still reads as an
            error, including a challenge the client could not follow (request() degrades that to a
            plain ApiError precisely so it lands here rather than as a dead [Verify]). */}
        {cats.error && !(cats.cause instanceof StepUpRequiredError) && (
          <ErrorBox label="categories" message={cats.error} />
        )}
        {cats.cause instanceof StepUpRequiredError && (
          <LockedPanel
            challenge={cats.cause.challenge}
            passive={passive}
            onVerify={() => onVerify((cats.cause as StepUpRequiredError).challenge)}
          />
        )}
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
                {tagEditing === cat.id && !tagDefs.data && (
                  <p className="mt-2 text-xs text-[var(--color-deny)]">
                    The tag dictionary isn't readable for this identity
                    {tagDefs.error ? ` (${tagDefs.error})` : ''} — the editor needs it to offer
                    legal values.
                  </p>
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
  passive,
  onVerify,
  onHome,
  onUp,
}: {
  catalog: Catalog
  category: Category
  passive: boolean
  onVerify: (challenge: Challenge) => void
  onHome: () => void
  onUp: () => void
}) {
  const prods = useAsync(() => listProducts(catalog.id, category.id), [catalog.id, category.id])
  // Same dictionary resolution as CatalogDetail: a product's governing team is its catalog's, so
  // the tag pickers here are driven by the catalog-resolved team dictionary.
  const tagDefs = useAsync(async () => {
    const teams = await lookupTeamByTarget('catalog', catalog.id)
    const team = teams.items[0]
    if (!team) return null
    return listTeamTagDefinitions(team.id)
  }, [catalog.id])
  const [tagEditing, setTagEditing] = useState<string | null>(null)
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
        tagDefs={tagDefs.data?.items}
        onCreate={async (v, tags) => {
          const priceCents = Math.round(Number(v.price) * 100)
          if (!Number.isFinite(priceCents) || priceCents < 0) {
            throw new Error('price must be a non-negative number like 19.99')
          }
          // Tags ride the create only when set: tag-on-create asks the TYPE-LEVEL assign-tags
          // decision on top of the create gate — an empty map shouldn't ask it.
          await createProduct(catalog.id, category.id, {
            name: v.name.trim(),
            priceCents,
            currency: 'USD',
            tags: Object.keys(tags).length > 0 ? tags : undefined,
          })
          prods.reload()
        }}
      />
      {prods.error && !(prods.cause instanceof StepUpRequiredError) && (
        <ErrorBox label="products" message={prods.error} />
      )}
      {prods.cause instanceof StepUpRequiredError && (
        <LockedPanel
          challenge={prods.cause.challenge}
          passive={passive}
          onVerify={() => onVerify((prods.cause as StepUpRequiredError).challenge)}
        />
      )}
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
            <TagLine tags={p.tags} />
            <div className="mt-3">
              <ActionButtons
                actions={p._actions}
                opens={{ 'assign-tags': () => setTagEditing(tagEditing === p.id ? null : p.id) }}
                onAct={async (verb) => {
                  if (verb === 'update')
                    await updateProduct(catalog.id, category.id, p.id, {
                      name: p.name,
                      description: `edited @ ${new Date().toISOString()}`,
                      // PUT is a full replace — echo the sku/price fields AND the tags, or the
                      // server reads their absence as "clear them" (the delta dispatch).
                      sku: p.sku,
                      priceCents: p.priceCents,
                      currency: p.currency,
                      tags: p.tags ?? {},
                    })
                  else if (verb === 'delete') await deleteProduct(catalog.id, category.id, p.id)
                  prods.reload()
                }}
              />
              {tagEditing === p.id && tagDefs.data && (
                <TagEditor
                  defs={tagDefs.data.items}
                  initial={p.tags ?? {}}
                  onClose={() => setTagEditing(null)}
                  onSave={async (tags) => {
                    // Content echoed unchanged — this PUT asks exactly the assign-tags decision.
                    await updateProduct(catalog.id, category.id, p.id, {
                      name: p.name,
                      description: p.description ?? undefined,
                      sku: p.sku,
                      priceCents: p.priceCents,
                      currency: p.currency,
                      tags,
                    })
                    prods.reload()
                  }}
                />
              )}
              {tagEditing === p.id && !tagDefs.data && (
                <p className="mt-2 text-xs text-[var(--color-deny)]">
                  The tag dictionary isn't readable for this identity
                  {tagDefs.error ? ` (${tagDefs.error})` : ''} — the editor needs it to offer
                  legal values.
                </p>
              )}
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
        if (def.operatorManaged) {
          // No public write path (delta-based 409 on assign/re-value/strip): show, never edit.
          return (
            <div key={def.key} className="flex flex-wrap items-center gap-2">
              <span className="w-24 text-xs font-medium text-[var(--color-muted)]">{def.key}</span>
              <span className="text-xs text-[var(--color-muted)]">
                {typeof current === 'string' ? current : Array.isArray(current) ? current.join(', ') : '—'}
                {' '}(operator-managed)
              </span>
            </div>
          )
        }
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
