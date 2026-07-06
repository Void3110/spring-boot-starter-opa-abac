import { useState } from 'react'
import {
  type Page,
  type RoleDefinition,
  type RoleDefinitionRequest,
  type Team,
  createRoleDefinition,
  deleteRoleDefinition,
  updateRoleDefinition,
} from './api'
import { type Notice, NoticeLine, errText } from './components'

// The authoring contract (RoleDefinitionService.validateContract — every violation is a live 422
// ROLE_DEFINITION_INVALID, which this panel deliberately lets you trigger):
//   level ladder 10/20/25/30 · tokens READ/WRITE/TAG/GRANT only · tokens within the level ceiling
//   (GRANT only at 30) · no team-management power on customs · denials must subtract from grants.
const AUTHORABLE_LEVELS = [
  { level: 10, hint: 'reader tier — ceiling: READ' },
  { level: 20, hint: 'member tier — ceiling: READ/WRITE/TAG' },
  { level: 25, hint: 'senior tier — ceiling: READ/WRITE/TAG' },
  { level: 30, hint: 'administrator tier — ceiling: READ/WRITE/TAG/GRANT' },
]
const CATEGORY_TOKENS = ['READ', 'WRITE', 'TAG', 'GRANT']
// "team" is included on purpose: a custom role may not carry team power, and submitting one
// demonstrates guard #2 (422) live.
const RESOURCE_TYPES = ['catalog', 'category', 'product', 'team']

// Mirrors PermissionCategories.ceiling() server-side — deterministic, so the form can flag
// violations visually while still letting them through to demo the live 422.
const CEILINGS: Record<number, string[]> = {
  10: ['READ'],
  20: ['READ', 'WRITE', 'TAG'],
  25: ['READ', 'WRITE', 'TAG'],
  30: ['READ', 'WRITE', 'TAG', 'GRANT'],
}

/** Why this (type, token) grant would 422 — or null if it is fine. */
function violation(level: number, type: string, token: string): string | null {
  if (type === 'team' && token === 'TAG')
    return 'team:TAG grants define-tags, a management verb — custom roles may not carry team power'
  if (!(CEILINGS[level] ?? []).includes(token))
    return `'${token}' exceeds the level-${level} ceiling`
  return null
}

/** "region=emea|apac; sensitivity=public" -> {region:[emea,apac], sensitivity:[public]} */
function parseKeyedLists(s: string): Record<string, string[]> {
  const out: Record<string, string[]> = {}
  for (const pair of s.split(';')) {
    const [k, v] = pair.split('=').map((x) => x.trim())
    if (k && v) out[k] = v.split('|').map((x) => x.trim()).filter(Boolean)
  }
  return out
}

function formatKeyedLists(m?: Record<string, string[]>): string {
  if (!m) return ''
  return Object.entries(m)
    .map(([k, v]) => `${k}=${v.join('|')}`)
    .join('; ')
}

function summarize(m?: Record<string, string[]>): string {
  if (!m || Object.keys(m).length === 0) return ''
  return Object.entries(m)
    .map(([k, v]) => `${k}: ${v.join(', ')}`)
    .join(' · ')
}

