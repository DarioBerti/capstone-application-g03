import { useEffect, useRef } from 'react'
import { skipToken } from '@reduxjs/toolkit/query/react'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { isAuthenticated } from '../../auth'
import { useGetOrderQuery } from '../orders/ordersApi'
import { bookingRestored } from './bookingSlice'
import { isTerminal } from './useOrderStream'
import { ACTIVE_LEG_STATUSES } from './legStatus'
import { clearBookingPointer, loadBookingPointer } from './bookingStorage'

// Reconnects the in-memory cart to its backend order after a refresh: re-fetch the persisted order by id
// (backend stays authoritative), then restore the slice. Mounted app-wide in Layout. A gone (404) or terminal
// order drops the pointer instead of restoring. Shares RTK Query's cache with CheckoutPage when ids match.
//
// The pointer is re-read from localStorage on every evaluation and the restore runs at most once per page
// load: a discard clears both the slice and the pointer, and a frozen snapshot would otherwise resurrect the
// discarded booking (old order id + old Idempotency-Key) the moment the cart empties.
export function useRestoreBooking(): void {
  const dispatch = useAppDispatch()
  const sliceOrder = useAppSelector((s) => s.booking.order)
  const done = useRef(false)

  const pointer = loadBookingPointer()
  const shouldRestore = !done.current && isAuthenticated() && !!pointer?.orderId && !sliceOrder
  const { data: fetched, isError } = useGetOrderQuery(shouldRestore ? pointer!.orderId : skipToken)

  useEffect(() => {
    if (!shouldRestore) return
    if (isError) {
      done.current = true
      clearBookingPointer()
      return
    }
    if (!fetched) return
    done.current = true
    // Terminal orders are finished; a booking with no active leg is spent (its released trips can never be
    // re-reserved on the same order) — neither belongs back in the cart.
    if (isTerminal(fetched.status) || !fetched.legs.some((l) => ACTIVE_LEG_STATUSES.has(l.status))) {
      clearBookingPointer()
      return
    }
    // Re-read at restore time: a discard that raced the fetch has already dropped the pointer by now.
    const current = loadBookingPointer()
    if (current?.orderId !== fetched.orderId) return
    dispatch(
      bookingRestored({
        order: fetched,
        idempotencyKey: current.idempotencyKey,
        selectedTripCode: current.selectedTripCode,
      }),
    )
  }, [shouldRestore, fetched, isError, dispatch])
}
