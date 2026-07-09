import { freshUser } from './auth'

// Minimal typed client over the catalog API, through the gateway. Types mirror the OpenAPI shapes
// (see example-catalog-management-service/.../openapi/catalog-api.yaml). Each enriched resource may
// carry the Phase-6 `_actions` affordance map — present only when the server enriched it, absent
// (never all-false) on enrichment failure.

/** The Phase-6 affordance map: which actions the caller may perform on this resource. */
export type Actions = Record<string, boolean>

/** A resource's tag map: string for a SINGLE-cardinality key, string[] for MULTI. */
export type Tags = Record<string, string | string[]>

export interface Catalog {
  id: string
  name: string
  description?: string
  createdAt: string
  tags?: Tags
  _actions?: Actions
}

export interface Category {
  id: string
  catalogId: string
  parentId?: string | null
  name: string
  description?: string | null
  tags?: Record<string, string | string[]>
  _actions?: Actions
}

export interface Product {
  id: string
  categoryId: string
  name: string
  description?: string | null
  sku?: string
  priceCents?: number
  currency?: string
  _actions?: Actions
}

// User-management shapes (user-mgmt-api.yaml). A User row links an IdP subject (Keycloak `sub`)
// to a profile; memberships reference the row's uuid, never the subject.
export interface User {
  id: string
  subject: string
  displayName: string
}

export interface Team {
  id: string
  name: string
  targetType: string
  targetId: string
  _actions?: Actions
}

/** One tag-dictionary entry (user-service): the vocabulary tag assignments are validated against. */
export interface TagDefinition {
  id: string
  key: string
  scope: 'GLOBAL' | 'TEAM'
  teamId?: string | null
  valueType: 'STRING' | 'ENUM'
  cardinality: 'SINGLE' | 'MULTI'
  allowedValues?: string[]
  valuePattern?: string | null
  system: boolean
}

export interface TagDefinitionRequest {
  key: string
  valueType: 'STRING' | 'ENUM'
  cardinality: 'SINGLE' | 'MULTI'
  allowedValues?: string[]
  valuePattern?: string | null
}

export interface Membership {
  id: string
  teamId: string
  userId: string
  roleCode: string
}

export interface RoleDefinition {
  code: string
  system: boolean
  teamId?: string | null
  roleLevel?: number | null
  attributes?: Record<string, unknown>
  permissions: Record<string, string[]>
  deniedActions?: Record<string, string[]>
  requiredTags?: Record<string, string[]>
  matchMode?: 'ANY_OF' | 'ALL_OF' | null
}

/** The authoring shape (create/update). Every contract violation answers 422 ROLE_DEFINITION_INVALID. */
export interface RoleDefinitionRequest {
  code: string
  roleLevel: number
  permissions: Record<string, string[]>
  deniedActions?: Record<string, string[]>
  requiredTags?: Record<string, string[]>
  matchMode?: 'ANY_OF' | 'ALL_OF' | null
}

/** The shared pagination envelope: {count, page, perPage, items}. */
export interface Page<T> {
  count: number
  page: number
  perPage: number
  items: T[]
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // freshUser refreshes an expired access token on demand (refresh grant) — without it, a demo
  // session older than the token lifespan sends a dead bearer and every call 401s at the gateway.
  const user = await freshUser()
  const headers = new Headers(init?.headers)
  // Not a bare application/json: several 204-only endpoints (e.g. DELETE role-definition) declare
  // only problem+json response content, so a json-only Accept fails Spring's content negotiation
  // with a whitelabel 406 before the handler even runs.
  headers.set('Accept', 'application/json, application/problem+json, */*')
  if (init?.body) headers.set('Content-Type', 'application/json')
  if (user?.access_token) headers.set('Authorization', `Bearer ${user.access_token}`)

