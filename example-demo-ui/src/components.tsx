import { useCallback, useEffect, useState } from 'react'
import { type Actions, ApiError } from './api'
import { type BadgeState, type ChipState, type Challenge, formatRemaining } from './stepup'

/** One error format everywhere: ApiError keeps its HTTP status ("422 — …"), the demo's denial voice. */
export function errText(e: unknown): string {
  return e instanceof ApiError ? `${e.status} — ${e.message}` : String(e)
}

export type Notice = { ok: boolean; text: string }

export function NoticeLine({ notice }: { notice: Notice | null }) {
  if (!notice) return null
  return (
    <div
      className="mt-2 text-xs"
      style={{ color: notice.ok ? 'var(--color-allow)' : 'var(--color-deny)' }}
    >
      {notice.ok ? '✓ ' : '✕ '}
      {notice.text}
    </div>
  )
}

// Small async-data helper: load on mount + expose a reload.
//
// `cause` is the thrown value itself, kept beside the rendered string: a caller that needs to react
// to the KIND of failure (a step-up challenge is the one that matters — it carries the parameters
// the locked panel renders) cannot recover that from a formatted message. Every existing consumer
// reads `error` and is unaffected. The retry-once policy deliberately does NOT live here — this hook
// stays a dumb loader; the panel owns that decision.
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cause, setCause] = useState<unknown>(null)
  const load = useCallback(() => {
    setError(null)
    setCause(null)
    fn()
      .then(setData)
      .catch((e) => {
        setCause(e)
        setError(errText(e))
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)
  useEffect(load, [load])
  return { data, error, cause, reload: load }
}

/**
 * The 401 moment, rendered in place: what the server refused, in the server's own words, and the one
 * button that can change it.
 *
 * <p>Deliberately **not** a modal and **not** an auto-redirect. It replaces only the contents area
 * that was refused — the header, breadcrumbs and the resource's own metadata card stay usable,
 * because the caller is not locked out of the resource, only out of its contents. And a redirect the
 * user did not ask for would be indistinguishable from a login loop the first time anything goes
 * wrong.
 *
 * <p>The parameters are shown as plain facts rather than prose: this is a demo of an authorization
 * mechanism, and `acr_values` / `max_age` are the mechanism.
 *
 * <p>`passive` is the post-verification variant: the user already answered a challenge and the read
 * is *still* refused. It says so honestly and leaves [Verify] available for a deliberate second
 * attempt — what it must never do is bounce them again on its own.
 */
export function LockedPanel({
  challenge,
  passive,
  onVerify,
}: {
  challenge: Challenge
  passive: boolean
  onVerify: () => void
}) {
  return (
    <div className="mt-4 rounded-xl border border-[var(--color-line)] bg-[var(--color-canvas)] p-5">
      <div className="flex items-start gap-3">
        <span aria-hidden className="text-lg leading-none">
          🔒
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-semibold">Production contents — fresh second factor required</h3>

          {/* The server's own sentence, verbatim. The console does not paraphrase an authorization
              decision: whatever the policy author wrote is what the reader should see. */}
          <p className="mt-1 text-sm text-[var(--color-muted)]">{challenge.description}</p>

          <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-[var(--color-muted)]">
            <div className="flex gap-1.5">
              <dt className="font-medium">acr_values</dt>
              <dd className="font-mono">{challenge.acrValues}</dd>
            </div>
            <div className="flex gap-1.5">
              <dt className="font-medium">max_age</dt>
              <dd className="font-mono">{challenge.maxAge}s</dd>
            </div>
          </dl>

          {passive && (
            <p className="mt-3 text-xs" style={{ color: 'var(--color-warn)' }}>
              Verification did not unlock production contents — the read is still refused. You can
              try again, or carry on elsewhere.
            </p>
          )}

          <button
            onClick={onVerify}
            className="mt-4 rounded-lg bg-[var(--color-brand)] px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)]"
          >
            Verify
          </button>
          <p className="mt-2 text-xs text-[var(--color-muted)]">
            Takes you to Keycloak to re-authenticate, then back to this page.
          </p>
        </div>
      </div>
    </div>
  )
}

/** A pill that surfaces the current identity's role(s). */
export function RoleChips({ roles }: { roles: string[] }) {
  if (roles.length === 0)
    return <span className="text-xs text-[var(--color-muted)]">no roles</span>
  return (
    <span className="flex flex-wrap justify-end gap-1">
      {roles.map((r) => (
        <span
          key={r}
          className="rounded-md bg-[var(--color-canvas)] px-1.5 py-0.5 text-xs text-[var(--color-muted)]"
        >
          {r}
        </span>
      ))}
    </span>
  )
}

/**
 * The elevation chip: whether a second factor is currently in hand, and for how much longer.
 *
 * <p>The countdown is the window the **server advertised** in its last challenge (T2 remembers it),
 * never a constant in this file. At zero the chip flips amber and <b>nothing on screen is hidden</b>
 * — the policy's skew may still admit a read for a few more seconds, and the client has no business
 * pre-empting that. The next fetch is what finds out; this is a prediction, honestly labelled.
 *
 * <p>Hidden entirely for members and viewers: they never need elevation, so a chip would be noise
 * about a mechanism that does not apply to them (K13).
 */
export function ElevationChip({ state }: { state: ChipState }) {
  if (state.kind === 'hidden') return null

  const amber = state.kind === 'lapsed' || state.kind === 'not-elevated'
  const label =
    state.kind === 'elevated'
      ? `Elevated · ${formatRemaining(state.remaining)}`
      : state.kind === 'elevated-unknown-window'
        ? 'Elevated (aal2)'
        : state.kind === 'lapsed'
          ? 'elevation lapsed'
          : 'not elevated'
  const title =
    state.kind === 'elevated'
      ? 'A fresh second factor is in hand. The countdown is the window the server advertised.'
      : state.kind === 'elevated-unknown-window'
        ? 'A second factor is in hand. No window was advertised this session, so no countdown is shown.'
        : state.kind === 'lapsed'
          ? 'The advertised window has run out — a prediction. Nothing is hidden; the next read is what decides.'
          : 'Production content is in play and no second factor is in hand.'

  return (
    <span
      title={title}
      className="rounded-md px-1.5 py-0.5 text-xs font-medium"
      style={
        amber
          ? { color: 'var(--color-warn)', background: 'color-mix(in srgb, var(--color-warn) 12%, transparent)' }
          : { color: 'var(--color-allow)', background: 'color-mix(in srgb, var(--color-allow) 12%, transparent)' }
      }
    >
      {label}
    </span>
  )
}

/**
 * The row markers on a catalog: how you hold it, what tier it is, and — only when the client can
 * honestly predict it — that opening it will ask for a second factor.
 *
 * <p>Amber is a **prediction from three inputs** the client already has; the server's 401 is the
 * truth and the locked panel is where it lands. Predicted-and-wrong is a UI bug, never a security
 * event — which is why amber never suppresses anything, it only forewarns.
 */
export function CatalogBadges({ badges }: { badges: BadgeState }) {
  if (!badges.supervised && !badges.production) return null
  return (
    <span className="ml-2 inline-flex flex-wrap items-center gap-1 align-middle">
      {badges.supervised && (
        <span
          title="You hold this catalog by supervision, not membership — it is in your page because someone who reports to you governs it."
          className="rounded-md bg-[var(--color-canvas)] px-1.5 py-0.5 text-xs text-[var(--color-muted)]"
        >
          supervised
        </span>
      )}
      {badges.production && (
        <span
          title={
            badges.amber
              ? 'Production tier, held by supervision, and no second factor in hand — opening its contents will ask you to verify. A prediction: the server decides.'
              : 'Production tier.'
          }
          className="rounded-md px-1.5 py-0.5 text-xs"
          style={
            badges.amber
              ? { color: 'var(--color-warn)', background: 'color-mix(in srgb, var(--color-warn) 12%, transparent)' }
              : { color: 'var(--color-muted)', background: 'var(--color-canvas)' }
          }
        >
          {badges.amber ? 'production · verify to open' : 'production'}
        </span>
      )}
    </span>
  )
}

/** Display-only affordance badges (read-only contexts like the catalog grid). */
export function ActionBadges({ actions }: { actions?: Actions }) {
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

export type ActionResult = { verb: string; ok: boolean; message: string }

/**
 * The payoff: real action buttons gated by the affordance map. A button is enabled iff `_actions`
 * grants the verb; `view` is informational (no-op). `onAct` performs the real API call for a verb
 * and returns the outcome — so a click proves the gate (a 403 surfaces honestly, an allowed call
 * succeeds). Verbs absent from the map (e.g. a non-enriched resource) render disabled + explained.
 * A verb listed in `opens` opens UI (an editor) instead of calling the API — the decision proof
 * then happens on that editor's own submit, so no fabricated "succeeded" line is shown here.
 */
export function ActionButtons({
  actions,
  onAct,
  opens,
}: {
  actions?: Actions
  onAct: (verb: string) => Promise<void>
  opens?: Record<string, () => void>
}) {
  const [busy, setBusy] = useState<string | null>(null)
  const [result, setResult] = useState<ActionResult | null>(null)

  if (!actions || Object.keys(actions).length === 0)
    return <span className="text-xs italic text-[var(--color-muted)]">no affordances enriched</span>

  const run = async (verb: string) => {
    setBusy(verb)
    setResult(null)
    try {
      await onAct(verb)
      setResult({ verb, ok: true, message: `${verb} succeeded` })
    } catch (e) {
      const msg = e instanceof ApiError ? `${e.status} — ${e.message}` : String(e)
      setResult({ verb, ok: false, message: `${verb} failed: ${msg}` })
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <div className="flex flex-wrap gap-1.5">
        {Object.entries(actions).map(([verb, allowed]) => {
          const disabled = !allowed || verb === 'view' || busy !== null
          return (
            <button
              key={verb}
              disabled={disabled}
              onClick={() => (opens?.[verb] ? opens[verb]() : run(verb))}
              title={allowed ? `Perform ${verb}` : `Not allowed for your role`}
              className="rounded-md border px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed"
              style={
                allowed
                  ? {
                      borderColor: 'var(--color-line)',
                      color: verb === 'view' ? 'var(--color-muted)' : 'var(--color-brand-ink)',
                      background: verb === 'view' ? 'transparent' : '#eef2ff',
                    }
                  : { borderColor: '#fecaca', color: 'var(--color-deny)', background: '#fef2f2', opacity: 0.7 }
              }
            >
              {busy === verb ? '…' : `${allowed ? '' : '🔒 '}${verb}`}
            </button>
          )
        })}
      </div>
      {result && (
        <div
          className="mt-2 text-xs"
          style={{ color: result.ok ? 'var(--color-allow)' : 'var(--color-deny)' }}
        >
          {result.ok ? '✓ ' : '✕ '}
          {result.message}
        </div>
      )}
    </div>
  )
}

export function Breadcrumbs({
  trail,
}: {
  trail: { label: string; onClick?: () => void }[]
}) {
  return (
    <nav className="mb-4 flex items-center gap-1.5 text-sm text-[var(--color-muted)]">
      {trail.map((c, i) => (
        <span key={i} className="flex items-center gap-1.5">
          {i > 0 && <span className="opacity-50">/</span>}
          {c.onClick ? (
            <button onClick={c.onClick} className="hover:text-[var(--color-brand-ink)] hover:underline">
              {c.label}
            </button>
          ) : (
            <span className="font-medium text-[var(--color-ink)]">{c.label}</span>
          )}
        </span>
      ))}
    </nav>
  )
}

export function Logo() {
  return (
    <div
      className="flex h-9 w-9 items-center justify-center rounded-lg font-bold text-white"
      style={{ background: 'var(--color-brand)' }}
    >
      A
    </div>
  )
}
