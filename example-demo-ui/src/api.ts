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
  headers.set('Accept', 'application/json')
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
