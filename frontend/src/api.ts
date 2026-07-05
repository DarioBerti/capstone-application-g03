import { getToken } from './auth'
import type { Availability, LegDirection, Order, Page, Profile, Station, Trip } from './types'

// All requests go to same-origin /api/* — proxied by Vite in dev (vite.config.ts), routed by the Traefik
// ingress in prod. The services are OAuth2 resource servers, so every call carries the Keycloak bearer token.
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

  // Stations populate the origin/destination pickers for the trip filter.
  listStations: (): Promise<Station[]> =>
    authedFetch('/api/catalog/stations?size=100')
      .then((r) => json<Page<Station>>(r))
      .then((page) => page.content),

  // Create an empty DRAFT booking. The buyer identity is taken from the bearer token. `idempotencyKey`
  // (client-minted, stable across retries of one "start booking" intent) rides in the Idempotency-Key header —
  // NOT the body — so a double-clicked/ retried create replays the same draft instead of creating a second.
  createDraft: (idempotencyKey?: string): Promise<Order> => {
    const headers: Record<string, string> = {}
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
    return authedFetch('/api/orders', { method: 'POST', headers }).then((r) => json<Order>(r))
  },

  // Add a leg (reserve-on-add). The first leg starts the backend-owned hold window and moves the order to HELD.
  addLeg: (orderId: string, leg: { direction: LegDirection; tripCode: string; qty: number }): Promise<Order> =>
    authedFetch(`/api/orders/${orderId}/legs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(leg),
    }).then((r) => json<Order>(r)),

  // Remove a leg (releasing its hold); if it was the last active leg the booking reverts to DRAFT.
  removeLeg: (orderId: string, legId: string): Promise<Order> =>
    authedFetch(`/api/orders/${orderId}/legs/${legId}`, { method: 'DELETE' }).then((r) => json<Order>(r)),

  // Pay within the window — the backend re-checks the window server-side, then authorizes the reserved legs.
  pay: (orderId: string): Promise<Order> =>
    authedFetch(`/api/orders/${orderId}/pay`, { method: 'POST' }).then((r) => json<Order>(r)),

  getOrder: (orderId: string): Promise<Order> =>
    authedFetch(`/api/orders/${orderId}`).then((r) => json<Order>(r)),

  // Live seat-availability overlay for the browse view. Advisory/best-effort — the authoritative
  // never-oversell guard is at reserve time. Public read via the ingress (paginated); returns the content.
  getAvailability: (): Promise<Availability[]> =>
    authedFetch('/api/inventory/availability?size=200')
      .then((r) => json<Page<Availability>>(r))
      .then((page) => page.content),

  // The caller's profile, provisioned just-in-time by the users service on first call.
  getMyProfile: (): Promise<Profile> =>
    authedFetch('/api/users/me').then((r) => json<Profile>(r)),
}
