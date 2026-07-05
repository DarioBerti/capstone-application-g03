import { describe, expect, test } from 'vitest'
import reducer, {
  idempotencyKeyMinted,
  orderCleared,
  orderUpdated,
  tripSelected,
} from './bookingSlice'
import type { Order } from '../../types'

const anOrder = (status: Order['status']): Order => ({
  orderId: 'o1',
  status,
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 14200,
  legs: [],
  expiresAt: null,
  remainingSeconds: 600,
  paymentInitiatedAt: null,
})

describe('bookingSlice', () => {
  test('orderUpdated stores the latest order snapshot', () => {
    const state = reducer(undefined, orderUpdated(anOrder('HELD')))
    expect(state.order?.status).toBe('HELD')
  })

  test('idempotencyKeyMinted keeps the key stable across snapshots (retry-safe)', () => {
    let state = reducer(undefined, idempotencyKeyMinted('key-1'))
    state = reducer(state, orderUpdated(anOrder('HELD')))
    expect(state.idempotencyKey).toBe('key-1')
  })

  test('orderCleared drops the active order and its idempotency key (new intent next time)', () => {
    let state = reducer(undefined, idempotencyKeyMinted('key-1'))
    state = reducer(state, orderUpdated(anOrder('CONFIRMED')))
    state = reducer(state, orderCleared())
    expect(state.order).toBeNull()
    expect(state.idempotencyKey).toBeNull()
  })

  test('tripSelected tracks the current intent trip', () => {
    const state = reducer(undefined, tripSelected('FR9592-20260629'))
    expect(state.selectedTripCode).toBe('FR9592-20260629')
  })
})
