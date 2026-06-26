import { userManager } from './auth'

// Minimal typed client over the catalog API, through the gateway. Types mirror the OpenAPI shapes
// (see example-catalog-management-service/.../openapi/catalog-api.yaml). We only model what the demo
// renders today (catalogs + the _actions affordance map); more is added as the views grow.

/** The Phase-6 affordance map: which actions the caller may perform on this resource. */
export type Actions = Record<string, boolean>

export interface Catalog {
  id: string
  name: string
  description?: string
  createdAt: string
  /** Present only when the server enriched it; absent (not all-false) on enrichment failure. */
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

/** Attach the current access token and call the gateway. Same-origin: paths start with /api. */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const user = await userManager.getUser()
  const headers = new Headers(init?.headers)
  headers.set('Accept', 'application/json')
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

export function listCatalogs(page = 0, perPage = 20): Promise<Page<Catalog>> {
  return request<Page<Catalog>>(`/api/v1/catalogs?page=${page}&perPage=${perPage}`)
}
