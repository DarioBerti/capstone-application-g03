import { getToken } from './auth'
import type { Authorization, InventoryItem, Order, Page, Product, Profile } from './types'

// All requests go to same-origin /api/* and are proxied to the right service by Vite (vite.config.ts).
// The services are OAuth2 resource servers, so every call carries the user's Keycloak bearer token.
async function authedFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const token = await getToken()
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return fetch(input, { ...init, headers })
}

async function json<T>(res: Response): Promise<T> {
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}${text ? ` — ${text}` : ''}`)
  }
  return (text ? JSON.parse(text) : undefined) as T
}

export interface ProductQuery {
  name?: string
  maxPriceCents?: number
  page?: number
  size?: number
}

export const api = {
  listProducts: (q: ProductQuery = {}): Promise<Page<Product>> => {
    const p = new URLSearchParams()
    if (q.name) p.set('name', q.name)
    if (q.maxPriceCents != null) p.set('maxPriceCents', String(q.maxPriceCents))
    p.set('page', String(q.page ?? 0))
    p.set('size', String(q.size ?? 50))
    return authedFetch(`/api/catalog/products?${p.toString()}`).then((r) => json<Page<Product>>(r))
  },

  placeOrder: (body: { productId: string; qty: number }): Promise<Order> =>
    authedFetch('/api/orders', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).then((r) => json<Order>(r)),

  getOrder: (orderId: string): Promise<Order> =>
    authedFetch(`/api/orders/${orderId}`).then((r) => json<Order>(r)),

  listInventory: (): Promise<InventoryItem[]> =>
    authedFetch('/api/inventory/items').then((r) => json<InventoryItem[]>(r)),

  getAuthorization: (orderId: string): Promise<Authorization> =>
    authedFetch(`/api/payments/authorizations/${orderId}`).then((r) => json<Authorization>(r)),

  // The caller's profile, provisioned just-in-time by the users service on first call.
  getMyProfile: (): Promise<Profile> =>
    authedFetch('/api/users/me').then((r) => json<Profile>(r)),
}
