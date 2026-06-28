// API-facing shapes, mirrored from the services' controller DTOs (the code is the source of truth).
export type OrderStatus = 'PENDING' | 'RESERVED' | 'CONFIRMED' | 'REJECTED'
export type RejectionReason = 'SOLD_OUT' | 'PAYMENT_DECLINED' | 'RESERVATION_EXPIRED'
export type AuthorizationStatus = 'AUTHORIZED' | 'DECLINED'

export interface Product {
  productId: string
  name: string
  priceCents: number
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
  productId: string
  qty: number
  amountCents: number
  status: OrderStatus
  reason: RejectionReason | null
}

export interface InventoryItem {
  productId: string
  totalSeats: number
  available: number
}

export interface Authorization {
  authorizationId: string
  orderId: string
  amountCents: number
  status: AuthorizationStatus
  replayed: boolean
}

// The caller's app-side profile, owned by the users service (keyed by the Keycloak subject).
export interface Profile {
  keycloakSub: string
  email: string
  givenName: string | null
  familyName: string | null
  preferredUsername: string | null
  createdAt: string
  updatedAt: string
}
