import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import PaymentForm from './PaymentForm'
import { loadPayPalSdk, type PayPalButtonsOptions, type PayPalNamespace } from '../../lib/paypal'
import type { Order } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => 'test-token',
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

vi.mock('../../lib/paypal', () => ({ loadPayPalSdk: vi.fn() }))

// A fake PayPal namespace whose Buttons captures the options so a test can drive `onApprove` — the seam we assert
// flows through to onPay — and whose render/close are no-ops.
let capturedOptions: PayPalButtonsOptions | null = null
const fakePayPal: PayPalNamespace = {
  Buttons: (options) => {
    capturedOptions = options
    return { render: async () => {}, close: async () => {} }
  },
}

const order: Order = {
  orderId: 'o1',
  status: 'HELD',
  rejectionReason: null,
  currency: 'EUR',
  amountCents: 1600,
  legs: [],
  expiresAt: null,
  remainingSeconds: 865,
  paymentInitiatedAt: null,
}

const server = setupServer(
  http.post('*/api/orders/:id/paypal-session', () =>
    HttpResponse.json({ clientId: 'PUB-CLIENT-ID', clientToken: 'ct-9', payPalOrderId: 'PP-ORDER-1' }),
  ),
)
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  capturedOptions = null
  vi.mocked(loadPayPalSdk).mockReset()
})
afterAll(() => server.close())

const renderForm = (onPay = vi.fn()) => {
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })
  render(
    <Provider store={store}>
      <PaymentForm order={order} onPay={onPay} processing={false} />
    </Provider>,
  )
  return onPay
}

describe('PaymentForm (PayPal Checkout button)', () => {
  test('opens a session, wires the server order into createOrder, and forwards the approved id to onPay', async () => {
    vi.mocked(loadPayPalSdk).mockResolvedValue(fakePayPal)
    const onPay = renderForm()

    // Once the session + SDK resolve, the button is rendered and its options captured.
    await waitFor(() => expect(capturedOptions).not.toBeNull())

    // createOrder resolves to the server-created PayPal order for this booking.
    await expect(capturedOptions!.createOrder()).resolves.toBe('PP-ORDER-1')

    // PayPal firing onApprove (buyer approved) forwards the approved order id to onPay.
    await capturedOptions!.onApprove({ orderID: 'PP-APPROVED-9' })
    await waitFor(() => expect(onPay).toHaveBeenCalledWith('PP-APPROVED-9'))
  })

  test('surfaces a session failure instead of a payable button', async () => {
    server.use(http.post('*/api/orders/:id/paypal-session', () => new HttpResponse(null, { status: 500 })))
    vi.mocked(loadPayPalSdk).mockResolvedValue(fakePayPal)
    renderForm()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(capturedOptions).toBeNull()
  })

  test('shows an error when the PayPal SDK fails to load', async () => {
    vi.mocked(loadPayPalSdk).mockRejectedValue(new Error('Failed to load the PayPal SDK'))
    renderForm()

    expect(await screen.findByText(/failed to load the paypal sdk/i)).toBeInTheDocument()
  })
})
