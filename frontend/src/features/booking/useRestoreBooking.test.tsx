import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import bookingReducer, { orderCleared } from './bookingSlice'
import { bookingPersistenceMiddleware } from './bookingPersistence'
import { useRestoreBooking } from './useRestoreBooking'
import type { Order } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const KEY = 'eurotransit-booking'

const anOrder = (id: string, status: Order['status']): Order => ({
  orderId: id,
  status,
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 14200,
  legs: [
    {
      legId: `${id}-leg`,
      direction: 'OUTBOUND',
      tripCode: 'FR9592-20260629',
      qty: 1,
      unitPriceCents: 14200,
      lineAmountCents: 14200,
      status: 'RESERVED',
    },
  ],
  expiresAt: null,
  remainingSeconds: 300,
  paymentInitiatedAt: null,
})

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())

function Probe() {
  useRestoreBooking()
  return null
}

const makeStore = () =>
  configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer, booking: bookingReducer },
    middleware: (gdm) => gdm().prepend(bookingPersistenceMiddleware.middleware).concat(apiSlice.middleware),
  })

const renderProbe = (store: ReturnType<typeof makeStore>) =>
  render(
    <Provider store={store}>
      <Probe />
    </Provider>,
  )

describe('useRestoreBooking', () => {
  test('re-fetches the persisted order and restores it into the slice', async () => {
    // Arrange
    localStorage.setItem(KEY, JSON.stringify({ orderId: 'o1', idempotencyKey: 'key-1', selectedTripCode: 'FR9592-20260629' }))
    server.use(http.get('*/api/orders/:orderId', () => HttpResponse.json(anOrder('o1', 'HELD'))))
    const store = makeStore()

    // Act
    renderProbe(store)

    // Assert
    await waitFor(() => expect(store.getState().booking.order?.orderId).toBe('o1'))
    expect(store.getState().booking.order?.status).toBe('HELD')
    expect(store.getState().booking.idempotencyKey).toBe('key-1')
    expect(store.getState().booking.selectedTripCode).toBe('FR9592-20260629')
  })

  test('drops the pointer and restores nothing when the order is terminal', async () => {
    // Arrange
    localStorage.setItem(KEY, JSON.stringify({ orderId: 'o1', idempotencyKey: 'key-1', selectedTripCode: null }))
    server.use(http.get('*/api/orders/:orderId', () => HttpResponse.json(anOrder('o1', 'CONFIRMED'))))
    const store = makeStore()

    // Act
    renderProbe(store)

    // Assert
    await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull())
    expect(store.getState().booking.order).toBeNull()
  })

  // Regression: after a discard the order survives server-side as a non-terminal empty DRAFT. A stale
  // in-memory copy of the pointer must never re-restore it once the cart has been cleared.
  test('a discarded booking is not resurrected after the cart empties', async () => {
    // Arrange: restore an active booking from the pointer.
    localStorage.setItem(KEY, JSON.stringify({ orderId: 'o1', idempotencyKey: 'key-1', selectedTripCode: 'FR9592-20260629' }))
    server.use(http.get('*/api/orders/:orderId', () => HttpResponse.json(anOrder('o1', 'HELD'))))
    const store = makeStore()
    renderProbe(store)
    await waitFor(() => expect(store.getState().booking.order?.orderId).toBe('o1'))

    // Act: the user discards — the cart empties and the persistence middleware drops the pointer.
    act(() => {
      store.dispatch(orderCleared())
    })

    // Assert: the booking stays gone (no re-restore from a stale pointer).
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(store.getState().booking.order).toBeNull()
    expect(store.getState().booking.idempotencyKey).toBeNull()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  test('drops the pointer when the order is gone (404)', async () => {
    // Arrange
    localStorage.setItem(KEY, JSON.stringify({ orderId: 'gone', idempotencyKey: null, selectedTripCode: null }))
    server.use(http.get('*/api/orders/:orderId', () => new HttpResponse(null, { status: 404 })))
    const store = makeStore()

    // Act
    renderProbe(store)

    // Assert
    await waitFor(() => expect(localStorage.getItem(KEY)).toBeNull())
    expect(store.getState().booking.order).toBeNull()
  })
})
