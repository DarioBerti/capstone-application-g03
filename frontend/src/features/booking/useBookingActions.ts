import { useCallback } from 'react'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { idempotencyKeyMinted, orderCleared, orderUpdated, tripSelected } from './bookingSlice'
import { ACTIVE_LEG_STATUSES } from './legStatus'
import { isTerminal } from './useOrderStream'
import {
  useAddLegMutation,
  useCreateDraftMutation,
  usePayMutation,
  useRemoveLegMutation,
} from '../orders/ordersApi'
import type { Order, OrderLeg } from '../../types'

// Encapsulates the money-path mutations with the client-owned idempotency rule: one stable Idempotency-Key
// per checkout intent (an outbound trip + qty), reused across retries, re-minted only when the intent changes
// or after a terminal order. The UI also disables the trigger while a mutation is in flight, so a double-click
// can't create a second draft.
export function useBookingActions() {
  const dispatch = useAppDispatch()
  const order = useAppSelector((s) => s.booking.order)
  const idempotencyKey = useAppSelector((s) => s.booking.idempotencyKey)
  const selectedTripCode = useAppSelector((s) => s.booking.selectedTripCode)

  const [createDraft, createState] = useCreateDraftMutation()
  const [addLeg, addLegState] = useAddLegMutation()
  const [removeLeg, removeLegState] = useRemoveLegMutation()
  const [pay, payState] = usePayMutation()

  // Start (or retry) a booking: create a DRAFT then reserve the OUTBOUND leg (moves it to HELD, starts the
  // hold window). Reuses the existing key when re-selecting the same trip on a still-live order (a retry);
  // mints a new one otherwise (a new intent).
  const startBooking = useCallback(
    async (tripCode: string, qty: number, priceToken: string): Promise<Order> => {
      const isRetry = !!order && !isTerminal(order.status) && selectedTripCode === tripCode && !!idempotencyKey
      const key = isRetry ? idempotencyKey! : crypto.randomUUID()
      if (!isRetry) {
        dispatch(idempotencyKeyMinted(key))
        dispatch(tripSelected(tripCode))
      }
      const draft = await createDraft({ idempotencyKey: key }).unwrap()
      const held = await addLeg({ orderId: draft.orderId, direction: 'OUTBOUND', tripCode, qty, priceToken }).unwrap()
      dispatch(orderUpdated(held))
      return held
    },
    [dispatch, order, idempotencyKey, selectedTripCode, createDraft, addLeg],
  )

  const addReturn = useCallback(
    async (orderId: string, tripCode: string, qty: number, priceToken: string): Promise<Order> => {
      const updated = await addLeg({ orderId, direction: 'RETURN', tripCode, qty, priceToken }).unwrap()
      dispatch(orderUpdated(updated))
      return updated
    },
    [dispatch, addLeg],
  )

  const dropLeg = useCallback(
    async (orderId: string, legId: string): Promise<Order> => {
      const updated = await removeLeg({ orderId, legId }).unwrap()
      dispatch(orderUpdated(updated))
      return updated
    },
    [dispatch, removeLeg],
  )

  // Re-pick a leg from checkout: release it so the seats free immediately, then the caller sends the user back
  // to /search to choose a replacement. A RETURN is chosen relative to its OUTBOUND, so changing the outbound
  // invalidates the return too — drop it first, leaving an empty DRAFT the search flow restarts cleanly from.
  const changeLeg = useCallback(
    async (orderToEdit: Order, leg: OrderLeg): Promise<void> => {
      if (leg.direction === 'OUTBOUND') {
        const returnLeg = orderToEdit.legs.find(
          (l) => l.direction === 'RETURN' && ACTIVE_LEG_STATUSES.has(l.status),
        )
        if (returnLeg) {
          dispatch(orderUpdated(await removeLeg({ orderId: orderToEdit.orderId, legId: returnLeg.legId }).unwrap()))
        }
      }
      dispatch(orderUpdated(await removeLeg({ orderId: orderToEdit.orderId, legId: leg.legId }).unwrap()))
    },
    [dispatch, removeLeg],
  )

  // Discard the whole booking: release every still-held leg on the backend (so the seats free immediately
  // rather than at hold-window expiry), then clear the local cart. Each removal is mirrored into the slice
  // before the next, so if one fails mid-way the cart reflects what's actually released and a retried discard
  // skips the already-dropped legs — convergent under partial failure.
  const discardBooking = useCallback(
    async (orderToDiscard: Order): Promise<void> => {
      const activeLegs = orderToDiscard.legs.filter((l) => ACTIVE_LEG_STATUSES.has(l.status))
      for (const leg of activeLegs) {
        const updated = await removeLeg({ orderId: orderToDiscard.orderId, legId: leg.legId }).unwrap()
        dispatch(orderUpdated(updated))
      }
      dispatch(orderCleared())
    },
    [dispatch, removeLeg],
  )

  // Pay within the window (202 Accepted). The confirm/capture saga then proceeds async — observed over SSE.
  // payPalOrderId is the browser-approved PayPal order (Card Fields); omitted for the legacy one-click path.
  const payNow = useCallback(
    async (orderId: string, payPalOrderId?: string): Promise<Order> => {
      const updated = await pay({ orderId, payPalOrderId }).unwrap()
      dispatch(orderUpdated(updated))
      return updated
    },
    [dispatch, pay],
  )

  const reset = useCallback(() => dispatch(orderCleared()), [dispatch])

  return {
    startBooking,
    addReturn,
    dropLeg,
    changeLeg,
    discardBooking,
    payNow,
    reset,
    starting: createState.isLoading || addLegState.isLoading,
    removing: removeLegState.isLoading,
    paying: payState.isLoading,
  }
}
