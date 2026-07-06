import { apiSlice } from '../../api/apiSlice'
import type { LegDirection, Order, Page } from '../../types'

export interface AddLegArgs {
  orderId: string
  direction: LegDirection
  tripCode: string
  qty: number
  // Signed price quote for this trip (from the trip's catalog response); the backend verifies it and snapshots
  // the price onto the leg.
  priceToken: string
}

export const ordersApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    getOrder: build.query<Order, string>({
      query: (orderId) => ({ url: `/orders/${orderId}` }),
      providesTags: (_r, _e, orderId) => [{ type: 'Order', id: orderId }],
    }),
    // "My bookings" (backend #1: GET /api/orders buyer-scoped) — may not be wired yet; the UI handles the
    // error/empty path.
    listMyOrders: build.query<Page<Order>, { page?: number; size?: number } | void>({
      query: (p) => ({ url: '/orders', params: { page: p?.page ?? 0, size: p?.size ?? 20 } }),
      providesTags: ['Order'],
    }),
    // Create an empty DRAFT. The client-minted Idempotency-Key rides in the header (NOT the body) so a
    // retried "place order" replays the same draft instead of creating a second.
    createDraft: build.mutation<Order, { idempotencyKey: string }>({
      query: ({ idempotencyKey }) => ({
        url: '/orders',
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      invalidatesTags: ['Order'],
    }),
    addLeg: build.mutation<Order, AddLegArgs>({
      query: ({ orderId, direction, tripCode, qty, priceToken }) => ({
        url: `/orders/${orderId}/legs`,
        method: 'POST',
        data: { direction, tripCode, qty, priceToken },
      }),
      invalidatesTags: (_r, _e, { orderId }) => [{ type: 'Order', id: orderId }],
    }),
    removeLeg: build.mutation<Order, { orderId: string; legId: string }>({
      query: ({ orderId, legId }) => ({ url: `/orders/${orderId}/legs/${legId}`, method: 'DELETE' }),
      invalidatesTags: (_r, _e, { orderId }) => [{ type: 'Order', id: orderId }],
    }),
    // Pay within the window (202 Accepted). The saga then proceeds async; observe it over SSE / poll.
    pay: build.mutation<Order, string>({
      query: (orderId) => ({ url: `/orders/${orderId}/pay`, method: 'POST' }),
      invalidatesTags: (_r, _e, orderId) => [{ type: 'Order', id: orderId }],
    }),
  }),
})

export const {
  useGetOrderQuery,
  useListMyOrdersQuery,
  useCreateDraftMutation,
  useAddLegMutation,
  useRemoveLegMutation,
  usePayMutation,
} = ordersApi
