import { useEffect, useMemo } from 'react'
import { skipToken } from '@reduxjs/toolkit/query/react'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { isAuthenticated } from '../../auth'
import { useGetOrderQuery } from '../orders/ordersApi'
import { bookingRestored } from './bookingSlice'
import { isTerminal } from './useOrderStream'
import { clearBookingPointer, loadBookingPointer } from './bookingStorage'

// Reconnects the in-memory cart to its backend order after a refresh: re-fetch the persisted order by id
// (backend stays authoritative), then restore the slice. Mounted app-wide in Layout. A gone (404) or terminal
// order drops the pointer instead of restoring. Shares RTK Query's cache with CheckoutPage when ids match.
export function useRestoreBooking(): void {
  const dispatch = useAppDispatch()
  const sliceOrder = useAppSelector((s) => s.booking.order)
  const pointer = useMemo(() => loadBookingPointer(), [])

  const shouldRestore = isAuthenticated() && !!pointer?.orderId && !sliceOrder
  const { data: fetched, isError } = useGetOrderQuery(shouldRestore ? pointer!.orderId : skipToken)

  useEffect(() => {
    if (!shouldRestore || !pointer) return
    if (isError) {
      clearBookingPointer()
      return
    }
    if (!fetched) return
    if (isTerminal(fetched.status)) {
      clearBookingPointer()
      return
    }
    dispatch(
      bookingRestored({
        order: fetched,
        idempotencyKey: pointer.idempotencyKey,
        selectedTripCode: pointer.selectedTripCode,
      }),
    )
  }, [shouldRestore, pointer, fetched, isError, dispatch])
}
