import { useMemo, useState } from 'react'
import {
  type Catalog,
  type Membership,
  type Team,
  type User,
  addMember,
  changeRole,
  createCatalog,
  createTeam,
  listAllUsers,
  listMembers,
  lookupTeamByTarget,
  listRoleDefinitions,
  removeMember,
  transferOwnership,
} from './api'
import { type Notice, NoticeLine, errText, useAsync } from './components'
import { RolesSection } from './roles'

// The system ladder (10 reader … 40 owner). Owner is never assignable — ownership moves only
// through transfer-ownership — so the pickers offer the four authorable tiers.
const ASSIGNABLE_ROLES = [
  { code: 'administrator', level: 30 },
  { code: 'senior', level: 25 },
  { code: 'member', level: 20 },
  { code: 'reader', level: 10 },
]
const DEFAULT_ROLE = 'member'

// ---------------------------------------------------------------- TeamPanel

/**
 * The team governing a catalog: roster + membership management. Manage controls are deliberately
 * optimistic — the change-role / transfer verbs are co-gated in Java (escalation ladder), which OPA
 * alone can't pre-answer (see TeamEnrichable), so the UI shows the action and lets the server's
 * typed denial (403/409/422) tell the story.
 */
export function TeamPanel({ catalogId, mySubject }: { catalogId: string; mySubject: string }) {
  // One-shot lookup (DIRECTORY-QUERY-FILTERS): the governing team answers in a single filtered
  // request (?targetType&targetId) — no page-walk, no truncated miss. The user list is still a
  // full walk: it feeds the member picker, whose real directory arrives with Slice 2.
  const teams = useAsync(() => lookupTeamByTarget('catalog', catalogId), [catalogId])
  const users = useAsync(() => listAllUsers(), [])

  const team = teams.data?.items[0]

  return (
    <div className="mt-6">
      <div className="mb-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          Team
        </h2>
        <p className="mt-0.5 text-xs text-[var(--color-muted)]">
          Membership is the sole access path to this catalog. Adding, re-tiering and removing
          members is decided per-action by OPA + the escalation ladder — denied attempts answer
          honestly.
        </p>
      </div>
      {teams.error && <PanelNote tone="deny">Failed to load teams: {teams.error}</PanelNote>}
      {!teams.data && !teams.error && (
        <PanelNote tone="muted">Loading team…</PanelNote>
      )}
      {teams.data && !team && (
        <PanelNote tone="muted">
          No team governs this catalog — without one, only its creator can reach it.
        </PanelNote>
      )}
      {team && (
        <Roster
          team={team}
          users={users.data ?? []}
          usersReady={users.data !== null}
          mySubject={mySubject}
        />
      )}
    </div>
  )
}

function PanelNote({ tone, children }: { tone: 'muted' | 'deny'; children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--color-line)] bg-[var(--color-surface)] p-4 shadow-sm">
      <span
        className="text-sm"
        style={{ color: tone === 'deny' ? 'var(--color-deny)' : 'var(--color-muted)' }}
      >
        {children}
      </span>
    </div>
  )
}

