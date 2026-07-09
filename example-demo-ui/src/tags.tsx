import { useState } from 'react'
import {
  type Page,
  type TagDefinition,
  type TagDefinitionRequest,
  type Team,
  createTeamTagDefinition,
  deleteTeamTagDefinition,
  updateTeamTagDefinition,
} from './api'
import { type Notice, NoticeLine, errText } from './components'

/**
 * The tag-dictionary editor (the RolesSection sibling): team-defined keys extend the global
 * dictionary, and anything defined here becomes assignable on this catalog's categories
 * immediately — that's the "user-managed tags" demo. Authoring is owner/administrator
 * (team:define-tags); GLOBAL/system keys render read-only (the server keeps them immutable), and
 * every deny/409/422 answers honestly in the notice line.
 */
export function TagKeysSection({
  team,
  tagDefs,
  error,
  onChanged,
}: {
  team: Team
  tagDefs: Page<TagDefinition> | null
  error: string | null
  onChanged: () => void
}) {
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<TagDefinition | 'new' | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const submit = async (req: TagDefinitionRequest) => {
    setBusy(true)
    setNotice(null)
    const existing = editing === 'new' ? null : editing
    try {
      if (existing) {
        const { key: _key, ...update } = req
        await updateTeamTagDefinition(team.id, existing.key, update)
      } else {
        await createTeamTagDefinition(team.id, req)
      }
      setNotice({ ok: true, text: `${existing ? 'update' : 'define'} ${req.key} succeeded` })
      setEditing(null)
      onChanged()
    } catch (e) {
      setNotice({ ok: false, text: `${existing ? 'update' : 'define'} failed: ${errText(e)}` })
    } finally {
      setBusy(false)
    }
  }

  const remove = async (key: string) => {
    if (!window.confirm(`Delete team tag key "${key}"?`)) return
    setBusy(true)
    setNotice(null)
    try {
      await deleteTeamTagDefinition(team.id, key)
      setNotice({ ok: true, text: `delete ${key} succeeded` })
      onChanged()
    } catch (e) {
      setNotice({ ok: false, text: `delete ${key} failed: ${errText(e)}` })
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
        {open ? '▾' : '▸'} Tag keys{tagDefs ? ` (${tagDefs.count})` : ''}
      </button>
      {open && (
        <div className="mt-2">
          <p className="mb-2 text-xs text-[var(--color-muted)]">
            The dictionary the tag pickers validate against: global keys plus this team's own. A
            key defined here is assignable on this catalog's categories immediately.
          </p>
          {error && (
            <p className="text-xs text-[var(--color-deny)]">
              Tag keys not visible to you: {error}
            </p>
          )}
          {!error && !tagDefs && (
            <p className="text-xs text-[var(--color-muted)]">Loading tag keys…</p>
          )}
          {tagDefs && (
            <div className="grid gap-1.5">
              {tagDefs.items.map((d) => (
                <TagKeyRow
                  key={d.key}
                  def={d}
                  busy={busy}
                  onEdit={() => setEditing(d)}
                  onDelete={() => remove(d.key)}
                />
              ))}
            </div>
          )}
          {!error && !editing && (
            <button
              onClick={() => setEditing('new')}
              className="mt-2 rounded-md border border-[var(--color-line)] px-2.5 py-1 text-xs font-medium text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
            >
              + Define tag key
            </button>
          )}
          {editing && (
            <TagKeyForm
              // Keyed by target: switching edit targets must remount the form (the RolesSection
              // lesson — stale field state otherwise survives onto the new target).
              key={editing === 'new' ? '«new»' : editing.key}
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

function TagKeyRow({
  def,
  busy,
  onEdit,
  onDelete,
}: {
  def: TagDefinition
  busy: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const values =
    def.valueType === 'ENUM'
      ? (def.allowedValues ?? []).join(' | ')
      : def.valuePattern
        ? `pattern ${def.valuePattern}`
        : 'free text'
  const custom = def.scope === 'TEAM' && !def.system
  return (
    <div className="rounded-lg border border-[var(--color-line)] px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-sm">
          <span className="font-medium">{def.key}</span>
          <span
            className="rounded px-1.5 py-0.5 text-xs"
            style={
              custom
                ? { background: '#eef2ff', color: 'var(--color-brand-ink)' }
                : { background: 'var(--color-canvas)', color: 'var(--color-muted)' }
            }
          >
            {custom ? 'team' : 'global'}
          </span>
        </div>
        {custom && (
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
        {def.valueType} · {def.cardinality} · {values}
      </div>
    </div>
  )
}

function TagKeyForm({
  initial,
  busy,
  onSubmit,
  onCancel,
}: {
  initial: TagDefinition | null
  busy: boolean
  onSubmit: (req: TagDefinitionRequest) => void
  onCancel: () => void
}) {
  const [key, setKey] = useState(initial?.key ?? '')
  const [valueType, setValueType] = useState<'STRING' | 'ENUM'>(initial?.valueType ?? 'ENUM')
  const [cardinality, setCardinality] = useState<'SINGLE' | 'MULTI'>(
    initial?.cardinality ?? 'SINGLE',
  )
  const [allowed, setAllowed] = useState((initial?.allowedValues ?? []).join(', '))
  const [pattern, setPattern] = useState(initial?.valuePattern ?? '')

  const submit = () => {
    const allowedValues = allowed
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    onSubmit({
      key: key.trim(),
      valueType,
      cardinality,
      // ENUM needs its closed set; a STRING key carries the optional pattern instead. Sending an
      // empty allowedValues for ENUM is left possible on purpose — the live 422 is the demo.
      allowedValues: valueType === 'ENUM' ? allowedValues : undefined,
      valuePattern: valueType === 'STRING' && pattern.trim() ? pattern.trim() : undefined,
    })
  }

  return (
    <div className="mt-2 rounded-lg border border-dashed border-[var(--color-line)] p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          {initial ? `Edit ${initial.key}` : 'Define tag key'}
        </span>
        <button onClick={onCancel} className="text-xs text-[var(--color-muted)] hover:underline">
          close
        </button>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <input
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder="tag key (kebab-case), e.g. tier"
          disabled={initial !== null}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm disabled:opacity-60"
        />
        <select
          value={valueType}
          onChange={(e) => setValueType(e.target.value as 'STRING' | 'ENUM')}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1.5 text-xs"
        >
          <option value="ENUM">ENUM — closed value set</option>
          <option value="STRING">STRING — free text (optional pattern)</option>
        </select>
        <select
          value={cardinality}
          onChange={(e) => setCardinality(e.target.value as 'SINGLE' | 'MULTI')}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1.5 text-xs"
        >
          <option value="SINGLE">SINGLE — one value</option>
          <option value="MULTI">MULTI — many values</option>
        </select>
      </div>
      <div className="mt-2 grid gap-1.5">
        {valueType === 'ENUM' && (
          <input
            value={allowed}
            onChange={(e) => setAllowed(e.target.value)}
            placeholder="allowed values (comma-separated), e.g. gold, silver, bronze"
            className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-xs"
          />
        )}
        {valueType === 'STRING' && (
          <input
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            placeholder="value pattern (optional regex), e.g. ^[a-z0-9-]+$"
            className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-xs"
          />
        )}
      </div>
      <p className="mt-1 text-xs italic text-[var(--color-muted)]">
        The server enforces the contract (owner/administrator only; ENUM needs values; global keys
        immutable) — denials and 422s answer honestly below.
      </p>
      <button
        disabled={busy || key.trim().length === 0}
        onClick={submit}
        className="mt-2 rounded-md bg-[var(--color-brand)] px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
      >
        {busy ? '…' : initial ? 'Save' : 'Define'}
      </button>
    </div>
  )
}
