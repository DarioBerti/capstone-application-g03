import { useCallback } from 'react'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { idempotencyKeyMinted, orderCleared, orderUpdated, tripSelected } from './bookingSlice'
import { isTerminal } from './useOrderStream'
import {
  useAddLegMutation,
  useCreateDraftMutation,
  usePayMutation,
  useRemoveLegMutation,
} from '../orders/ordersApi'
import type { Order } from '../../types'

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

  // Pay within the window (202 Accepted). The confirm/capture saga then proceeds async — observed over SSE.
  const payNow = useCallback(
    async (orderId: string): Promise<Order> => {
      const updated = await pay(orderId).unwrap()
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
    payNow,
    reset,
    starting: createState.isLoading || addLegState.isLoading,
    removing: removeLegState.isLoading,
    paying: payState.isLoading,
  }
}
