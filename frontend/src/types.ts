// API-facing shapes, mirrored from the services' controller DTOs (the code is the source of truth).
export type OrderStatus = 'PENDING' | 'RESERVED' | 'CONFIRMED' | 'REJECTED'
export type RejectionReason = 'SOLD_OUT' | 'PAYMENT_DECLINED' | 'RESERVATION_EXPIRED'
export type AuthorizationStatus = 'AUTHORIZED' | 'DECLINED' | 'VOIDED'

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
}

// Spring Data `Page<T>` (only the fields the UI uses).
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface Order {
  orderId: string
  tripCode: string
  qty: number
  amountCents: number
  currency: string
  status: OrderStatus
  rejectionReason: RejectionReason | null
}

export interface InventoryItem {
  tripCode: string
  totalSeats: number
  available: number
}

export interface Authorization {
  authorizationId: string
  orderId: string
  amountCents: number
  currency?: string
  status: AuthorizationStatus
  pspReference?: string | null
  declineReason?: string | null
  replayed: boolean
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