export function RolesSection({
  team,
  roleDefs,
  error,
  onChanged,
}: {
  team: Team
  roleDefs: Page<RoleDefinition> | null
  error: string | null
  onChanged: () => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<RoleDefinition | 'new' | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const submit = async (req: RoleDefinitionRequest) => {
    setBusy(true)
    setNotice(null)
    const existing = editing === 'new' ? null : editing
    try {
      if (existing) await updateRoleDefinition(team.id, existing.code, req)
      else await createRoleDefinition(team.id, req)
      setNotice({ ok: true, text: `${existing ? 'update' : 'create'} ${req.code} succeeded` })
      setEditing(null)
      onChanged()
    } catch (e) {
      setNotice({ ok: false, text: `${existing ? 'update' : 'create'} failed: ${errText(e)}` })
    } finally {
      setBusy(false)
    }
  }

  const remove = async (code: string) => {
    if (!window.confirm(`Delete custom role "${code}"?`)) return
    setBusy(true)
    setNotice(null)
    try {
      await deleteRoleDefinition(team.id, code)
      setNotice({ ok: true, text: `delete ${code} succeeded` })
      onChanged()
    } catch (e) {
      setNotice({ ok: false, text: `delete ${code} failed: ${errText(e)}` })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-3 border-t border-[var(--color-line)] pt-3">
      <button
        onClick={() => setOpen(!open)}
        className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)] hover:text-[var(--color-brand-ink)]"
      >
        {open ? '▾' : '▸'} Roles{roleDefs ? ` (${roleDefs.count})` : ''}
      </button>
      {open && (
        <div className="mt-2">
          {error && (
            <p className="text-xs text-[var(--color-deny)]">
              Role authoring is owner-only (team:define-roles): {error}
            </p>
          )}
          {!error && !roleDefs && (
            <p className="text-xs text-[var(--color-muted)]">Loading roles…</p>
          )}
          {roleDefs && (
            <div className="grid gap-1.5">
              {roleDefs.items.map((r) => (
                <RoleRow
                  key={r.code}
                  role={r}
                  busy={busy}
                  onEdit={() => setEditing(r)}
                  onDelete={() => remove(r.code)}
                />
              ))}
            </div>
          )}
          {!error && !editing && (
            <button
              onClick={() => setEditing('new')}
              className="mt-2 rounded-md border border-[var(--color-line)] px-2.5 py-1 text-xs font-medium text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
            >
              + Define role
            </button>
          )}
          {editing && (
            <RoleForm
              // Keyed by target: switching edit targets must remount the form, or the previous
              // role's field state survives and Save writes it onto the new target's code.
              key={editing === 'new' ? '«new»' : editing.code}
              initial={editing === 'new' ? null : editing}
              busy={busy}
              onSubmit={submit}
              onCancel={() => setEditing(null)}
            />
          )}
          <NoticeLine notice={notice} />
        </div>
      )}
    </div>
  )
}

