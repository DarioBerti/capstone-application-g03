// API-facing shapes, mirrored from the services' controller DTOs (the code is the source of truth).
// A booking is one journey: an OUTBOUND leg (required) + an optional RETURN leg. Two-phase, human-gated:
// DRAFT -> HELD (legs reserved, hold window running) -> PAYING -> AUTHORIZED -> CONFIRMED, or REJECTED.
export type OrderStatus = 'DRAFT' | 'HELD' | 'PAYING' | 'AUTHORIZED' | 'CONFIRMED' | 'REJECTED'
export type RejectionReason =
  | 'SOLD_OUT'
  | 'PAYMENT_DECLINED'
  | 'RESERVATION_EXPIRED'
  | 'EXPIRED_NO_PAYMENT' // the hold window elapsed and the user never paid (user inaction)
  | 'SERVICE_UNAVAILABLE' // pay was accepted but the pipeline stalled past the window (system fault)
export type LegDirection = 'OUTBOUND' | 'RETURN'
export type LegStatus = 'PENDING' | 'RESERVED' | 'SOLD_OUT' | 'CONFIRMED' | 'EXPIRED' | 'RELEASED'

// A station a trip runs between (catalog reference data).
export interface Station {
  code: string
  name: string
  city: string
  country: string
}

// A sellable scheduled departure. `code` (e.g. 'FR9592-20260629') is the cross-service vocabulary the
// order/inventory APIs key on; the structured fields make the journey queryable instead of prose.
export interface Trip {
  id: string
  code: string
  carrierCode: string
  serviceNumber: string
  serviceDate: string
  origin: Station
  destination: Station
  departureTime: string
  arrivalTime: string
  priceCents: number
  currency: string
  // Signed browse-time price quote (JWS). Carried into add-leg so the backend snapshots the price it verifies
  // locally — no synchronous Catalog call on the money path.
  priceToken: string
}

// Spring Data `Page<T>` (only the fields the UI uses).
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface OrderLeg {
  legId: string
  direction: LegDirection
  tripCode: string
  qty: number
  unitPriceCents: number
  lineAmountCents: number
  status: LegStatus
}

export interface Order {
  orderId: string
  status: OrderStatus
  rejectionReason: RejectionReason | null
  currency: string
  amountCents: number
  legs: OrderLeg[]
  // Backend-owned hold window. `remainingSeconds` is computed server-side (max(0, expiresAt - now)); the UI
  // renders a countdown from it but is NEVER the authority on expiry — the backend re-checks at pay.
  expiresAt: string | null
  remainingSeconds: number | null
  paymentInitiatedAt: string | null
}

// Public seat-availability projection (mirrors inventory's AvailabilityResponse). Advisory browse-time counts.
export interface Availability {
  tripCode: string
  totalSeats: number
  available: number
}

// The caller's app-side profile, owned by the users service (surrogate id + Keycloak subject natural key).
export interface Profile {
  id: string
  keycloakSub: string
  email: string
  givenName: string | null
  familyName: string | null
  preferredUsername: string | null
  locale: string | null
  preferredCurrency: string | null
  createdAt: string
  updatedAt: string
}
