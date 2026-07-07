import { apiSlice } from '../../api/apiSlice'
import type { LegDirection, Order, OrderStatus, Page } from '../../types'

export interface AddLegArgs {
  orderId: string
  direction: LegDirection
  tripCode: string
  qty: number
  // Signed price quote for this trip (from the trip's catalog response); the backend verifies it and snapshots
  // the price onto the leg.
  priceToken: string
}

// Filters for the buyer-scoped order list. `status` binds to the backend's CSV Set<OrderStatus> param;
// dates are ISO days resolved to inclusive bounds server-side. All optional (absent = current behavior).
export interface ListOrdersArgs {
  page?: number
  size?: number
  status?: OrderStatus | OrderStatus[]
  createdFrom?: string
  createdTo?: string
}

export const ordersApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    getOrder: build.query<Order, string>({
      query: (orderId) => ({ url: `/orders/${orderId}` }),
      providesTags: (_r, _e, orderId) => [{ type: 'Order', id: orderId }],
    }),
    // "My bookings" (GET /api/orders, buyer-scoped). Pre-rollout backends ignore the extra params and
    // return every status — callers that depend on the filter also guard client-side.
    listMyOrders: build.query<Page<Order>, ListOrdersArgs | void>({
      query: (p) => ({
        url: '/orders',
        params: {
          page: p?.page ?? 0,
          size: p?.size ?? 20,
          ...(p?.status?.length && { status: Array.isArray(p.status) ? p.status.join(',') : p.status }),
          ...(p?.createdFrom && { createdFrom: p.createdFrom }),
          ...(p?.createdTo && { createdTo: p.createdTo }),
        },
      }),
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
    // Invalidate both the per-order tag (getOrder) AND the general 'Order' tag that listMyOrders provides, so
    // the CartIndicator's HELD list refetches the instant a leg add/remove flips the booking's held state —
    // without the general tag it would stay stale until the 30s poll or a full remount.
    addLeg: build.mutation<Order, AddLegArgs>({
      query: ({ orderId, direction, tripCode, qty, priceToken }) => ({
        url: `/orders/${orderId}/legs`,
        method: 'POST',
        data: { direction, tripCode, qty, priceToken },
      }),
      invalidatesTags: (_r, _e, { orderId }) => [{ type: 'Order', id: orderId }, 'Order'],
    }),
    removeLeg: build.mutation<Order, { orderId: string; legId: string }>({
      query: ({ orderId, legId }) => ({ url: `/orders/${orderId}/legs/${legId}`, method: 'DELETE' }),
      invalidatesTags: (_r, _e, { orderId }) => [{ type: 'Order', id: orderId }, 'Order'],
    }),
    // Pay within the window (202 Accepted). The saga then proceeds async; observe it over SSE / poll.
    pay: build.mutation<Order, string>({
      query: (orderId) => ({ url: `/orders/${orderId}/pay`, method: 'POST' }),
      invalidatesTags: (_r, _e, orderId) => [{ type: 'Order', id: orderId }, 'Order'],
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
