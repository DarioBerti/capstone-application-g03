import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import bookingReducer from '../booking/bookingSlice'
import { bookingPersistenceMiddleware } from '../booking/bookingPersistence'
import CheckoutPage from './CheckoutPage'
import type { LegStatus, Order } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const heldOrder = (outboundStatus: LegStatus): Order => ({
  orderId: 'o1',
  status: 'HELD',
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 2100,
  legs: [
    {
      legId: 'leg-1',
      direction: 'OUTBOUND',
      tripCode: 'FR9114-20260707',
      qty: 1,
      unitPriceCents: 2100,
      lineAmountCents: 2100,
      status: outboundStatus,
    },
  ],
  expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  remainingSeconds: 900,
  paymentInitiatedAt: null,
})

let sessionRequested = false
const server = setupServer(
  http.post('*/api/orders/o1/paypal-session', () => {
    sessionRequested = true
    return HttpResponse.json({ payPalOrderId: 'pp-1', clientId: 'client-1' })
  }),
  http.get('*/api/catalog/trips/:code', () => new HttpResponse(null, { status: 404 })),
  http.get('*/api/notifications/orders/:id/events', () => new HttpResponse(null, { status: 404 })),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
beforeEach(() => {
  sessionRequested = false
})
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const renderCheckout = (order: Order) => {
  server.use(http.get('*/api/orders/o1', () => HttpResponse.json(order)))
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer, booking: bookingReducer },
    middleware: (gdm) => gdm().prepend(bookingPersistenceMiddleware.middleware).concat(apiSlice.middleware),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/orders/o1']}>
        <Routes>
          <Route path="/orders/:orderId" element={<CheckoutPage />} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  )
}

describe('CheckoutPage payment gating', () => {
  // The backend rejects a payment session until the outbound reservation is RESERVED — the page must wait for
  // the settling reservation instead of firing a doomed session request and stranding the user on an error.
  test('does not open a payment session while the outbound reservation is settling', async () => {
    renderCheckout(heldOrder('PENDING'))

    expect(await screen.findByText(/securing your seats/i)).toBeInTheDocument()
    expect(sessionRequested).toBe(false)
    expect(screen.queryByText(/payment details/i)).not.toBeInTheDocument()
  })

  test('opens the payment session once the outbound leg is reserved', async () => {
    renderCheckout(heldOrder('RESERVED'))

    expect(await screen.findByText(/payment details/i)).toBeInTheDocument()
    await waitFor(() => expect(sessionRequested).toBe(true))
  })
})
