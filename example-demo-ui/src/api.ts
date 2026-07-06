import { userManager } from './auth'

// Minimal typed client over the catalog API, through the gateway. Types mirror the OpenAPI shapes
// (see example-catalog-management-service/.../openapi/catalog-api.yaml). Each enriched resource may
// carry the Phase-6 `_actions` affordance map — present only when the server enriched it, absent
// (never all-false) on enrichment failure.

/** The Phase-6 affordance map: which actions the caller may perform on this resource. */
export type Actions = Record<string, boolean>

export interface Catalog {
  id: string
  name: string
  description?: string
  createdAt: string
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
  const user = await userManager.getUser()
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
export function updateCategory(
  catalogId: string,
  id: string,
  patch: { name: string; description?: string },
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
  patch: { name: string; description?: string; priceCents?: number },
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

export function listTeams(page = 0, perPage = 100): Promise<Page<Team>> {
  return request<Page<Team>>(`/api/v1/teams?page=${page}&perPage=${perPage}`)
}

// perPage is hard-capped at 100 server-side, so anything that scans a whole collection (the team-
// by-target lookup, the user directory, ensureUser's find-by-subject) must walk pages — a page-0
// scan silently misses rows once the store outgrows one page. Slice-2 backlog: server-side
// ?targetType/?targetId and ?subject filters make these walks one-shot.
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

export function listAllTeams(): Promise<Team[]> {
  return listAll((p) => listTeams(p))
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
 * Idempotent: finds the row by subject first (walking all pages), creates it only when missing.
 */
export async function ensureUser(subject: string, displayName: string): Promise<User> {
  const users = await listAllUsers()
  const existing = users.find((u) => u.subject === subject)
  if (existing) return existing
  return request<User>(`/api/v1/users`, {
    method: 'POST',
    body: JSON.stringify({ subject, displayName }),
  })
}
