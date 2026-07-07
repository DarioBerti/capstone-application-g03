import { useCallback } from 'react'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { idempotencyKeyMinted, orderCleared, orderUpdated, tripSelected } from './bookingSlice'
import { ACTIVE_LEG_STATUSES } from './legStatus'
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
  // hold window). Reuses the existing key when re-selecting the same trip on a still-editable order (a retry);
  // mints a new one otherwise (a new intent). A trip whose leg on the current order already dropped out
  // (RELEASED/SOLD_OUT/EXPIRED) can never be retried there — the (order, trip) reservation pair is one-shot
  // across the saga — so that case is a new intent on a fresh order.
  const startBooking = useCallback(
    async (tripCode: string, qty: number, priceToken: string): Promise<Order> => {
      const editable = !!order && (order.status === 'DRAFT' || order.status === 'HELD')
      const burned = !!order && order.legs.some((l) => l.tripCode === tripCode && !ACTIVE_LEG_STATUSES.has(l.status))
      const isRetry = editable && !burned && selectedTripCode === tripCode && !!idempotencyKey
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

  // Mirror a removal into the slice; when nothing active remains the booking is spent (its released trips can
  // never be re-reserved on the same order), so the cart resets and the next selection starts a fresh order.
  const syncAfterRemoval = useCallback(
    (updated: Order): Order => {
      const hasActive = updated.legs.some((l) => ACTIVE_LEG_STATUSES.has(l.status))
      dispatch(hasActive ? orderUpdated(updated) : orderCleared())
      return updated
    },
    [dispatch],
  )

  const discardBooking = useCallback(
    async (orderToDiscard: Order): Promise<void> => {
      // Release every still-held leg on the backend (seats free immediately rather than at window expiry),
      // mirroring each removal so a partial failure leaves the cart reflecting what's actually released and a
      // retried discard skips the already-dropped legs — convergent under partial failure.
      const activeLegs = orderToDiscard.legs.filter((l) => ACTIVE_LEG_STATUSES.has(l.status))
      for (const leg of activeLegs) {
        syncAfterRemoval(await removeLeg({ orderId: orderToDiscard.orderId, legId: leg.legId }).unwrap())
      }
      dispatch(orderCleared())
    },
    [dispatch, removeLeg, syncAfterRemoval],
  )

  // Extend a held one-way with its return. A return trip whose leg already dropped out of THIS booking
  // (removed/expired) cannot be re-reserved on the same order — the saga's (order, trip) pair is one-shot —
  // so that case transparently rebooks: release the old booking and rebuild outbound + return on a fresh one.
  const addReturn = useCallback(
    async (
      orderToExtend: Order,
      tripCode: string,
      qty: number,
      priceToken: string,
      outbound?: { tripCode: string; qty: number; priceToken: string },
    ): Promise<Order> => {
      const burned = orderToExtend.legs.some((l) => l.tripCode === tripCode && !ACTIVE_LEG_STATUSES.has(l.status))
      if (!burned || !outbound) {
        const updated = await addLeg({ orderId: orderToExtend.orderId, direction: 'RETURN', tripCode, qty, priceToken }).unwrap()
        dispatch(orderUpdated(updated))
        return updated
      }
      await discardBooking(orderToExtend)
      const held = await startBooking(outbound.tripCode, outbound.qty, outbound.priceToken)
      const updated = await addLeg({ orderId: held.orderId, direction: 'RETURN', tripCode, qty, priceToken }).unwrap()
      dispatch(orderUpdated(updated))
      return updated
    },
    [dispatch, addLeg, discardBooking, startBooking],
  )

  const dropLeg = useCallback(
    async (orderId: string, legId: string): Promise<Order> =>
      syncAfterRemoval(await removeLeg({ orderId, legId }).unwrap()),
    [removeLeg, syncAfterRemoval],
  )

  // Re-pick a leg from checkout: release it so the seats free immediately, then the caller sends the user back
  // to /search to choose a replacement. A RETURN is chosen relative to its OUTBOUND, so changing the outbound
  // invalidates the return too — drop it first; once nothing active remains the cart resets and the search
  // flow restarts on a fresh order.
  const changeLeg = useCallback(
    async (orderToEdit: Order, leg: OrderLeg): Promise<void> => {
      if (leg.direction === 'OUTBOUND') {
        const returnLeg = orderToEdit.legs.find(
          (l) => l.direction === 'RETURN' && ACTIVE_LEG_STATUSES.has(l.status),
        )
        if (returnLeg) {
          syncAfterRemoval(await removeLeg({ orderId: orderToEdit.orderId, legId: returnLeg.legId }).unwrap())
        }
      }
      syncAfterRemoval(await removeLeg({ orderId: orderToEdit.orderId, legId: leg.legId }).unwrap())
    },
    [removeLeg, syncAfterRemoval],
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
