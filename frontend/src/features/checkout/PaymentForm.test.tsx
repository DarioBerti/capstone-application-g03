import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import PaymentForm from './PaymentForm'
import { loadPayPalSdk, type PayPalCardFieldsOptions, type PayPalNamespace } from '../../lib/paypal'
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

// A fake PayPal namespace whose CardFields captures the options and, on submit(), fires onApprove with a known
// approved order id — the seam we assert flows through to onPay.
let capturedOptions: PayPalCardFieldsOptions | null = null
const fakePayPal: PayPalNamespace = {
  CardFields: (options) => {
    capturedOptions = options
    const field = () => ({ render: async () => {} })
    return {
      isEligible: () => true,
      NameField: field,
      NumberField: field,
      ExpiryField: field,
      CVVField: field,
      submit: async () => {
        capturedOptions?.onApprove({ orderID: 'PP-APPROVED-9' })
      },
    }
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

describe('PaymentForm (PayPal Card Fields)', () => {
  test('opens a session, renders hosted fields, and forwards the approved order id to onPay on submit', async () => {
    vi.mocked(loadPayPalSdk).mockResolvedValue(fakePayPal)
    const onPay = renderForm()

    // The hosted-field containers are present for the SDK to render into.
    expect(document.querySelector('#pp-card-number')).toBeInTheDocument()

    // Once the session + SDK resolve, the Pay button enables.
    const payButton = await screen.findByRole('button', { name: /pay/i })
    await waitFor(() => expect(payButton).toBeEnabled())

    fireEvent.click(payButton)
    await waitFor(() => expect(onPay).toHaveBeenCalledWith('PP-APPROVED-9'))
  })

  test('surfaces a session failure instead of a payable form', async () => {
    server.use(http.post('*/api/orders/:id/paypal-session', () => new HttpResponse(null, { status: 500 })))
    vi.mocked(loadPayPalSdk).mockResolvedValue(fakePayPal)
    renderForm()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /loading secure fields/i })).toBeDisabled()
  })

  test('shows an error when the PayPal SDK fails to load', async () => {
    vi.mocked(loadPayPalSdk).mockRejectedValue(new Error('Failed to load the PayPal SDK'))
    renderForm()

    expect(await screen.findByText(/failed to load the paypal sdk/i)).toBeInTheDocument()
  })
})
