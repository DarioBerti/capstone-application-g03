import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import MyBookingsPage from './MyBookingsPage'
import type { Order, Page } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const emptyPage: Page<Order> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }

let lastRequestUrl: URL | null = null
const server = setupServer(
  http.get('*/api/orders', ({ request }) => {
    lastRequestUrl = new URL(request.url)
    return HttpResponse.json(emptyPage)
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  lastRequestUrl = null
})
afterAll(() => server.close())

const renderPage = () => {
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <MyBookingsPage />
      </MemoryRouter>
    </Provider>,
  )
}

const trip = (code: string, from: string, to: string, dep: string, arr: string) => ({
  id: `id-${code}`,
  code,
  carrierCode: code.slice(0, 2),
  serviceNumber: '9935',
  serviceDate: dep.slice(0, 10),
  origin: { code: from.slice(0, 3).toUpperCase(), name: from, city: from, country: 'IT' },
  destination: { code: to.slice(0, 3).toUpperCase(), name: to, city: to, country: 'IT' },
  departureTime: dep,
  arrivalTime: arr,
  priceCents: 2510,
  currency: 'EUR',
  priceToken: 'tok',
  availableSeats: 42,
})

const roundTripOrder: Order = {
  orderId: 'abcd1234-5678-90ab-cdef-000000000000',
  status: 'CONFIRMED',
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 5020,
  createdAt: '2025-09-01T09:00:00Z',
  expiresAt: null,
  remainingSeconds: null,
  paymentInitiatedAt: null,
  // Deliberately RETURN-first to prove the card sorts outbound-first regardless of API array order.
  legs: [
    { legId: 'l2', direction: 'RETURN', tripCode: 'FR9908-20250918', qty: 1, unitPriceCents: 2510, lineAmountCents: 2510, status: 'CONFIRMED' },
    { legId: 'l1', direction: 'OUTBOUND', tripCode: 'FR9935-20250916', qty: 1, unitPriceCents: 2510, lineAmountCents: 2510, status: 'CONFIRMED' },
  ],
}

describe('MyBookingsPage', () => {
  test('requests only CONFIRMED orders, newest first from the backend default sort', async () => {
    renderPage()
    expect(await screen.findByText(/no confirmed trips yet/i)).toBeInTheDocument()
    expect(lastRequestUrl?.searchParams.get('status')).toBe('CONFIRMED')
    expect(lastRequestUrl?.searchParams.get('size')).toBe('20')
    expect(lastRequestUrl?.searchParams.get('createdFrom')).toBeNull()
  })

  test('renders a ticket-style card with route, booking code, schedule and total', async () => {
    server.use(
      http.get('*/api/orders', () =>
        HttpResponse.json({ content: [roundTripOrder], totalElements: 1, totalPages: 1, number: 0, size: 20 }),
      ),
      http.get('*/api/catalog/trips/FR9935-20250916', () =>
        HttpResponse.json(trip('FR9935-20250916', 'Torino Porta Susa', 'Milano Centrale', '2025-09-16T11:40:00Z', '2025-09-16T12:30:00Z')),
      ),
      http.get('*/api/catalog/trips/FR9908-20250918', () =>
        HttpResponse.json(trip('FR9908-20250918', 'Milano Centrale', 'Torino Porta Susa', '2025-09-18T10:30:00Z', '2025-09-18T11:24:00Z')),
      ),
    )
    renderPage()

    expect(await screen.findByText('ET-ABCD1234')).toBeInTheDocument()
    // round-trip header + leg rows resolve the trip codes to station names (await the async catalog reads)
    expect((await screen.findAllByText('Torino Porta Susa')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Milano Centrale').length).toBeGreaterThan(0)
    // both legs rendered, outbound-first even though the API returned them return-first
    const outbound = screen.getByText('OUTBOUND')
    const ret = screen.getByText('RETURN')
    expect(outbound).toBeInTheDocument()
    expect(ret).toBeInTheDocument()
    expect(outbound.compareDocumentPosition(ret) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByRole('link', { name: /view trip/i })).toHaveAttribute(
      'href',
      '/orders/abcd1234-5678-90ab-cdef-000000000000',
    )
  })
})
