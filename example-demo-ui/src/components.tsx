import { useCallback, useEffect, useState } from 'react'
import { type Actions, ApiError } from './api'

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
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
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