function Roster({
  team,
  users,
  usersReady,
  mySubject,
}: {
  team: Team
  users: User[]
  usersReady: boolean
  mySubject: string
}) {
  const members = useAsync(() => listMembers(team.id), [team.id])
  // Owner-only (team:define-roles) — everyone else falls back to the hardcoded system ladder.
  const roleDefs = useAsync(() => listRoleDefinitions(team.id), [team.id])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  // Hold the manage controls until the directory is in: `me` and display names come from it, and
  // acting before it resolves would e.g. show a self-removal confirm without the lose-access warning.
  const held = busy || !usersReady

  const usersById = useMemo(() => new Map(users.map((u) => [u.id, u])), [users])
  const me = users.find((u) => u.subject === mySubject)

  const assignableRoles = roleDefs.data
    ? roleDefs.data.items
        .filter((r) => r.code !== 'owner')
        .map((r) => ({ code: r.code, level: r.roleLevel ?? 0 }))
        .sort((a, b) => b.level - a.level)
    : ASSIGNABLE_ROLES

  const act = async (label: string, fn: () => Promise<unknown>) => {
    setBusy(true)
    setNotice(null)
    try {
      await fn()
      setNotice({ ok: true, text: `${label} succeeded` })
      members.reload()
    } catch (e) {
      setNotice({ ok: false, text: `${label} failed: ${errText(e)}` })
    } finally {
      setBusy(false)
    }
  }

  if (members.error)
    return (
      <PanelNote tone="deny">
        Roster not visible to you: {members.error}
      </PanelNote>
    )
  if (!members.data) return <PanelNote tone="muted">Loading members…</PanelNote>

  const roster = members.data.items
  const memberIds = new Set(roster.map((m) => m.userId))

  return (
    <div className="rounded-xl border border-[var(--color-line)] bg-[var(--color-surface)] p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <span className="font-medium">{team.name}</span>
        <span className="text-xs text-[var(--color-muted)]">
          {roster.length} member{roster.length === 1 ? '' : 's'}
        </span>
      </div>
      <div className="mt-3 grid gap-2">
        {roster.map((m) => (
          <MemberRow
            key={m.id}
            membership={m}
            user={usersById.get(m.userId)}
            isMe={me?.id === m.userId}
            roles={assignableRoles}
            busy={held}
            onChangeRole={(roleCode) =>
              act(`change-role → ${roleCode}`, () => changeRole(team.id, m.userId, roleCode))
            }
            onRemove={() => {
              const who = usersById.get(m.userId)?.displayName ?? m.userId
              const self = me?.id === m.userId
              if (
                window.confirm(
                  self
                    ? 'Remove yourself? You will lose access to this catalog.'
                    : `Remove ${who} from the team?`,
                )
              )
                act(`remove ${who}`, () => removeMember(team.id, m.userId))
            }}
            onTransfer={() => {
              const who = usersById.get(m.userId)?.displayName ?? m.userId
              if (window.confirm(`Transfer ownership to ${who}? You stop being the owner.`))
                act(`transfer-ownership → ${who}`, () => transferOwnership(team.id, m.userId))
            }}
          />
        ))}
      </div>
      <AddMemberForm
        users={users.filter((u) => !memberIds.has(u.id))}
        roles={assignableRoles}
        busy={held}
        onAdd={(user, roleCode) =>
          act(`add ${user.displayName} as ${roleCode}`, () =>
            addMember(team.id, user.id, roleCode),
          )
        }
      />
      <RolesSection
        team={team}
        roleDefs={roleDefs.data}
        error={roleDefs.error}
        onChanged={roleDefs.reload}
      />
      <NoticeLine notice={notice} />
    </div>
  )
}

function MemberRow({
  membership,
  user,
  isMe,
  roles,
  busy,
  onChangeRole,
  onRemove,
  onTransfer,
}: {
  membership: Membership
  user?: User
  isMe: boolean
  roles: { code: string; level: number }[]
  busy: boolean
  onChangeRole: (roleCode: string) => void
  onRemove: () => void
  onTransfer: () => void
}) {
  const isOwner = membership.roleCode === 'owner'
  // Keep the row's select renderable even for a role code the list doesn't know.
  const roleOptions = roles.some((r) => r.code === membership.roleCode)
    ? roles
    : [{ code: membership.roleCode, level: 0 }, ...roles]

  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border border-[var(--color-line)] px-3 py-2">
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 text-sm font-medium">
          <span className="truncate">{user?.displayName ?? membership.userId}</span>
          {isOwner && <span title="Team owner">👑</span>}
          {isMe && (
            <span className="rounded bg-[var(--color-canvas)] px-1.5 py-0.5 text-xs text-[var(--color-muted)]">
              you
            </span>
          )}
        </div>
        {user && (
          <div className="truncate text-xs text-[var(--color-muted)]">{user.subject}</div>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        {isOwner ? (
          <span className="text-xs text-[var(--color-muted)]" title="Owners leave only via transfer-ownership">
            owner
          </span>
        ) : (
          <>
            <select
              value={membership.roleCode}
              disabled={busy}
              onChange={(e) => onChangeRole(e.target.value)}
              className="rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1 text-xs"
              title="Change this member's role — the escalation ladder answers"
            >
              {roleOptions.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.code}
                </option>
              ))}
            </select>
            <button
              onClick={onTransfer}
              disabled={busy}
              title="Transfer ownership to this member (owner-only)"
              className="rounded-md border border-[var(--color-line)] px-2 py-1 text-xs text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
            >
              👑
            </button>
            <button
              onClick={onRemove}
              disabled={busy}
              title="Remove from team"
              className="rounded-md border px-2 py-1 text-xs transition-colors hover:bg-[#fef2f2]"
              style={{ borderColor: '#fecaca', color: 'var(--color-deny)' }}
            >
              ✕
            </button>
          </>
        )}
      </div>
    </div>
  )
}