function RoleRow({
  role,
  busy,
  onEdit,
  onDelete,
}: {
  role: RoleDefinition
  busy: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const perms = summarize(role.permissions)
  const tags = summarize(role.requiredTags)
  const denied = summarize(role.deniedActions)
  return (
    <div className="rounded-lg border border-[var(--color-line)] px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-sm">
          <span className="font-medium">{role.code}</span>
          {role.roleLevel != null && (
            <span className="rounded bg-[var(--color-canvas)] px-1.5 py-0.5 text-xs text-[var(--color-muted)]">
              lvl {role.roleLevel}
            </span>
          )}
          <span
            className="rounded px-1.5 py-0.5 text-xs"
            style={
              role.system
                ? { background: 'var(--color-canvas)', color: 'var(--color-muted)' }
                : { background: '#eef2ff', color: 'var(--color-brand-ink)' }
            }
          >
            {role.system ? 'system' : 'custom'}
          </span>
        </div>
        {!role.system && (
          <div className="flex shrink-0 gap-1.5">
            <button
              onClick={onEdit}
              disabled={busy}
              className="rounded-md border border-[var(--color-line)] px-2 py-0.5 text-xs transition-colors hover:bg-[var(--color-canvas)]"
            >
              edit
            </button>
            <button
              onClick={onDelete}
              disabled={busy}
              className="rounded-md border px-2 py-0.5 text-xs transition-colors hover:bg-[#fef2f2]"
              style={{ borderColor: '#fecaca', color: 'var(--color-deny)' }}
            >
              ✕
            </button>
          </div>
        )}
      </div>
      <div className="mt-0.5 text-xs text-[var(--color-muted)]">
        {perms || 'no grants'}
        {tags && <> — requires {tags}{role.matchMode ? ` (${role.matchMode})` : ''}</>}
        {denied && <> — denies {denied}</>}
      </div>
    </div>
  )
}

function RoleForm({
  initial,
  busy,
  onSubmit,
  onCancel,
}: {
  initial: RoleDefinition | null
  busy: boolean
  onSubmit: (req: RoleDefinitionRequest) => void
  onCancel: () => void
}) {
  const [code, setCode] = useState(initial?.code ?? '')
  const [level, setLevel] = useState(initial?.roleLevel ?? 10)
  const [perms, setPerms] = useState<Record<string, string[]>>(initial?.permissions ?? {})
  const [tags, setTags] = useState(formatKeyedLists(initial?.requiredTags))
  const [denied, setDenied] = useState(formatKeyedLists(initial?.deniedActions))
  const [matchMode, setMatchMode] = useState<'ANY_OF' | 'ALL_OF'>(initial?.matchMode ?? 'ANY_OF')

  const toggle = (type: string, token: string) => {
    setPerms((p) => {
      const cur = new Set(p[type] ?? [])
      if (cur.has(token)) cur.delete(token)
      else cur.add(token)
      const next = { ...p, [type]: [...cur] }
      if (next[type].length === 0) delete next[type]
      return next
    })
  }

  const submit = () => {
    const requiredTags = parseKeyedLists(tags)
    const req: RoleDefinitionRequest = {
      code: code.trim(),
      roleLevel: level,
      permissions: perms,
      deniedActions: parseKeyedLists(denied),
      requiredTags,
      matchMode: Object.keys(requiredTags).length > 0 ? matchMode : null,
    }
    onSubmit(req)
  }

  const offending = RESOURCE_TYPES.flatMap((type) =>
    (perms[type] ?? []).filter((t) => violation(level, type, t)).map((t) => `${type}:${t}`),
  )

  return (
    <div className="mt-2 rounded-lg border border-dashed border-[var(--color-line)] p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          {initial ? `Edit ${initial.code}` : 'Define role'}
        </span>
        <button onClick={onCancel} className="text-xs text-[var(--color-muted)] hover:underline">
          close
        </button>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-2">
        <input
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="role code (kebab-case)"
          disabled={initial !== null}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm disabled:opacity-60"
        />
        <select
          value={level}
          onChange={(e) => setLevel(Number(e.target.value))}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1.5 text-xs"
        >
          {AUTHORABLE_LEVELS.map((l) => (
            <option key={l.level} value={l.level}>
              {l.level} — {l.hint}
            </option>
          ))}
        </select>
      </div>

      <table className="mt-2 text-xs">
        <thead>
          <tr className="text-[var(--color-muted)]">
            <th className="pr-3 text-left font-normal">grants</th>
            {CATEGORY_TOKENS.map((t) => {
              const outside = !(CEILINGS[level] ?? []).includes(t)
              return (
                <th
                  key={t}
                  className="px-2 font-normal"
                  style={outside ? { opacity: 0.35 } : undefined}
                  title={outside ? `outside the level-${level} ceiling` : undefined}
                >
                  {t}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {RESOURCE_TYPES.map((type) => (
            <tr key={type}>
              <td className="pr-3 font-medium">{type}</td>
              {CATEGORY_TOKENS.map((t) => {
                const why = violation(level, type, t)
                const checked = perms[type]?.includes(t) ?? false
                return (
                  <td
                    key={t}
                    className="px-2 text-center"
                    style={checked && why ? { background: '#fef3c7', borderRadius: 4 } : undefined}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggle(type, t)}
                      title={why ? `${why} — submitting shows the live 422` : `within the level-${level} ceiling`}
                    />
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
      {offending.length > 0 && (
        <p className="mt-1 text-xs" style={{ color: '#b45309' }}>
          ⚠ {offending.join(', ')} violate{offending.length === 1 ? 's' : ''} the level-{level}{' '}
          authoring contract — Create will answer the live 422 (uncheck to fix).
        </p>
      )}
      <p className="mt-1 text-xs italic text-[var(--color-muted)]">
        The server enforces the contract, not this form — the amber boxes are left clickable on
        purpose so the 422 stays demoable. READ/WRITE on "team" are accepted but inert: management
        capability is fixed to the system ladder.
      </p>

      <div className="mt-2 grid gap-1.5">
        <input
          value={tags}
          onChange={(e) => setTags(e.target.value)}
          placeholder="required tags, e.g. region=emea|apac; sensitivity=public"
          className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-xs"
        />
        {tags.trim() && (
          <select
            value={matchMode}
            onChange={(e) => setMatchMode(e.target.value as 'ANY_OF' | 'ALL_OF')}
            className="w-fit rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1 text-xs"
          >
            <option value="ANY_OF">ANY_OF — any listed key may match</option>
            <option value="ALL_OF">ALL_OF — every listed key must match</option>
          </select>
        )}
        <input
          value={denied}
          onChange={(e) => setDenied(e.target.value)}
          placeholder="deny-overrides, e.g. category=delete|update (must subtract from grants)"
          className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-xs"
        />
      </div>

      <button
        disabled={busy || code.trim().length === 0}
        onClick={submit}
        className="mt-2 rounded-md bg-[var(--color-brand)] px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
      >
        {busy ? '…' : initial ? 'Save' : 'Create'}
      </button>
    </div>
  )
}
