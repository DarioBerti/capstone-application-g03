import { apiSlice } from '../../api/apiSlice'

// What the browser needs to render PayPal Card Fields for a booking. No card or secret: the client-id is public,
// the client token is a short-lived SDK credential, and the order id is the one the SDK will approve.
export interface PayPalSession {
  clientId: string
  clientToken: string
  payPalOrderId: string
}

// Opening a session creates a PayPal order server-side, so it's a mutation (a side effect), not a query. It's
// idempotent on the backend (PayPal-Request-Id = session-<orderId>), so a checkout remount is safe.
export const paymentSessionApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    createPayPalSession: build.mutation<PayPalSession, string>({
      query: (orderId) => ({ url: `/orders/${orderId}/paypal-session`, method: 'POST' }),
    }),
  }),
})

export const { useCreatePayPalSessionMutation } = paymentSessionApi
