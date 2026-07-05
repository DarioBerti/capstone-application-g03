import { createApi } from '@reduxjs/toolkit/query/react'
import { axiosBaseQuery } from './axiosBaseQuery'

// The single RTK Query API. Feature slices (catalog/inventory/orders/users) inject their endpoints via
// `apiSlice.injectEndpoints`, keeping each feature's data layer beside its components.
export const apiSlice = createApi({
  reducerPath: 'api',
  baseQuery: axiosBaseQuery(),
  tagTypes: ['Order', 'Trip', 'Availability', 'Profile'],
  endpoints: () => ({}),
})
