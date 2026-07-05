import { configureStore } from '@reduxjs/toolkit'
import { apiSlice } from '../api/apiSlice'
import bookingReducer from '../features/booking/bookingSlice'

export const store = configureStore({
  reducer: {
    [apiSlice.reducerPath]: apiSlice.reducer,
    booking: bookingReducer,
  },
  middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(apiSlice.middleware),
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
