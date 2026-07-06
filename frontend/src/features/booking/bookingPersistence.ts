import { createListenerMiddleware, isAnyOf } from '@reduxjs/toolkit'
import {
  bookingRestored,
  idempotencyKeyMinted,
  orderCleared,
  orderUpdated,
  tripSelected,
} from './bookingSlice'
import { isTerminal } from './useOrderStream'
import { clearBookingPointer, saveBookingPointer } from './bookingStorage'
import type { RootState } from '../../app/store'

// Mirrors the booking slice into localStorage so the cart survives a refresh. Fires only on booking actions
// (not on every RTK Query cache update). A null or terminal (CONFIRMED/REJECTED) order clears the pointer, so
// a finished booking is never resurrected into the cart on the next load.
export const bookingPersistenceMiddleware = createListenerMiddleware()

bookingPersistenceMiddleware.startListening({
  matcher: isAnyOf(orderUpdated, orderCleared, idempotencyKeyMinted, tripSelected, bookingRestored),
  effect: (_action, api) => {
    const { order, idempotencyKey, selectedTripCode } = (api.getState() as RootState).booking
    if (!order || isTerminal(order.status)) {
      clearBookingPointer()
      return
    }
    saveBookingPointer({ orderId: order.orderId, idempotencyKey, selectedTripCode })
  },
})