  const res = await fetch(path, { ...init, headers })
  if (!res.ok) {
    let detail = res.statusText
    try {
      const body = await res.json()
      detail = body.detail ?? body.errorCode ?? detail
    } catch {
      /* non-JSON error body; keep statusText */
    }
    // A gateway 401 carries no problem body — after the on-demand refresh above, it means the
    // whole session lapsed (refresh token idle-timed-out). Say so instead of a bare "Unauthorized".
    if (res.status === 401 && detail === res.statusText) {
      detail = 'session expired — use "Switch identity" to sign in again'
    }
    throw new ApiError(res.status, detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

// --- reads -------------------------------------------------------------------
export function listCatalogs(page = 0, perPage = 100): Promise<Page<Catalog>> {
  return request<Page<Catalog>>(`/api/v1/catalogs?page=${page}&perPage=${perPage}`)
}

/** Single catalog GET — this IS enriched (the list endpoint is not). */
export function getCatalog(id: string): Promise<Catalog> {
  return request<Catalog>(`/api/v1/catalogs/${id}`)
}

export function listCategories(catalogId: string, page = 0, perPage = 100): Promise<Page<Category>> {
  return request<Page<Category>>(
    `/api/v1/catalogs/${catalogId}/categories?page=${page}&perPage=${perPage}`,
  )
}

export function listProducts(
  catalogId: string,
  categoryId: string,
  page = 0,
  perPage = 100,
): Promise<Page<Product>> {
  return request<Page<Product>>(
    `/api/v1/catalogs/${catalogId}/categories/${categoryId}/products?page=${page}&perPage=${perPage}`,
  )
}

// --- mutations (driven by the affordance buttons) ----------------------------
// The creates are NOT keyed off `_actions`: enrichment probes only instance-scoped verbs on the
// resource itself, and child-create is a type-level decision resolved against the parent
// (roleResource, ADR 0018) — so the UI offers the form and a denied POST answers honestly.
export function createCategory(
  catalogId: string,
  body: { name: string; description?: string; tags?: Tags },
): Promise<Category> {
  return request<Category>(`/api/v1/catalogs/${catalogId}/categories`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function createProduct(
  catalogId: string,
  categoryId: string,
  body: { name: string; priceCents: number; currency: string; description?: string },
): Promise<Product> {
  return request<Product>(`/api/v1/catalogs/${catalogId}/categories/${categoryId}/products`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

// PUT is a full replace — and ABSENT tags mean "clear all tags" (the server's delta dispatch reads
// null as an empty map, and clearing IS a tags change). Callers must echo the current tags back
// unless a tag change is exactly what they're asking for. Catalogs carry the same semantics since
// ADR 0022 (taggable roots).
export function updateCatalog(
  id: string,
  patch: { name: string; description?: string; tags?: Tags },
): Promise<Catalog> {
  return request<Catalog>(`/api/v1/catalogs/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch),
  })
}

export function updateCategory(
  catalogId: string,
  id: string,
  patch: { name: string; description?: string; parentId?: string | null; tags?: Tags },
): Promise<Category> {
  return request<Category>(`/api/v1/catalogs/${catalogId}/categories/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch),
  })
}

export function deleteCategory(catalogId: string, id: string): Promise<void> {
  return request<void>(`/api/v1/catalogs/${catalogId}/categories/${id}`, { method: 'DELETE' })
}

export function updateProduct(
  catalogId: string,
  categoryId: string,
  id: string,
  patch: { name: string; description?: string; priceCents?: number; currency?: string },
): Promise<Product> {
  return request<Product>(`/api/v1/catalogs/${catalogId}/categories/${categoryId}/products/${id}`, {
    method: 'PUT',
    body: JSON.stringify(patch),
  })
}

export function deleteProduct(catalogId: string, categoryId: string, id: string): Promise<void> {
  return request<void>(`/api/v1/catalogs/${catalogId}/categories/${categoryId}/products/${id}`, {
    method: 'DELETE',
  })
}

// --- self-service: catalogs + teams (Slice: team-management UI) --------------
export function createCatalog(body: { name: string; description?: string }): Promise<Catalog> {
  return request<Catalog>(`/api/v1/catalogs`, { method: 'POST', body: JSON.stringify(body) })
}

export function createTeam(body: {
  name: string
  targetType: string
  targetId: string
}): Promise<Team> {
  return request<Team>(`/api/v1/teams`, { method: 'POST', body: JSON.stringify(body) })
}

export function listUsers(page = 0, perPage = 100): Promise<Page<User>> {
  return request<Page<User>>(`/api/v1/users?page=${page}&perPage=${perPage}`)
}

/**
 * The tag dictionary visible to a team: the GLOBAL keys plus the team's own custom keys. This is
 * the team-scoped path deliberately — the flat `/api/v1/tag-definitions` listing is NOT
 * gateway-exposed (the gateway proxies only /api/v1/teams* + /api/v1/users* to the user-service),
 * while this one rides the /api/v1/teams* route. Drives the tag pickers; assignments are
 * validated against the dictionary server-side either way.
 */
export function listTeamTagDefinitions(teamId: string, page = 0, perPage = 100): Promise<Page<TagDefinition>> {
  return request<Page<TagDefinition>>(
    `/api/v1/teams/${teamId}/tag-definitions?page=${page}&perPage=${perPage}`,
  )
}

// Tag-key authoring (owner/administrator — team:define-tags); global/system keys are immutable
// server-side, so mutations address team-scoped keys only.
export function createTeamTagDefinition(
  teamId: string,
  body: TagDefinitionRequest,
): Promise<TagDefinition> {
  return request<TagDefinition>(`/api/v1/teams/${teamId}/tag-definitions`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateTeamTagDefinition(
  teamId: string,
  key: string,
  body: Omit<TagDefinitionRequest, 'key'>,
): Promise<TagDefinition> {
  return request<TagDefinition>(
    `/api/v1/teams/${teamId}/tag-definitions/${encodeURIComponent(key)}`,
    { method: 'PUT', body: JSON.stringify(body) },
  )
}

export function deleteTeamTagDefinition(teamId: string, key: string): Promise<void> {
  return request<void>(`/api/v1/teams/${teamId}/tag-definitions/${encodeURIComponent(key)}`, {
    method: 'DELETE',
  })
}

// perPage is hard-capped at 100 server-side, so scanning a whole collection means walking pages.
// The single-resource lookups no longer walk: server-side exact-match filters (?subject on /users,
// ?targetType+?targetId on /teams) answer them in one request (DIRECTORY-QUERY-FILTERS). The
// member-picker no longer walks either — it searches the identity directory server-side
// (searchDirectory, Slice 2); listAllUsers remains only to resolve roster rows to display names.
async function listAll<T>(fetchPage: (page: number) => Promise<Page<T>>): Promise<T[]> {
  const first = await fetchPage(0)
  const items = [...first.items]
  let page = 0
  while (items.length < first.count) {
    page += 1
    const next = await fetchPage(page)
    if (next.items.length === 0) break
    items.push(...next.items)
  }
  return items
}

export function listAllUsers(): Promise<User[]> {
  return listAll((p) => listUsers(p))
}

/**
 * One identity-directory account (USER-DIRECTORY-PORT): exactly `subject` + `displayName` — the
 * type-bounded disclosure ceiling. No `id`: a directory account may have no provisioned profile
 * yet; the SPA provisions on select via {@link ensureUser}.
 */
export interface DirectoryUser {
  subject: string
  displayName: string
}

export interface DirectoryUserList {
  items: DirectoryUser[]
  limit: number
}

/**
 * Searches the identity directory — every realm account, not just provisioned profiles (the
 * UserDirectory port, Slice 2). A bounded plain list, NOT a Page (no count; limit clamps to 20
 * default / 50 max server-side). No-oracle contract: blank `q`, zero matches, and a directory
 * outage are all the same 200-empty — render them identically.
 */
export function searchDirectory(q: string, limit = 20): Promise<DirectoryUserList> {
  return request<DirectoryUserList>(
    `/api/v1/users/search?q=${encodeURIComponent(q)}&limit=${limit}`,
  )
}

/**
 * One-shot user lookup by IdP subject (the `?subject=` exact-match filter): a one-item page — 0 or
 * 1 rows, never a page-walk, never a truncated miss.
 */
export async function findUserBySubject(subject: string): Promise<User | null> {
  const page = await request<Page<User>>(
    `/api/v1/users?subject=${encodeURIComponent(subject)}`,
  )
  return page.items[0] ?? null
}

/**
 * One-shot lookup of the team governing a team-target (the `?targetType=&targetId=` exact-match
 * pair filter, both-or-400): a one-item page — `items[0]` is the governing team or absent.
 */
export function lookupTeamByTarget(targetType: string, targetId: string): Promise<Page<Team>> {
  return request<Page<Team>>(
    `/api/v1/teams?targetType=${encodeURIComponent(targetType)}&targetId=${encodeURIComponent(targetId)}`,
  )
}

export function listMembers(teamId: string, page = 0, perPage = 100): Promise<Page<Membership>> {
  return request<Page<Membership>>(`/api/v1/teams/${teamId}/members?page=${page}&perPage=${perPage}`)
}

export function addMember(teamId: string, userId: string, roleCode: string): Promise<Membership> {
  return request<Membership>(`/api/v1/teams/${teamId}/members`, {
    method: 'POST',
    body: JSON.stringify({ userId, roleCode }),
  })
}

export function changeRole(teamId: string, userId: string, roleCode: string): Promise<Membership> {
  return request<Membership>(`/api/v1/teams/${teamId}/members/${userId}`, {
    method: 'PUT',
    body: JSON.stringify({ roleCode }),
  })
}

export function removeMember(teamId: string, userId: string): Promise<void> {
  return request<void>(`/api/v1/teams/${teamId}/members/${userId}`, { method: 'DELETE' })
}

export function transferOwnership(teamId: string, newOwnerUserId: string): Promise<void> {
  return request<void>(`/api/v1/teams/${teamId}/transfer-ownership`, {
    method: 'POST',
    body: JSON.stringify({ newOwnerUserId }),
  })
}

// --- role definitions (owner-only: team:define-roles) -------------------------
export function listRoleDefinitions(teamId: string, page = 0, perPage = 100): Promise<Page<RoleDefinition>> {
  return request<Page<RoleDefinition>>(
    `/api/v1/teams/${teamId}/role-definitions?page=${page}&perPage=${perPage}`,
  )
}

export function createRoleDefinition(teamId: string, body: RoleDefinitionRequest): Promise<RoleDefinition> {
  return request<RoleDefinition>(`/api/v1/teams/${teamId}/role-definitions`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateRoleDefinition(
  teamId: string,
  code: string,
  body: Omit<RoleDefinitionRequest, 'code'>,
): Promise<RoleDefinition> {
  return request<RoleDefinition>(`/api/v1/teams/${teamId}/role-definitions/${code}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteRoleDefinition(teamId: string, code: string): Promise<void> {
  return request<void>(`/api/v1/teams/${teamId}/role-definitions/${code}`, { method: 'DELETE' })
}

/**
 * Ensure the signed-in identity has a User profile row. Memberships (and team creation) key on the
 * user-service's own uuid, so a first-time identity must be provisioned before it can own anything.
 * Idempotent: one exact `?subject=` lookup (non-truncating — no page-walk), create only on a miss.
 */
export async function ensureUser(subject: string, displayName: string): Promise<User> {
  const existing = await findUserBySubject(subject)
  if (existing) return existing
  return request<User>(`/api/v1/users`, {
    method: 'POST',
    body: JSON.stringify({ subject, displayName }),
  })
}