function AddMemberForm({
  users,
  roles,
  busy,
  onAdd,
}: {
  users: User[]
  roles: { code: string; level: number }[]
  busy: boolean
  onAdd: (user: User, roleCode: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<User | null>(null)
  const [roleCode, setRoleCode] = useState(DEFAULT_ROLE)

  if (!open)
    return (
      <button
        onClick={() => setOpen(true)}
        className="mt-3 rounded-md border border-[var(--color-line)] px-2.5 py-1 text-xs font-medium text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
      >
        + Add member
      </button>
    )

  const candidates = users.filter(
    (u) =>
      u.displayName.toLowerCase().includes(query.toLowerCase()) ||
      u.subject.toLowerCase().includes(query.toLowerCase()),
  )

  return (
    <div className="mt-3 rounded-lg border border-dashed border-[var(--color-line)] p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          Add member
        </span>
        <button
          onClick={() => {
            setOpen(false)
            setSelected(null)
            setQuery('')
          }}
          className="text-xs text-[var(--color-muted)] hover:underline"
        >
          close
        </button>
      </div>
      <input
        autoFocus
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setSelected(null)
        }}
        placeholder="Search the user directory…"
        className="mt-2 w-full rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm"
      />
      <div className="mt-2 grid max-h-40 gap-1 overflow-auto">
        {candidates.length === 0 && (
          <span className="text-xs text-[var(--color-muted)]">
            No directory users match — only provisioned profiles are listed.
          </span>
        )}
        {candidates.map((u) => (
          <button
            key={u.id}
            onClick={() => setSelected(u)}
            className="flex items-center justify-between rounded-md border px-2.5 py-1.5 text-left text-sm transition-colors"
            style={{
              borderColor: selected?.id === u.id ? 'var(--color-brand)' : 'var(--color-line)',
              background: selected?.id === u.id ? '#eef2ff' : 'transparent',
            }}
          >
            <span className="font-medium">{u.displayName}</span>
            <span className="truncate pl-3 text-xs text-[var(--color-muted)]">{u.subject}</span>
          </button>
        ))}
      </div>
      <div className="mt-2 flex items-center gap-2">
        <select
          value={roleCode}
          onChange={(e) => setRoleCode(e.target.value)}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-1.5 py-1 text-xs"
        >
          {roles.map((r) => (
            <option key={r.code} value={r.code}>
              {r.code}
            </option>
          ))}
        </select>
        <button
          disabled={!selected || busy}
          onClick={() => {
            if (!selected) return
            onAdd(selected, roleCode)
            setSelected(null)
            setQuery('')
          }}
          className="rounded-md bg-[var(--color-brand)] px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          Add
        </button>
      </div>
    </div>
  )
}

// ------------------------------------------------------- CreateCatalogPanel

/**
 * Self-service catalog creation is a two-step contract: POST the catalog, then POST its owning
 * team (owner-on-create, ADR 0019). A catalog without a team is invisible to everyone — so when
 * step 2 fails we keep the created catalog and retry only the team.
 */
export function CreateCatalogPanel({ onCreated }: { onCreated: () => void }) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [pendingCatalog, setPendingCatalog] = useState<Catalog | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const reset = () => {
    setOpen(false)
    setName('')
    setDescription('')
    setPendingCatalog(null)
    setNotice(null)
  }

  const submit = async () => {
    setBusy(true)
    setNotice(null)
    // Local, not the state var: the catch must see a catalog created in THIS call (the state
    // update hasn't re-rendered yet), or the first team-step failure loses its recovery message.
    let catalog = pendingCatalog
    try {
      if (!catalog) {
        catalog = await createCatalog({ name, description: description || undefined })
        setPendingCatalog(catalog)
      }
      await createTeam({
        name: `${catalog.name} team`,
        targetType: 'catalog',
        targetId: catalog.id,
      })
      reset()
      onCreated()
    } catch (e) {
      setNotice({
        ok: false,
        text: catalog
          ? `Catalog "${catalog.name}" exists but its team failed: ${errText(e)} — retry creates only the team.`
          : `create failed: ${errText(e)}`,
      })
    } finally {
      setBusy(false)
    }
  }

  if (!open)
    return (
      <button
        onClick={() => setOpen(true)}
        className="mb-3 rounded-md border border-[var(--color-line)] px-2.5 py-1 text-xs font-medium text-[var(--color-brand-ink)] transition-colors hover:bg-[var(--color-canvas)]"
      >
        + New catalog
      </button>
    )

  return (
    <div className="mb-4 rounded-xl border border-dashed border-[var(--color-line)] bg-[var(--color-surface)] p-4">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-[var(--color-muted)]">
          New catalog — you become its team's owner
        </span>
        <button onClick={reset} className="text-xs text-[var(--color-muted)] hover:underline">
          close
        </button>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <input
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Catalog name"
          disabled={pendingCatalog !== null}
          className="rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm"
        />
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Description (optional)"
          disabled={pendingCatalog !== null}
          className="min-w-48 flex-1 rounded-md border border-[var(--color-line)] bg-transparent px-2.5 py-1.5 text-sm"
        />
        <button
          disabled={busy || (!pendingCatalog && name.trim().length === 0)}
          onClick={submit}
          className="rounded-md bg-[var(--color-brand)] px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-[var(--color-brand-ink)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? '…' : pendingCatalog ? 'Retry team' : 'Create'}
        </button>
      </div>
      <NoticeLine notice={notice} />
    </div>
  )
}
