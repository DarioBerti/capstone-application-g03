import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import bookingReducer, { orderUpdated } from './bookingSlice'
import BookingSummary from './BookingSummary'
import type { Order, Trip } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const trip: Trip = {
  id: 't1',
  code: 'FR9592-20260629',
  carrierCode: 'Frecciarossa',
  serviceNumber: '9592 · Standard',
  serviceDate: '2026-06-29',
  origin: { code: 'MIL', name: 'Milano Centrale', city: 'Milano', country: 'IT' },
  destination: { code: 'TOR', name: 'Torino Porta Nuova', city: 'Torino', country: 'IT' },
  departureTime: '2026-06-29T08:00:00Z',
  arrivalTime: '2026-06-29T09:00:00Z',
  priceCents: 1600,
  currency: 'EUR',
  priceToken: 'test.price.token',
}

const anOrder = (status: Order['status'], legStatus: Order['legs'][number]['status']): Order => ({
  orderId: 'o1',
  status,
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 1600,
  legs: [
    {
      legId: 'o1-leg',
      direction: 'OUTBOUND',
      tripCode: trip.code,
      qty: 1,
      unitPriceCents: 1600,
      lineAmountCents: 1600,
      status: legStatus,
    },
  ],
  expiresAt: null,
  remainingSeconds: 865,
  paymentInitiatedAt: null,
})

const server = setupServer(http.get('*/api/catalog/trips/:code', () => HttpResponse.json(trip)))
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

const renderSummary = (order: Order | null) => {
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer, booking: bookingReducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })
  if (order) store.dispatch(orderUpdated(order))
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <BookingSummary />
      </MemoryRouter>
    </Provider>,
  )
}

describe('BookingSummary', () => {
  test('renders the cart with checkout action for a HELD order', async () => {
    // Arrange + Act
    renderSummary(anOrder('HELD', 'RESERVED'))

    // Assert
    expect(screen.getByRole('button', { name: /proceed to checkout/i })).toBeInTheDocument()
    expect(await screen.findByText('Milano – Torino')).toBeInTheDocument()
  })

  test('shows the empty cart once the order is paid (CONFIRMED), not a re-checkoutable cart', () => {
    // Arrange + Act
    renderSummary(anOrder('CONFIRMED', 'CONFIRMED'))

    // Assert
    expect(screen.queryByRole('button', { name: /proceed to checkout/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/prices are guaranteed/i)).not.toBeInTheDocument()
    expect(screen.getByText(/select a journey/i)).toBeInTheDocument()
  })

  test('shows the empty cart for a REJECTED order', () => {
    // Arrange + Act
    renderSummary(anOrder('REJECTED', 'RELEASED'))

    // Assert
    expect(screen.queryByRole('button', { name: /proceed to checkout/i })).not.toBeInTheDocument()
    expect(screen.getByText(/select a journey/i)).toBeInTheDocument()
  })
})
