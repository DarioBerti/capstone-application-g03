import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { Order } from '../../types'

// Client-owned state for the active checkout intent. The order snapshot is the single source of truth the
// SagaTracker/BookingPanel render; the idempotency key makes a retried "place order" replay one order (see
// the frontend idempotency rule). The backend is always authoritative on status and the hold window — this
// slice only mirrors what the API/SSE report.
interface BookingState {
  order: Order | null
  idempotencyKey: string | null
  selectedTripCode: string | null
}

const initialState: BookingState = {
  order: null,
  idempotencyKey: null,
  selectedTripCode: null,
}

const bookingSlice = createSlice({
  name: 'booking',
  initialState,
  reducers: {
    orderUpdated(state, action: PayloadAction<Order>) {
      state.order = action.payload
    },
    orderCleared(state) {
      state.order = null
      state.idempotencyKey = null
      state.selectedTripCode = null
    },
    idempotencyKeyMinted(state, action: PayloadAction<string>) {
      state.idempotencyKey = action.payload
    },
    tripSelected(state, action: PayloadAction<string | null>) {
      state.selectedTripCode = action.payload
    },
    // Rehydrate the whole booking intent from the persisted pointer after a refresh (order re-fetched from
    // the backend, key + trip restored from localStorage) — atomic so it triggers a single re-persist.
    bookingRestored(
      state,
      action: PayloadAction<{ order: Order; idempotencyKey: string | null; selectedTripCode: string | null }>,
    ) {
      state.order = action.payload.order
      state.idempotencyKey = action.payload.idempotencyKey
      state.selectedTripCode = action.payload.selectedTripCode
    },
  },
})

export const { orderUpdated, orderCleared, idempotencyKeyMinted, tripSelected, bookingRestored } =
  bookingSlice.actions
export default bookingSlice.reducer
