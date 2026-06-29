import { getToken } from './auth'
import type { Authorization, InventoryItem, Order, Page, Profile, Station, Trip } from './types'

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

// Filters for the trip list (mirror the catalog TripFilter). All optional: an empty query lists everything.
export interface TripQuery {
  origin?: string
  destination?: string
  carrierCode?: string
  serviceDate?: string
  maxPriceCents?: number
  page?: number
  size?: number
}

export const api = {
  listTrips: (q: TripQuery = {}): Promise<Page<Trip>> => {
    const p = new URLSearchParams()
    if (q.origin) p.set('origin', q.origin)
    if (q.destination) p.set('destination', q.destination)
    if (q.carrierCode) p.set('carrierCode', q.carrierCode)
    if (q.serviceDate) p.set('serviceDate', q.serviceDate)
    if (q.maxPriceCents != null) p.set('maxPriceCents', String(q.maxPriceCents))
    p.set('page', String(q.page ?? 0))
    p.set('size', String(q.size ?? 50))
    return authedFetch(`/api/catalog/trips?${p.toString()}`).then((r) => json<Page<Trip>>(r))
  },

  getTrip: (code: string): Promise<Trip> =>
    authedFetch(`/api/catalog/trips/${encodeURIComponent(code)}`).then((r) => json<Trip>(r)),

  // Stations populate the origin/destination pickers for the trip filter.
  listStations: (): Promise<Station[]> =>
    authedFetch('/api/catalog/stations?size=100')
      .then((r) => json<Page<Station>>(r))
      .then((page) => page.content),

  // The order references the trip by its business `code`; the buyer identity is taken from the bearer token.
  placeOrder: (body: { tripCode: string; qty: number }): Promise<Order> =>
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
