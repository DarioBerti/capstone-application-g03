import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import CartIndicator from './CartIndicator'
import type { Order, Page } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const order = (id: string, status: Order['status'], remainingSeconds: number | null): Order => ({
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
  remainingSeconds,
  paymentInitiatedAt: null,
})

const page = (content: Order[]): Page<Order> => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  number: 0,
  size: 10,
})

let lastRequestUrl: URL | null = null
const server = setupServer(
  http.get('*/api/orders', ({ request }) => {
    lastRequestUrl = new URL(request.url)
    return HttpResponse.json(page([order('a1', 'HELD', 300), order('b2', 'HELD', 500), order('c3', 'CONFIRMED', null)]))
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  lastRequestUrl = null
})
afterAll(() => server.close())

const renderIndicator = () => {
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <CartIndicator />
      </MemoryRouter>
    </Provider>,
  )
}

describe('CartIndicator', () => {
  test('shows the HELD count and the soonest countdown, requesting status=HELD', async () => {
    renderIndicator()
    expect(await screen.findByText('2')).toBeInTheDocument()
    expect(screen.getByText('05:00')).toBeInTheDocument()
    expect(lastRequestUrl?.searchParams.get('status')).toBe('HELD')
  })

  test('opens a popover listing each pending order as a resume link', async () => {
    renderIndicator()
    const trigger = await screen.findByRole('button', { name: /pending purchases/i })
    trigger.click()
    const dialog = await screen.findByRole('dialog', { name: /pending purchases/i })
    expect(dialog).toBeInTheDocument()
    const links = screen.getAllByRole('link')
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/orders/a1', '/orders/b2'])
  })

  test('renders nothing when no order is HELD', async () => {
    server.use(
      http.get('*/api/orders', ({ request }) => {
        lastRequestUrl = new URL(request.url)
        return HttpResponse.json(page([order('c3', 'CONFIRMED', null)]))
      }),
    )
    renderIndicator()
    await vi.waitFor(() => expect(lastRequestUrl).not.toBeNull())
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
