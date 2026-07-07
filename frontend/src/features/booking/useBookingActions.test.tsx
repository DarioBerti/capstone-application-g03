import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { apiSlice } from '../../api/apiSlice'
import bookingReducer from './bookingSlice'
import { useBookingActions } from './useBookingActions'
import type { Order, OrderLeg } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const leg = (legId: string, direction: OrderLeg['direction']): OrderLeg => ({
  legId,
  direction,
  tripCode: direction === 'OUTBOUND' ? 'FR9592-20260706' : 'FR9593-20260708',
  qty: 1,
  unitPriceCents: 1600,
  lineAmountCents: 1600,
  status: 'RESERVED',
})

const roundTrip: Order = {
  orderId: 'o1',
  status: 'HELD',
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 3200,
  legs: [leg('o1-out', 'OUTBOUND'), leg('o1-ret', 'RETURN')],
  expiresAt: null,
  remainingSeconds: 600,
  paymentInitiatedAt: null,
}

let deleted: string[] = []
const server = setupServer(
  http.delete('*/api/orders/:orderId/legs/:legId', ({ params }) => {
    deleted.push(String(params.legId))
    return HttpResponse.json({ ...roundTrip, status: 'DRAFT', legs: [] })
  }),
)
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  deleted = []
})
afterAll(() => server.close())

const makeStore = () =>
  configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer, booking: bookingReducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })

const renderActions = (store: ReturnType<typeof makeStore>) => {
  const wrapper = ({ children }: { children: ReactNode }) => <Provider store={store}>{children}</Provider>
  return renderHook(() => useBookingActions(), { wrapper })
}

describe('useBookingActions.changeLeg', () => {
  test('changing the outbound drops the return first (a return is meaningless without its outbound)', async () => {
    // Arrange
    const store = makeStore()
    const { result } = renderActions(store)

    // Act
    await act(async () => {
      await result.current.changeLeg(roundTrip, leg('o1-out', 'OUTBOUND'))
    })

    // Assert — return released before the outbound; the emptied booking is spent (its released trips can't be
    // re-reserved on the same order), so the cart resets for a fresh order instead of keeping the dead DRAFT.
    expect(deleted).toEqual(['o1-ret', 'o1-out'])
    expect(store.getState().booking.order).toBeNull()
    expect(store.getState().booking.idempotencyKey).toBeNull()
    expect(store.getState().booking.selectedTripCode).toBeNull()
  })

  test('changing the return drops only the return, keeping the outbound', async () => {
    // Arrange
    const store = makeStore()
    const { result } = renderActions(store)

    // Act
    await act(async () => {
      await result.current.changeLeg(roundTrip, leg('o1-ret', 'RETURN'))
    })

    // Assert
    expect(deleted).toEqual(['o1-ret'])
  })
})
